package org.telegram.messenger.camera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Wraps the vendored RNNoise library (see TMessagesProj/jni/voip/rnnoise - BSD-2-clause, already
 * compiled into libtmessages.49.so for VoIP group calls; this reuses the exact same compiled code
 * for the recording path, adding zero APK size) for round video and voice-message recording only
 * - never used for VoIP calls, which have their own separate RNNoise call site in
 * tgcalls/group/GroupInstanceCustomImpl.cpp that this class does not touch.
 *
 * One instance per recording session, same lifecycle convention as VoiceIsolationProcessor -
 * construct fresh every time a new float-format AudioRecord session starts, release() when it
 * ends. Applied FIRST in the audio chain, before VoiceIsolationProcessor's bandpass/gate and
 * before PixelGramSettings.applyMicGainFloat - see PixelGramSettings.getSpeechEnhancementMode().
 * Only meaningful when capturing in ENCODING_PCM_FLOAT (RNNoise operates on float samples); not
 * constructed at all for the PCM_16BIT fallback path.
 *
 * Frame-size handling: RNNoise processes fixed 480-sample (10ms @ 48kHz) blocks - not
 * configurable, and neither round video's per-read chunk size (512 float samples) nor voice
 * messages' AudioRecord-minBufferSize-derived read size is a clean multiple of it. process() is
 * still an in-place, same-length transform (required - the same ByteBuffer it's given is handed
 * synchronously to VoiceIsolationProcessor/gain/the encoder immediately afterward), so this uses
 * two internal queues rather than processing each call's chunk in isolation:
 *   - `pendingRaw`: not-yet-denoised samples, carried across calls until a full 480-sample block
 *     accumulates (which may span the boundary between two different callers' buffers).
 *   - `readyDenoised`: a circular buffer of denoised samples already produced but not yet written
 *     into a caller's buffer. Sized well beyond one block (see CAPACITY) because the backlog is
 *     NOT bounded to a single block: since round video's 512-sample chunks aren't an integer
 *     multiple of 480, the shortfall carried in pendingRaw grows by a few samples on most calls
 *     and periodically lets *two* blocks complete within a single call's absorb loop - at which
 *     point up to ~2 blocks of denoised audio are ready to drain at once, not one.
 * Every process() call first absorbs its entire input into pendingRaw (denoising each complete
 * block as it forms and appending the result to readyDenoised), then fills the caller's buffer by
 * draining readyDenoised - which by construction holds strictly older audio than what was just
 * absorbed, so every sample gets a genuine, gap-free RNNoise pass with no per-chunk splice
 * between denoised and raw content (the bug an earlier, simpler version of this class had - it
 * denoised only the first complete block of each chunk and left the remainder raw, producing an
 * audible click/splice on every single chunk boundary; confirmed audible in testing and replaced
 * with this design). This introduces a small, bounded end-to-end delay instead of a per-chunk
 * discontinuity - fine for recorded files per the request this was built for. Two edge effects
 * fall out of this design rather than needing special-case code: at the very start of a
 * recording, before the first block has primed, readyDenoised is empty and the caller's buffer
 * simply keeps whatever raw values were already in it past that point (a few ms of unprocessed
 * audio at the very beginning only, once per recording); at the very end, up to FRAME_SIZE-1
 * trailing samples that never completed a block are dropped rather than flushed (a few ms
 * silently truncated from the tail of each recording - no flush() exists to avoid it). Both are
 * one-off, bounded, and far less audible than the per-chunk splice this replaced; revisit only if
 * either turns out audible in practice.
 *
 * Wet/dry blend: PixelGramSettings.getSpeechEnhancementWetFraction() controls how much of
 * RNNoise's output vs. the original raw signal ends up in the final result (applied once per
 * completed block, right after the native call). At less than 100% wet, anything RNNoise fully
 * suppresses - background music, or quiet word-endings/breath it misclassifies as noise - comes
 * back at a fixed, predictable attenuation instead of disappearing outright. See
 * PixelGramSettings' field doc and FINDINGS.md for why this is a flat ratio, not time-varying.
 */
public class SpeechEnhancer {

    // Must match speech_enhancer.c's SPEECH_ENHANCER_FRAME_SIZE and the vendored rnnoise's actual
    // rnnoise_get_frame_size() (480, i.e. FRAME_SIZE=(120<<2) in denoise.c) - not queried at
    // runtime, see speech_enhancer.c's comment for why.
    public static final int FRAME_SIZE = 480;

    // Generous headroom for readyDenoised's circular buffer - see the class doc's explanation of
    // why the backlog isn't bounded to a single block. 8x FRAME_SIZE (3840 samples, 15KB) is far
    // beyond the largest backlog this app's actual buffer sizes can produce, with margin to spare
    // for any future caller using a different chunk size.
    private static final int READY_CAPACITY = FRAME_SIZE * 8;

    private long nativeHandle;

    // One dedicated small direct buffer used only to pass exactly one FRAME_SIZE block into/out
    // of the native RNNoise call - decoupled from whatever buffer callers pass to process(),
    // which can vary in size and isn't necessarily block-aligned itself.
    private final ByteBuffer nativeScratch = ByteBuffer.allocateDirect(FRAME_SIZE * 4).order(ByteOrder.nativeOrder());

    private final float[] pendingRaw = new float[FRAME_SIZE];
    private int pendingCount;

    // Circular buffer: valid data occupies READY_CAPACITY-slot indices [readyHead, readyHead +
    // readyCount) mod READY_CAPACITY.
    private final float[] readyDenoised = new float[READY_CAPACITY];
    private int readyHead;
    private int readyCount;

    // RNNoise's own per-frame voice-activity probability (confirmed via denoise.c's source -
    // rnnoise_process_frame() returns its RNN's dedicated VAD output head directly, not
    // something else - see speech_enhancer.c's comment), from the most recently completed
    // 480-sample block. 0 until at least one block has completed. AdaptiveGainProcessor reads
    // this to decide whether to freeze its slow gain adaptation - see FINDINGS.md.
    private float lastVadProbability;

    public SpeechEnhancer() {
        nativeHandle = nativeCreate();
    }

    /** Denoises length bytes (length/4 float samples) of buffer in place, in Android's normalized
     * [-1.0, 1.0] ENCODING_PCM_FLOAT domain - the FloatS16 rescale RNNoise itself needs happens
     * entirely on the native side (see speech_enhancer.c's nativeProcessFrame for why that
     * matters and why it's not done here). No-op if construction failed (nativeHandle == 0). See
     * the class doc for the carry-buffer design this uses and its two bounded, one-off edge
     * effects. */
    public void process(ByteBuffer buffer, int length) {
        if (nativeHandle == 0) return;
        int sampleCount = length / 4;

        // Absorb all of this call's raw input, denoising every complete block as it forms and
        // appending each one to the ready queue (there can be more than one - see class doc).
        int readPos = 0;
        while (readPos < sampleCount) {
            int need = FRAME_SIZE - pendingCount;
            int take = Math.min(need, sampleCount - readPos);
            for (int k = 0; k < take; k++) {
                pendingRaw[pendingCount + k] = buffer.getFloat((readPos + k) * 4);
            }
            pendingCount += take;
            readPos += take;
            if (pendingCount == FRAME_SIZE) {
                for (int k = 0; k < FRAME_SIZE; k++) {
                    nativeScratch.putFloat(k * 4, pendingRaw[k]);
                }
                lastVadProbability = nativeProcessFrame(nativeHandle, nativeScratch, 0);
                // Wet/dry blend: content RNNoise fully suppresses (denoised~0) reappears at
                // (1-wet) of its original amplitude instead of disappearing outright - a fixed,
                // predictable attenuation (20*log10(1-wet) dB), not elimination. Read live per
                // block (not cached) so an in-recording setting change takes effect immediately,
                // same convention as the rest of this package. See PixelGramSettings' field doc
                // and FINDINGS.md for why this is a flat ratio rather than time-varying for now.
                // pendingRaw[k] is still the pre-denoise sample here - only pendingCount (the
                // logical "how many are filled" counter, not the array contents) gets reset below.
                float wet = PixelGramSettings.getSpeechEnhancementWetFraction();
                float dry = 1f - wet;
                int spaceLeft = READY_CAPACITY - readyCount;
                int enqueue = Math.min(FRAME_SIZE, spaceLeft);
                int writeStart = (readyHead + readyCount) % READY_CAPACITY;
                for (int k = 0; k < enqueue; k++) {
                    float denoised = nativeScratch.getFloat(k * 4);
                    readyDenoised[(writeStart + k) % READY_CAPACITY] = wet * denoised + dry * pendingRaw[k];
                }
                readyCount += enqueue;
                // If the ready queue is somehow already full (shouldn't happen with
                // READY_CAPACITY's headroom under this app's actual buffer sizes), the oldest
                // denoised samples are effectively overwritten by falling behind rather than
                // this method throwing or blocking - a defensive fallback, not the expected path.
                pendingCount = 0;
            }
        }

        // Fill the caller's buffer from the ready queue - strictly older audio than what was
        // just absorbed above. Any positions beyond what's available in the ready queue keep
        // their original raw values (only happens before the pipeline has primed its first
        // block, i.e. the very start of a recording).
        int writeCount = Math.min(readyCount, sampleCount);
        for (int k = 0; k < writeCount; k++) {
            buffer.putFloat(k * 4, readyDenoised[(readyHead + k) % READY_CAPACITY]);
        }
        readyHead = (readyHead + writeCount) % READY_CAPACITY;
        readyCount -= writeCount;
    }

    /** Releases the native RNNoise state. Must be called exactly once when the recording session
     * ends - mirrors VoiceIsolationProcessor's per-recording lifecycle (no shared/static
     * instance). Safe to call more than once; safe if construction failed. */
    public void release() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    /** RNNoise's own voice-activity probability ([0,1]) from the most recently completed
     * 480-sample block - 0 before the first block completes (start-of-recording priming delay,
     * same as the rest of this class). Confirmed to genuinely be a speech probability (not
     * guessed at) - see the field's own doc. */
    public float getLastVadProbability() {
        return lastVadProbability;
    }

    private static native long nativeCreate();

    private static native void nativeDestroy(long handle);

    private static native float nativeProcessFrame(long handle, ByteBuffer buffer, int offsetFloats);
}
