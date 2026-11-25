package com.irlab.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Owns the Camera2 device/session. All camera work runs on a private background thread. */
final class CameraController {

    interface Listener {
        void onCameraOpened(CameraProbe.Report report, Size previewSize, boolean rawReady);
        void onCameraError(String message);
        void onExposureInfo(long exposureNs, int iso, float focusDiopters);
        void onRawSaved(Uri uri, String rawSummary);
    }

    /** Sensor-side settings. Immutable once handed to the controller (always pass a copy). */
    static final class Settings {
        boolean manualExposure;
        long exposureNs = 33_333_333L;
        int iso = 400;
        int evIndex;
        boolean manualFocus;
        float focusDiopters;
        boolean awbOff;
        boolean lowLight;
        boolean saveRaw = true;
        /** Freeze auto-exposure and auto-white-balance (used by the IR remote test). */
        boolean lock3A;

        Settings copy() {
            Settings s = new Settings();
            s.manualExposure = manualExposure;
            s.exposureNs = exposureNs;
            s.iso = iso;
            s.evIndex = evIndex;
            s.manualFocus = manualFocus;
            s.focusDiopters = focusDiopters;
            s.awbOff = awbOff;
            s.lowLight = lowLight;
            s.saveRaw = saveRaw;
            s.lock3A = lock3A;
            return s;
        }
    }

    private final Context ctx;
    private final CameraManager mgr;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;

    private CameraDevice device;
    private CameraCaptureSession session;
    private CameraCharacteristics chars;
    private CameraProbe.Report report;
    private Surface previewSurface;
    private ImageReader rawReader;
    private Size previewSize;
    private Settings settings = new Settings();
    private long lastHudMs;
    private volatile int openGeneration;

    // pending RAW capture
    private TotalCaptureResult pendingResult;
    private Image pendingImage;
    private String pendingDescription;
    private int pendingOrientation;

    CameraController(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("IrLabCamera");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    void shutdown() {
        if (bg != null) bg.post(this::closeInternal);
        if (thread != null) thread.quitSafely();
        thread = null;
        bg = null;
    }

    void open(final String cameraId, final SurfaceTexture st, final Settings s) {
        if (bg == null) return;
        final int gen = ++openGeneration;
        bg.post(() -> openInternal(cameraId, st, s, gen));
    }

    void close() {
        if (bg == null) return;
        openGeneration++;
        bg.post(this::closeInternal);
    }

    void updateSettings(final Settings s) {
        if (bg == null) return;
        bg.post(() -> {
            settings = s;
            restartRepeating();
        });
    }

    /** Fires a single RAW_SENSOR capture and writes it as a DNG. */
    void captureRaw(final String description, final int exifOrientation) {
        if (bg == null) return;
        bg.post(() -> captureRawInternal(description, exifOrientation));
    }

    // ------------------------------------------------------------------ internals (bg thread)

    @SuppressLint("MissingPermission")
    private void openInternal(String id, SurfaceTexture st, Settings s, int gen) {
        closeInternal();
        if (gen != openGeneration) return;
        settings = s;
        try {
            chars = mgr.getCameraCharacteristics(id);
            report = CameraProbe.build(id, null, chars);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("No stream configuration map");
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(st);
            if (report.raw && report.largestRaw != null) {
                rawReader = ImageReader.newInstance(report.largestRaw.getWidth(), report.largestRaw.getHeight(),
                        ImageFormat.RAW_SENSOR, 2);
                rawReader.setOnImageAvailableListener(reader -> {
                    Image img = reader.acquireNextImage();
                    if (img == null) return;
                    if (pendingImage != null) pendingImage.close();
                    pendingImage = img;
                    tryFinishRaw();
                }, bg);
            }
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    if (gen != openGeneration) { cam.close(); return; }
                    device = cam;
                    createSession(true);
                }
                @Override public void onDisconnected(CameraDevice cam) {
                    cam.close();
                    if (device == cam) device = null;
                    // Ignore disconnects of a camera we have already moved away from (pause, switch).
                    if (gen == openGeneration) postError("Camera disconnected (another app may have taken it).");
                }
                @Override public void onError(CameraDevice cam, int error) {
                    cam.close();
                    if (device == cam) device = null;
                    if (gen == openGeneration) postError("Camera error " + error);
                }
            }, bg);
        } catch (Exception e) {
            postError("Could not open camera " + id + ": " + e.getMessage());
        }
    }

    private void createSession(final boolean withRaw) {
        if (device == null) return;
        List<OutputConfiguration> outs = new ArrayList<>();
        outs.add(new OutputConfiguration(previewSurface));
        final boolean useRaw = withRaw && rawReader != null;
        if (useRaw) outs.add(new OutputConfiguration(rawReader.getSurface()));
        Executor exec = r -> bg.post(r);
        SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, exec,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        if (!useRaw && rawReader != null) {
                            rawReader.close();
                            rawReader = null;
                        }
                        restartRepeating();
                        final CameraProbe.Report r = report;
                        final Size ps = previewSize;
                        final boolean rawOk = rawReader != null;
                        main.post(() -> listener.onCameraOpened(r, ps, rawOk));
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        if (useRaw) {
                            createSession(false); // retry without the RAW stream
                        } else {
                            postError("Camera session configuration failed");
                        }
                    }
                });
        try {
            device.createCaptureSession(cfg);
        } catch (CameraAccessException e) {
            postError("Session error: " + e.getMessage());
        }
    }

    private void restartRepeating() {
        if (session == null || device == null) return;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            applySettings(b, settings);
            session.setRepeatingRequest(b.build(), hudCallback, bg);
        } catch (Exception e) {
            postError("Preview request failed: " + e.getMessage());
        }
    }

    private final CameraCaptureSession.CaptureCallback hudCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
            long now = System.currentTimeMillis();
            if (now - lastHudMs < 200) return;
            lastHudMs = now;
            Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            final long e = exp == null ? 0 : exp;
            final int i = iso == null ? 0 : iso;
            final float f = fd == null ? -1f : fd;
            main.post(() -> listener.onExposureInfo(e, i, f));
        }
    };

    private void applySettings(CaptureRequest.Builder b, Settings s) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        if (s.manualExposure && report.manualSensor && report.exposureRange != null && report.isoRange != null) {
            long exp = clamp(s.exposureNs, report.exposureRange.getLower(), report.exposureRange.getUpper());
            int iso = (int) clamp(s.iso, report.isoRange.getLower(), report.isoRange.getUpper());
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            long frame = Math.max(exp, 33_333_333L);
            Long maxFrame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
            if (maxFrame != null && frame > maxFrame) frame = Math.max(maxFrame, exp);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            Range<Integer> evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (evRange != null) {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        (int) clamp(s.evIndex, evRange.getLower(), evRange.getUpper()));
            }
            if (s.lowLight) {
                Range<Integer> fps = lowestFpsRange();
                if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            }
            b.set(CaptureRequest.CONTROL_AE_LOCK, s.lock3A);
        }
        b.set(CaptureRequest.CONTROL_AWB_LOCK, s.lock3A);

        if (s.lowLight && supports(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        }

        if (s.manualFocus && report.manualSensor && report.minFocusDistance > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0f, Math.min(s.focusDiopters, report.minFocusDistance)));
        } else if (supports(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }

        if (s.awbOff && report.manualPost) {
            // Unity gains + identity matrix: the sensor's native channel balance, which is what you
            // want when judging IR (auto white balance "corrects" the IR away).
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(1f, 1f, 1f, 1f));
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform(new int[]{
                    1, 1, 0, 1, 0, 1,
                    0, 1, 1, 1, 0, 1,
                    0, 1, 0, 1, 1, 1}));
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    private void captureRawInternal(String description, int orientation) {
        if (session == null || device == null || rawReader == null) {
            postError("RAW capture is not available on this camera");
            return;
        }
        pendingResult = null;
        if (pendingImage != null) { pendingImage.close(); pendingImage = null; }
        pendingDescription = description;
        pendingOrientation = orientation;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(rawReader.getSurface());
            b.addTarget(previewSurface);
            applySettings(b, settings);
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                                         TotalCaptureResult result) {
                    pendingResult = result;
                    tryFinishRaw();
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                                                      android.hardware.camera2.CaptureFailure f) {
                    postError("RAW capture failed (reason " + f.getReason() + ")");
                }
            }, bg);
        } catch (Exception e) {
            postError("RAW capture error: " + e.getMessage());
        }
    }

    private void tryFinishRaw() {
        if (pendingResult == null || pendingImage == null || pendingDescription == null) return;
        Image img = pendingImage;
        TotalCaptureResult res = pendingResult;
        String desc = pendingDescription;
        pendingImage = null;
        pendingResult = null;
        pendingDescription = null;
        try {
            String stats = rawChannelStats(img, res);
            DngCreator dng = new DngCreator(chars, res);
            dng.setDescription(desc + " | " + stats);
            dng.setOrientation(pendingOrientation);
            String name = ImageSaver.newName("RAW", "dng");
            final Uri uri = ImageSaver.saveDng(ctx, dng, img, name, desc + " | " + stats);
            dng.close();
            final String summary = stats;
            main.post(() -> listener.onRawSaved(uri, summary));
        } catch (Exception e) {
            postError("Saving DNG failed: " + e.getMessage());
        } finally {
            img.close();
        }
    }

    /**
     * Black-subtracted mean of each colour-filter channel over the centre of the RAW frame.
     * This is untouched sensor data. With an IR-pass filter attached, these numbers show how much
     * NIR actually reached each channel.
     */
    private String rawChannelStats(Image img, CaptureResult res) {
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
            int rowStride = p.getRowStride();
            int pixStride = p.getPixelStride();
            int w = img.getWidth(), h = img.getHeight();
            Integer white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            float whiteLevel = white == null ? 1023f : white;
            float[] black = new float[4];
            float[] dyn = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            BlackLevelPattern pat = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            for (int i = 0; i < 4; i++) {
                if (dyn != null && dyn.length == 4) black[i] = dyn[i];
                else if (pat != null) black[i] = pat.getOffsetForIndex(i % 2, i / 2);
            }
            // channel index for each 2x2 position: 0=R 1=G 2=B
            int[] layout;
            switch (report.cfa) {
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: layout = new int[]{1, 0, 2, 1}; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: layout = new int[]{1, 2, 0, 1}; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: layout = new int[]{2, 1, 1, 0}; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: layout = new int[]{0, 1, 1, 2}; break;
                default: layout = null; // mono / NIR / unknown
            }
            double[] sum = new double[3];
            long[] cnt = new long[3];
            int x0 = (w / 4) & ~1, x1 = (3 * w / 4) & ~1, y0 = (h / 4) & ~1, y1 = (3 * h / 4) & ~1;
            for (int y = y0; y < y1; y += 8) {
                for (int dy = 0; dy < 2; dy++) {
                    int row = (y + dy) * rowStride;
                    for (int x = x0; x < x1; x += 8) {
                        for (int dx = 0; dx < 2; dx++) {
                            int idx = dy * 2 + dx;
                            int v = buf.getShort(row + (x + dx) * pixStride) & 0xFFFF;
                            float n = (v - black[idx]) / (whiteLevel - black[idx]);
                            int ch = layout == null ? 1 : layout[idx];
                            sum[ch] += n;
                            cnt[ch]++;
                        }
                    }
                }
            }
            if (layout == null) {
                return String.format(Locale.US, "RAW centre mean %.2f%% of full scale (single channel)",
                        100 * sum[1] / Math.max(1, cnt[1]));
            }
            double r = sum[0] / Math.max(1, cnt[0]), g = sum[1] / Math.max(1, cnt[1]), b = sum[2] / Math.max(1, cnt[2]);
            return String.format(Locale.US,
                    "RAW centre means (black-subtracted, %% of full scale) R %.2f G %.2f B %.2f; R/G %.2f B/G %.2f",
                    100 * r, 100 * g, 100 * b, g > 1e-6 ? r / g : 0, g > 1e-6 ? b / g : 0);
        } catch (Exception e) {
            return "RAW stats unavailable";
        }
    }

    private void closeInternal() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {}
        session = null;
        if (device != null) device.close();
        device = null;
        if (rawReader != null) rawReader.close();
        rawReader = null;
        if (pendingImage != null) pendingImage.close();
        pendingImage = null;
        pendingResult = null;
        pendingDescription = null;
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
    }

    // ------------------------------------------------------------------ helpers

    private static Size choosePreviewSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            int w = s.getWidth(), h = s.getHeight();
            if (w * 3 == h * 4 && w <= 1920 && h <= 1440) {
                if (best == null || w * h > best.getWidth() * best.getHeight()) best = s;
            }
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1440) {
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private Range<Integer> lowestFpsRange() {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (best == null || r.getLower() < best.getLower()
                    || (r.getLower().equals(best.getLower()) && r.getUpper() > best.getUpper())) best = r;
        }
        return best;
    }

    private boolean supports(CameraCharacteristics.Key<int[]> key, int value) {
        int[] modes = chars.get(key);
        if (modes == null) return false;
        for (int m : modes) if (m == value) return true;
        return false;
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void postError(final String msg) {
        main.post(() -> listener.onCameraError(msg));
    }
}
