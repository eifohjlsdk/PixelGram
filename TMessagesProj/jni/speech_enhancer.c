#include <jni.h>
#include <stdint.h>
#include "voip/rnnoise/include/rnnoise.h"

// JNI wrapper around the vendored RNNoise (voip/rnnoise) for org.telegram.messenger.camera
// .SpeechEnhancer - reuses the exact same compiled code already linked into libtmessages.49.so
// for VoIP group calls (see tgcalls/group/GroupInstanceCustomImpl.cpp), adding zero additional
// APK size. See SpeechEnhancer.java's class doc for the full design (per-recording-session
// lifecycle, frame-size handling, why a small per-chunk tail is left unprocessed).
//
// Fixed at 480 samples (10ms @ 48kHz) - this is rnnoise_get_frame_size()'s actual value in this
// vendored build (see denoise.c: "#define FRAME_SIZE (120<<FRAME_SIZE_SHIFT)" with
// FRAME_SIZE_SHIFT=2, i.e. 480), hardcoded here rather than queried at runtime since it cannot
// change without a source change to the vendored library, and hardcoding lets SpeechEnhancer.java
// size its native-side call loop without an extra JNI round trip. If the vendored rnnoise/denoise.c
// FRAME_SIZE constant is ever changed, this must be updated to match.
#define SPEECH_ENHANCER_FRAME_SIZE 480

JNIEXPORT jlong JNICALL Java_org_telegram_messenger_camera_SpeechEnhancer_nativeCreate(JNIEnv *env, jclass clazz) {
    // NULL model = the compiled-in default (voip/rnnoise/src/rnn_data.c) - there is no separate
    // weight file to ship, matching "model compiled in" for this library.
    DenoiseState *st = rnnoise_create(NULL);
    return (jlong) (intptr_t) st;
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_camera_SpeechEnhancer_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) {
        rnnoise_destroy((DenoiseState *) (intptr_t) handle);
    }
}

// Denoises exactly SPEECH_ENHANCER_FRAME_SIZE (480) samples in place, starting at
// offsetFloats floats into the given direct ByteBuffer, which must hold native-endian 32-bit
// float samples in Android's normalized ENCODING_PCM_FLOAT domain: [-1.0, 1.0].
//
// *** IMPORTANT - RNNoise's own float convention is NOT [-1.0, 1.0]. ***
// rnnoise_process_frame()'s "float *in"/"float *out" are what WebRTC (vendored in this same
// tree, see common_audio/include/audio_util.h) calls "FloatS16": the same numeric magnitude as
// 16-bit PCM, i.e. roughly [-32768.0, 32768.0], just stored as float instead of quantized to
// int16 - NOT the [-1.0, 1.0] "Float" convention Android's own AudioFormat.ENCODING_PCM_FLOAT
// uses, which is what every other buffer in this app's audio pipeline is in. rnnoise.h's own
// comments say nothing about range at all ("in and out must be at least rnnoise_get_frame_size()
// large") - the only place this distinction is documented in this entire codebase is WebRTC's
// audio_util.h, three directories away from rnnoise itself, so it is easy to miss.
//
// Getting this wrong does NOT crash or throw. It just makes RNNoise see [-1,1] input as if it
// were audio 32768x quieter than it actually is - i.e. near-silence - so the "denoised" output
// is a near-silent, badly-processed signal that LOOKS like "the denoiser barely does anything"
// rather than any obvious error. That failure mode is exactly why this scaling is applied here,
// once, right next to the actual rnnoise_process_frame() call, rather than left to callers.
// Returns RNNoise's own per-frame voice-activity probability in [0,1] (denoise.c: the local
// variable computed by compute_rnn()'s dedicated VAD output head is literally named "vad_prob"
// and returned directly - confirmed by reading the vendored source, not assumed from rnnoise.h's
// own comments, which say nothing about the return value at all). AdaptiveGainProcessor uses this
// to freeze its slow gain adaptation during non-speech - see FINDINGS.md's Adaptive Gain section.
JNIEXPORT jfloat JNICALL Java_org_telegram_messenger_camera_SpeechEnhancer_nativeProcessFrame(JNIEnv *env, jclass clazz, jlong handle, jobject buffer, jint offsetFloats) {
    if (handle == 0) return 0.0f;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;

    if (buffer == NULL || offsetFloats < 0) return 0.0f;

    // Defense in depth: never trust the caller's offset alone. A Java-side miscount (or any
    // future caller that doesn't respect SpeechEnhancer's own bookkeeping) must not turn into
    // an out-of-bounds native write - GetDirectBufferCapacity() is the only source of truth for
    // how large this buffer actually is, independent of whatever offsetFloats claims.
    jlong capacityBytes = (*env)->GetDirectBufferCapacity(env, buffer);
    if (capacityBytes <= 0) return 0.0f;
    jlong capacityFloats = capacityBytes / (jlong) sizeof(float);
    if ((jlong) offsetFloats + SPEECH_ENHANCER_FRAME_SIZE > capacityFloats) return 0.0f;

    float *base = (float *) (*env)->GetDirectBufferAddress(env, buffer);
    if (base == NULL) return 0.0f;
    float *samples = base + offsetFloats;

    float scaled[SPEECH_ENHANCER_FRAME_SIZE];
    for (int i = 0; i < SPEECH_ENHANCER_FRAME_SIZE; i++) {
        scaled[i] = samples[i] * 32768.0f;      // [-1, 1]  ->  FloatS16
    }

    float vadProb = rnnoise_process_frame(st, scaled, scaled);  // in-place on the rescaled copy

    for (int i = 0; i < SPEECH_ENHANCER_FRAME_SIZE; i++) {
        samples[i] = scaled[i] * (1.0f / 32768.0f); // FloatS16  ->  [-1, 1]
    }
    // Deliberately not clamped back to [-1,1] here - PixelGramSettings.applyMicGainFloat's soft
    // limiter (downstream, same as every other stage in this chain) is the one place clamping
    // happens, same as for the un-denoised signal.
    return vadProb;
}
