# IR Lab: experimental infrared camera for Android

[![Build APK](https://github.com/mahajanparesh/irlab-android/actions/workflows/build.yml/badge.svg)](https://github.com/mahajanparesh/irlab-android/actions/workflows/build.yml)

A native Android camera app (Java, Camera2, OpenGL ES 2.0). It has no third-party dependencies. It inspects the phone's camera hardware, reports honestly whether genuine infrared capture is possible, and offers real-time IR-style processing, residual-NIR capture, RAW DNG output and an empirical IR leak test.

## What to expect on a Galaxy S25 Ultra

| Camera | Sensor | Colour filter | Genuine IR? |
|---|---|---|---|
| Main 200 MP | Samsung ISOCELL HP2 | RGB Bayer + IR-cut filter | Residual NIR only (720 nm filter + long exposure) |
| Ultra-wide 50 MP | Samsung ISOCELL JN3 | RGB Bayer + IR-cut filter | Residual NIR only |
| 3× tele 10 MP | Sony IMX754 | RGB Bayer + IR-cut filter | Residual NIR only |
| 5× tele 50 MP | Sony IMX854 | RGB Bayer + IR-cut filter | Residual NIR only |
| Front 12 MP | n/a | RGB Bayer + IR-cut filter | Residual NIR only (often leaks a little more) |

The S25 Ultra has **no NIR sensor, no monochrome sensor and no time-of-flight camera**. The laser-AF module is not exposed to apps. So:

* **Genuine IR is possible but weak.** Put a 720 nm IR-pass filter over the lens. Only near-IR then reaches the sensor, and that NIR leaked through the internal IR-cut filter. Expect exposures from 1/4 s to several seconds on a tripod. Use RAW DNG for the cleanest data.
* **Without that filter, IR MONO / FALSE COLOUR / CHANNEL SWAP are software visualisations of visible light.** The on-screen badge says so in red ("SIMULATED IR LOOK"), and every saved file records it.
* **For real-time IR video or night vision, use an external NIR camera** (see below).

The in-app **Hardware** screen confirms all of this from the live Camera2 data on your phone. It shows the colour-filter arrangement, capabilities, exposure range, RAW support and USB external-camera support. Use **Copy report** to save it.

## Build and install (≈5 minutes)

1. Install **Android Studio** (any 2024.2+ release).
2. *File → Open* → select this `IRLab` folder. Let Gradle sync. Android Studio downloads SDK 35 and Gradle 8.11.1 if they're missing.
3. On the phone: *Settings → About phone → Software information →* tap **Build number** 7×. Then *Developer options → USB debugging* on.
4. Connect the phone by USB, select it in the device dropdown, press **Run ▶**.

Command line alternative: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`, then `adb install -r app-debug.apk`.

## Features

* **Hardware probe** (first launch + *HARDWARE ⓘ*): per-camera colour-filter arrangement (RGGB / MONO / **NIR**), hardware level, manual sensor/colour/RAW/depth/monochrome capabilities, exposure and ISO ranges, physical sub-cameras, USB-host and external-camera support. It classifies each camera as *Genuine NIR sensor*, *External*, *Depth/ToF*, *Monochrome* or *RGB + IR-cut*. It refreshes live when a USB camera is attached.
* **IR remote test** (*IR TEST* mode): point a TV/AC remote at the lens and hold a button. The app detects the remote LED's ~940 nm flicker. It looks for local brightness jumps while the rest of the scene stays still, and saves a pass/fail per camera. This measures each module's actual NIR leakage.
* **Live GPU processing**: Natural, IR Mono (channel-weighted luminance), False colour (Ironbow / Rainbow / Night-green), Channel swap (Aerochrome-style), IR test overlay. Brightness, contrast, gamma and an unsharp-mask "enhance" control, all real time.
* **Sensor controls**: manual shutter and ISO (log sliders limited to what the camera allows apps), EV compensation, manual focus (IR focuses slightly behind visible light), **native colour balance** (AWB off, unity gains, identity matrix, so the ISP doesn't "correct" IR away).
* **Low-light mode**: lowest AE frame rate (longer auto exposures), high-quality noise reduction, and **8-frame stacking** on capture.
* **Capture**: processed JPEG at preview resolution (stacked, processed on the GPU), plus optional **RAW DNG** at full sensor RAW resolution. After each RAW, the app reports black-subtracted R/G/B channel means from the untouched sensor data.
* **Gallery** (`Pictures/IRLab`): tiles marked **RAW DNG** or **PROCESSED**. The viewer shows the embedded provenance record, with share/open/delete.

## Genuine vs. software, and how the app labels it

The badge under the camera selector always states what the pixels are:

| Badge | Meaning |
|---|---|
| 🟢 GENUINE NIR SENSOR DATA | Camera reports an NIR colour-filter array |
| 🟢 EXTERNAL CAMERA | USB camera (NIR only if it is a NoIR module) |
| 🟡 RESIDUAL NIR via IR-pass filter | You declared a filter is attached: real NIR, faint, ISP-processed |
| ⚪ VISIBLE LIGHT | Natural mode |
| 🔴 SIMULATED IR LOOK | IR-style rendering of visible light |

Each JPEG's EXIF `ImageDescription`/`UserComment` and each DNG's description record: provenance, camera ID, CFA, IR class, filter declaration, exposure/ISO, white-balance mode, frames stacked and the full GPU processing chain. File names end in `_SW` (software-processed) or `_RAW`.

## Shooting genuine IR with the S25 Ultra

1. A 720 nm IR-pass filter (clip-on or held flush over the main lens; block stray light around it).
2. Tripod. Daylight scenes with foliage work best (chlorophyll reflects NIR strongly).
3. *ADJUST* → **IR-pass filter attached** (switches to IR MONO, equal channel weights, native colour balance) → **Manual exposure**: longest shutter available, ISO 800-3200. Or use **Low-light mode** to stack 8 frames.
4. Keep **Also save RAW (DNG)** on. Develop the DNG in Lightroom/Snapseed, or use the JPEG directly.
5. Check the RAW channel means in the toast. Real NIR behind a 720 nm filter lifts all channels, with red the strongest.

## External camera options

* **Near-IR USB-C/OTG UVC cameras** (genuine NIR video, night vision): NoIR or day/night modules with 850/940 nm IR LEDs, e.g. Arducam day/night USB cameras or ELP NoIR modules. Android can expose these through Camera2 only if the phone maker enables the external-camera HAL. The Hardware screen shows "External cameras via Camera2: supported / not declared". If supported, the camera appears in IR Lab automatically. If not, use a dedicated UVC app. Adding a libuvc backend to IR Lab would be the next step.
* **Thermal cameras** (long-wave IR, 8-14 µm, *heat*, not near-IR): InfiRay P2 Pro, TOPDON TC001, FLIR ONE (USB-C). These use their own apps/SDKs. No phone image sensor can see this band.
* **Full-spectrum converted cameras**: removing the IR-cut filter from a sealed phone module isn't practical. A converted mirrorless camera is the serious option.

## Limitations

* Android does not report IR-cut filter presence or spectral response. The probe classifies from the colour-filter arrangement and capabilities. The IR remote test is the empirical check.
* Samsung exposes a subset of its cameras to third-party apps. Physical lenses inside the logical back camera are shown in the report but are switched by the OS.
* Processed JPEGs are at preview resolution (≈1440×1920) because they come from the GPU pipeline. Full resolution is in the DNG.
* Very long manual exposures depend on the maximum exposure Samsung allows apps (shown on the Hardware screen).

## Project layout

```
app/src/main/java/com/irlab/camera/
  MainActivity.java      camera UI, modes, controls, capture flow, provenance labels
  CameraController.java  Camera2 session, manual controls, RAW/DNG capture, RAW channel stats
  IrRenderer.java        OpenGL ES pipeline: live shader, stats, IR flicker detector, frame stacking
  CameraProbe.java       hardware capability probe and IR classification
  IrRemoteTest.java      empirical NIR leak test
  ImageSaver.java        MediaStore writer with EXIF/DNG provenance
  ProbeActivity.java     hardware report, guidance, external options
  GalleryActivity.java / ViewerActivity.java
```
