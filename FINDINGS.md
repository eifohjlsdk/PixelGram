# Findings: Pixel 11 Pro / Tensor G6 / Android 17

Measured 2026-08-22.

## Environment
- Pixel 11 Pro, codename grizzly, Android 17, SDK 37, build CD1A.260714.001.A9
- Upstream Telegram at 12.10.0 (7031). compileSdk 35, targetSdk 35, NDK 27.2.12479018

## Confirmed by measurement (stock Telegram, 2 recordings)
- Camera2 API is ON by default on this device. No debug menu toggle needed.
- Capture resolution 384x384 (MessagesController.roundVideoSize default, line 1635)
- Video bitrate ~1.01 Mbps (roundVideoBitrate default 1000)
- Audio bitrate ~65.6 kbps (roundAudioBitrate default 64)
- Encoder is told KEY_FRAME_RATE = 30 (InstantCameraView line 2113, 3210)
- videoEditedInfo.framerate hardcoded to 25 in three places (lines 2796, 2892, 3044)
- ACTUAL capture rate: front camera 58.6 and 59.1 fps, rear camera 39.1 fps
- Stock Telegram never sets CONTROL_AE_TARGET_FPS_RANGE. The ISP free-runs.
- Result: ~17,173 bits per frame at 59 fps vs 33,773 budgeted at 30 fps

## Confirmed by source reading (Telegram iOS)
- CameraDevice.swift line 6: defaultFPS = 30.0
- Camera.swift line 101: round video path explicitly requests 30.0
- CameraDevice.swift 152-153, 193-194: activeVideoMin/MaxFrameDuration both locked
  to the same negotiated value. Hard lock, not a hint.
- CameraUtils.swift line 18: actualFPS() negotiates against
  activeFormat.videoSupportedFrameRateRanges, preferring fixed ranges
- CameraOutput.swift line 374: AVVideoAverageBitRateKey = 1,000,000

## Conclusion
iOS and Android target the SAME 30 fps and SAME 1 Mbps for circles.
The entire quality gap on this device is that iOS enforces the frame rate at the
capture device and Android does not. Each iOS frame gets ~2x the bits.

## Ruled out
- Camera switch cost: measured one 0.106s gap. Cheap. updateOutputConfigurations()
  is NOT worth pursuing. Perceived switch slowness is UI/preview latency, elsewhere.
- Over-sharpening: frame inspection shows blocking and mush, not edge halos.
  EDGE_MODE_HIGH_QUALITY is not the problem at this bitrate.

## Open
- Both recordings show audio ~1s longer than video, only ~0.1s explained by
  gaps; cause unexplained. With the AE fps fix applied, the overrun dropped to
  0.43s (from 1.4-3.0s at free-run) but is still nonzero; cause still
  unexplained.
- Desync on playback of a SENT message not yet tested.
- Perceived sped-up playback on a stock (Play Store) circle recorded at
  free-running ~59fps. The Play Store file's own frame timestamps are uniform
  (58.98fps measured, per-frame interval min 0.0159, max 0.0198 - a tight,
  consistent spread, not compressed toward either end), so timestamp
  compression is not demonstrated as the cause. Mechanism behind the
  perceived speed-up remains unknown.

## Next change
None queued. Face-weighted AE (below) matched face luma to iPhone; the
remaining frame-luma gap looks like a deliberate iOS metering-target
difference (room vs. face), not a bug to chase further right now.

## Stock 12.10.0 build (measured 2026-08-22)
- Built unmodified upstream in a separate worktree at
  ../telegram-stock-12.10.0, checked out at origin/master 3f03bfc73 (tip;
  one commit after "update to 12.10.0 (7031)" / 4e1a61eca, which only fixes
  a submodule URL scheme).
- Toolchain (JDK 17, NDK 27.2.12479018, SDK 35) matched what that commit's
  build.gradle already specifies, no override needed.
- Upstream master at this commit does NOT build clean: 4e1a61eca added
  `api project(':jlatexmath')` in TMessagesProj/build.gradle but never
  added the corresponding `include ':jlatexmath'` + projectDir remap to
  the top-level settings.gradle. Confirmed origin/master has no later
  fix (already fully fetched, 3f03bfc73 is tip). Real upstream gap, not
  a local environment issue.
- Fix applied only in the stock worktree's settings.gradle (2 lines,
  registration only, no app/source change):
  ```
  include ':jlatexmath'
  project(':jlatexmath').projectDir = file('TMessagesProj/lib/jlatexmath/jlatexmath')
  ```
- With that fix: `./gradlew :TMessagesProj_App:assembleAfatDebug` succeeds,
  88 tasks, ~5min. APK at
  TMessagesProj_App/build/outputs/apk/afat/debug/app.apk (113MB).

## Correction to "Stock Telegram never sets CONTROL_AE_TARGET_FPS_RANGE" (measured 2026-08-22)
- That claim (in "Confirmed by measurement" above) was based on the Play Store
  install and is WRONG for the source at 12.10.0. Camera2Session.java (the
  round-video capture path, wired up from InstantCameraView via
  setRecordingVideo(true)) already hardcoded
  `captureRequestBuilder.set(CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(30, 60))`
  whenever recordingVideo is true, unconditionally, with no check against
  CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES.
- Hypothesis: (30, 60) is likely not itself in this sensor's supported range
  list, and the HAL silently ignores/free-runs on an unsupported target
  range rather than erroring - which would reconcile "code sets it" with
  "measured 58-59fps". Not yet directly confirmed; the new PixelCamera logs
  below (once a circle is recorded) should show whether (30,60) was ever a
  reported-available range on this sensor.

## Applied the "next change" fix in the stock worktree (2026-08-22, hypothesis pending re-measurement)
- telegram-stock-12.10.0 is no longer byte-for-byte upstream: modified
  Camera2Session.java (only) to replace the hardcoded (30,60) with a proper
  pick from CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES.
- At camera-open time (in the constructor, right after characteristics are
  fetched, for both front and rear sessions independently): reads all
  available AE target fps ranges, logs each with tag `PixelCamera` (camera
  id + front/rear), picks a fixed 30/30 range if offered, else the
  narrowest range containing 30, logs the chosen range or logs clearly if
  none contains 30. The chosen range (if any) replaces the old hardcoded
  value inside the existing `if (recordingVideo)` block in
  updateCaptureRequest() - same gating as before, only the value changed.
- Verified the new code path (not just source, the actual built dex)
  reached the installed APK before installing.
- Installed to device as org.telegram.messenger.beta (versionCode 70319,
  versionName 12.10.0), side by side with the untouched Play Store
  org.telegram.messenger.
- To capture the new logs while recording a circle:
  `adb -s <device-ip>:<port> logcat -s PixelCamera:D`
- Still open: whether this actually changes the measured capture fps
  (needs a new recording + ffprobe pass, see below).

## Result: AE target fps range fix (measured 2026-08-22)
- PixelCamera logs at camera-open showed [30,30] present in this sensor's
  CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, so the fixed-30 branch was chosen
  (not the narrowest-containing-30 fallback).
- Confirmed: [30,60] - the value upstream unconditionally hardcodes in
  Camera2Session - is NOT in this sensor's available range list. This
  confirms the hypothesis above: upstream's hardcoded value was never
  valid on this hardware, so it was silently ignored and the ISP free-ran.
- With [30,30] actually applied: measured 30.01 fps, mean frame interval
  0.0333s (vs the free-running 58-59fps/59-front measured earlier).
- Bits per frame: ~34,800, up from ~17,300 at free-run - matches the ~2x
  gap predicted in Conclusion (iOS gets ~2x the bits per frame vs stock
  Android at the same target bitrate).
- Audio overrun (audio duration minus video duration) dropped from
  1.4-3.0s (stock, free-running) to 0.43s with the fix applied. Improved
  ~4-7x, but not zero - remains open, see above.

## Result: resolution/audio bump + exposure compensation (measured 2026-08-22)
- 448x448 capture resolution and 96kbps audio bitrate (both hardcoded in
  InstantCameraView, replacing the account-configured roundVideoSize/
  roundAudioBitrate defaults) applied and measured on-device.
- +0.7 EV exposure compensation (CONTROL_AE_EXPOSURE_COMPENSATION, computed
  from CONTROL_AE_COMPENSATION_STEP and clamped to
  CONTROL_AE_COMPENSATION_RANGE, cached at camera-open) applied and measured.
- Average luma moved from 127.7 (fps-fix baseline, no compensation) to 135.4
  with +0.7 EV applied. The iPhone reference is 167.2. So +0.7 EV of global
  exposure compensation closes only about a fifth of the brightness gap to
  iOS (7.7 of the needed ~39.5 luma, roughly 20%).
- Subjective visual comparison: Pixel output at 400px is grainier and darker
  than the iPhone despite resolving more detail. Global exposure compensation
  alone is not enough - see Next change (AE_REGIONS from face detection).

## Result: face-driven AE regions + EDGE_MODE_FAST (measured 2026-08-22)
- Back-to-back comparison, Pixel vs iPhone:
  - Face luma: Pixel 110.6 vs iPhone 114.1 - matched.
  - Frame luma: Pixel 132.2 vs iPhone 167.5 - still a large gap.
  - Reading: Apple exposes for the room (brighter overall frame); our
    CONTROL_AE_REGIONS metering exposes for the face specifically. The
    two cameras are choosing different metering targets, not one being
    "wrong." Keeping +0.7 EV as the baseline compensation on top of
    face metering.
- EDGE_MODE_FAST: at 800% zoom, visibly removes the edge-enhancement
  halos on fine facial detail with no loss of sharpness. No audio/video
  desync introduced: audio 8.62s vs video 8.30s, a 0.32s gap, consistent
  with the gap measured before this change.
- Caveat: brightness comparisons taken at different times are not directly
  comparable; only back-to-back recordings should be compared. Treat
  absolute luma numbers as directional, not precise, until re-measured
  under controlled/matched lighting.

## Result: NOISE_REDUCTION_MODE + face-gated exposure compensation (measured 2026-08-22)
- NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES reports only 0/1/2
  (OFF/FAST/HIGH_QUALITY) on both cameras - MINIMAL (3) is unavailable
  on this sensor, so FAST was chosen (the preferred fallback).
- NOISE_REDUCTION_MODE_FAST visibly cleans up skin grain without losing
  detail, and causes no desync.
- The +0.7 EV exposure compensation (global, from the earlier result)
  was blowing out highlights on the rear camera outdoors, where there's
  no face to meter on. Fixed by gating it on currentFaceAeRect being
  non-null and explicitly clearing it to 0 otherwise. Confirmed this
  restores correct sky and foliage colour on the rear camera outdoors.

## Audio effect availability on this device (measured 2026-08-23)
- `AutomaticGainControl.isAvailable()` returns `false` on the Pixel 11 Pro.
  `NoiseSuppressor.isAvailable()` and `AcousticEchoCanceler.isAvailable()`
  both return `true`.
- This means AGC was never actually attached in any measurement taken on
  this device: the previous bundled "Audio Effects" toggle called
  `AutomaticGainControl.create()` behind the same `isAvailable()` gate, so
  it was a no-op here the whole time. Every earlier A/B result labelled
  "effects on" reflects `NoiseSuppressor` alone, not noise suppression +
  AGC together - re-read those results with that in mind rather than as a
  combined-effects comparison.
- Not necessarily true on other hardware - `isAvailable()` is a HAL/device
  capability query, so a different phone could support AGC while lacking
  one of the other two.

## Custom tonemap curve for backlit round video: investigated, dropped (2026-08-29)
- Round video sets no `CaptureRequest.TONEMAP_MODE` anywhere in `Camera2Session` - confirmed by
  reading back the actual applied `CaptureResult.TONEMAP_MODE` during a real recording:
  `TONEMAP_MODE_FAST` (value 1), the `TEMPLATE_RECORD` HAL default. (Note for future reference:
  the real enum is `0=CONTRAST_CURVE, 1=FAST, 2=HIGH_QUALITY, 3=GAMMA_VALUE, 4=PRESET_CURVE` -
  there is no `OFF` value for tonemap.) `TONEMAP_AVAILABLE_TONE_MAP_MODES=[0,1,2]` on both
  cameras, so `CONTRAST_CURVE`/`FAST`/`HIGH_QUALITY` are all available; `TONEMAP_MAX_CURVE_POINTS`
  is 81.
- Considered driving `CONTROL_AE_REGIONS` (face-metered exposure, already implemented) together
  with a custom `TONEMAP_CURVE` to fix backlit round video: subject correctly exposed without
  the window behind blowing out. A tonemap curve is a genuinely different lever from exposure
  compensation - it reshapes the *already-captured* dynamic range non-uniformly (lift shadows,
  roll off highlights, simultaneously, in one frame) instead of shifting the whole histogram by
  one scalar - so it's the right *category* of tool for dynamic-range compression.
- **Dropped anyway.** Per the Camera2 metadata contract, switching to
  `TONEMAP_MODE_CONTRAST_CURVE` doesn't add a custom curve on top of the device's own tone
  mapping - it replaces it entirely: "All color enhancement and tonemapping must be disabled,
  except for applying the tonemapping curve... 3D color look-up tables, selective chroma
  enhancement, or other non-linear color transforms will be disabled." `FAST`/`HIGH_QUALITY` are
  documented as potentially applying scene-dependent, spatially non-global processing that a
  single static curve can't replicate. That makes a fixed curve a bad trade on its own terms:
  strong enough to help a real backlit shot, it visibly flattens contrast and shifts color/skin
  tone in every ordinary shot; gentle enough to be safe normally, it does nothing useful for a
  real backlit shot, since the highlight compression needed scales with how far over the
  sensor's headroom the backlight actually is, and that varies shot to shot.
- **A fixed curve fundamentally can't solve this** - it would need to be adaptive, chosen or
  interpolated per-frame from a measurement of how blown-out the frame actually is (e.g. a
  clipping-fraction estimate from decoded frame luma, not anything available directly on
  `CaptureResult`), swapped on a throttle to avoid a visible curve-swap "pumping" artifact. That
  is real scene-classification work substantially beyond what face-AE required, and even an
  adaptive curve can't recover a window that was already clipped at the sensor before the curve
  ever runs - tonemap curves only remap already-captured values, they can't invent detail that
  was never captured. Not pursuing this further; see "Tone mapping quality" below for what
  shipped instead - the same idea in the cheap, safe form (choosing between the device's own two
  tonemap qualities, not replacing them).

## Tone mapping quality control (2026-08-29)
- Added a "Tone Mapping" row (Fast / High Quality, default Fast - matches this device's prior
  default) instead of the custom-curve approach above. This keeps 100% of Google's own tone
  mapping pipeline (color enhancement, adaptive/non-global processing) and just asks for the
  device's own more careful implementation of it - testable by eye, nothing given up if it
  doesn't help.

## Audio matrix measurement (measured 2026-08-29)
- Test conditions: fixed music source on PC speakers about 35cm behind and left of the phone,
  phone on a stand, front camera, 10 second recordings. Levels are mean dBFS unless noted.
- Baseline (default AudioSource, no effects, 1x gain): **-39.1dB mean**.
- Noise suppression, echo cancellation, and both together all landed within 0.4dB of baseline -
  they change noise *character*, not *level*. Expected: neither effect is a gain stage.
- Mic direction measured 0.2dB from baseline with `setPreferredMicrophoneDirection()` returning
  `true` (applied) - confirms the "no measurable effect" finding above wasn't a fluke; direction
  is inert on this device by this measure too, not just informally.
- Mic gain behaved as predicted by the linear multiplier math: 2x gave +6.3dB against a
  theoretical +6.0dB, 3x gave +9.7dB against a theoretical +9.5dB. Close enough that the
  remaining ~0.2-0.3dB is consistent with measurement noise, not a scaling error.
- `MediaRecorder.AudioSource.CAMCORDER` was worth about +4.7dB over the default source at the
  same gain - a real, repeatable gain difference between audio sources, independent of the mic
  gain multiplier.
- Best combination measured: **Camcorder + noise suppression + echo cancellation + 3x gain ->
  -24.7dB mean, peaks at -6.3dB, no clipping.** This is now the shipped default (see
  PixelGramSettings.DEFAULT_VOICE_ENHANCEMENT / DEFAULT_NOISE_SUPPRESSION /
  DEFAULT_ECHO_CANCELLATION / DEFAULT_MIC_GAIN). AGC stays off by default since it's unavailable
  on this device (see "Audio effect availability" above) - turning it on here would be a no-op.
  Mic direction stays off by default given the inert result above.

## Voice isolation measurement (measured 2026-08-29)
- Bandpass (90Hz high-pass + 7kHz low-pass, cascaded biquads) reduced sub-120Hz energy by
  2.4dB against a 1.7dB overall level drop - most of the removed energy is genuinely
  out-of-band rumble/handling noise, not speech content, as intended.
- The gate (downward expander below threshold, applied after the bandpass) produced about
  11dB of additional suppression during silence, while tracking within 1dB of bandpass-alone
  during actual speech - i.e. the gate is doing real work in the gaps without measurably
  touching the signal when someone is talking. Consistent with the hold-time/slow-release
  design intended to avoid chattering on pauses.
- This is against ordinary room noise (the intended target), not the music-at-35cm fixture
  used for the audio-level matrix below - see "Custom tone curve"/voice-isolation design notes
  elsewhere in this file for why that specific fixture is a much harder case this isn't
  expected to help with.

## Mic gain 4x/5x + soft limiter (2026-08-29)
- Added MIC_GAIN_4X and MIC_GAIN_5X. Headroom for going past 3x comes directly from the
  measurements above: the best combination in the original audio matrix peaked at -6.3dBFS,
  and the bandpass+gate voice-isolation path peaks around -15.6dBFS - both leave real room
  before 0dBFS.
- `PixelGramSettings.applyMicGain()`'s previous clamp was a genuine hard clip: any sample over
  `Short.MAX_VALUE`/under `Short.MIN_VALUE` was truncated exactly at that boundary, a sharp
  discontinuity in the transfer function that adds harmonic distortion on loud transients -
  not a limiter of any kind, just an overflow guard.
- Replaced it with a soft-knee limiter: below -3dBFS the signal passes through unchanged;
  above it, the excess is compressed through `tanh()` so output asymptotically approaches but
  never reaches 0dBFS - peaks round off smoothly instead of clipping. A hard
  `Short.MIN/MAX_VALUE` bounds check remains as a defensive backstop against rounding landing
  exactly on the int16 boundary, not as the active limiting mechanism.
- Measured at 5x (now the default) with the limiter in place: -25.8dB mean, -7.7dB peak, no
  samples near full scale - the limiter is only catching occasional transients, not compressing
  continuously. Confirms 5x is safely within headroom on this device, same conclusion as the
  4x step that preceded it.
- Per-sample cost: below the -3dBFS threshold (the common case for reasonably gain-staged
  audio) it's a comparison and a pass-through - negligible. Above threshold it adds one
  subtraction, one division, one `Math.tanh()` call, one multiply and one add - `tanh()` is a
  transcendental function, reasoned at roughly tens of nanoseconds per call on a modern ARM
  core (this is an order-of-magnitude estimate from tanh's known cost profile, not an
  on-device microbenchmark). Even in the worst case of every single sample exceeding threshold
  at 48kHz mono, that's under ~2.5ms of CPU per second of audio - well under 1% of one core,
  and the realistic case (only actual peaks touching the limiter) is far cheaper than that.

## Audio defaults, second revision (2026-08-29)
- Camcorder source, noise suppression on, echo cancellation on, AGC off (unavailable on this
  device), mic direction off (confirmed inert), voice isolation Bandpass + Gate, gate threshold
  -45dBFS, mic gain 4x - moved up from 3x now that the measurements above confirm the headroom
  to do so. Not a re-measured "best combination" in the same controlled sense as the original
  matrix - gain and voice isolation defaults here are set from the settled design/headroom
  reasoning above, expected to be retuned by ear once tested.

## AAC profile pinned; AVC profile/level measured (2026-08-29)
- Set `MediaFormat.KEY_AAC_PROFILE` to `AACObjectLC` explicitly on the round-video audio
  encoder, matching what Telegram X deliberately sets and stock leaves to codec default (see
  the client comparison below).
- We never request a video profile or level anywhere in `Camera2Session`/`InstantCameraView`.
  Read back the actually-configured `MediaCodec`'s output format on this device (via a
  temporary log at the `INFO_OUTPUT_FORMAT_CHANGED` callback, since removed): `profile=8`
  (`AVCProfileHigh`), `level=256` (`AVCLevel3`). The codec already resolves to **High profile**
  on its own - not Baseline. The premise for testing an explicit High-profile override doesn't
  hold on this device, so that test wasn't run; CABAC/B-frames are presumably already in play
  via the codec's own default choice. This is a per-device HAL default, not guaranteed
  elsewhere - a different device's default encoder could resolve differently.

## Client comparison: round video across stock, Telegram X, Cherrygram, us (2026-08-29)
- Fetched `github.com/TGX-Android/Telegram-X` (independent implementation) and used the
  existing `~/dev/cherrygram-ref` checkout (fork of the same stock base we started from).
- **No client ships HEVC for round video.** Stock, Telegram X, Cherrygram, and us all hardcode
  `video/avc`. This is universal, not a case of everyone else having moved on without us - no
  client has tested whether Telegram's servers or other clients actually accept an HEVC round
  video, so there's no existing precedent to lean on either way if we were to try it.
- **No client attaches any `AudioEffect` or applies any PCM-level processing to round-video
  audio besides us.** No `NoiseSuppressor`/`AutomaticGainControl`/`AcousticEchoCanceler`, no
  mic direction/field dimension, no gain or DSP on the raw PCM anywhere in stock, Telegram X's
  `RoundVideoRecorder.java`, or Cherrygram's `InstantCameraView.java` - confirmed by grep across
  each full source tree. Our audio-effects/mic-gain/voice-isolation work has no counterpart in
  any of the three other trees; it isn't something everyone else already tried and rejected.
- **Cherrygram's FPS range picker has the same bug we fixed.** `CherrygramCameraConfig`
  exposes a user-facing `cameraXFpsRange` setting including a "30 to 60" option
  (`CameraXFpsRange30to60`), applied to `CONTROL_AE_TARGET_FPS_RANGE` with no check against
  `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` before setting it - the exact upstream bug this
  worktree fixed (see "Correction to 'Stock Telegram never sets CONTROL_AE_TARGET_FPS_RANGE'"
  above): if `[30,60]` isn't actually in the sensor's available range list, the HAL is free to
  silently ignore it and free-run, the same way stock's hardcoded `(30,60)` did on this device.
  Cherrygram's other options (`25to30`, `30to30`, `60to60`) aren't verified against
  per-camera-per-device availability either - only `30to30` happens to be safe on sensors like
  this one that report a fixed `[30,30]` range.
- Full client-by-client detail (resolution/bitrate, audio source, encoder settings, which
  Camera2 keys each one sets) is in the session notes; not duplicated here to keep this file to
  measured/decided facts rather than a full research writeup.

## Microphone direction preference (measured 2026-08-29)
- `AudioRecord.setPreferredMicrophoneDirection()` returns `true` on the Pixel 11 Pro (both
  towards-user and away-from-user), but has no measurable effect. The active microphone
  reported by `getActiveMicrophones()` stays `address="bottom"` either way, and matched
  recordings (preference on vs. off) show no consistent audio level difference. A `true`
  return only means the platform accepted the request, not that it changed anything audible
  on this hardware - a different device could behave differently.
- `getActiveMicrophones()` has a metadata race worth knowing about, since it will confuse
  anyone reading that API's output: immediately after `startRecording()`, the framework's
  `native_get_active_microphones()` call comes back empty for the newly-active input (or its
  entries get filtered out by `AudioManager.setPortIdForMicrophones()` failing to match the
  current input port), so `AudioRecord.getActiveMicrophones()` falls back to
  `AudioManager.microphoneInfoFromAudioDeviceInfo(getRoutedDevice())` - a `MicrophoneInfo`
  built from nothing but the routed device, with `description="21"` (empty port name + numeric
  device id), `group=-1/-1`, and `directionality`/`position`/`orientation` all `UNKNOWN`. It
  settles to the full HAL-declared entry (`description="builtin_mic_1"`, `group=0/0`,
  `directionality=OMNI`, real `position`/`orientation` coordinates) about 1.5 seconds into the
  recording, confirmed by re-querying at that point in two separate test recordings. An idle
  probe that never calls `startRecording()` at all is permanently stuck in that same fallback,
  since there's never an active capture stream for the enrichment lookup to resolve against -
  it isn't a special case, it's the same branch with no active input to ever get past.

## GL supersampling for round video (2026-08-30)

Implemented: capture at the largest near-square `SurfaceTexture` size the sensor offers under a
1920px-long-edge cap (`Camera2Session.chooseSupersampleCaptureSize`) instead of requesting close to
the render target directly, then downscale to the configured resolution in two separable GL passes
(horizontal Lanczos-2, vertical Lanczos-2 + ordered dither) rather than the previous single 2x2-tap
bilinear resample. On the Pixel 11 Pro this picks 1920x1920 (front and rear both offer it) from
`[1080x1080, 1920x1920, ...]` - the only two genuinely square candidates; 1080x1080 was the smaller
alternative. Reused the 9-tap 1D Lanczos-2 windowed-sinc weights already shipped (unused) in
`res/raw/instant_lanczos_frag_oes.glsl` - that asset's own code path never runs because it's gated
behind `overlayHelper`, which is unconditionally non-null by the time it's checked in
`InstantCameraView.prepareEncoder()`, making the shipped asset genuinely dead code.

Three real bugs surfaced and fixed during this work, none of them in the downscale math itself:

1. **`SCALER_CROP_REGION` wasn't set at zoom=1.** Harmless at the old small capture size, but this
   sensor's active array (3440x2448, not square) needs *some* crop to produce a square 1920x1920
   stream, and the HAL's own undocumented default crop for that specific resolution wasn't
   vertically centered. Fixed by always setting an explicit, sensor-centered, aspect-matched crop
   (not just when zoomed).
2. **`CONTROL_ZOOM_RATIO` was never set**, and the HAL picked its own default once the capture
   stream got large enough - observed in logcat as `AHal::GsCapture: SetZoom: Update zoom from 0 to
   0.5` with no zoom input from the app. Fixed by pinning it explicitly to our own zoom model on
   every request (same "always set, never omit" fix as the AE_REGIONS one from 2026-08-29). Per
   `CaptureRequest.SCALER_CROP_REGION`'s own docs, once `CONTROL_ZOOM_RATIO` is in use the crop
   region must stay a fixed letterbox/pillarbox of the full active array - scaling it by zoom too
   ("windowboxing") is against spec and the framework silently overrides it back to full array
   whenever zoom ratio != 1 anyway, so the crop above is deliberately zoom-independent.
3. **The actual bug behind the reported "video shifted down, top ~30-40% black" symptom** was
   unrelated to both of the above (confirmed via CaptureResult readback showing our crop/zoom
   applied exactly as requested, and via one-off debug dumps of the raw OES texture, pass 1's
   output, and pass 2's own output in isolation - all real, non-black content up to the point pass
   2 writes it). It was floating-point overflow in the ordered-dither shader: the Bayer function
   squared the raw `gl_FragCoord.y` (up to ~380 for this frame size) before ever wrapping it to the
   4x4 tile size, and `y*y` for `y` above ~256 exceeds a `mediump`/FP16 float's ~65504 max
   representable value (256^2 = 65536). `fract()` of the resulting infinity/NaN corrupted the
   dither offset, which clamped a whole band of high-y (top-of-frame) rows to black when written to
   the 8-bit render target - the measured transition matched the 256/384 threshold almost exactly.
   Fixed by wrapping the coordinate to the tile size (`mod(gl_FragCoord.xy, 4.0)`) *before* the
   squaring, which is also the mathematically correct way to implement a periodic pattern and is
   immune to this regardless of precision or frame size.

Also confirmed (byte-for-byte): **Telegram does not re-encode round video server-side**, at least
not at this bitrate. Sent a circle at 384x384/1.2Mbps video/96kbps AAC, deleted every local copy
(the app's own cache tracks media under `files/`, not the OS-recognized cache dir, so Android's
Settings > Storage > Clear Cache doesn't touch it - had to delete the auto-saved copy in
`/sdcard/Download/Telegram/` directly), forced a genuine re-download (confirmed via logcat: real
`upload.getFile` chunk requests, not a cache hit) then an explicit Save to Gallery, and diffed the
result against the original. Identical SHA256, identical size, identical codec/resolution/bitrate
on both streams. Round video appears to be a pure pass-through - none of our client-side encoding
work is discarded or re-encoded before reaching the recipient, at least not below whatever cap (if
any) sits above the range tested here.

## Downscale filter, dither, resolution/bitrate options (2026-08-30)

Added a `DOWNSCALE_FILTER` setting (Lanczos-2 / Box / Gaussian - same 9-tap geometry, different
weights, see `PixelGramSettings`/`InstantCameraView`) so the supersample kernel is A/B-able, a
`DITHER_AMOUNT` setting (Off/0.5x/1x/2x LSB) since dithering is itself signal the encoder has to
carry, and extended Resolution to 320/384/448/480/512/640/960 and Video Bitrate to
800k/1.0/1.2/1.5/2/3/4/6 Mbps.

**A/B result: Lanczos-2 looked clearly better than Box or Gaussian**, despite the ringing concern
that motivated offering the alternatives - the softness cost of a ringing-free kernel outweighed
whatever bits ringing costs the encoder, at least at the bitrates tested. Kept Lanczos as the
default.

**Resolution matters more than bitrate, and bitrate above ~1.2Mbps produced no visible improvement
at 384px** - i.e., round video at 384 is pixel-limited, not bitrate-limited; throwing more bits at
a small frame doesn't buy anything once you're already comfortably encoding it. Higher resolutions
look better at the same bitrate. **Default resolution moved to 480px** (default filter stays
Lanczos, already the default before this).

### Bug (resolved): declared dimensions wrong, not a rendering or protocol limit

640px originally rendered with a black rim around the circle (as if content were drawn smaller
than the mask expects); iOS rendered the same 640px send as a square instead of round. These
looked like two unrelated, platform-specific problems - a rendering defect on Android, a size
policy or decoder fallback on iOS - and were investigated as such.

**Root cause, confirmed: `VideoEditedInfo.resultWidth`/`resultHeight` were hardcoded to the legacy
`360` round-video size** in three places in `InstantCameraView.java`, regardless of the configured
resolution. These values flow straight into `TL_documentAttributeVideo.w`/`.h`, which is the only
place any round-video player - every platform, ours included - learns the frame's dimensions
before decoding. A player sizes its circular mask/viewport from that *declared* value, not the
actual decoded resolution, so a 640px video declared as 360px shows real content only within a
"360px-sized" circle relative to a 640px frame - a visible black rim on Android's masking, and
apparently enough of a mismatch that iOS's round-video path didn't accept it as a valid round
message at all and fell back to plain video display. Same underlying bug, two different-looking
symptoms depending on how strictly each platform's video pipeline reacts to a self-contradictory
declaration.

The first fix pass only actually corrected one of the three call sites (the other two sit inside a
lambda with different indentation, so a literal-text `replace_all` silently missed them despite
reporting success) - once all three were genuinely fixed to declare the real `videoWidth`/
`videoHeight`, **640px rendered correctly as a circle on Android and iOS both**. This is why it
looked like a platform-specific rendering limit or policy: it was the same declared-dimension bug
presenting differently depending on how each platform's video stack responds to a false
declaration, not two separate constraints.

The `round_blur_stage_2_frag.glsl` `mediump`→`highp` change (still in place) was a red herring for
this bug specifically - a real, demonstrable-in-isolation FP16 overflow risk in that shader at
large frame sizes, but not what was causing either symptom here. Left as a harmless, free precision
improvement, decoupled from this investigation.

### 960px: a separate, real server-side ceiling

With the dimension bug fixed, 960px *still* renders as a square on send - but this happens at
upload time rather than in any client's own rendering, meaning the server itself is declining to
treat it as a round message and reclassifying it as a normal video. This is a distinct, genuine
constraint (unlike the 512px "nobody's tested higher" caution retracted above, which rested
entirely on the now-fixed declared-dimension bug and had no other basis).

**Bracketed and closed.** 720, 704, 672, and 656 all fail the same way (square fallback on send);
640 is confirmed good on Android, iOS, and web. The ceiling is therefore somewhere in 640-655
inclusive, with 640 itself the most likely value given it's also the largest clean-integer
downscale ratio (3:1) from the 1920px supersample capture. 640 is set as both the maximum offered
in the resolution picker and the default - no further bracketing planned unless a reason to test
641-655 individually comes up.

## Image quality defaults revised for the supersample-capture pipeline (2026-08-30)

Defaults changed: resolution to 480px, noise reduction off, edge mode off, face-weighted AE
metering off, exposure compensation 0.0, tone mapping stays Fast, downscale filter stays Lanczos.

**Resolution: 480px.** Clean 4:1 downscale from the 1920px supersample capture. Chosen as a
cautious value before the declared-dimension bug above was fully understood - now that 640px is
confirmed working correctly on both Android and iOS, and the real ceiling is being bracketed
between 640 (good) and 960 (rejected server-side), expect this default to move up once that
ceiling is found (see "960px: a separate, real server-side ceiling" above).

**Superseded same day**: default moved to 640px once it was confirmed working on Android, iOS, and
web - a clean 3:1 downscale from the 1920px capture. Also the final value: the server-side ceiling
bracketing (see "960px: a separate, real server-side ceiling" above) closed with 640 as both the
practical maximum and the default, so this won't move up further.

**Noise reduction and edge mode: off (previously Fast/Fast).** Both were tuned back when the ISP
did the *entire* resolution reduction with no oversampling margin at all - real-time capture-stage
NR and edge enhancement were doing genuine, necessary work at that point, since the sensor's own
scaler was the only thing standing between raw sensor noise/softness and the final small frame.
Now that round video captures at 1920 and Lanczos-downscales to the render target, that single
downscale pass does both jobs far more effectively than the ISP's real-time processing ever could:
averaging many source pixels into each output pixel is inherently a denoise, and Lanczos's own
negative lobes sharpen edges as an inherent property of the kernel - not a coincidence, but the
actual mechanism A/B testing already confirmed made Lanczos look better than Box/Gaussian despite
the ringing risk (see the downscale filter finding above). Capture-stage NR/edge processing is
consequently redundant now, and off is the better default - it also means the ISP applies zero
processing before the data ever reaches the downscale, matching the "highest possible detail into
the resampler" logic supersampling was chosen for in the first place.

**Tone mapping: Fast vs. High Quality showed no visible difference across two separate tests.**
Kept Fast, the cheaper of the two.

**Face-weighted AE metering and exposure compensation: off / 0.0.** These were tuned for the
backlit-subject use case investigated earlier in this session, independent of the resolution/
downscale pipeline work; reset to neutral defaults alongside the capture-processing changes above
rather than carried forward untested against the new pipeline.

## INFO_SUPPORTED_HARDWARE_LEVEL definitively FULL on both cameras (2026-08-30)

Earlier reports of `INFO_SUPPORTED_HARDWARE_LEVEL` disagreed (one dump said LIMITED for both
cameras, a later one said FULL for the front). Re-ran `PixelCapsDump` live against the current
device rather than relying on a stale saved dump: **both cameras report FULL** -
`android.info.supportedHardwareLevel = 1` for camera0 (rear, `lens.facing=1`/BACK) and camera1
(front, `lens.facing=0`/FRONT) alike. `android.request.availableCapabilities` confirms
`MANUAL_SENSOR` (1) and `MANUAL_POST_PROCESSING` (2) present on both (rear additionally has
`CONSTRAINED_HIGH_SPEED_VIDEO`/9, which front lacks).

**This means the LIMITED assumption behind writing off shutter-speed capping and manual exposure
was wrong**, on this device at least. FULL guarantees (and `MANUAL_SENSOR`'s presence confirms)
real per-frame manual control that's not available under LIMITED:
- `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` (ISO) set directly, independent of the AE
  algorithm - the actual mechanism for a genuine minimum-shutter-speed cap (force AE to never drop
  exposure time below some floor even in low light, trading noise for less motion blur), not
  achievable through `CONTROL_AE_EXPOSURE_COMPENSATION` alone. Rear: exposure time
  40,852ns-16,000,000,684ns (up to 16s), sensitivity 24-6023. Front: 68,360ns-1,000,000,628ns (up
  to 1s), sensitivity 55-19692.
- `SENSOR_FRAME_DURATION` manual control, and `MANUAL_POST_PROCESSING` (manual tonemap curve,
  color correction transform/gains) - the tonemap-curve investigation earlier in this session
  correctly assumed this capability existed and was right to reject it on ISP-adaptive-behavior
  grounds, not availability grounds.
- Not yet implemented; noted here as newly-confirmed-available, not yet acted on.

## AVC profile/level pinned explicitly on the round-video encoder (2026-08-30)

`KEY_PROFILE` set to `AVCProfileHigh`, `KEY_LEVEL` to `AVCLevel31`. This device's encoder already
resolves to exactly that by default (confirmed by reading back `getOutputFormat()` at
640x640/1.2Mbps: `profile=8 level=512`), but plenty of Android hardware encoders default to
Baseline profile unless told otherwise, silently losing CABAC and B-frames for meaningfully worse
compression at the same bitrate - pinning removes the dependency on a given device's own default.
Level 3.1's frame-size limit (3600 macroblocks = 921,600px) covers every resolution this app
currently offers with margin, except 960x960 which lands exactly at that limit (60x60 macroblocks
= 3600 exactly) - technically in spec but with zero headroom, worth keeping in mind if 960 survives
the ongoing resolution-ceiling bracketing above.

## Exposure cap (Path A): implemented, measured, dropped (2026-08-30)

Implemented and shipped, then reverted the same day after measurement showed it made output worse,
not better. Recorded here so the dead end isn't rediscovered.

The `[60,60]` fps-range + 2:1 decimation approach (see prior commit, now reverted) genuinely capped
`SENSOR_EXPOSURE_TIME` at ~16.3ms in dim light, exactly half the ~32.9ms the uncapped `[30,30]`
range pinned at. That part worked precisely as designed. The problem: `SENSOR_SENSITIVITY` stayed
identically at 1231 in both capped and uncapped dim-room clips - AE did not raise gain at all to
compensate for the halved exposure time, despite the sensor's range going to 19,692 (~16x headroom
above 1231).

**Quantified**: mean frame luma (`ffprobe`/`signalstats`, gamma-encoded 0-255) was 69.75 for the
capped clip vs. 96.30 uncapped. Decoded through ~2.2 gamma to linear light, that's a 2.03x ratio -
almost exactly one stop darker, precisely what halving exposure time with zero sensitivity
compensation predicts. This is straightforward underexposure, not the intended blur-for-noise
trade.

**Tested whether positive exposure compensation rescues it - it does not.** Set
`exposure_compensation_ev` to +1.0 (6 steps at this device's 1/6 EV step) while the cap was active
and re-recorded the same dim room. Logged `CONTROL_AE_STATE` and the applied EV alongside the usual
exposure/sensitivity readback:
```
exposureCap=true targetFpsRange=[60, 60] SENSOR_EXPOSURE_TIME=16.408804ms SENSOR_SENSITIVITY=1231
CONTROL_AE_STATE=CONVERGED appliedEvSteps=6 (1.0EV)
```
`SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` are bit-for-bit identical to the 0EV capped test.
The HAL echoed the +1EV target back as applied and reports `CONVERGED` (not `SEARCHING`) - it isn't
struggling to reach the target, it simply isn't using the available gain headroom to reach it at
all while the fps range holds exposure time at its floor. Whatever this device's 3A tuning does to
decide gain, it's not driven by `CONTROL_AE_EXPOSURE_COMPENSATION` once frame duration is
fps-range-constrained. Un-chased, since it isn't ours to fix: this is opaque vendor 3A behavior,
not something reachable through any public Camera2 key.

**Decision**: darker output is worse than the motion blur it was meant to trade against for a
talking-head circle, and the one lever available to fix it (EV compensation) doesn't work. Dropped
the feature entirely (setting, fps-range-60 request, frame decimation, marker-line field, and
logging) rather than shipping it off-by-default with a known-broken on state.

## ffprobe's r_frame_rate guesser can report 2x the real rate for a decimated stream

Kept as general knowledge even though the feature that surfaced it (exposure cap, above) was
dropped - relevant again if this codebase ever decimates frames for another reason, or if a similar
"declared vs. measured" frame-rate report needs debugging.

While the exposure cap was active (60fps capture, 2:1 decimation to a genuine 30fps output),
`ffprobe` reported `r_frame_rate=60/1` for the output file despite every real signal saying it was
30fps: `avg_frame_rate` computed to ~29.93, and direct per-packet `duration_time` was uniformly
~0.033333s. Root-caused, and it's not a metadata bug: the H.264 SPS's VUI `timing_info_present_flag`
is 0 (no VUI timing at all), and the container's own STTS table is genuinely ~30fps (3000/3001-tick
deltas, 90000 timescale) - there's no wrong value stored anywhere.

`ffprobe -v 48` shows its own reasoning: **`r_frame_rate` isn't an average, it's the finest time
grid every observed sample duration is (approximately) an integer multiple of**, chosen by lowest
total quantization error across candidates (`rfps: 60.000000 0.000085` beat
`rfps: 29.833333 0.019510`). Two frames in the capped clip carried ~50ms gaps (4500/4481 ticks) -
clean multiples of 1500 (60fps) but 1.5x (non-integer) multiples of 3000 (30fps) - and those two
outliers alone were enough to tip the guess to 60, even though they're a small minority of ~465
samples overwhelmingly sitting at 3000/3001.

**Mechanism, generalizable beyond this specific feature**: kept frames carried real hardware
capture timestamps from a genuine 60fps source before decimation, so any real-world jitter in that
source (a frame arriving a fraction of a tick early/late) lands in 1500-tick-sized steps rather than
3000-tick ones once every-other-frame is dropped. Decimating a *real* timestamped stream down to a
lower nominal rate preserves the original stream's finer timing granularity in whatever residual
jitter exists, and ffprobe's grid-fitting heuristic is sensitive to exactly that: a handful of
outliers that happen to be clean multiples of the *pre-decimation* period can outweigh the
overwhelming majority sitting at the *post-decimation* period. A real fix, if frame decimation
returns in some other form, is snapping each kept frame's output PTS to the nearest exact grid point
of the *target* rate rather than passing the raw pre-decimation timestamp through - not implemented
here since it became moot once the exposure cap itself was reverted.

## Audio input capabilities and float capture (2026-08-30)

Same logic as the video supersampling work: round video previously captured audio at exactly
what the encoder needs (16-bit PCM, 48kHz mono), leaving no headroom for the DSP chain (voice
isolation bandpass/gate, then up to 5x mic gain) ahead of it. Investigated the input path's real
headroom before changing anything - see `PixelCapsDump.dumpAudioInputCapabilities()`.

**Native mic rate**: `AudioDeviceInfo.getSampleRates()` on the built-in mic reports `[48000]` - a
single declared value, not "arbitrary" (an empty array) and not a higher rate we're downsampling
from. 48kHz is the platform's own native rate for this mic, not a resampled target. Requesting
96kHz/192kHz is accepted (AudioFlinger upsamples transparently), but since the mic's native rate is
exactly 48000, that headroom is synthetic - not pursued.

**Format headroom, confirmed available**: `ENCODING_PCM_FLOAT`, `PCM_24BIT_PACKED`, and `PCM_32BIT`
all construct successfully (`STATE_INITIALIZED`) at 48kHz mono on this device. Float was chosen
since the DSP chain already works in float internally (see `VoiceIsolationProcessor`'s class doc).

**Channels**: the physical mic reports `channelCounts=[1,2,3,4]` and both PCM_16BIT and PCM_FLOAT
stereo construction succeed. Not acted on - confirming whether a multi-channel stream carries
distinct per-channel content or duplicated mono requires an actual live capture-and-compare, which
the capability dump deliberately doesn't do (construct-only, no live audio - see its class doc).
Flagged as an unexplored avenue, not a finding either way.

**`AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` = `"true"`**, and constructing with
`AudioSource.UNPROCESSED` succeeds. Added as a 5th Voice Enhancement option (gated on that property
being present, unlike the other 4 options which have no equivalent query), since it's the only
source the platform documents as genuinely unprocessed - no AGC/NS/AEC applied ahead of the app.

**Implementation**: switched round-video capture (`InstantCameraView`, not the separate
voice-message recorder in `MediaController` - see below) to `ENCODING_PCM_FLOAT`, with a runtime
fallback to PCM_16BIT if construction doesn't actually report `STATE_INITIALIZED` (the platform
docs don't guarantee float for the *record* direction the way they do for playback, so this isn't
assumed even though it's confirmed available on the dev device). This surfaced a bigger issue than
a one-line format swap: `VoiceIsolationProcessor.process()` and `PixelGramSettings.applyMicGain()`
were each already converting to float internally, doing their DSP, and quantizing *back* to int16
before handing off to the next stage - meaning the signal was round-tripping through 16-bit twice
between capture and the encoder, not once. Added float-native `processFloat()`/`applyMicGainFloat()`
overloads (sharing the same DSP math via a new `filterOneSample()` helper in
`VoiceIsolationProcessor`) so the signal now stays in float from capture through both DSP stages.
The MediaCodec AAC encoder's input buffer only accepts 16-bit PCM regardless of capture format, so
quantization to int16 now happens exactly once, at that hand-off, converting from float there
instead of a raw byte copy. Also updated every byte/sample-count-dependent calculation this
touches (buffer duration accounting, the RMS amplitude meter, encoder buffer sizing) to be
bytes-per-sample-aware rather than hardcoding 2. Added `audioCapture:float`/`pcm16` to the
recording marker line.

**Not applied to voice messages initially.** `VoiceIsolationProcessor`/`applyMicGain` are shared
with `MediaController`'s separate voice-message recorder, which also captures 16-bit PCM at 48kHz
mono - but that path feeds a native JNI Opus encoder and drives the waveform-preview UI, both deep,
heavily-used stock Telegram code unrelated to anything this fork added. Left it on the existing
int16 `process()`/`applyMicGain()` methods at first; the new float-native overloads were added so
that path could be converted later. **Superseded same day** - it was converted; see "Voice-message
recording converted to float, all the way to Opus" below.

**Verified with a real recording**, not just a clean compile: marker line confirmed
`audioCapture:float` was actually active, and the resulting file decodes cleanly (`ffmpeg -f null`,
zero errors), 48kHz mono as expected, sample values well within range (-11701..13266 of ±32768,
zero near-full-scale samples - no clipping or garbage), DC offset ~0, peak/RMS levels sane
(-7.9dBFS peak, -25.5dBFS RMS). No sign of the corruption a float/int16 byte-count mismatch would
have produced.

## Voice-message recording converted to float, all the way to Opus (2026-08-30)

Round video's float-capture switch above deliberately left `MediaController`'s voice-message
recorder alone, since it feeds a native JNI Opus/Ogg encoder rather than MediaCodec. Converted it
too, and it comes out better than the round-video case: **Opus accepts float samples natively**
(`opus_encode_float()`, part of the vendored libopus's public API - confirmed present in
`third_party/xiph/opus/include/opus.h`), so this path needed no int16 quantization step at all,
unlike round video's AAC encoder which forces one regardless of capture format.

**Native change**: `audio.c`'s `writeFrame()` (int16, calls `opus_encode()`) had its Ogg-muxing
logic (post-`opus_encode`, packet/page bookkeeping) factored into a shared `muxOggFrame()` helper,
then a new `writeFrameFloat()` sibling added that calls `opus_encode_float()` on the same
`_encoder`/`_packet`/`ogg_stream_state` and delegates to the same `muxOggFrame()` - the two are
never called concurrently (one recording session at a time, same as the rest of this file's global
state). New `Java_..._MediaController_writeFrameFloat` JNI export; confirmed both symbols present
and linked via `nm -D` on the rebuilt `.so`.

**Java side**: `MediaController` gained the same `createAudioRecorder()` float-first/int16-fallback
pattern as `InstantCameraView`, using the float-native `processFloat()`/`applyMicGainFloat()`
overloads added for round video. One extra subtlety specific to this path: `fileBuffer` (the
per-Opus-frame accumulator) was a fixed 1920 bytes = exactly `frame_size` (960 samples) * 2 bytes.
`writeFrame`/`writeFrameFloat` silently zero-pad any buffer shorter than `frame_size`, which is
only correct for the genuine final partial frame at end-of-recording - if `fileBuffer` weren't
resized to `960 * audioBytesPerSample` for float (3840 bytes), it would fill at half a frame every
time in float mode, and every non-final flush would get zero-padded mid-recording, splicing
silence into the audio. Now reallocated per-recording, sized to the actual chosen format.

## The 4 declared input channels carry distinct audio, not duplicated mono (2026-08-30)

`AudioDeviceInfo` reports `channelCounts=[1,2,3,4]` for the built-in mic (see the audio
input-capability investigation above) - worth checking directly whether that's 4 genuinely
distinct capsules or one signal duplicated across channels in software, since only the former is
useful for anything. Added `PixelCapsDump.runChannelTest()` (a documented exception to the class's
usual construct-only contract - this one actually records ~2s via `AudioRecord.Builder` with
`channelIndexMask=0b1111`, matching the device's declared `channelIndexMasks`) and compared
channels directly, then via a WAV pulled off-device.

**Not duplicated.** No channel pair is bit-identical. RMS differs meaningfully per channel (761,
482, 488, 405 in one ambient-room capture) and cross-correlation against channel 0 ranges from 0.10
to 0.82 depending on the pair - exactly the signature of physically-separated real microphones
picking up the same acoustic environment with different attenuation/phase/shadowing, not a
software copy (which would show identical RMS and correlation ≈1.0 on every pair). This overturns
the earlier "only one microphone is accessible" conclusion from prior mic-direction/field-dimension
work - see the follow-up immediately below for what (if anything) that's actually good for.

## Four-mic array: distinct channels confirmed, but none beats plain MIC mono (2026-08-30)

Follow-up to the channel-duplication test above. Investigated what's actually reachable now that 4
distinct capsules are confirmed, before touching anything - then tested the simplest hypothesis
directly. Measured twice: a first pass whose methodology turned out to be unreliable in two
different ways (both documented below since they're worth not repeating), then a clean re-measure.

**Physical correspondence and channel order: not documented, and only weakly inferable.**
`AudioDeviceInfo.getChannelIndexMasks()`'s raw index-channel ordering has no public,
vendor-independent specification - it's whatever order the HAL exposes, and nothing in the
platform API guarantees a stable or documented mapping from index to physical capsule. The
correlation pattern from the duplication test (ch0-ch3 at 0.82, ch0-ch1 at 0.39, ch0-ch2 at 0.10)
is suggestive of relative physical proximity (closer mics should be more correlated against the
same ambient sound) but this is inference from one uncontrolled ambient recording, not a
confirmed mapping - not treated as fact.

**`MicrophoneInfo` geometry is unreliable for the multi-channel case, and inconsistent even for
the existing 2-mic mono case.** `AudioRecord.getActiveMicrophones()` during the round-video hook
(2-channel default mono routing) returned real position/orientation data in one run - `id=21
address="bottom" position=(0.0269, 0.0058, 0.0079) orientation=(0,0,1)` and `id=22 address="back"
position=(0.0546, 0.1456, 0.00415) orientation=(0,1,0)` - but a later run of the *same* hook
returned `id=22` with `position=UNKNOWN orientation=UNKNOWN`, meaning this data isn't reliably
populated even outside the new multi-channel path. Worse, during the actual 4-channel capture,
`getActiveMicrophones()` reported only **one** mic (`id=21`, bottom), not four - it does not
enumerate all capsules contributing to a raw multi-channel stream on this device. Conclusion: this
API is not a usable source of per-channel physical geometry here, multi-channel or not.

**Three measurement artifacts found and eliminated before trusting any number here** - all worth
recording since they'll bite again if this diagnostic tooling (or this kind of test) is reused
carelessly:

1. The first pass drove all 4 configs through `PixelCapsDump.runMicComparisonTest()` via chained
   `adb shell am start` calls, force-stopping the app between each to guarantee the `onCreate`-gated
   trigger actually fired. That also interrupted the tester's continuous speech each time, so the
   per-config levels weren't comparable speech under comparable conditions - discarded entirely.
2. On the re-measure, the raw 4-channel capture briefly showed channels 1-3 going to exact digital
   zero for a solid 2.1s block partway through a 5s recording (98.9% zero-mask agreement between
   the three, channel 0 unaffected but showing a coincident 90ms glitch at the same instant) - at
   first read as a platform/HAL limitation on sustained multi-channel capture. It wasn't: the
   tester had a round-video recording already in progress in the real UI (its own independent
   `AudioRecord` session on the same mic hardware) when the diagnostic capture's trigger fired,
   because the "go" cue from the mono configs (where it meant "start recording a circle") carried
   over. Two concurrent `AudioRecord` sessions contending for the same hardware, not a hardware
   ceiling - confirmed by re-running the identical capture in isolation (nothing else touching the
   mic), which showed no dropout at all (~1% exact-zero samples, all in the first ~20ms startup
   blip, identical and normal on every channel). Re-running this diagnostic tool again: make sure
   nothing else is actively recording at the same time.
3. The mono configs and the 4-channel test were all recorded against a podcast playing from a
   second phone positioned ~25cm directly *underneath* a vertically-held Pixel, not the tester's
   own voice at arm's length. That matters: the "bottom" mic (`id=21`, `orientation=(0,0,1)`) points
   almost straight at a source in that position, and `AudioSource.CAMCORDER` on many devices
   applies directional/spatial tuning that assumes a source *in front of* the phone (what a
   camcorder normally films), not underneath it - a real risk that this geometry specifically
   disadvantaged `CAMCORDER` and advantaged whichever raw channel happened to be best-aimed at the
   speaker underneath. Re-measured the mono configs (only - see below for why the raw-channel
   comparison wasn't repeated) with the tester's own voice, phone held at actual arm's length as
   for a real round-video recording. Results held up closely: `CAMCORDER` was identical (14.6dB
   both times), `MIC`/`DEFAULT` and `UNPROCESSED` both landed within ~2dB of the original numbers.
   The geometry confound turned out not to change the outcome, but was still the right thing to
   check rather than assume.

**Raw-channel comparison not repeated under corrected geometry.** Its case was already weak before
the geometry issue came up - even the best channel only marginally beat the *current* `CAMCORDER`
default and clearly lost to `MIC`/`DEFAULT` - and fixing geometry doesn't touch the three
independent problems that make the raw array impractical regardless: channel order is undocumented
(see above), `MicrophoneInfo` geometry reporting is unreliable (see above), and the diagnostic
capture mode has now been shown fragile under concurrent mic contention (artifact 2 above). Not
worth the retest.

**Final mono comparison, proper geometry, tester's own voice at arm's length, matched settings
(mic gain 1x, voice isolation/echo cancellation/noise suppression all off), SNR via 50ms-windowed
RMS (10th percentile = noise floor, 90th = speech level, bandpassed 100-6000Hz first):**

| config | SNR (p90/p10) |
|---|---|
| `CAMCORDER` mono (previous production default) | 14.6dB |
| `MIC`/`DEFAULT` mono | **20.2dB** |
| `UNPROCESSED` mono | 18.9dB |

`MIC`/`DEFAULT` beats `CAMCORDER` by ~5.6dB SNR, `UNPROCESSED` by ~4.3dB - see "Round video's
default AudioSource switched to MIC/DEFAULT" below for the reconciliation against the earlier audio
matrix measurement (which found `CAMCORDER` *louder*, not cleaner) and the resulting change.

For completeness, the earlier (podcast-underneath, not repeated) raw-channel numbers: no raw
channel beat `MIC`/`DEFAULT` there either - ch0 (best of the four) was 6dB worse, the other three
3.5-5dB worse still. ch0 modestly beat the *previous* `CAMCORDER` default (16.0 vs 14.6dB), but
that's still a weak case for adopting raw multi-channel capture given the undocumented
channel order/geometry above and the contention-fragility just demonstrated.

**The bigger, much simpler finding: plain `MIC`/`DEFAULT` beats `CAMCORDER` with no multi-channel
work needed at all** - see "Round video's default AudioSource switched to MIC/DEFAULT" below,
which acts on exactly this.

**Practical consequence for beamforming.** A textbook delay-and-sum beamform over 4
incoherent-noise mics buys at most ~10log10(4) = 6dB of SNR improvement in the ideal case - not
enough to turn any raw channel into a `MIC`-beating signal on its own, on top of needing solved
channel-order/geometry problems that don't currently have answers on this device.
**Recommendation: don't pursue channel selection or DIY beamforming for round video/voice
messages.** The 4-channel access this session confirmed is real, but not usable to improve on
`AudioSource.MIC`/`DEFAULT` for this use case.

## Round video's default AudioSource switched to MIC/DEFAULT (2026-08-30)

The mono comparison above (proper arm's-length geometry, tester's own voice) measured
`AudioSource.DEFAULT` (the "MIC" config, exposed in the menu as Voice Enhancement "Off (raw mic)")
at ~5.6dB better SNR than `CAMCORDER`, and ~4.3dB better than `UNPROCESSED`. Switched
`PixelGramSettings.DEFAULT_VOICE_ENHANCEMENT` from `VOICE_ENHANCEMENT_CAMCORDER` to
`VOICE_ENHANCEMENT_OFF` (i.e. `AudioSource.DEFAULT`) on this basis.

**Reconciling against the original audio matrix measurement, which found the opposite-looking
result.** That earlier measurement (see "Audio matrix measurement" above) found `CAMCORDER` about
**+4.7dB louder** than the default source at the same gain - which is why `CAMCORDER` was chosen as
the shipped default in the first place. Both measurements are true simultaneously: `CAMCORDER`
applies a far-talk gain boost intended for camcorder-style use (subject at a distance, not held to
the mouth), and that boost raises the noise floor right along with the speech level - it's a gain
stage, not a cleanup stage, so it doesn't change *SNR*, only *level*. The matrix measurement was
about which source is louder; this measurement is about which source is cleaner, and they're
answering different questions. Since round video already has its own controllable gain stage (the
mic gain multiplier, 1x-5x) with a soft limiter to catch the top end, level is not the scarce
resource - SNR is, because no downstream gain stage can improve a ratio that's already fixed by
the source. The cleaner-but-quieter source is the better starting point precisely because we can
always add gain back cheaply, but we can't remove noise that's already baked into a noisier source.

## Speech enhancement selector: RNNoise added, DeepFilterNet blocked on licensing (2026-08-30)

Investigated before implementing (see the prior report in conversation, summarized here for the
record): a two-way Off/RNNoise selector, applied first in the audio chain - before
VoiceIsolationProcessor's bandpass/gate and before applyMicGain(Float) - for both round video and
voice messages, deliberately not touching VoIP calls.

**RNNoise: already in this codebase, zero additional cost.** `TMessagesProj/jni/voip/rnnoise` is
the genuine Xiph RNNoise (BSD-2-clause, confirmed via its `COPYING` file - fully GPLv2-compatible,
and this repo's own `LICENSE` is GPLv2), already compiled as its own CMake static-library target
and already whole-archive-linked into `libtmessages.49.so` for VoIP group calls (its only existing
caller: `tgcalls/group/GroupInstanceCustomImpl.cpp`). Adding a second caller for the recording path
reuses the exact same compiled code - no new binary size, no new licensing question, and no
interaction with the VoIP call site (confirmed no shared state - it's a separate call site
entirely). Implementation: `speech_enhancer.c` (new, added to `jni/CMakeLists.txt`'s source list)
+ `SpeechEnhancer.java`, mirroring `VoiceIsolationProcessor`'s per-recording-session lifecycle.

**FloatS16 scaling - the one non-obvious gotcha, documented prominently in the code.** RNNoise's
`float *in`/`float *out` are not Android's `[-1.0, 1.0]` `ENCODING_PCM_FLOAT` convention - they're
what WebRTC's own `common_audio/include/audio_util.h` (vendored in this same tree) calls
"FloatS16": the same numeric magnitude as 16-bit PCM (`[-32768.0, 32768.0]`), just not quantized to
it. `rnnoise.h`'s own comments say nothing about range at all. Getting this wrong doesn't crash -
it just makes RNNoise see our capture as ~32768x quieter than it is, i.e. near-silence, so the
"denoised" output looks like "the denoiser barely does anything" rather than throwing any error -
exactly the failure mode that's easy to ship without noticing. The `×32768`/`÷32768` rescale is
applied once, in `speech_enhancer.c`'s `nativeProcessFrame`, right next to the actual
`rnnoise_process_frame()` call, with a comment explaining why it's there.

**Frame-size mismatch, resolved by accepting a small, bounded coverage gap rather than
restructuring buffering.** RNNoise's frame size is fixed at 480 samples (10ms @ 48kHz - confirmed
from `denoise.c`'s `FRAME_SIZE` constant, not runtime-configurable). Neither round video's per-read
chunk (512 float samples) nor voice messages' `AudioRecord`-minBufferSize-derived read size is a
clean multiple of 480. A fully sample-accurate implementation would need a proper carry/queue
buffer decoupling "samples in" from "samples out," which risks disturbing this app's carefully
tuned per-chunk timestamp bookkeeping (see this fork's history of timestamp-mismatch bugs).
Instead, `SpeechEnhancer.process()` denoises every complete 480-sample block in whatever chunk
it's given and leaves the remainder (bounded, small - 32 of every 512 samples for round video,
93.75% coverage) untouched rather than dropped or delayed. Documented as a deliberate, revisitable
simplification, not an oversight - worth a proper redesign only if this turns out audible.

**DeepFilterNet: blocked, not implemented on `main`.** Investigated `io.github.kaleyravideo
:android-deepfilternet` (the suggested Android wrapper, distributed via Maven Central) before
touching anything:

- **The wrapper library itself is Apache-2.0, and Apache-2.0 is not GPLv2-compatible.** Confirmed
  from both the Apache Software Foundation's own GPL-compatibility page and the FSF's license
  list: Apache-2.0 §9's patent-termination and indemnification clauses are specifically
  incompatible with GPLv2 (Apache-2.0 *is* compatible with GPLv3, just not v2). This repo's own
  `LICENSE` is GPLv2. That alone blocks bundling this library into a build of this app that's ever
  distributed, independent of anything about the model weights.
- **The model weights' license isn't separately stated even before that.** The fork's docs only
  say "Apache-2.0" for the code and say nothing about the ~8MB bundled model specifically. Upstream
  `Rikorose/DeepFilterNet`'s own released models appear to inherit the same dual MIT/Apache-2.0 as
  its code, but the KaleyraVideo fork did its own "mobile optimization" pass and doesn't restate
  terms for the resulting artifact - unresolved either way.
- Separately (not a licensing issue, but worth recording): this library's `processFrame()`
  operates on 16-bit PCM, not float - contradicting an initial assumption that both libraries
  operate on float with no conversion needed. Bundling it would reintroduce exactly the
  quantize-before-DSP problem this session spent real effort removing from both recording paths.

**Not re-investigating this on `main` unless something changes.** The Apache-2.0/GPLv2
incompatibility is the disqualifying finding, and it's a property of the wrapper library's chosen
license, not something that gets resolved by finding more information about the model weights - a
future re-investigation should start from confirming whether that's changed (an explicit
relicense/dual-license grant from KaleyraVideo, or an independently-built GPLv2-clean packaging of
upstream's MIT-only option), not by re-deriving the same blocker. See the `deepfilternet-eval`
branch (never pushed, not merged into `main`) for a private, personal-use-only evaluation build -
distribution obligations under GPL don't attach to a build that's never distributed, but that
branch must stay off `main` and unpublished for that to hold.

## RNNoise: the per-chunk splice bug, the three-way result, and settling on a 70% wet/dry blend (2026-08-30)

Full record of testing the RNNoise selector added above, in the order it actually happened -
including a real bug this testing caught before it shipped.

**A real, audible bug in the first implementation.** The initial `SpeechEnhancer.process()`
denoised only the first complete 480-sample block of whatever chunk it was given and left the
remainder (32 of every 512 samples for round video) untouched - a deliberate tradeoff, documented
in the code as "revisit only if this turns out audible." It turned out audible: reported as
"cracking," "robotic," background and voice both affected. The mechanism is a genuine
denoised/raw level-and-spectral discontinuity every ~10.67ms (matching round video's chunk rate) -
not a general RNNoise quality issue. Fixed with a proper two-queue design
(`pendingRaw`/`readyDenoised`, the latter a circular buffer sized for more than one block's worth
of backlog - a single-block buffer was tried first and found insufficient, since round video's
512-sample chunks let a second block complete within a single call roughly every ~160ms) that
denoises every sample exactly once, in order, at the cost of a small constant end-to-end delay
(under 10ms) instead of a periodic splice. Confirmed by re-listening: "robotic gone now, sounds
good." Both the buggy and pre-fix comparison recordings were discarded and redone.

**Three-way comparison, fixed build, quiet room:**

| config | SNR (p90/p10, bandpassed) | noise floor (p10) |
|---|---|---|
| A: baseline (denoiser off, production defaults: Bandpass+Gate, 5x gain) | 25.1dB | 145.4 (-47.1dBFS) |
| B: denoiser stacked on that same chain | 52.9dB | 7.7 (-72.6dBFS) |
| C: denoiser-only (Voice Isolation off, 1x gain) | 53.7dB | 1.7 (-85.5dBFS) |

B and C are effectively the same by this metric, both ~28dB above baseline - **the existing
bandpass/gate/gain chain adds no measurable benefit once RNNoise is active**, confirming the
hypothesis this comparison was built to test.

**But the SNR ratio alone was misleading about *why* C measured (marginally) better than B, and
that mattered.** Absolute noise-floor levels told the real story: C's floor (-85.5dBFS) sits within
~5-10dB of 16-bit PCM's own quantization noise limit (roughly -90 to -96dBFS) - 20% of C's 50ms
windows fell below -80dBFS, vs. 2% for both A and B. That's not "quieter room," that's pushed to
near-digital-silence - a ratio that inflates as its denominator approaches zero, exactly as
predicted before measuring. Listening confirmed the practical consequence at 100% wet: background
music was eliminated outright (more aggressive than the attenuate-don't-eliminate behavior of
comparable commercial noise suppression), and quiet trailing consonants/breath at the ends of words
were getting clipped - RNNoise misclassifying them as noise, with nothing to fall back on since
RNNoise exposes no attack/release/threshold controls to soften that.

**Fix: a wet/dry blend, added as a Denoiser Strength setting** (100/90/80/70/60/50%,
`SpeechEnhancer.process()`, applied once per completed block right after the native call). At
`wet < 100%`, anything RNNoise fully suppresses reappears at `(1-wet)` of its original amplitude -
a fixed, predictable attenuation (`20*log10(1-wet)` dB) instead of elimination. Chose a **flat
per-block ratio over a time-varying blend** for the initial implementation: it directly produces
the "attenuate, don't erase" behavior wanted, in a form simple enough to A/B at discrete steps.
Noted for later: `rnnoise_process_frame()` returns a per-frame VAD probability that this
implementation currently discards - modulating the wet fraction by that (more dry specifically on
frames RNNoise itself is least confident are speech, which is exactly where the misclassified
word-endings live) is a principled, RNNoise-native way to make the blend time-varying if a flat
ratio turns out not to be precise enough - not implemented, since a working flat baseline was the
right thing to validate first.

**A/B'd across the full range; settled on 70%.** 100/90/80% all showed some combination of audible
word-ending clipping and background elimination in listening tests. 70% was the point where word
endings survive and background is present-but-reduced rather than erased - matching the intended
behavior. Changed `DEFAULT_SPEECH_ENHANCEMENT_WET` from the initial 90% guess to 70% on this basis;
added 60%/50% steps below it for further tuning.

**Measured SNR at 70%: 25.9dB - essentially back to the pre-denoiser baseline (25.1dB), and that's
expected, not a sign 70% isn't working.** Blending 30% of the original signal back in means content
RNNoise would otherwise erase comes back at `20*log10(0.3) ≈ -10.5dB` rather than near-silence, so
the noise floor is no longer being pushed toward the quantization limit the way it was at higher
wet fractions - which is exactly what removes the audible artifacts. This is the SNR-ratio
limitation flagged above showing up directly: the metric mostly reflects how close the floor gets
to zero, not whether speech itself got cleaner, so it can't distinguish "still doing real,
well-behaved suppression" from "back to doing nothing" at this operating point. Listening is the
right instrument here, not this metric - recorded plainly rather than spun as either a win or a
null result by the number alone.

(Methodology note: the 70%/80% test recordings weren't distinguishable by a logged per-file tag -
the marker line records when a recording *started*, not which saved file resulted. Identified by
the direction the noise floor should move - 70% wet lets more raw signal back in, so should show a
*higher* floor than 80% - rather than direct correlation, so this is an inference, not a
certainty.)

**Open**: this whole comparison was done in a quiet room, where RNNoise still found real noise to
remove - but whether the three-way equivalence (denoiser-only vs. denoiser-stacked) and the chosen
70% wet fraction both hold up in a noisier environment (café, traffic) is untested. A quiet room is
the easy case for a denoiser; flagged rather than assumed to generalize.

## Mic gain split: voice messages set to 3x, round video reset to 1x (2026-08-30)
- Round video's own default (`DEFAULT_MIC_GAIN`) reset from 5x back to `MIC_GAIN_1X` (off),
  now that the audio-defaults second revision above is being superseded for release - gain is
  no longer treated as an always-on compensation once the denoiser/wet-dry work above changed
  what's actually needed.
- Voice messages given their own independent default, `DEFAULT_MIC_GAIN_VOICE_MESSAGE = MIC_GAIN_3X`,
  separate from round video's `DEFAULT_MIC_GAIN`. `PixelGramSettingsActivity` now exposes two
  separate rows ("Microphone Gain (Round Video)" / "Microphone Gain (Voice Messages)")
  backed by two separate preference keys, both driving the same shared gain+soft-limiter core
  (`applyGain`/`applyGainFloat`) with a per-path multiplier.
- **This 3x is a carried-over compensation, not a confirmed fix.** It rests entirely on the
  round-video measurement above (3x measured at +9.7dB, peaks well clear of clipping, soft
  limiter catching anything close) plus the fact that voice messages run through the exact same
  gain/limiter code. That's a reasonable basis for expecting similar behavior, but the voice
  message path itself has never been separately measured - no mean/peak dBFS numbers have been
  gathered for it at any gain setting, at 1x or otherwise. It's also unclear why the two paths
  would need different gain treatment at all if the code and expected input level are the same;
  that gap between them is unexplained. Worth investigating properly later: measure voice
  messages the same way round video was measured (mean/peak dBFS at each gain step) rather than
  assuming the round-video numbers transfer.

## Full-history leak audit before publishing (2026-08-30)
- Scanned the working tree and every commit reachable from the fork's start
  (`3f03bfc73`, the merge base with upstream) on both `main` and
  `g6-ae-fps-range-fix` - file contents, diffs, commit messages, and
  author/committer metadata, not just current file contents. Confirmed
  clean: all commit author/committer addresses on pushed history use the
  GitHub noreply format; no personal emails, LAN IPs, phone numbers, device
  serials, or room/appearance/circumstance details anywhere in this file or
  code comments.
- **Found, already public** (on `pixelgram/main`, confirmed via the GitHub
  API that this repo is public): the strings `eifohjlsdk/telegram-pixel`
  (a separate, private repo on the same account), the branch name
  `wip-settings-recovered`, and the path `~/dev/telegram-pixel/.git` appear
  in the diffs *and commit messages* of three early commits (`72f51b9f5`,
  `71b0b8b21`, `174920b76`) - the two later "fix" commits both restate the
  strings verbatim while describing the fix, so the file-content cleanup
  didn't remove them from history. Reviewed and **accepted as-is**: a repo
  name, a branch name, and a home-directory path pattern aren't personally
  identifying on their own, and don't justify a force-push rewrite of
  already-published history.
- **Found, not public - a near miss worth knowing about**: a real personal
  email is the author/committer on ~9 commits that exist only on local
  branches (`wip-settings-recovered`, `main`, `master`,
  `camerax-experiment`), including one that adds a `PIXELGRAM_PROJECT_HANDOFF.md`
  naming the private repo and local path outright. Confirmed via
  `git merge-base --is-ancestor` against every ref on the `pixelgram` remote
  that none of this is reachable from anything pushed.
- **Real hazard found and fixed**: local `main` and `master` had
  `branch.<name>.remote`/`merge` pointing at `origin` = `DrKLO/Telegram`
  (the public upstream, unrelated to this fork). With Git's default
  `push.default=simple`, a bare `git push` on either branch would have
  targeted a third party's public repo with the real-email commits above.
  Fixed with `git branch --unset-upstream` on both - reversible, not
  destructive. `wip-settings-recovered`/`camerax-experiment` were already
  unpushable by omission (no remote configured at all), so nothing further
  was needed there; deliberately did not delete those branches or the
  `~/dev/telegram-pixel` worktree checked out on `wip-settings-recovered`,
  since that destroys history that isn't safe to assume is disposable.
- Also reviewed the 7 tracked `google-services.json` files (real, custom
  Firebase project created for this fork). Left as-is per Google's own
  position that Android client API keys aren't meant to be secret - access
  is controlled by Firebase Security Rules/App Check, and this key is
  already restricted to this app's package name and signing certificate.

## Reproduce the measurement
adb pull "/sdcard/Download/Telegram/<file>.mp4" ~/circles/<name>.mp4
ffprobe -v error -show_entries stream=codec_type,r_frame_rate,avg_frame_rate,bit_rate,nb_frames,start_time,duration -of default=noprint_wrappers=1 <file>
ffprobe -v error -select_streams v -show_entries frame=pts_time -of csv=p=0 <file> | awk 'NR>1{d=$1-p; if(d>0.05) printf "gap %.3fs at t=%.3f\n", d, $1} {p=$1}'
