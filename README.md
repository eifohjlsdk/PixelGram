# PixelGram

PixelGram is a fork of Telegram for Android, built specifically for the
Pixel 11 Pro and Pixel 11 Pro XL, that fixes round video message ("circle
video") recording quality on those devices.

It is **not affiliated with, endorsed by, or supported by Telegram**. It's an
independent, unofficial modification of the open-source Telegram Android
client.

## Who made this and why

No CS degree here, this is vibecoded with AI assistance. Circle videos on my
Pixel looked worse than the same thing on an iPhone, so I measured why and
fixed it. It's a hobby project, not a maintained product, and support will be
whatever I can manage. Issues and pull requests are welcome at hobby pace.

Software is full of small broken things everyone just learns to live with. I
take one apart, work out what's actually wrong, and put the fix where anyone
can use it. If that's worth a coffee to you :) [Buy Me a Coffee](https://buymeacoffee.com/pixfurr) <img src="pixfurr-avatar.jpg" width="20" height="20" alt="pixfurr">

## What's different from upstream Telegram

Everything below was found and measured on this exact device (Pixel 11 Pro,
Tensor G6) against upstream Telegram for Android 12.10.0. The full
measurement log lives in [`FINDINGS.md`](FINDINGS.md); the summary here is
what actually shipped.

### The core bug: an invalid frame rate request halves recording quality

Upstream Telegram's round-video capture path unconditionally requests
`CONTROL_AE_TARGET_FPS_RANGE = (30, 60)` from the Camera2 API, with no check
against what the sensor actually supports. On this hardware, `(30, 60)` is
**not** one of the ranges reported in
`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` - so the camera HAL silently ignores
the request and the sensor free-runs at whatever rate it likes (measured:
~59fps on the front camera). The video encoder, meanwhile, is still budgeted
for a 30fps bitrate. The practical effect: roughly twice as many frames are
squeezed into the same bit budget, so every frame gets about half the bits it
was supposed to - a substantial, silent quality loss with no error, warning,
or visible symptom other than "the video looks worse than it should."

**The fix**: at camera-open time, read the sensor's actual
`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, and pick a real, supported range - a
fixed 30/30 range if the sensor offers one, otherwise the narrowest available
range that still contains 30fps.

**Measured effect**: capture rate locked to 30.01fps (from a free-running
~59fps), bits per frame roughly doubled (~34,800 vs. ~17,300), matching the
gap predicted by comparing against iOS's behavior (which explicitly locks its
frame rate for the same reason). As a side effect, audio/video drift on the
recorded file also dropped substantially (from 1.4-3.0s down to 0.43s).

### Other quality changes

- **Resolution and audio bitrate**: capture resolution raised to 448x448 and
  audio bitrate to 96kbps (from upstream's 384x384 / 64kbps).
- **Face-weighted AE metering**: when a face is detected during recording,
  `CONTROL_AE_REGIONS` is set to meter exposure on the face rather than the
  full frame, plus a small (+0.3 EV by default, adjustable) exposure
  compensation boost - applied only while a face is actually present, so it
  doesn't blow out highlights outdoors with nothing to meter on.
- **Edge mode and noise reduction**: `EDGE_MODE_FAST` and
  `NOISE_REDUCTION_MODE_FAST`, chosen after comparing against the
  alternatives this sensor supports (`OFF`/`FAST`/`HIGH_QUALITY` are all
  available; only `MINIMAL` isn't) - `FAST` removes visible
  edge-enhancement halos and skin grain with no measured downside. Both are
  user-adjustable.
- **Voice enhancement**: a configurable microphone `AudioSource` (default
  Voice Recognition - chosen after A/B listening tests showed Voice
  Communication running roughly 12dB below stock and effectively unusable),
  optional hardware noise suppression and automatic gain control, and a
  manual gain multiplier (1x-3x) applied to the raw audio before encoding.
  This applies to both round video and regular voice messages.

All of the above is adjustable from an in-app **PixelGram Camera** settings
screen (Settings → PixelGram Camera), not just hardcoded - every setting has
a sensible default but can be changed or reset.

## License

PixelGram is, like upstream Telegram for Android, licensed under the
**GNU General Public License v2.0**. See [`LICENSE`](LICENSE) for the full
text. Source code is available at:

**https://github.com/eifohjlsdk/PixelGram**

## Installing (sideloaded APK)

This isn't distributed through the Play Store, so installation is manual,
and it needs your own Telegram API credentials:

1. **Get an api_id and api_hash**: go to
   [my.telegram.org](https://my.telegram.org), log in with any Telegram
   account, and open "API development tools." Create an app (any name/
   description is fine) and you'll get an `api_id` and `api_hash`. This is
   required because Telegram has every application identify itself this
   way - PixelGram doesn't ship a shared credential like some forks do, so
   each install uses its own. It's free and takes under a minute.
2. Download the latest release APK from the
   [Releases page](https://github.com/eifohjlsdk/PixelGram/releases).
3. On your device, allow your browser or file manager to install unknown
   apps (Settings → Apps → Special app access → Install unknown apps).
   **Android Play Protect will likely warn you about installing an app from
   outside the Play Store** - this is expected for any sideloaded APK, not
   specific to PixelGram, and there's no way around it without Play Store
   distribution.
4. Open the downloaded APK and install it.
5. On first launch, PixelGram will ask for the `api_id`/`api_hash` from step
   1 before showing the normal login screen. Enter them once - they're
   stored locally on your device and you won't be asked again unless you
   clear app data. You can view or change them later from Settings →
   PixelGram Camera → API Credentials.

PixelGram uses a distinct application ID from the Play Store Telegram app, so
it installs and runs side by side with it - it will not replace or conflict
with your existing Telegram install.

The app includes a built-in update checker (Settings → PixelGram Camera →
Updates) that periodically checks this repository's releases and can also be
triggered manually with "Check Now."

## Support

Scan to buy me a coffee:

![Buy Me a Coffee QR code](bmc_qr.png)

More from me: **https://github.com/eifohjlsdk**
