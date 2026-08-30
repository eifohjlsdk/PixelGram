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
entirely on the now-fixed declared-dimension bug and had no other basis). Where the actual ceiling
sits between 640px (known good) and 960px (known rejected) is being bracketed empirically - see the
downscale filter/dither/resolution options above for the current picker range.

## Image quality defaults revised for the supersample-capture pipeline (2026-08-30)

Defaults changed: resolution to 480px, noise reduction off, edge mode off, face-weighted AE
metering off, exposure compensation 0.0, tone mapping stays Fast, downscale filter stays Lanczos.

**Resolution: 480px.** Clean 4:1 downscale from the 1920px supersample capture. Chosen as a
cautious value before the declared-dimension bug above was fully understood - now that 640px is
confirmed working correctly on both Android and iOS, and the real ceiling is being bracketed
between 640 (good) and 960 (rejected server-side), expect this default to move up once that
ceiling is found (see "960px: a separate, real server-side ceiling" above).

**Superseded same day**: default moved to 640px once it was confirmed working on Android, iOS, and
web (see "960px: a separate, real server-side ceiling" above) - a clean 3:1 downscale from the
1920px capture. 720px is confirmed rejected server-side; the actual ceiling is being bracketed
between 641 and 719 with 672/704 candidates. Expect this default to move up again once found.

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

## Exposure cap investigation and implementation (Path A) (2026-08-30)

Investigated capping exposure time in dim light (where AE rides SENSOR_EXPOSURE_TIME toward the
33.3ms frame budget at 30fps, producing motion blur) now that MANUAL_SENSOR is confirmed available
(see above). Findings, before implementing:

- **`CONTROL_AE_TARGET_FPS_RANGE` only bounds the ceiling, and we're already at it.** Frame
  duration can't exceed `1/fps_min`, so AE's own exposure-time choice is already capped at ~33.3ms
  by the current fixed `[30,30]` range - that's the behavior being complained about, not something
  the fps range already prevents. `SENSOR_EXPOSURE_TIME` is documented as ignored entirely under
  `CONTROL_AE_MODE_ON*` - a real, always-effective cap below the current ceiling requires either
  full manual control (`CONTROL_AE_MODE_OFF`) or lowering the ceiling itself.
- **AWB/AF are unaffected by AE going manual** - independent state machines in the Camera2 model;
  `AE_MODE_OFF` + `AWB_MODE_AUTO` + `AF_MODE_CONTINUOUS_VIDEO` is an explicitly valid combination.
- **No platform-provided brightness signal exists under full manual AE** - only relevant if going
  fully manual (Path B, not built): would need our own mean-luma feedback loop off actual captured
  pixels (e.g. reusing `InstantCameraVideoEncoderOverlayHelper`'s existing 48x48 downsample).
- **Middle path found and used**: request a fixed `[60,60]` `CONTROL_AE_TARGET_FPS_RANGE` instead
  of `[30,30]` (confirmed available on the front camera:
  `[[15,15],[15,24],[24,24],[15,30],[24,30],[30,30],[15,60],[60,60]]`) - this halves the
  frame-duration ceiling to ~16.7ms while AE, AWB, and AF all stay fully automatic, no custom
  brightness tracking needed. Deliberately used the fixed `[60,60]` rather than the also-available
  variable `[15,60]`, to avoid reintroducing the free-running variable-frame-rate bug this fork
  started by fixing.
- Front camera ranges: `SENSOR_INFO_EXPOSURE_TIME_RANGE` 68,360ns-1,000,000,628ns,
  `SENSOR_INFO_SENSITIVITY_RANGE` 55-19,692. Proposed cap: 16.7ms (1/60s) - exactly what the
  `[60,60]` middle path gives for free, well clear of the sensor's 68us floor.

**Implementation**: new `Exposure Cap` setting (off by default - only matters in dim light and
costs extra sensor/ISP power otherwise). When on, `Camera2Session` requests `[60,60]` instead of
`[30,30]`; `VideoRecorder.frameAvailable()` explicitly decimates 2:1 using the real per-frame
hardware timestamp for every kept frame (real 60fps frames arrive ~16.7ms apart, so keeping every
other one naturally produces a uniformly ~33.3ms-spaced sequence, matching what a genuine 30fps
capture produces - deliberately not relying on the encoder to convert a 60fps input while
configured for 30, which is exactly the timestamp mismatch this fork started by fixing). Logs the
actual `SENSOR_EXPOSURE_TIME`/`SENSOR_SENSITIVITY` from `CaptureResult` once per second so AE's
real behavior in each mode is directly observable rather than assumed. Included in the recording
marker line.

### Measured (2026-08-30)

**AE genuinely never approaches the ceiling in normal room lighting** - a 15s recording with the
cap off held `SENSOR_EXPOSURE_TIME` at 7-11ms throughout (well under even the *capped* 16.7ms
ceiling), `SENSOR_SENSITIVITY` 55-208. Confirms the concern this feature addresses doesn't exist in
normal lighting - the cap would be a genuine no-op there, exactly as expected, and correctly not
something to worry about outside dim conditions.

**In dim lighting, AE pins at the ceiling for the whole recording**: cap off, same dim room,
`SENSOR_EXPOSURE_TIME` climbed to 32.3-32.9ms (right at the 33.3ms ceiling) and stayed pinned there
for all 15s, `SENSOR_SENSITIVITY` fixed at 1231 - the exact motion-blur-risk scenario the cap is
meant to address, confirmed present and real.

**With the cap on, same dim room**: `targetFpsRange=[60,60]` applied as requested,
`SENSOR_EXPOSURE_TIME` held at 16.2-16.4ms for the whole recording (right at the ~16.7ms ceiling,
almost exactly half the uncapped value), `SENSOR_SENSITIVITY` unchanged at 1231. The cap works
exactly as designed.

**Output fps verified genuinely uniform 30.0fps with the cap on**: per-frame PTS interval
distributions for capped vs. uncapped 15s dim-room recordings are essentially identical - both
dominated by ~33.333ms intervals (226/261 and 280/306 frames respectively at exactly 0.033333s,
the rest within a few microseconds of it, a couple of one-off ~50ms hiccups in each), and
`nb_frames/duration` computes to ~29.9fps for both. The 2:1 decimation produces output
indistinguishable in timing quality from native 30fps capture. One cosmetic oddity: ffprobe's
`r_frame_rate` heuristic reports `60/1` for the capped file despite `avg_frame_rate` and the actual
per-frame deltas both agreeing on ~29.9-30.0fps - not chased further given the per-frame interval
evidence is conclusive, but flagged here rather than silently ignored in case it affects some
player's format detection.

**No measurable thermal or battery difference over a 15s recording**: battery temperature 31.1°C →
30.9°C (cap off) vs. 31.4°C → 31.4°C (cap on) - within normal noise, and 15s is almost certainly too
short a window for a real thermal delta to show up regardless of sensor fps. Device was AC-powered
throughout (charging), which caps how informative a short-clip power comparison can be; a
longer-duration test on battery power would be needed for a real answer to the power-cost
question. Not conclusive either way - absence of a measurable difference at 15s isn't evidence of
absence at longer durations.

## Reproduce the measurement
adb pull "/sdcard/Download/Telegram/<file>.mp4" ~/circles/<name>.mp4
ffprobe -v error -show_entries stream=codec_type,r_frame_rate,avg_frame_rate,bit_rate,nb_frames,start_time,duration -of default=noprint_wrappers=1 <file>
ffprobe -v error -select_streams v -show_entries frame=pts_time -of csv=p=0 <file> | awk 'NR>1{d=$1-p; if(d>0.05) printf "gap %.3fs at t=%.3f\n", d, $1} {p=$1}'
