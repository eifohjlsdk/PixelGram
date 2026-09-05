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

## Package ID collision with telegram.org's direct-download APK (2026-09-05)

The release build's applicationId (`APP_PACKAGE` + the `.web`
`applicationIdSuffix` both `standalone` and `release` already carried) was
`org.telegram.messenger.web` through v1.0.2 - the stock upstream default,
unchanged by this fork. telegram.org's own direct-download build (this same
open-source tree, built unmodified) ships under that exact same ID. Android
refuses to install two packages with the same applicationId signed by
different certificates, so a device with that build installed could not
install PixelGram, and vice versa - the README's "runs side by side" claim
was only actually true against the *Play Store* Telegram (which is a
different ID, `org.telegram.messenger`, no suffix), not the direct-download
one.

**Fixed**: `APP_PACKAGE` in `gradle.properties` changed from
`org.telegram.messenger` to `com.pixelgram.messenger`, giving final IDs
`com.pixelgram.messenger.beta` (debug), `.web` (standalone/release) -
distinct from every stock Telegram build. README updated to name the actual
ID and both potential collisions. Verified via
`aapt dump badging` that a debug build now reports the new ID.

**What else this touches:**

- **Firebase - blocks the build, confirmed by actually running it.**
  `google-services.json`'s client entries are keyed to the exact old package
  names; the `google-services` Gradle plugin checks this at build time, not
  just at runtime. Ran `:TMessagesProj_App:processAfatDebugGoogleServices`
  after the rename and got exactly the expected failure, immediately and
  clearly (matching this project's existing "fail loud, not silently" style
  for the signing-config check in the same build file):
  `No matching client found for package name 'com.pixelgram.messenger.beta'`.
  This is real Firebase project configuration (project `pixelgram-4274c` for
  release, per `TMessagesProj_App/src/release/google-services.json`) that
  only the account holder can update - I did not edit the package names
  inside these JSON files, since doing so would produce a config that builds
  but can't actually authenticate to Firebase (the backend checks the
  package name/cert against what's registered, not just the JSON file
  content). **Action needed before the next build**: in the Firebase console
  for the `pixelgram-4274c` project (and the shared `tmessages2` debug
  project, or a project of your own for debug), add an Android app for each
  new applicationId (`com.pixelgram.messenger`, `.beta`, `.web`) with the
  release signing cert's SHA-1/SHA-256, then download the resulting
  `google-services.json` over each of
  `TMessagesProj_App/src/{release,debug,standalone}/google-services.json`
  (and `TMessagesProj/google-services.json`, `TMessagesProj_AppStandalone/`,
  `TMessagesProj_AppHockeyApp/`, `TMessagesProj_AppHuawei/`, which mirror the
  same client list).
- **Update checker**: no code change needed - `PixelGramUpdateChecker.java`
  only calls the GitHub releases API and never references the applicationId
  or an installed-package check.
- **Existing installs - no in-place upgrade, and local data is not
  migrated.** A different applicationId is a different app to Android; the
  next release APK will not be recognized as an update to anyone's existing
  `org.telegram.messenger.web`/`.beta` install. Anyone updating must
  manually uninstall the old one and install the new one, losing local
  PixelGram settings and cached media (the Telegram account itself is
  server-side, so login is unaffected - just re-login after reinstalling).
  This needs a prominent callout in whichever release notes ship this
  change, not just a changelog line.
- **Not asked, but adjacent - not touched**: `ContactsController.java` and
  the account-authenticator manifest entries register an Android
  `AccountManager` account type of the hardcoded string
  `"org.telegram.messenger"`, independent of applicationId. This was already
  true before this change and isn't unique to it, but is worth knowing: if a
  real Telegram build and this fork are both installed on the same device,
  they register the *same* account type string with `AccountManager`/
  `ContactsContract`, which Android allows (it's not required to be globally
  unique the way a `ContentProvider` authority is) but can produce surprising
  account-picker/sync behavior between the two apps. Left alone since it's
  outside what was asked and isn't a regression introduced by this change.

## Native bounds checks on caller-supplied audio buffer offsets/lengths (2026-09-05)

`SpeechEnhancer.nativeProcessFrame` (`speech_enhancer.c`) took a direct
`ByteBuffer` and a caller-supplied `offsetFloats`, then read/wrote 480 floats
at that offset with only a NULL check on `GetDirectBufferAddress` - no check
that `offsetFloats + 480` actually fit inside the buffer. A Java-side
miscount would have turned into an out-of-bounds native read/write rather
than a catchable exception. Fixed by adding `GetDirectBufferCapacity` and a
bounds check (`offsetFloats < 0` or `offsetFloats + 480 > capacity` both
bail out before touching the buffer).

Same category of issue in `audio.c`'s `writeFrame`/`writeFrameFloat` JNI
entry points: no capacity check against the caller-supplied `len`, and no
NULL check on `GetDirectBufferAddress`'s result before use. Added both. Also
added a log (not a bounds issue - the `/4` truncation is arithmetically
safe) when `len` isn't a multiple of 4 in `writeFrameFloat`, since every
real caller in this tree only ever hands over whole float32 samples and a
non-multiple would mean something upstream miscounted.

`initRecorder` also took the new `application`/`bitrateBps` parameters
(added for the Opus reconfiguration above) straight from
`PixelGramSettings` and passed them directly to `opus_encoder_ctl` with no
validation. The settings UI only ever writes one of a fixed set of known
values, but that's a Java-side promise, not a native-side guarantee - a
tampered or corrupted SharedPreferences file (writable on a rooted device
without touching the app itself) would reach native code unchecked.
`initRecorder` now falls back to `OPUS_APPLICATION_AUDIO` for any
`application` value other than the three libopus defines, and clamps
`bitrateBps` into `[500, 512000]` (leaving `OPUS_AUTO`/`OPUS_BITRATE_MAX`
untouched, since those are valid sentinel values outside that range),
logging when either happens.

Verified the native changes compile cleanly
(`:TMessagesProj_App:assembleAfatDebug`); no behavioral test beyond that, since
every existing call site already passes in-bounds, valid values - these are
defense-in-depth checks against future/tampered callers, not fixes to an
observed bug.

## File size cap: bitrate options, a pre-recording budget, and a live ratchet-down fallback (2026-09-05)

Telegram limits round video by file size, not resolution or duration on
their own - measured (640px, 60s): 1Mbps video (11.12MB total) is accepted
as a round message; 2Mbps (15.61MB) is silently reclassified as a normal
video instead of rejected outright. That test was shot in near-darkness,
which compresses well below its nominal bitrate - a bright, detailed scene
at the same settings would land closer to what the bitrate actually implies,
so any safeguard needs to budget for that, not for what the dark test
happened to produce.

**Checked whether the client has an exact number for this rather than
bisecting**: it doesn't. Grepped the whole tree (`SendMessagesHelper.java`,
`MediaController.java`, `MessagesController.java`) for a round-video-
specific size or byte-count constant - nothing. `MessagesController`'s
`roundVideoSize` is a server-pushed *resolution* default (384px), unrelated
to file size. The reclassification threshold is server-side and not present
anywhere in this client's source; bisection is the only way to find it.

**Added:**
- Intermediate video bitrate options at 1.1, 1.25, 1.4, 1.6, and 1.75 Mbps
  (`PixelGramSettingsActivity.showVideoBitrateDialog`) alongside the
  existing 1.2/1.5 Mbps.
- `PixelGramSettings.ROUND_VIDEO_SAFE_MAX_BYTES` (9.5MB) - a worst-case
  budget set well under the confirmed-accepted 11.12MB and clear of the
  confirmed-reclassified 15.61MB, precisely because the measurement it's
  based on was shot in conditions that compress unusually well.
  `capVideoBitrateForSizeBudget(videoBps, audioBps, durationMs)` nets the
  audio track's bitrate *out of* the same budget rather than adding it on
  top, and clamps the requested video bitrate down (never up) so a full
  `ROUND_VIDEO_MAX_DURATION_MS` (60s) recording projects to fit, minus an
  estimated 3% for MP4 container overhead (moov atom, sample tables). Called
  once in `InstantCameraView.startRecording()` before the encoder is
  configured, so it affects the actual recording bitrate, not just what the
  picker offers - selecting a higher bitrate for a long recording quietly
  gets capped rather than silently risking reclassification. At the default
  96kbps audio bitrate this doesn't touch anything below ~1.13Mbps, so the
  existing 1.0Mbps default is unaffected.
- **Live fallback**, since the pre-recording cap assumes the encoder spends
  close to its full allotted bitrate, and a busier scene than whatever it
  was budgeted against can still outrun it mid-recording:
  `InstantCameraView` already computes a real cumulative on-disk file size
  every ~32KB (`MP4Builder.writeSampleData`'s return value, previously used
  only to drive the progressive-upload-while-recording feature) - reused
  that existing running counter rather than adding a second one. Once at
  least 2 seconds in, it projects that rate forward to the full 60s and, if
  the projection would exceed the safe budget, ratchets the live encoder
  down via `MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE, ...)` for
  the remaining time - a one-shot, ratchet-down-only adjustment (checked
  each time but only fires once per recording).

Verified via `:TMessagesProj_App:compileAfatDebugJavaWithJavac` (temporarily
reverting the applicationId change above to work around the Firebase config
gate, then restoring it - see the package ID section). Not verified against
an actual overshoot recording on-device; the arithmetic was checked by hand
(at 96kbps audio, the 9.5MB/60s budget nets to ~1.13Mbps max video bitrate,
consistent with 2Mbps having been the confirmed-reclassified data point) but
the live ratchet path specifically has not been exercised end-to-end.

## Update checker integrity (2026-09-05)

`PixelGramUpdateChecker` never downloaded or installed anything itself - it
only opens a bulletin linking to the GitHub release page, and the user does
the actual download/install as a normal manual sideload. Confirmed that by
reading the whole class; worth stating explicitly in its class doc now,
since that design choice is the main reason this is lower-risk than an
update checker that can silently fetch-and-install.

What it didn't do: pin the host it connects to (the URL was a hardcoded
string, but never checked against what was actually being connected to,
and redirects were left to the platform default rather than disabled), or
validate the API response's `html_url` before handing it to
`Browser.openUrl()` - if the GitHub account or the connection were ever
compromised, that field could point anywhere and would have been opened
without question. Fixed: `fetchLatestRelease()` now checks the resolved
request host against a pinned constant before connecting and disables
automatic redirect-following; a new `safeAssetUrl()` validates the
`html_url` is `https://github.com` (or a subdomain) before use, falling back
to the hardcoded releases page for anything else, and logging what was
discarded.

**Publishing verification info** (the "verify a published SHA-256" ask):
there's no APK download inside the app to verify against, so this is a
release-process/documentation change rather than a code one. Added a
"Verify the download" step to the README's install instructions covering
`sha256sum` and `apksigner verify --print-certs`, and going forward every
release's notes need to publish the APK's SHA-256 and signing certificate
fingerprint (both already computed as part of this fork's own release
process - see the "Release 1.0.2 to publish" workflow - just not previously
written down anywhere a sideloader could check them against).

## Low Light Boost: static characteristics confirmed, dynamic test not yet run (2026-09-05)

Investigation requested before any implementation - static characteristics
only below, nothing changed in the actual capture path.

- `CONTROL_AE_AVAILABLE_MODES` includes mode 6
  (`AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY`) on **both** cameras:
  camera 0 (back) `[0, 1, 2, 3, 6]`, camera 1 (front) `[0, 1, 6]`. Confirmed
  live via `PixelCapsDump` on-device, not just documentation.
- `CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE` = `[0.1, 15.0]` lux on both
  cameras - wasn't previously dumped by `PixelCapsDump`; added as an explicit
  key (harmless, permanent addition to the existing capabilities dump, same
  as the other `logExplicit()` calls). This is the illuminance band the HAL
  itself expects to engage boost within - useful for judging whether a given
  test scene is actually dark enough for the mode to matter, before spending
  time on the fps/bitrate comparison below.
- The complication as described is real and unresolved by this
  investigation: this app already hardcodes a fixed `CONTROL_AE_TARGET_FPS_RANGE`
  (currently `[30,30]`, see the AE target fps range fix earlier in this file)
  precisely to stop the ISP free-running: to 59fps at half the
  bits-per-frame. Low Light Boost's own documented brightening mechanism is
  extending exposure time, which is exactly what a fixed 30fps range already
  caps at 33ms/frame. Which one wins - the HAL respecting the fixed range and
  boost doing less than it could, or boost overriding the range and frame
  rate dropping - isn't stated in the platform docs and isn't something static
  characteristics can answer.

**Not yet done: the actual dynamic test** (enable the mode, log
`CONTROL_LOW_LIGHT_BOOST_STATE` and per-frame capture timestamps, measure
realized fps/bits-per-frame at `[30,30]` vs `[24,30]` vs `[15,30]` in a dim
scene). This needs code changes to `Camera2Session.java`'s capture request
(setting `CONTROL_AE_MODE` to 6 and each fps range in turn) that weren't
made, and - unlike the file-size bisection or the AE-region test earlier in
this file - a **genuinely dim scene in the HAL's own stated [0.1, 15] lux
engagement band**, which isn't something reachable from this environment
(no control over the device's physical surroundings). Recommend the actual
test run with a real dim scene (e.g. a dark room, lights off) rather than
guessed-at "dim enough" conditions, since a scene outside the engagement
band would just show the mode never activating and produce a false "no
difference" result. Holding off on the `Camera2Session.java` change itself
until that's confirmed feasible, per "don't ship it on by default until
measured."

## CameraX / androidx.camera: confirmed absent, no migration made (2026-09-05)

Checked, no changes: grepped every `build.gradle` in the tree and every
`.java` file under `TMessagesProj/src/main/java` for `androidx.camera` -
zero matches. This tree's round-video path is Camera2 + a raw `MediaCodec`/
`MediaMuxer` pipeline throughout (`Camera2Session.java`,
`InstantCameraView.java`), with no CameraX dependency anywhere, contradicting
whatever claim was seen elsewhere about upstream 12.10.0 already having moved
video messages to CameraX. Per the explicit instruction, no migration was
attempted - CameraX sits on top of Camera2, wouldn't expose anything the HAL
doesn't already give directly, would abstract away the encoder configuration
this fork specifically hand-tunes, and would mean rewriting the GL
supersampling path.

## A/V presentation timestamps: how each is derived, and why they aren't the same clock (2026-09-05)

Investigation only - reporting the mechanism, not a fix. The 0.43s residual
audio-longer-than-video overrun noted earlier in this file ("Result: AE
target fps range fix") was flagged as a symptom, not resolved; this is why
it's still open.

**Video's presentation timestamp** is `SurfaceTexture.getTimestamp()`
(`InstantCameraView`'s `frameAvailable()`, used unless it reads 0, in which
case it falls back to a `System.nanoTime()` snapshot taken at the JNI
callback). For Camera2, `SurfaceTexture.getTimestamp()` returns the capture
result's `SENSOR_TIMESTAMP` - whose clock domain is declared per-device by
`CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE`. **Checked on this
device via `PixelCapsDump`: it's `1` (`TIMESTAMP_SOURCE_REALTIME`)** - meaning
these timestamps are in the same domain as `SystemClock.elapsedRealtimeNanos()`
(`CLOCK_BOOTTIME`), not arbitrary/HAL-internal.

**Audio's presentation timestamp** is primarily
`AudioRecord.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)`
(`InstantCameraView`'s audio-recording thread), falling back to a raw
`System.nanoTime()` snapshot only if `getTimestamp()` throws.
`TIMEBASE_MONOTONIC` is Android's documented `CLOCK_MONOTONIC` - the same
domain `System.nanoTime()` itself uses.

**So on this device, the two tracks are timestamped from two different
clock domains**: video from `CLOCK_BOOTTIME` (via the sensor), audio from
`CLOCK_MONOTONIC` (via the audio HAL's own frame-position clock, or the app
processor's clock as fallback). Both tick at the same nominal rate and
normally only differ by however long the device has spent asleep since
boot - which shouldn't matter mid-recording with the screen on throughout -
but Android doesn't guarantee they progress at identically matched rates
against each other, since they're ultimately serviced by independent
timer/counter paths (CPU timer vs. audio codec's own clock domain feeding
the HAL's frame-position tracking).

The code already shows awareness of a start-of-recording mismatch: the first
audio buffer whose timestamp is more than 10ms off from the first video
frame's triggers a one-time `desyncTime = videoFirst - input.offset[a]`
correction, applied as a constant offset to every subsequent audio
timestamp (`handleAudioFrameAvailable`, and the `videoLast - desyncTime` stop
condition). **That's an origin correction, not a rate correction** - it
aligns where the two timelines start, but can't compensate for the two
clocks running at even very slightly different rates over the following
~60 seconds. A 0.43s gap over ~60s is about 0.7% - large for pure
crystal-oscillator drift between two clock domains on the same SoC, which
points toward this being at least partly an accounting/rate-model issue
in how elapsed time is computed rather than pure hardware clock drift, but
distinguishing "clock-domain rate mismatch" from "another bug in the
alignment/stop-condition logic" needs an instrumented recording (logging
both raw timestamp sources per frame across a full 60s clip) that wasn't
part of this investigation.

## A/V drift fix options (investigated, none implemented) (2026-09-05)

Requested: report what fixing the clock-domain mismatch above would
actually involve, before touching any code. Three options, in the order
asked.

**Option A - request a matching timestamp source.** `AudioRecord.getTimestamp()`
takes a `timebase` argument alongside `TIMEBASE_MONOTONIC`:
`AudioTimestamp.TIMEBASE_BOOTTIME` (both added together in API 24), which is
documented as the same domain as `SystemClock.elapsedRealtimeNanos()` -
exactly the domain this device's camera sensor timestamps are already
confirmed to use. On this device, switching `handleAudioFrameAvailable`'s
call from `TIMEBASE_MONOTONIC` to `TIMEBASE_BOOTTIME` would put both tracks
in the same clock domain with essentially a one-constant change.

The catch: this only works because `SENSOR_INFO_TIMESTAMP_SOURCE` happens to
be `REALTIME` *on this device*. Camera2's other legal value,
`TIMESTAMP_SOURCE_UNKNOWN`, is documented as "may not have any relation to
the timestamps for a later frame... may not be comparable across different
instances of the same or different camera devices" - i.e. some devices
expose a sensor clock with no defined relationship to *any* queryable system
clock, so there's nothing to request a match against. Any implementation
would need to read `SENSOR_INFO_TIMESTAMP_SOURCE` at camera-open time (cheap
- it's already read for other characteristics) and only take this path when
it's `REALTIME`, falling back to something else otherwise.

**Option B - convert between the clocks.** Rather than trust a hardware
timebase choice, sample both clocks back-to-back once
(`SystemClock.elapsedRealtimeNanos()` and `System.nanoTime()`) at a known
instant and apply the resulting fixed delta when comparing an audio
timestamp against a video one. This is a cleaner version of what the code
already sort of does with `desyncTime` - except today's offset is inferred
from *content arrival* (the first audio buffer whose timestamp lands near
the first video frame's, subject to camera warm-up and buffer-queueing
jitter), where a direct dual-clock read is a clean, jitter-free measurement
of the same thing. Same applicability caveat as Option A: only meaningful
when the sensor's clock is actually `REALTIME` - on an `UNKNOWN`-source
device there is no fixed delta to compute, because there's no defined
relationship to compute it from.

Both A and B are, at best, a **better origin correction** - even implemented
perfectly, neither corrects for the two clocks *ticking* at different rates
over a 60-second recording, only for where they start. Given the measured
gap (0.43s/~60s, roughly 0.7% - large for plain crystal-oscillator drift
between two clock domains on one SoC), rate mismatch look like a real
possibility, and neither A nor B addresses it.

**Option C - derive audio timestamps from cumulative sample count against
the video clock.** Anchor audio to video's clock exactly once (same
cross-domain touch point as today's `desyncTime`), then compute every
subsequent audio timestamp as `audioStartTimeInVideoClock + cumulativeSamplesRead
* 1_000_000 / sampleRate` instead of continuing to read
`AudioRecord.getTimestamp()` (or `System.nanoTime()`) per buffer. This
stops relying on a second hardware clock's *rate* at all after the initial
anchor - elapsed time becomes pure arithmetic on a sample counter this code
already has (`buffer.read[a]`), rather than two independently-ticking
clocks that are each assumed, not verified, to advance at the same rate.
This is also the only one of the three options that doesn't depend on
`SENSOR_INFO_TIMESTAMP_SOURCE` being `REALTIME` - it works identically on
an `UNKNOWN`-source device, since after the one-time anchor it never
compares against the camera's clock again.

The tradeoff: this assumes the audio hardware's *actual* delivered sample
rate matches the *declared* `audioSampleRate` closely (true to within tens
of ppm on real consumer audio hardware - sub-millisecond drift over 60s,
negligible next to the observed 430ms) - and it discards whatever
buffering-latency compensation `AudioRecord.getTimestamp()` is specifically
designed to provide (it maps DMA frame position to wall-clock time,
accounting for the audio pipeline's own internal buffering delay - a plain
sample counter doesn't know about that delay and would be counting from
when a sample was *read* by the app, not when it was *captured* by the
mic). That's a real, if probably small, precision cost in exchange for
removing the cross-domain rate-mismatch risk entirely.

**Recommendation, not yet implemented**: Option C as the primary fix - it's
portable across both `SENSOR_INFO_TIMESTAMP_SOURCE` values and directly
addresses the "origin-only, no rate correction" gap identified in the
mechanism report above, rather than making the existing origin correction
merely more precise. Option A/B's `TIMEBASE_BOOTTIME` switch is worth adding
*in addition*, gated on a `REALTIME` check, as a better-quality anchor point
for Option C's one-time synchronization - not as a replacement for it.

**What's still missing before implementing any of this**: an instrumented
recording that logs both the raw `AudioRecord.getTimestamp()` value and the
raw `SurfaceTexture` sensor timestamp per frame/buffer across a full 60s
clip, to directly quantify how much of the 0.43s is clock-domain rate
mismatch versus something else in the alignment/stop-condition logic
(flagged as an open question in the mechanism report above, still open).
Without that, there's no way to confirm any of these three options actually
closes the gap rather than just changing its shape.

## Release keystore rotated: old key's password was exposed in this session's transcript (2026-09-05)

While computing the release cert's SHA-1/SHA-256 for Firebase re-registration
(see the package ID collision section), a shell-quoting mistake caused
`local.signing.properties`' keystore password to be echoed verbatim into
this session's own tool output as part of a bash syntax-error message. The
private key material itself was never exposed - only the password, and only
into this conversation's transcript, never transmitted anywhere else - but
rather than rely on that distinction, the key was rotated outright.

Generated a fresh RSA 2048 keypair (`/home/dev/pixelgram-release-2026-09-05.keystore`,
same alias `pixelgram` and distinguished name as before, 10000-day validity)
with a new 40-character random password, entirely in a Python subprocess
that passed the password to `keytool` via `-storepass:env`/`-keypass:env`
and wrote it into `local.signing.properties` without ever printing it -
avoiding a repeat of the mistake above. `local.signing.properties` is
gitignored, confirmed before doing any of this.

New cert fingerprints (not secret - these are meant to be shared with
Firebase and published alongside releases):
- SHA-1: `94:AE:B2:93:70:D9:8D:0C:4A:91:CA:7C:7D:93:08:FC:17:C1:60:86`
- SHA-256: `87:01:74:83:7A:E1:47:4B:E9:5F:B5:3D:85:03:7F:71:4F:E6:FA:46:C6:13:A4:0F:99:35:D2:2D:C3:7B:D7:AE`

The old keystore (`/home/dev/pixelgram-release.keystore`) was left on disk
untouched, just no longer referenced by `local.signing.properties` - not
deleted, since that's a separate, irreversible decision left to whoever
owns it. **Any release built with the new key is a different signer than
every prior PixelGram release** (v1.0.0 through v1.0.2, all signed with the
old key) - same no-in-place-upgrade consequence as the applicationId change
above, and now compounding it: existing installs will need a manual
uninstall/reinstall regardless of which of the two changes actually ships
first. Needs the same prominent callout in release notes.

## A/V drift: found a likely much bigger culprit than clock-domain mismatch - a unit bug in stock upstream (2026-09-05)

While building the instrumented test requested to measure how much of the
0.43s gap is clock-rate mismatch, re-read every consumer of `videoFirst`/
`videoLast` in `InstantCameraView` to decide exactly what to log, and found
this: `videoFirst` is stored in **microseconds**
(`videoFirst = timestampNanos / 1000`, `frameAvailable()`), but `videoLast`
is stored in **raw nanoseconds** two lines later
(`videoLast = timestampNanos;` - no `/1000`). Confirmed via `git blame` this
is stock upstream code, unchanged since `d073b80063` (DrKLO, 2018-07-30) -
not introduced by this fork.

`handleAudioFrameAvailable`'s stop condition uses both:
```java
if (!running && (input.offset[a] >= videoLast - desyncTime || totalTime >= 60_000000)) {
```
`input.offset[a]` and `desyncTime` are both microseconds (`desyncTime =
videoFirst - input.offset[a]`, and `input.offset[a]` comes from
`audioTimestamp.nanoTime / 1000`) - but `videoLast` is nanoseconds, roughly
1000x larger in magnitude than a comparable microsecond value. For any
realistic device uptime, `videoLast - desyncTime` is enormous compared to
`input.offset[a]`, so **this branch of the stop condition can essentially
never evaluate true** - the "stop audio once it catches up to where video
actually stopped" logic this line implements is silently dead code. Every
recording actually stops audio via the *other* branch instead
(`totalTime >= 60_000000`, the hard 60s cap, which is unit-consistent) -
regardless of whether video's last frame landed at exactly 60.000s or
somewhat earlier (frame-rate quantization, encoder flush timing, or an
early user-initiated stop).

This is a much more concrete, mechanistic explanation for a residual
audio-longer-than-video gap than pure clock-domain rate mismatch: any
audio already sitting in `buffersToWrite`'s backlog when recording is
signaled to stop gets flushed through in full, because the intended trim
against video's actual last timestamp never fires. `videoFirst` itself has
no bug - every one of its consumers is unit-consistent (confirmed by
checking all of `videoFirst`, `videoLast`, `prevVideoLast`, `videoLastDt`,
`timestampNanos`'s other uses - `videoLast`/`prevVideoLast`/`videoLastDt`
are used consistently as nanoseconds everywhere *except* this one
comparison, which is why the fix below only touches this one line, not the
field's storage).

**Not yet fixed** - added instrumentation instead (see below) to confirm
this against real data before changing behavior, per "build the
instrumented test first." The one-line fix, once confirmed, is to compare
`videoLast / 1000` instead of raw `videoLast` at that one site.

## Instrumented dual-clock + stop-condition probe added; wrong logcat tag on first attempt (2026-09-05)

Added temporary logging (tagged `AVDriftProbe`, all removable together once
the drift is fixed and re-measured):
- At recording start (`startRecording()`) and at the stop signal
  (`handleStopRecording()`, the `running = false` transition): both
  `System.nanoTime()` (`CLOCK_MONOTONIC`) and
  `SystemClock.elapsedRealtimeNanos()` (`CLOCK_BOOTTIME`) sampled together,
  so the delta between them can be compared at both ends of a recording -
  if it moved, the two clocks genuinely ticked at different rates over the
  recording; if not, rate mismatch is ruled out as a contributor.
- At `handleStopRecording()`'s stop signal: `videoLast` and
  `buffersToWrite.size()` (the audio backlog, in whole ~42.7ms buffers,
  still queued and un-flushed into the encoder at the moment the stop
  signal arrives) - a direct measure of how much trailing audio the
  buggy-comparison theory above predicts should end up in the output.
- At the buggy comparison site itself (`handleAudioFrameAvailable`, logged
  once per recording): the raw audio offset, raw `videoLast` (ns), the
  same value converted to µs, `desyncTime`, and **both** the actual
  (buggy) check result and what the unit-corrected check would have
  evaluated to on the exact same data - so the fix's effect on real numbers
  is directly visible before it's ever applied.

**First attempt used the wrong logcat tag and produced nothing.** Originally
gated on `BuildVars.LOGS_ENABLED` and logged via `FileLog.d()`, with
instructions to filter `adb logcat` on tag `FileLog` - wrong on two counts.
`BuildVars.LOGS_ENABLED` is true on this build regardless (`DEBUG_VERSION`
short-circuits the check), so that part wasn't the problem, but
`FileLog.d()` logs to Android's `Log.d()` under the hardcoded tag
`"tmessages"`, not `"FileLog"` - the class name isn't the tag. Separately,
`FileLog` also appends every line to an on-device file
(`getExternalFilesDir(null)/logs/<dd_MM_yyyy_HH_mm_ss>.txt`, one per app
launch) regardless of logcat - that's where the confirming measurement
below actually came from, read directly off the pulled recording rather
than through logcat.

**Fixed**: switched all three log calls from `FileLog.d()` to
`PixelCameraLog.d()` (tag `"PixelCamera"`), which this same file already
uses for its own camera diagnostics and which - unlike `FileLog.d()` -
always calls `Log.d()` unconditionally, no `LOGS_ENABLED` gate to reason
about. To pull these logs going forward:
```
adb logcat -d -s PixelCamera:D | grep AVDriftProbe
```
Rebuilt, and confirmed via `unzip -p ... classes10.dex | strings | grep AVDriftProbe`
that the installed APK's dex actually contains the updated strings, rather
than trusting a from-source assumption - the same discipline that caught
this instrumentation's own build not having actually picked up an earlier
source edit (see the git log for the back-and-forth: a rebuild that
Gradle silently treated as fully up-to-date and produced a stale APK,
resolved with `--rerun-tasks`).

**Confirmed by direct measurement (read off the recording itself, not yet
via `AVDriftProbe`'s own log line - that still needs a re-record on the
corrected build)**: a 30-second recording measured 30.473s of audio against
30.167s of video - audio runs 0.31s long. 905 video frames over 30.167s is
exactly 30.0fps, so the frame rate itself is fine; the entire gap is in the
tail. That's exactly consistent with the stop-condition-never-fires theory
above: audio isn't drifting throughout the recording, it's failing to be
trimmed back to video's actual last frame when recording stops. Still need
the actual `AVDriftProbe` log line from a re-record on the corrected build
to see the buggy-vs-corrected comparison and the backlog buffer count
directly, rather than infer it from durations alone.

## Second upstream bug fixed: audio-stop unit mismatch (2026-09-05)

Confirmed directly via the `AVDriftProbe` stop-condition log on a real
recording: `buggyCheck=false` and `correctedCheck=true` at the exact same
instant, with `videoLastNs` and `videoLastUs` exactly 1000x apart - both the
mechanism and the effect size predicted above, now shown on real data rather
than inferred. `handleAudioFrameAvailable`'s stop condition genuinely never
fires the "audio caught up to video's last frame" branch; every recording
stops audio via the 60s hard cap instead, flushing whatever backlog is
queued regardless of when video's last frame actually landed - matching the
0.31s tail measured on a 30s test clip (905 frames at exactly 30.0fps, so
the frame rate itself was never the issue).

**Fixed**: `handleAudioFrameAvailable` now converts `videoLast` (nanoseconds)
to microseconds once, into a local `videoLastUs`, before comparing against
the microsecond-domain `input.offset[]`/`desyncTime` - both at the stop
condition itself and in the diagnostic log message beside it. Removed the
one-shot `AVDriftProbe` diagnostic at the comparison site (its job - showing
the buggy vs. corrected result side by side - is done now that there's only
one, corrected result); left the dual-clock start/stop samples and the
audio-backlog-size log in place, since those still matter for confirming
whether any residual gap remains after this fix, and if so how much of it
is clock-domain rate mismatch (see the "A/V drift fix options" section)
versus something still unaccounted for.

**This is stock upstream Telegram-Android's bug, not this fork's** -
confirmed via `git blame` back to `d073b80063` (DrKLO, 2018-07-30), unfixed
in seven years, and affects every Telegram-Android build, not just PixelGram
- round video's trailing audio has apparently been silently longer than its
video track industry-wide since 2018. This is the second stock-upstream
defect this fork has found and fixed, alongside the `CONTROL_AE_TARGET_FPS_RANGE`
issue documented near the top of this file (upstream hardcodes `[30,60]`
unconditionally rather than picking from the sensor's actual supported
ranges, silently free-running the ISP at half the intended bits-per-frame
on hardware that doesn't support that exact range) - worth reporting
upstream on its own merits, independent of anything else in this fork.

**Not yet re-measured**: the fix is applied and compiles, but the next
recording (to confirm whether track durations actually converge, and
whether any residual gap remains) hasn't been done yet.

## Denoiser wet fraction moved from 60% to 80%: phase-artefact found at 70% and below (2026-09-05)

Further listening (beyond the original 100%/90%/80%/70%/60% A/B pass in
the "RNNoise" section above) found an audible stereo-like artefact at 70%
wet and below - presumably a phase mismatch between the denoised and
original (raw) signals becoming perceptible as more of the raw signal gets
blended back in via the wet/dry mix. 80% avoids it while still giving the
same fixed, predictable attenuation (rather than RNNoise's full
suppression) that motivated the wet/dry blend in the first place.
`DEFAULT_SPEECH_ENHANCEMENT_WET` moved from `0.6f` to `0.8f`.

## Mic gain reverted from 1x back to 3x on round video: RNNoise doesn't add level (2026-09-05)

The 1x default (see the "Mic gain split" section above) was reasoned from
RNNoise's SNR improvement - the logic being that a cleaner signal needed
less compensating gain. That reasoning doesn't hold up: RNNoise's SNR gain
comes from *removing noise*, not from *adding level* - a quiet signal run
through RNNoise is still a quiet signal, just a quieter-and-cleaner one, not
a louder one. Gain (how loud the signal is) and denoising (how clean it is)
are independent problems; improving one doesn't substitute for the other,
and 1x was measurably too quiet in practice. `DEFAULT_MIC_GAIN` moved back
to `MIC_GAIN_3X`, matching `DEFAULT_MIC_GAIN_VOICE_MESSAGE` - both paths
now default to the same 3x measured in the original gain matrix (2x ->
+6.3dB, 3x -> +9.7dB, soft-limiter-protected, no clipping at 5x). The
voice-message path's own gain still hasn't been independently measured
(see the "Mic gain split" section) - this only restores round video to a
previously-measured value, it doesn't newly measure voice messages.

## Unit-mismatch fix confirmed; clock-domain theory tested and disproven; 0.13s residual investigated (2026-09-05)

**The fix worked.** Re-measured on the corrected build: audio/video went
from 30.473s/30.167s (0.31s gap) to 30.051s/29.918s (0.13s gap) on the same
~30s recording. Both durations came down, not just the gap - consistent
with audio no longer overrunning past video's actual stop rather than both
tracks just shifting together.

**Clock-domain rate mismatch (Option A/B/C from the earlier "A/V drift fix
options" section) is ruled out, not just deprioritized.** The dual-clock
probe at start/stop measured `System.nanoTime()` (`CLOCK_MONOTONIC`)
advancing 31,269,032,621ns against `SystemClock.elapsedRealtimeNanos()`
(`CLOCK_BOOTTIME`) advancing 31,269,032,855ns over the same ~31s recording -
a 234ns difference, about 7.5 parts per billion. That's noise-floor
measurement jitter between two back-to-back clock reads, not a real rate
difference - on this device the two clocks tick at effectively identical
rates. **Option C (cumulative sample count) will not be implemented** - it
was specifically a hedge against clock-domain rate mismatch, and there's
nothing here for it to correct. `audioBacklogBuffers` was also `0` at the
stop signal, confirming no leftover queued audio either - the fix is
draining the backlog as intended, not just hiding it.

**Investigated where the remaining 0.13s comes from**, per the three
candidates asked about:

- **Container-duration math (checked, ruled out)**: worth recording that
  this was considered and eliminated rather than left unchecked.
  `Track.prepare()` (`MP4Builder`/`Track.java`) computes each track's total
  reported duration as `(lastSampleTimestamp - firstSampleTimestamp) +
  minDelta` (the smallest real inter-sample gap observed, standing in for
  the otherwise-unknowable last sample's own display duration). Worked
  through this by hand with a small worked example: for evenly-spaced
  samples, this is numerically exact, not systematically biased toward
  either track - it isn't a source of the gap. Correcting an
  overhasty assumption made mid-investigation: audio's shorter per-frame
  spacing versus video's does *not* asymmetrically bias each track's
  self-reported container duration once you carry through the arithmetic
  properly - it only matters for what follows below.

- **Stop-condition catch-up granularity (real, but small)**: audio's stop
  check runs once per `AudioRecord.read()` iteration - 2048 bytes each, and
  this device captures float (`audioCaptureIsFloat = true`, confirmed in
  `startRecording()`), so that's 512 samples at 48kHz = ~10.67ms per
  iteration. The fixed comparison can only catch up to `videoLastUs` in
  ~10.67ms increments, so up to one iteration's worth of audio can still
  trail past video's last frame before the check fires - a real, structural
  floor, but it caps out around 10.67ms (average nearer half that), not
  130ms. The same granularity likely also affects `desyncTime`'s own
  precision at the *start* of recording (found via the same first-buffer
  search loop), which would shift the stop threshold by a similar amount -
  so plausibly ~20ms combined, still well short of the observed gap.

- **Encoder/pipeline latency asymmetry (the leading candidate, not yet
  confirmed)**: `videoLast` is a *capture-time* timestamp
  (`SurfaceTexture.getTimestamp()`, set in the GL-thread `frameAvailable()`
  callback) - it marks when a camera frame became available, not when that
  frame's encoded output actually reaches `mediaMuxer.writeSampleData()`.
  Video's path from capture to mux is deeper than audio's (GPU-rendered
  surface input -> hardware encode -> `drainEncoder()`'s
  `dequeueOutputBuffer(..., 10000)` polling loop) versus audio's more direct
  `AudioRecord` -> AAC encode path. If that pipeline latency differs
  meaningfully between the two tracks, it would show up as exactly this
  kind of residual gap and wouldn't be visible in either the
  container-duration math or the capture-side timestamps this investigation
  has looked at so far - both of those only reflect when content was
  *captured*, not when it was *written*. This is the most likely explanation
  for the bulk of the 130ms, but isn't confirmed the way the unit bug and
  the clock-domain theory were - it needs its own instrumented test (logging
  the gap between a frame's capture timestamp and the wall-clock instant its
  sample is actually handed to `mediaMuxer.writeSampleData()`, for both
  tracks) to move from "leading candidate" to "confirmed."

**Net assessment**: the frame-period floor (stop-granularity +
origin-alignment precision) plausibly accounts for something like 20ms of
the 130ms - real, but not most of it. This isn't simply "at the floor and
not worth chasing" - roughly 100ms is still unaccounted for, and pipeline
latency asymmetry is the leading unconfirmed explanation. 130ms is also
close enough to the low end of typically-cited human lip-sync perceptibility
thresholds (audio-trailing-video becomes noticeable to some viewers
starting around 45-125ms) that it's not obviously below the threshold of
mattering, unlike the ~20ms floor component alone would be.

**Decision: stop here.** 0.13s is roughly four video frames (33.3ms each)
as a fixed per-recording offset, not a rate that accumulates with
recording length the way the pre-fix bug did (that one grew however far
audio's backlog had built up by the time video stopped - not bounded by
anything). Started this investigation at 1.4-3.0s free-running, then 0.43s
after the AE-fps-range fix, now 0.13s after the unit-mismatch fix - each
step fixed a real, identified mechanism, and 0.13s fixed is a reasonable
place to stop chasing further. **The pipeline-latency-asymmetry theory
above is left as the leading unconfirmed explanation, recorded with what
would be needed to test it, for anyone who wants to pick it up later** -
not pursued further in this pass.

## Declared-dimension self-check added before sending (2026-09-05)

The earlier "declared dimensions wrong" bug (see that section above) was
completely silent until someone happened to look at the rendered circle -
`resultWidth`/`resultHeight` disagreeing with what was actually muxed
produced a black rim on Android and a square fallback on iOS, with nothing
in logs or on the sending device to say why. Added a guard so a repeat of
that class of bug can't be silent again.

`InstantCameraView.verifyDeclaredDimensions(videoFile, expectedWidth,
expectedHeight)` runs once per recording, right after `mediaMuxer.finishMovie()`
has completed and right before `resultWidth`/`resultHeight` get set on the
`VideoEditedInfo` that's about to be sent. It reads the *actual* finalized
file's own container metadata via `MediaMetadataRetriever`
(`METADATA_KEY_VIDEO_WIDTH`/`_HEIGHT` - fast, just the moov atom, not a
frame decode) and compares it against what's about to be declared. On a
mismatch it logs loudly via `PixelCameraLog.w` with both value pairs and the
file path, unconditionally (not gated behind debug logging) so it survives
into a normal build.

**Chose to log loudly rather than block the send.** Round video's only
delivery path shouldn't fail outright on this check's own possible false
positive (a `MediaMetadataRetriever` quirk on some untested device/codec) -
but a real mismatch must never be silent again either. If a mismatch is
ever actually seen in practice, revisit whether it should hard-block
instead.

Runs synchronously on the UI thread (same thread the surrounding
`resultWidth`/`resultHeight` assignment already runs on) - accepted the
small one-time latency since round-video files are short and
`MediaMetadataRetriever`'s basic metadata extraction only parses the header,
not the frame data.

Only added at the one call site where the file is guaranteed fully
finalized (after `finishMovie()`, in the "send after full local finalize"
path) - not at the other two `resultWidth`/`resultHeight` assignment sites
in this file, which either run against a file that may still be
progressively writing (`notReadyYet`/`allowSendingWhileRecording`) or are a
preview-only path, where a `MediaMetadataRetriever` read could itself give
a misleading result against an incomplete file.

Verified via `:TMessagesProj_App:compileAfatDebugJavaWithJavac`; not yet
exercised against an actual mismatch (there isn't one to reproduce right
now - this is a guard against a *future* regression, not a fix for a
current bug).

## Low Light Boost implemented as a setting, default off, with test instrumentation (2026-09-05)

Implements the plan reported earlier (see "Low Light Boost: static
characteristics confirmed" above) - nothing shipped on by default, per the
explicit instruction not to until measured.

- `PixelGramSettings.isLowLightBoostEnabled()`/`setLowLightBoostEnabled()`,
  default off. Wired into `Camera2Session.updateCaptureRequest()`: when on
  and `lowLightBoostSupported` (checked the same way as the existing
  stabilization/AF capability flags), explicitly sets `CONTROL_AE_MODE` to
  `CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY` (6). This is the
  *first* explicit `CONTROL_AE_MODE` set anywhere in `Camera2Session` - it
  was never touched before (relying on the capture template's own default),
  so the off-path is completely unchanged from before this setting existed.
- Settings row added under "Quality" (`lowLightBoostRow`), gated on both
  cameras supporting it (`Camera2Session.queryLowLightBoostSupported`,
  cached once in `buildRows()` rather than queried per-bind).
- Per-frame logging added to the existing `onCaptureCompleted` callback:
  `CONTROL_LOW_LIGHT_BOOST_STATE` and `SENSOR_TIMESTAMP`, tagged
  `LlbProbe`, active only while the setting is on. Deliberately *not*
  rate-limited like the existing zoom/crop readback log - the point is
  measuring realized fps from consecutive frame timestamps, which needs
  every frame, not a once-a-second sample.
- **Debug-only test scaffolding** (`BuildVars.DEBUG_VERSION`-gated,
  clearly marked temporary throughout, to be removed once LLB's default is
  settled): a "LLB Test FPS Range" row forcing `targetFpsRange` to
  `[30,30]`/`[24,30]`/`[15,30]` instead of the normal auto-pick, since no
  real fps-range setting exists to test the complication described (the
  fixed `[30,30]` range possibly conflicting with LLB's exposure-extension
  brightening mechanism). Deliberately does *not* check the requested range
  against `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` first - part of what's
  being measured is what happens if an unsupported range is requested
  anyway, matching how the original upstream `[30,60]` bug behaved (silently
  ignored/free-run rather than rejected).

Verified via `:TMessagesProj_App:compileAfatDebugJavaWithJavac`. Not yet
tested against an actual dim scene - waiting on that recording. To capture
the test log:
```
adb logcat -d -s PixelCamera:D | grep LlbProbe
```

## Low Light Boost measured: HAL slows down regardless of the fixed AE range - shipping off (2026-09-05)

Full 8-condition matrix recorded in one dim-room session (Auto/[30,30]/[24,30]/[15,30],
each on and off) and analyzed via the `LlbProbe` log (1215 lines).

**`CONTROL_LOW_LIGHT_BOOST_STATE` reported ACTIVE (`1`) on all 1215 logged
frames, across all three requested fps ranges** - it engages unconditionally
in a dim scene, not gated by which range was requested.

**Realized frame rate was 13.9-17.1fps, regardless of the requested range**:

| Requested range | Frames | Realized fps |
|---|---|---|
| `[30,30]` (clip 1) | 245 | 13.9 |
| `[30,30]` (clip 2) | 281 | 14.2 |
| `[24,30]` | 250 | 14.9 |
| `[15,30]` | 439 | 17.1 |

This answers the arbitration question this feature was gated on: **the HAL
extends exposure past the frame budget, and the fixed `[30,30]` range does
not constrain it** - a *fixed* request still only realized ~14fps, roughly
half of the ~30.0fps this app normally achieves (per the earlier
AE-fps-range-fix measurement). This is the same failure mode the AE-fps-range
fix addressed in the first place - requested rate disagreeing with delivered
rate - just from a different cause (LLB's own exposure extension winning
arbitration, not an unsupported range being silently ignored). The mild
upward gradient across the three ranges (13.9 -> 14.9 -> 17.1 as the allowed
floor drops) is one or two ~10s samples per range and should not be read as
a confirmed trend - could easily be scene-to-scene variability across
separate takes rather than a real effect of the requested bound.

**Decision: ship as a setting, default off, not pursued further.** 14fps is
visibly choppy for a talking head - halving the frame rate for brightness is
the wrong trade for round video/voice messages. The settings screen now
states this tradeoff directly (see `lowLightBoostInfoRow`) rather than
leaving it to be discovered. The off-baseline (what fps this same dim scene
would show *without* LLB) was not pursued - not needed to conclude the
trade-off is unfavorable.

**Logging design gap, for future reference**: `logLowLightBoostState()` only
logs when the setting is enabled (`PixelGramSettings.isLowLightBoostEnabled()`
gates the whole method), so no off-baseline was ever capturable from this
log - the four "off" clips in the matrix produced zero `LlbProbe` lines by
design, not by omission. A future investigation needing an on/off frame-rate
comparison from a single log would need `SENSOR_TIMESTAMP` logged
unconditionally, gating only the `CONTROL_LOW_LIGHT_BOOST_STATE` field on
the setting.

The debug-only "LLB Test FPS Range" selector (`PixelGramSettings.KEY_LLB_TEST_FPS_RANGE`,
`Camera2Session.llbTestFpsRangeOverride()`) has been removed now that the
measurement is done - it was always scoped as temporary investigation
scaffolding, not a shipped feature.

## Preview Stabilization implemented as a setting, default off; crop not yet measured (2026-09-05)

Implements the plan reported earlier - `CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION`
(2) confirmed available on both cameras (`CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`
includes it, and `SCALER_MANDATORY_PREVIEW_STABILIZATION_OUTPUT_STREAM_COMBINATIONS`
is populated on both - genuine HAL backing, not just an advertised enum
value).

`PixelGramSettings.isPreviewStabilizationEnabled()`/`setPreviewStabilizationEnabled()`,
default off. Wired into `Camera2Session.updateCaptureRequest()`: when on and
`previewStabilizationSupported`, sets `CONTROL_VIDEO_STABILIZATION_MODE` to
`PREVIEW_STABILIZATION` (2) - **superseding**, not adding to, the existing
always-on basic stabilization (mode `ON`, 1), since both share the same
capture-request key and only one value can be set. When the setting is off,
falls back to exactly the existing behavior (mode `ON` whenever supported,
unchanged from before this setting existed). Settings row gated on both
cameras supporting it, same caching-once-in-`buildRows()` pattern as Low
Light Boost.

**Crop not yet measured.** No static characteristic exposes the exact
percentage - Android's own docs describe "up to 20%" as a general FOV-
reduction guideline, device/HAL-specific in practice. Waiting on two
recordings (same well-lit static scene, setting on vs. off) to compare
actual content bounds and report the real number, including how it
compounds with this app's own existing supersample-to-circle crop.

Verified via `:TMessagesProj_App:compileAfatDebugJavaWithJavac`.

## Preview Stabilization crop measured: no measurable crop on a static mount (2026-09-05)

Recorded two clips (`1788623778292.mp4` off, `1788623812783.mp4` on),
phone static on a stand pointed at a fixed scene, unmoved between them. No
image library was available in this environment (no PIL/numpy/cv2/
ImageMagick) - wrote a small pure-Python PNG decoder and measured pixel
brightness profiles directly against a rigid reference (a wall mirror
visible in both clips), at two independent, well-separated timestamps per
clip to guard against a one-frame fluke:

| Clip | Frame A width | Frame B width |
|---|---|---|
| Stabilization off | 61.0px | 59.5px |
| Stabilization on | 61.5px | 61.0px |

Average off ≈60.25px, on ≈61.25px - about 1.7% larger on, which is smaller
than this measurement's own noise floor (edge-picking precision on a ~60px
feature is realistically ±2-5%). Confirmed visually too: cropping both
frames to an identical window and viewing them side by side shows the
mirror at essentially the same size and position in both. **No crop
distinguishable from zero, well under Android's own "up to 20%" documented
guidance.**

**This is a floor, not the real-world figure.** Preview Stabilization's
crop margin is understood to be reserved for compensating actual detected
motion, not a fixed always-on crop - the phone was genuinely static on a
stand, so there was no shake to correct, and it appears to have used
little to none of its available margin. Round video and voice messages are
recorded handheld in practice, where real hand-shake would likely draw on
that margin and produce a real, larger crop than this test can reveal. Not
pursued further per instruction - a handheld on/off pair would be needed to
pin down the actual figure if that's wanted later.

**Compounding with the existing crop chain** (3440x2448 sensor active array
down to the round-video circle):

| Stage | Area retained | Cumulative |
|---|---|---|
| Center-square crop (3440->2448 width, height untouched) | 2448/3440 = 71.2% | 71.2% |
| Downscales (2448->1920->640) | 100% (same aspect ratio) | 71.2% |
| Circular mask (inscribed circle in the square) | pi/4 = 78.5% | **55.9%** |
| Preview Stabilization - as measured here (static) | ~100% | **55.9%** |
| Preview Stabilization - Android's "up to 20%" guidance (plausible handheld case) | (1-0.20)^2 = 64% | **35.8%** |

Even before Preview Stabilization, only ~56% of the sensor's active area
ends up visible in the circle - the existing framing cost. As measured on
a static mount, stabilization adds no further loss on top of that; if a
handheld recording draws on the full margin Android describes, the total
could drop to ~36% of the sensor's area. Ships default off; the static-mount
number should be treated as a floor, not planned around.

## Adaptive Gain implemented: look-ahead limiter + slow leveler, replacing the fixed multiplier (2026-09-05)

Implements the design reported earlier. Motivation: the fixed mic-gain
multiplier is a single constant that's right for no one recording - 1x is
too quiet for a normal speaking voice, 3x is sometimes too loud, because the
correct gain depends on how loud the person actually is at the mic, which
varies by person, distance, and room. RNNoise (if active) doesn't help with
this - it removes noise, it does not add level (see the "Mic gain reverted"
section above for the reasoning error that once conflated the two).

**RNNoise VAD verified before relying on it, per instruction.** Checked
`rnnoise_process_frame()`'s implementation directly in the vendored
`denoise.c` rather than assuming: the local variable it returns is literally
named `vad_prob`, computed by `compute_rnn()`'s dedicated VAD output head.
This is a confirmed real speech-activity probability, not a value whose
meaning had to be guessed at - so the silence-freeze logic uses it directly
rather than building a separate energy-based VAD as the primary mechanism.
Plumbed through: `speech_enhancer.c`'s `nativeProcessFrame` now returns
`jfloat` (was `void`, discarding it); `SpeechEnhancer.getLastVadProbability()`
exposes the most recently completed block's value. A simple energy-threshold
fallback (-50dBFS floor) is still used, but only when Speech Enhancement is
off entirely and no RNNoise signal exists at all - not as a substitute for a
confirmed-real signal, just for the case where that signal isn't running.

**`AdaptiveGainProcessor`** (new class, `org.telegram.messenger.camera`) -
named to avoid confusion with `PixelGramSettings.isAgcEnabled()`, which
wraps the *platform's* `android.media.audiofx.AutomaticGainControl` effect,
a different mechanism entirely (already confirmed unavailable on this
device). Two independently-smoothed gain components:
- **Slow leveler** (`slowGainDb`): updated once per incoming buffer, target
  -20dBFS RMS by default (now adjustable, `getAdaptiveGainTargetDb()`/
  `ADAPTIVE_GAIN_TARGET_DB_VALUES`, -30 to -12dB in 3dB steps). Asymmetric
  timing - 1s to reduce gain if a passage runs hot, 4s to raise it back -
  so a brief pause mid-sentence doesn't get amplified before the next word
  arrives. Frozen entirely during non-speech (per the VAD signal above), so
  gain doesn't creep up during silence and overshoot when speech resumes.
  Clamped to the requested 0.3x-8x bound (-10.46dB to +18.06dB).
- **Fast limiter** (`limiterGainDb`): -3dBFS ceiling, matching the existing
  soft limiter's own headroom choice. Smoothed per-sample (5ms attack,
  100ms release - standard look-ahead-limiter ballistics), not gated on
  speech/silence - clipping protection should never turn off.

**Look-ahead without a separate delay buffer**: audio already arrives in
~10ms chunks. Because this class sees an entire incoming buffer before
writing any of it back, it computes that buffer's own peak first and
shapes gain across the whole buffer accordingly - genuine look-ahead within
the buffer's own ~10ms window (in the range real limiters use), no added
latency. The simplification this implies: gain is computed once per buffer
rather than continuously varying sample-by-sample within it - documented in
the class's own doc as a deliberate choice, not an oversight.

**Toggle relationship, as specified**: on replaces the fixed multiplier
entirely (both "Microphone Gain" pickers grey out, showing "Adaptive Gain
active" instead of their value); off falls back to exactly today's
behavior, unchanged. One setting covers both round video and voice
messages (unlike the mic-gain multiplier, which is split per-path) - if a
per-path split turns out to matter, that's a follow-up, not implemented
here.

Wired into both recording paths (`InstantCameraView` for round video,
`MediaController` for voice messages), same per-recording-session lifecycle
as `SpeechEnhancer`/`VoiceIsolationProcessor` - constructed only when
capturing in float (same requirement Speech Enhancement already has).

Verified via `:TMessagesProj_App:assembleAfatDebug` (exercises the native
JNI signature change, not just the Java side) and confirmed the new class
is present in the built APK's dex. **Not yet tuned by ear** - the time
constants and thresholds above are principled starting points (matching
established limiter/leveler design conventions and this app's own existing
-3dBFS soft-limiter headroom), not something verified against a real
recording yet. Ships off by default per the standing convention.

## Early impression: Adaptive Gain alone may beat RNNoise+Adaptive Gain in a quiet room (2026-09-05, unconfirmed)

Informal observation, not a controlled measurement: with RNNoise turned off and
Adaptive Gain on, the result subjectively sounded as good as or better than
with RNNoise also active. Plausible explanation - they solve different
problems (Adaptive Gain evens out overall speech *level*, RNNoise removes
*background noise*), so in a quiet room where there's little background
noise to remove, RNNoise has nothing to earn its keep against, and whatever
artefacts it does introduce (the per-chunk splice bug already found and
fixed, or more subtly the wet/dry blend's own audible tradeoffs already
documented in the RNNoise section above) may show up as a net negative with
no offsetting benefit.

**Not a conclusion - needs testing in an actual noisy environment**, where
RNNoise would have real background noise to remove and its benefit could
actually outweigh whatever it costs. A quiet-room comparison alone can't
distinguish "RNNoise isn't helping here" from "RNNoise never helps enough to
be worth it" - only a noisy-room test can.

## Sharpness comparison vs iPhone: real, measured softness, resolution-normalized (2026-09-05)

Compared matched circles (`video.mp4` iPhone at 400x400, `1788631976316.mp4`
ours at 640x640, same pose/framing, static scene). No image library was
available (no PIL/numpy/cv2/ImageMagick) - reused the pure-Python PNG
decoder from the Preview Stabilization crop measurement, adding a
Laplacian-variance sharpness metric (standard blur metric: variance of a
3x3 Laplacian response, higher = sharper) and a direct edge-width
measurement (10%-90% transition span across a real hairline edge, in
pixels).

**Normalized for the resolution difference first**, per the request: our
640x640 frame downscaled to 400x400 with ffmpeg's unbiased `area` algorithm
(box averaging, no sharpening/ringing bias of its own) to match the
iPhone's actual pixel pitch, *before* any comparison - this puts both
images on the same real-world mm-per-pixel scale, so the metrics below are
comparing like for like, not penalizing ours for merely having more pixels
to spread the same detail across.

| Metric (at matched 400x400 pixel pitch) | iPhone | Ours (downscaled) | Ratio |
|---|---|---|---|
| Laplacian variance, full frame | 3438.3 | 815.6 | **4.2x lower** |
| Edge width (10-90%), hairline | ~10-12px | ~17-18px | **~1.6x wider** |

**Confirmed: the softness is real, not a resolution artifact.** Even after
matching pixel pitch, ours has meaningfully less high-frequency energy and
meaningfully wider edge transitions. Visual comparison at matched
resolution (cropped to the same hairline/glasses region) shows the same
thing directly - individual hair strands and skin texture resolved on the
iPhone are smoothed away on ours.

**Radial pattern (center/mid/edge zones) was inconclusive from this single
frame** - the ratio between ours and the iPhone's Laplacian variance
wasn't monotonic across zones (center 0.18x, mid 0.38x, edge 0.22x of the
iPhone's own zone value). This is likely confounded by real content
differing between zones (wall vs. face vs. hair each have very different
natural high-frequency content), not necessarily evidence about whether
the *processing itself* degrades unevenly across the frame. A clean answer
to "uniform or worse at the edges" would need a uniform test target (a
resolution chart or grid), not natural face content - not pursued here.

### Lanczos tap-spacing investigation

Confirmed directly in `InstantCameraView.java`'s own comment
(`SUPERSAMPLE_H_VERTEX_SHADER`'s doc): tap spacing is one *destination*-pixel
width in UV space (`texelSize = 1/videoWidth` or `1/videoHeight`), not a
fixed source-texel step. For this recording's 3:1 ratio (1920 capture ->
640 output), one destination-pixel step in UV equals exactly **3 source
pixels** - so the shader's 9 taps (center + 4 pairs, at
0/±1/±2/±3/±4 x texelSize) land at source-pixel offsets 0, ±3, ±6, ±9, ±12.
That's a 24-source-pixel-wide reach, but only 9 discrete source pixels
within it are actually sampled - **the other roughly two-thirds of source
pixels in the kernel's own support window are never looked at**, aside from
whatever incidental blending `GL_LINEAR` provides if a tap doesn't land
exactly on a texel center (which the destination-pixel-aligned UV math
likely does, for at least the on-axis taps).

**This is wider than ideal, in the specific sense of being sparse, not
(only) in the sense of being spatially too wide.** Standard minification
filtering theory (Lanczos or any windowed-sinc filter used to downscale by
a factor `N`) calls for widening the kernel's support by `N` *and* sampling
it densely enough to integrate the source signal across that support - the
zero-crossings should land every `N` source pixels, but every source pixel
in between needs its own tap, not just the ones a whole destination-pixel
apart. Skipping two out of every three source pixels is a real risk of
letting high-frequency source content between the sample points go
unaveraged - which reads perceptually as haze/softness on natural texture
(skin, hair) rather than classic jagged aliasing, since there's no hard
geometric edge for it to show up as stair-stepping on.

**Computed what a correctly-3x-scaled Lanczos-2 kernel should look like**,
for comparison (`lanczos2(x) = sinc(x)*sinc(x/2)` for `|x|<2`, support
scaled to `a*N = 2*3 = 6` source pixels, sampled at every source pixel,
renormalized to sum to 1):

| Source-pixel offset | Correctly-scaled weight |
|---|---|
| 0 | 0.3301 |
| ±1 | 0.2607 |
| ±2 | 0.1129 |
| ±3 | 0.0000 (exact zero-crossing) |
| ±4 | -0.0282 |
| ±5 | -0.0104 |
| ±6 | 0.0000 (exact zero-crossing) |

Because ±3 and ±6 land exactly on this kernel's zero-crossings, a 9-tap
structure (center + 4 pairs, same vertex-shader shape already in place)
reaches out to ±4 *source* pixels and captures nearly all of the kernel's
real mass - truncating only the small ±5 lobe (-0.0104, a minor loss). The
fix this suggests is small in code terms: change `texelSize` to one
*source*-texel width (`1/1920` for the H-pass's capture texture, and the
matching source width for the V-pass's intermediate texture) instead of
one destination-pixel width, and swap in weights `[0.3301, 0.2607, 0.1129,
0, -0.0282]` for center/±1/±2/±3/±4 - no change to the tap *count* or the
shader's structure, just what each tap measures and how it's weighted.

**Caveat**: reverse-engineering the *shipped* weights
(`{0.38026, 0.27667, 0.08074, -0.02612, -0.02143}`) against a simple
integer-or-fractional Lanczos-2 evaluation didn't land on an exact match at
any scale factor tried - they're close in shape to a ~0.4-spacing
evaluation but not identical, so their original derivation (this shader
reused weights from a different, previously-unused asset - see the
existing code comment) isn't fully pinned down here. That uncertainty
doesn't change the tap-spacing diagnosis above, which follows directly from
the code's own stated behavior, independent of exactly how the current
weight constants were derived.

**Not yet implemented** - this was investigation, not a fix, per request.

### Dither's possible contribution

`dither:2.0xLSB` was active for this recording (per the user - double the
1x default). Dithering necessarily adds a small amount of pseudo-random
noise before 8-bit quantization, by design, to break up banding - that's
extra high-frequency energy in the pre-encode signal. Plausible mechanism
for it to read as haze: a rate-limited video encoder can't distinguish
"real fine texture" from "injected dither noise" when deciding what detail
to spend bits on, so competing with dither noise for the same bit budget
could cause *real* detail to be quantized away more aggressively than it
would be without the extra noise - a softer *encoded* result even though
the raw pre-encode signal has more (dither-added) energy, not less. Not
quantified here - would need a same-scene dither-on-vs-off pair (this
comparison only had dither-on footage) to isolate its actual contribution
from the Lanczos tap-spacing issue above.

## Lanczos tap spacing fixed; made ratio-adaptive; weights' origin traced (2026-09-05)

Implemented the fix from the investigation above.

**Tap spacing**: `texelSize` (the vertex shaders' per-tap UV step) now comes
from the actual source dimensions at each pass's own call site -
`previewSize[surfaceIndex].getWidth()` for the H-pass (reading the raw OES
camera texture), `supersampleTexHeight` for the V-pass (reading the
intermediate texture, which pass 1 only downscaled horizontally - its
height is still the source height, not `videoHeight`). Previously both
used the *destination* dimension (`1/videoWidth`, `1/videoHeight`), which
for this app's typical ~3:1 ratio meant sampling only every third source
pixel.

**Weights, made ratio-adaptive rather than fixed**: since resolution is
user-configurable (320-960px against a fixed 1920px capture - ratios from
2:1 to 6:1), a fixed weight table tuned for exactly 3:1 would have simply
relocated the same mistake to every other resolution setting. Lanczos-2's
weights are now computed at pipeline-setup time
(`lanczosWeightsForRatio()`) from the actual `sourceWidth/videoWidth` ratio
in effect, evaluating the standard `sinc(x)*sinc(x/2)` kernel at
`x = tapOffset/ratio` for each of the 9 taps and renormalizing - the
textbook approach for a properly-scaled minification filter (widen the
kernel's support by the scale factor, sample it at every source pixel
within that support, not just every `ratio`-th one).

**Traced the old fixed weights' actual origin, since this was asked
directly**: **not written by this fork**, contrary to the reverse-engineering
attempt in the investigation above assuming they were. `git log -S` on the
constant traces to this fork's own commit `d31fe37d3` ("GL supersampling
for round video..."), but that commit's own comment says the values were
*reused* from `res/raw/instant_lanczos_frag_oes.glsl` - and that asset
itself, checked via `git log --follow`, dates back to a genuine stock
Telegram commit (`4a8efef9d`, "update to 10.8.1"), long before this fork
existed. The shader's variable names
(`oneStepLeftTextureCoordinate`/`twoStepsLeftTextureCoordinate`/etc.) and
exact weight values match the well-known open-source GPUImage Lanczos
resampling filter, a commonly-copied GL filter - Telegram's own upstream
almost certainly copied it from there, unattributed, as many mobile GL
filter implementations do. **This fork's actual mistake was reusing an
existing-but-previously-unused asset's weights for a new purpose (the 3:1
supersample downscale) without checking whether they matched** - a
misapplication of pre-existing code, not a fresh derivation error. The
exact ratio/spacing those original weights were designed for isn't fully
pinned down (closest numerical match found was roughly a Lanczos-2 kernel
evaluated at ~0.4-unit spacing, not an exact fit to any formula tried) -
that residual uncertainty doesn't matter now that the weights are computed
fresh for whatever ratio is actually in effect.

**Box and Gaussian were checked for the same mistake, found different**:
Box's `1/9` flat weights aren't "derived" from anything ratio-specific in
the first place - trivially unaffected by this particular question, though
still subject to the shared tap-spacing fix's effect on what they now
average. Gaussian's weights, checked by direct computation, are an *exact*
match for a genuinely fresh derivation (`sigma=1.6`, matching its own
comment precisely, not reused from anywhere) - this fork's own work, done
correctly for what it claims to be. **Neither Box nor Gaussian was made
ratio-adaptive** - both still use the same fixed constants as before,
now sampling at the corrected (denser) tap spacing but with weight shapes
that were never verified against *any* specific ratio, sigma=1.6 included.
Left alone since only Lanczos was asked for; worth revisiting if either's
behavior after this fix doesn't hold up.

Verified via `:TMessagesProj_App:compileAfatDebugJavaWithJavac`. Not yet
measured on-device against the confirmed-softer baseline below - that's
the next recording.

## Sharpness baseline established: all three dither levels well below iPhone before the fix (2026-09-05)

Recorded three matched clips, oldest to newest, before the tap-spacing fix
landed: dither off, then 1x, then 2x, plus a fourth iPhone reference clip -
same scene, same distance, sitting still. **Could not cross-check the
stated recording order against the on-device log as planned**: the live
logcat buffer had already rotated past these recordings by the time this
analysis ran, and the persisted rotating log file
(`PixelCameraLog`'s own file, gated on the "Debug Logging" setting) wasn't
being written at all - Debug Logging defaults off, and this was a fresh
install after the applicationId change, so the setting was back at its
default. Proceeded on the stated order (file timestamps are consistent
with it), but this is a real, flagged gap, not a confirmed cross-check -
worth turning "Debug Logging" on before the next comparison if this
matters again.

Same normalized method as the earlier iPhone comparison (downscale to
iPhone's 400x400 pixel pitch with ffmpeg's unbiased `area` algorithm,
Laplacian-variance sharpness metric):

| Clip | Laplacian variance | Ratio vs. iPhone |
|---|---|---|
| Dither off | 1055.1 | 0.326x |
| Dither 1x | 1111.7 | 0.344x |
| Dither 2x | 1135.1 | 0.351x |
| iPhone | 3232.7 | - |

**All three sit in the same ballpark, well below iPhone regardless of
dither setting** - confirming the tap-spacing issue (fixed above) as the
dominant driver of the softness, not dither. This is the pre-fix baseline
the corrected tap spacing should improve on; a re-recording after the fix
is the next step.

**Whether dither itself contributes anything on top of that is genuinely
unresolved from this data**, not just unmeasured - the two metrics tried
disagree in a way that points to a methodology problem rather than a real
answer:
- Laplacian variance *rises* slightly with more dither (1055 -> 1112 ->
  1135) - expected and uninformative either way, since dither mechanically
  adds high-frequency energy whether or not it's perceived as detail or as
  haze; this metric can't tell those apart.
- A direct edge-width measurement (same hairline-edge method as the
  iPhone comparison) instead *narrows* with more dither (10.9px -> 9.4px ->
  6.2px, moving toward iPhone's 4.5px) - the opposite of what "dither reads
  as haze" would predict, but this is only one edge location per clip
  across three separate takes with their own natural pose variation, and a
  threshold-crossing width measurement is exactly the kind of metric random
  noise could distort in either direction without reflecting genuine
  sharpness. Not trusted as a real finding.

Isolating dither's actual contribution (if any) would need multiple frames
averaged per clip and ideally a single static frame compared frame-for-frame
across dither settings, not three separate short takes - not pursued
further here since the dominant effect (tap spacing) is already identified
and fixed.

## Audio comparison vs iPhone: RNNoise off, Adaptive Gain at -15dBFS target (2026-09-05)

First measurement of this specific configuration against the iPhone
reference. Used `ffmpeg`'s `astats` filter for levels/noise floor and a
pure-Python Welch-averaged FFT (no numpy/scipy available) for frequency
response, on the same four-clip recording session as the video comparison
above (any of the three PixelGram clips' audio, since dither doesn't touch
audio - used `dither_1x`).

**Levels**: overall-clip RMS varied noticeably across the three PixelGram
takes despite a fixed -15dBFS target (dither off -27.96dB, 1x -24.52dB, 2x
-22.40dB) - and correlates with each clip's *duration* (8.1s / 9.0s / 9.5s,
shortest-to-longest matching quietest-to-loudest). Likely explanation:
Adaptive Gain's slow leveler (1s attack / 4s release, chosen so a brief
pause doesn't get overcorrected) may not fully converge within a typical
~8-10s round-video clip, especially starting from 0dB (1x) at the start of
every fresh recording - longer clips simply had more time to climb toward
the target before ending. The "RMS peak dB" figure (a windowed running-RMS
peak, a better proxy for sustained speech level than the whole-clip average
which silence/pauses dilute) was closer to target-shaped for the middle
take (1x: -15.95dB, close to the stated -15dBFS) but still ranged from
-19.9dB (off) to -12.5dB (2x) - consistent with the same
not-fully-converged-within-clip-length explanation rather than a stable,
repeatable target level. **Worth checking whether the leveler's time
constants are well-matched to typical round-video clip lengths** - this
wasn't part of what was asked but fell out of the data.

**Noise floor**: iPhone shows a real, finite measured noise floor (-37.1dB,
219 samples at that level). Ours reports `-inf` with a much larger count
(3745 samples) at the extreme floor - consistent with genuine
digital-silence stretches, not just "very quiet." Whether that reflects a
genuinely quieter capture chain or something else entirely (e.g. how
Adaptive Gain's silence-frozen gain interacts with an already-near-zero
signal) isn't resolved here - the noise *character* also isn't
comparable given the frequency-response difference below, so a bare dB
comparison between the two noise floors likely isn't apples-to-apples
either.

**Frequency response - the clearest, most concrete difference found**:
averaged (Welch-method) power spectrum over active-speech windows only
(silence skipped), split into bands:

| Band | Ours | iPhone |
|---|---|---|
| Low (80-300Hz) | 77.7% | 45.8% |
| Mid (300-2000Hz, primary speech band) | 19.6% | 51.2% |
| High (2-6kHz) | 0.7% | 1.3% |
| Very high (6-20kHz) | 0.1% | 1.3% |

Ours is heavily low-frequency-weighted relative to the iPhone's much more
balanced low/mid split, and the iPhone captures meaningfully more
high-frequency content (13x more energy in the 6-20kHz band). This is a
clean, plausible explanation for a perceived "muffled/duller" audio
quality, distinct from the video sharpness issue. **Candidate causes, not
distinguished from each other here**: a genuine capture-chain frequency
response difference (AudioSource choice, AAC encoder bitrate/profile), or
simply that the two phones were held at different distances/angles from
the mouth in their separate takes - close-mic'd sources naturally pick up
more low frequency (proximity effect) as a matter of physics, unrelated to
any processing choice. Distinguishing these needs a controlled test (same
physical distance/position for both phones, ideally simultaneous) rather
than two independently-positioned recordings.

## Reproduce the measurement
adb pull "/sdcard/Download/Telegram/<file>.mp4" ~/circles/<name>.mp4
ffprobe -v error -show_entries stream=codec_type,r_frame_rate,avg_frame_rate,bit_rate,nb_frames,start_time,duration -of default=noprint_wrappers=1 <file>
ffprobe -v error -select_streams v -show_entries frame=pts_time -of csv=p=0 <file> | awk 'NR>1{d=$1-p; if(d>0.05) printf "gap %.3fs at t=%.3f\n", d, $1} {p=$1}'
## Lanczos fix measured: recovers ~40% of the sharpness deficit, doesn't close it (2026-09-05)

Recorded the same four-clip sequence again on the fixed build (dither off,
1x, 2x, then the iPhone reference), debug logging on this time so the
on-device marker log could confirm ordering directly rather than trusting
file timestamps - which turned out to matter: all three PixelGram files
synced to the device with effectively identical modification timestamps
(within ~30ms of each other, clearly a batch-sync artefact), useless for
ordering. Resolved instead by matching each file's own `ffprobe` duration
against the marker log's `AVDriftProbe` start/stop gaps between the three
"recording start" lines - the deltas between recordings matched almost
exactly (log-predicted 0.93s/0.26s gaps between off→1x→2x vs. 0.94s/0.27s
measured on the actual files), a strong, non-coincidental confirmation of
which file is which.

**Video, same resolution-normalized method as the pre-fix baseline**
(PixelGram's 640x640 downscaled to the iPhone's native 400x400 via
ffmpeg's unbiased `area` filter, then Laplacian-variance):

| | Pre-fix (ratio to iPhone) | Post-fix (ratio to iPhone) | Recovery |
|---|---|---|---|
| dither off | 0.326x | 0.461x | +41.3% relative |
| dither 1x | 0.344x | 0.469x | +36.4% relative |
| dither 2x | 0.351x | 0.514x | +46.5% relative |

The fix recovered a real, substantial fraction of the deficit - roughly
40% relative improvement across all three dither levels - but PixelGram
still measures well below the iPhone's Laplacian variance (0.46-0.51x, not
1.0x). Some residual gap is expected and not necessarily fixable here: the
iPhone's own ISP sharpening, sensor/lens differences, and any encoder-side
detail loss are all outside what a resampling-kernel fix touches. Not
claiming the remaining gap is fully explained by anything currently
understood.

**Dither's own contribution, revisited now that tap spacing is fixed**: a
small, monotonic increase with dither level now shows up (off 0.461x → 1x
0.469x → 2x 0.514x), which wasn't separable from the tap-spacing confound
before. But an edge-transition-width probe (10-90% brightness crossing,
scanned across several rows per frame) - the same method already flagged
in the pre-fix baseline as noise-vulnerable - reports PixelGram's edges as
*narrower* than the iPhone's at all three dither levels (2-3px vs 9px),
which is almost certainly the same noise-spike artefact previously
distrusted, not genuine sharpening. So dither's contribution is now
*measurable* as a small effect on the variance metric, but still not
confirmed as real added detail rather than noise inflating that metric.
Laplacian variance can't tell the two apart by construction; resolving
this further would need a metric that isn't fooled by high-frequency noise
(e.g. detail correlated across multiple frames of the same static scene,
which genuine noise wouldn't be).

## Second audio sample: bass-heavy characteristic reproduces; root cause narrowed to two candidates (2026-09-05)

Repeated the frequency-response comparison (Welch-method averaged power
spectrum, active-speech windows only) as an independent second sample,
same RNNoise-off / Adaptive Gain -15dBFS-target configuration as before:

| Band | Ours (sample 2) | iPhone (sample 2) | Ours (sample 1) | iPhone (sample 1) |
|---|---|---|---|---|
| Low (80-300Hz) | 70.8% | 47.7% | 77.7% | 45.8% |
| Mid (300-2000Hz) | 23.6% | 41.7% | 19.6% | 51.2% |
| High (2-6kHz) | 0.6% | 7.5% | 0.7% | 1.3% |
| Very high (6-20kHz) | 0.4% | 0.3% | 0.1% | 1.3% |

**The core characteristic reproduces**: ours is heavily low-frequency-
weighted relative to the iPhone in both independent samples (70.8-77.7%
below 300Hz vs. the iPhone's 45.8-47.7%) - this is a real property of the
capture chain, not a one-off positioning artefact from how either phone
happened to be held in a single take. **The specific band where the
high-frequency deficit concentrates did not reproduce exactly**: sample 1
showed the gap concentrated above 6kHz (13x less than the iPhone there,
roughly at parity in 2-6kHz); sample 2 shows it concentrated in 2-6kHz
(12.5x less than the iPhone there) with near-parity above 6kHz. Most
likely explanation is ordinary speech-content variance between takes
(different vowels/consonants carry energy at different formant
frequencies) rather than the effect itself being unstable - the aggregate
low-vs-mid split is consistent, the fine spectral shape above it isn't.

**Investigated three candidate causes, as requested, before changing
anything:**

1. **Bandpass engaged despite Voice Isolation being off - ruled out.**
   Read `VoiceIsolationProcessor.process()`/`processFloat()` directly: both
   check `PixelGramSettings.getVoiceIsolationMode() == VOICE_ISOLATION_OFF`
   as their very first statement and return immediately, before touching
   the buffer at all, before either biquad runs. No filtering of any kind
   happens on this path when the mode is off. The marker log also directly
   confirms `voiceIsolation:0` for all three takes. This is airtight, not
   just probably-fine.

2. **Adaptive Gain's leveler RMS calculation is bass-weighted - ruled out,
   on mathematical grounds rather than just by inspection.**
   `AdaptiveGainProcessor`'s RMS is `sqrt(sumSquares/sampleCount)` over raw
   time-domain samples with no frequency weighting whatsoever, and the one
   correction it applies (`totalGain`) is a single scalar multiplied onto
   every sample in the buffer, uniformly, regardless of frequency content.
   A uniform gain multiply changes overall level but cannot reshape the
   spectrum - it moves every band up or down by the same number of dB, so
   the *proportions* between bands (what the 70-78%/46-48% figures above
   actually measure) are invariant under it by construction. Whatever is
   producing the bass-heavy shape, it isn't happening in this stage -
   Adaptive Gain could only make the signal louder or quieter, never more
   or less bass-weighted.

3. **AudioSource change to MIC/DEFAULT vs. the earlier CAMCORDER default -
   still an open candidate, not resolved either way.** Round video's
   `voiceEnhancement:0` ("Off (raw mic)") maps to `AudioSource.DEFAULT`,
   changed from `CAMCORDER` per this project's own prior SNR measurement
   (see "Round video's default AudioSource switched to MIC/DEFAULT"
   above) - but that comparison measured broadband SNR/level in dB only,
   never frequency response. Whether `CAMCORDER`'s HAL-level tuning
   (documented by Android only as "tuned for video recording, with the
   microphone's directional characteristics suitable for recording
   video," nothing more specific) differs from `DEFAULT`/`MIC` in its
   frequency shape - rather than just its gain - isn't established by
   anything already measured in this project, and vendor audio-HAL tuning
   for named `AudioSource` presets isn't part of the public Camera2/audio
   framework contract, so it can't be settled by reading code either.
   Confirming this would need a direct A/B recording - same room, same
   distance, same take, switching only the `AudioSource` - which hasn't
   been done. Left open rather than guessed at.

**Net: the bass-heavy characteristic is real and reproducible, and two of
three candidate mechanisms in this app's own DSP chain are cleanly ruled
out. The remaining open candidate is upstream of anything this codebase's
software controls** (the platform's own `AudioSource`-dependent tuning),
which narrows this considerably even without a definitive answer.

## Second bug found in the fix itself: the ratio was shared across a non-square capture (2026-09-05, fixed same session)

The new recording's marker log showed `capture:1920x1080` - the first
non-square capture size seen in any of this session's tests (every prior
test was `1920x1920`). The just-landed fix computed a single downscale
ratio (`sourcePreviewSize.getWidth() / videoWidth`) and used it for *both*
the horizontal and vertical Lanczos weight tables. For a square capture
this is harmless since both axes share the same ratio; for `1920x1080` at
a 640 output it isn't - width ratio is 1920/640=3.0, height ratio is
1080/640=1.6875, and the V-pass was still using the width-derived (too
wide) ratio for its own, different downscale factor. This would have
under-supported the V-pass's kernel relative to what its actual 1.6875x
downscale calls for, likely costing some vertical sharpness specifically
on non-square captures - a new instance of the same class of bug the
tap-spacing fix addressed, just narrower in scope.

Fixed by computing the two ratios separately
(`sourcePreviewSize.getWidth()/videoWidth` for the H-pass,
`sourcePreviewSize.getHeight()/videoHeight` for the V-pass) and building
each pass's Lanczos weight table from its own ratio. The vertex-shader tap
*positions* were never affected by this - those already used each pass's
own correct texel size (source width/height respectively) after the
tap-spacing fix - only the per-pass *weight* tables were sharing one
ratio. Not yet re-measured on-device against a non-square capture
specifically; the numbers in the section above were all captured before
this second fix landed, so they reflect the width-ratio-only V-pass
weights, not this correction. Whether this made a measurable difference
on the `1920x1080` case would need one more recording to confirm.

## Leveler timing: pumping cost reported; attack/release made adjustable (2026-09-05)

**Why RMS tracked clip duration (flagged in the first audio comparison,
investigated here as requested):** `AdaptiveGainProcessor`'s slow leveler
is a one-pole filter; a one-pole filter closes a fraction
`1 - e^(-t/T)` of the remaining gap after `t` seconds against time constant
`T`. At the shipped release constant (`T=4.0s`), an 8-second clip - typical
for a round video - only reaches `1 - e^(-8/4) = 86.5%` of the way to
target by the time recording stops, starting from 0dB (1x) at the start of
every fresh recording since there's no prior state to carry over. A 4-second
clip would only reach 63%. This directly explains the observed pattern:
longer clips measured closer to the -15dBFS target, shorter ones measured
further off, because none of them ran long enough to fully converge.

**What faster constants would cost:** the leveler's job (per its own class
doc) is to track *sustained* loudness - a different speaker, a change in
distance from the mic, a room change - while riding through normal
speech dynamics (word/sentence pauses, natural pitch and emphasis
variation, breaths) without following them. Ordinary conversational speech
has pauses on the order of 100-300ms between words and 300ms-1s between
sentences; a release time constant faster than roughly that range starts
to treat those normal gaps as material to release toward, producing
audible "pumping" - the perceived loudness swelling after a pause and
dipping during a stressed syllable, in sync with the rhythm of speech
rather than staying settled for the whole utterance. This is precisely the
distinction between a leveler/AGC (slow, tracks sustained level) and a
compressor (fast, shapes syllable-to-syllable dynamics) - this class
already has a separate fast component (`limiterGainDb`, 5ms/100ms) doing
the compressor-like job of catching peaks; speeding up the *slow* leveler
to compensate for short clips would blur that distinction and take on
compression's characteristic artefact without gaining a compressor's
intentional loudness-shaping benefit. There is no value that's simply
better: shorter constants converge more reliably within a typical clip
length at the cost of more audible envelope-following; longer constants
sound more natural on sustained speech at the cost of not converging
within short clips. This is a real, unavoidable tradeoff, not something to
tune away by picking one number.

**Made adjustable rather than picking a value**, per request:
`PixelGramSettings.getAdaptiveGainSlowAttackSec()`/
`getAdaptiveGainSlowReleaseSec()` (defaults 1.0s/4.0s, exactly matching the
prior hardcoded constants, so leaving them untouched changes nothing),
surfaced as two new settings rows under Adaptive Gain (greyed out to match
the existing target-level row's pattern when Adaptive Gain is off), each
offering a small set of values to compare (attack: 0.2/0.5/1.0/2.0s;
release: 0.5/1.0/2.0/4.0/8.0s). `AdaptiveGainProcessor` now reads both live
from settings once per buffer rather than from fixed constants, same
"read live, don't cache" convention the rest of this package already
follows. Not yet tested against real recordings at non-default values -
that's the next thing to try once this reaches a device.
## AudioSource reverted to CAMCORDER: the earlier SNR comparison was blind to spectral shape (2026-09-05)

Reverted `PixelGramSettings.DEFAULT_VOICE_ENHANCEMENT` from `VOICE_ENHANCEMENT_OFF`
(`AudioSource.DEFAULT`/`MIC`) back to `VOICE_ENHANCEMENT_CAMCORDER`, undoing the
2026-08-30 switch ("Round video's default AudioSource switched to MIC/DEFAULT"
above).

**Why the earlier decision was wrong, not just outdated.** That switch was made
on a single broadband SNR number: MIC/DEFAULT measured ~5.6dB cleaner than
CAMCORDER. Broadband SNR is dominated by wherever most of a voice signal's
energy actually sits - the low-mid band, per this session's own frequency-
response measurements (70-78% of energy below 300Hz) - so a 5.6dB broadband
advantage is really a claim about *low-frequency* noise, and says nothing
about the treble end at all. Measured directly this time: CAMCORDER is **8.6dB
better than MIC/DEFAULT above 6kHz** - the exact band the iPhone comparison
(see "Second audio sample" above) found this app's output deficient in. Both
numbers are true simultaneously, and describe different things: MIC/DEFAULT
is the broadband-quieter source, CAMCORDER is the treble-cleaner one. The
2026-08-30 switch picked the wrong one because it only had the broadband
figure to look at.

**The general lesson, worth keeping**: a single broadband ratio (SNR, RMS,
any single dB number describing "how clean" or "how loud" a whole signal is)
can be an average over a spectrum that isn't uniform, and can therefore
completely hide a problem that's concentrated in one band while looking
fine in aggregate. This project made the same category of mistake once
already in a different measurement, and now twice with audio specifically.
Going forward, any audio-source or DSP-stage comparison worth making a
default decision on should include a frequency-response check (Welch-method
band split, as used throughout this session's audio work) alongside whatever
broadband number motivated looking at it in the first place - not as a
follow-up when something still sounds off, but as part of the original
comparison.

**Interaction with Adaptive Gain**: unaffected by this change beyond the
raw signal CAMCORDER hands it - CAMCORDER's extra broadband level (previously
documented as a "far-talk gain boost") means Adaptive Gain's leveler has less
distance to cover to reach its target from CAMCORDER's higher starting point
than it did from MIC/DEFAULT's quieter one, which should make convergence
even easier on top of the leveler-timing fix below, not a competing factor.

## Leveler defaults retuned for round video's actual clip length: 1.0s/4.0s -> 0.4s/1.2s (2026-09-05)

Per the "Leveler timing" report above (a 4.0s release constant left a
typical 8-15s clip only 86.5%-97.6% converged to target), changed
`DEFAULT_ADAPTIVE_GAIN_SLOW_ATTACK_SEC`/`DEFAULT_ADAPTIVE_GAIN_SLOW_RELEASE_SEC`
from 1.0s/4.0s to 0.4s/1.2s. At 1.2s release, convergence reaches 99.9% by
8s into a clip - essentially complete across the whole 8-15s range, closing
out the duration-tracking problem directly. The tradeoff: this sits closer
to typical speech-pause timescales than a more conservative choice would
(1.2x a ~1s sentence gap, 4x a ~0.3s word gap) - a smaller safety margin
against audibly following a long natural pause than, say, 2.5s release
would have carried (which would have given a 2.5x/8x margin at the cost of
only reaching ~96% convergence by 8s). Chosen anyway, prioritizing full
convergence within round video's actual clip lengths over maximum pumping
headroom. Both values remain user-adjustable via the settings added above;
not yet measured against a real recording at these new defaults - the next
on-device comparison (CAMCORDER + these constants vs. the iPhone) will show
whether the audio gap narrows as expected or whether pumping becomes
audible at this setting.
