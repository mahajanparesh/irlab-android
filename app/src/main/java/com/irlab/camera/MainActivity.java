package com.irlab.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IrRenderer.Host, CameraController.Listener {
    private static final int REQ_CAMERA = 10;
    private static final int LOW_LIGHT_FRAMES = 8;
    static final String PREFS = "irlab";

    private GLSurfaceView glView;
    private IrRenderer renderer;
    private CameraController camera;
    private SurfaceTexture surfaceTexture;
    private boolean resumed;

    private List<CameraProbe.Report> cameras = new ArrayList<>();
    private int camIndex;
    private CameraProbe.Report current;
    private boolean rawReady;

    private final ProcessingParams params = new ProcessingParams();
    private final CameraController.Settings settings = new CameraController.Settings();
    private boolean irFilterOn;
    private final IrRemoteTest irTest = new IrRemoteTest();

    private long lastExposureNs;
    private int lastIso;
    private float lastFocus = -1f;
    private float lastFps;
    private int deviceDegrees;
    private OrientationEventListener orientationListener;

    private boolean capturing;
    private String pendingCaptureDescription;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // views
    private FrameLayout root;
    private LinearLayout topBar, bottomBar, adjustPanel, paletteRow;
    private ScrollView adjustScroll;
    private HorizontalScrollView paletteScroll;
    private TextView camButton, badge, hud, status, adjustButton;
    private HistogramView histogram;
    private ShutterButton shutter;
    private ImageView galleryThumb;
    private final List<TextView> modeChips = new ArrayList<>();
    private final List<TextView> paletteChips = new ArrayList<>();

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        buildUi();
        camera = new CameraController(this, this);
        camera.start();
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceDegrees = ((o + 45) / 90 * 90) % 360;
            }
        };
        if (hasCameraPermission()) {
            initCameras();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        loadLatestThumb();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glView.onResume();
        orientationListener.enable();
        maybeOpenCamera();
    }

    @Override
    protected void onPause() {
        resumed = false;
        irTest.cancel();
        renderer.setFastStats(false);
        if (pendingTestStart != null) { uiHandler.removeCallbacks(pendingTestStart); pendingTestStart = null; }
        preTestSettings = null; // the user's settings object itself was never changed
        camera.close();
        glView.onPause();
        surfaceTexture = null; // destroyed with the GL context; a new one arrives on resume
        orientationListener.disable();
        capturing = false;
        shutter.setProgress(-1);
        status.setVisibility(View.GONE);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        camera.shutdown();
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAMERA) return;
        if (hasCameraPermission()) {
            initCameras();
            maybeOpenCamera();
        } else {
            showStatus("Camera permission is required. Tap here to grant it.", true);
            status.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA));
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void initCameras() {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameras = CameraProbe.openable(CameraProbe.probeAll(mgr));
        camIndex = 0;
        for (int i = 0; i < cameras.size(); i++) {
            if (cameras.get(i).facingLabel.equals("Back")) { camIndex = i; break; }
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("probe_seen", false)) {
            prefs.edit().putBoolean("probe_seen", true).apply();
            startActivity(new Intent(this, ProbeActivity.class).putExtra(ProbeActivity.EXTRA_FIRST_RUN, true));
        }
        if (cameras.isEmpty()) showStatus("No cameras are available to apps on this device.", true);
    }

    private void maybeOpenCamera() {
        if (!resumed || surfaceTexture == null || !hasCameraPermission() || cameras.isEmpty()) return;
        camera.open(cameras.get(camIndex).id, surfaceTexture, settings.copy());
    }

    // ------------------------------------------------------------------ renderer callbacks

    @Override
    public void onSurfaceTextureReady(SurfaceTexture st) {
        surfaceTexture = st;
        maybeOpenCamera();
    }

    @Override
    public void onFrameStats(IrRenderer.FrameStats s) {
        histogram.setData(s.histogram);
        lastFps = s.fps;
        updateHud();
        if (irTest.isRunning()) {
            boolean finished = irTest.feed(s);
            if (finished) {
                finishIrTest();
            } else {
                showStatus(String.format(Locale.US, "IR REMOTE TEST · %ds\nAim a TV/AC remote at the lens from ~20 cm "
                        + "and hold a button. Keep the phone still.", (irTest.remainingMs() + 999) / 1000), false);
            }
        }
    }

    @Override
    public void onCaptureProgress(int done, int total) {
        shutter.setProgress(done / (float) total);
        if (total > 1) showStatus("Stacking frame " + done + " / " + total + " · hold still", false);
    }

    @Override
    public void onCaptureReady(final Bitmap bitmap, final ProcessingParams used, final int frames) {
        final String desc = pendingCaptureDescription + " Frames stacked: " + frames + ".";
        final int exifOrientation = ImageSaver.exifOrientationFor(deviceDegrees);
        final String name = ImageSaver.newName(used.modeTag() + "_SW", "jpg");
        status.setVisibility(View.GONE);
        io.execute(() -> {
            try {
                ImageSaver.saveJpeg(getApplicationContext(), bitmap, name, desc, exifOrientation);
                final Bitmap thumb = Bitmap.createScaledBitmap(bitmap, 160, Math.max(1, 160 * bitmap.getHeight() / bitmap.getWidth()), true);
                bitmap.recycle();
                runOnUiThread(() -> {
                    galleryThumb.setImageBitmap(thumb);
                    Toast.makeText(this, "Saved processed JPEG (" + used.modeTag() + ")", Toast.LENGTH_SHORT).show();
                    endCapture();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    endCapture();
                });
            }
        });
    }

    private void endCapture() {
        capturing = false;
        shutter.setProgress(-1);
    }

    // ------------------------------------------------------------------ camera callbacks

    @Override
    public void onCameraOpened(CameraProbe.Report report, Size previewSize, boolean rawReady) {
        current = report;
        this.rawReady = rawReady;
        renderer.setBufferGeometry(previewSize.getWidth(), previewSize.getHeight(), report.sensorOrientation);
        camButton.setText(report.shortLabel() + "  ⟳");
        status.setVisibility(View.GONE);
        buildAdjustPanel();
        updateBadge();
    }

    @Override
    public void onCameraError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (capturing) endCapture();
    }

    @Override
    public void onExposureInfo(long exposureNs, int iso, float focusDiopters) {
        lastExposureNs = exposureNs;
        lastIso = iso;
        lastFocus = focusDiopters;
        updateHud();
    }

    @Override
    public void onRawSaved(Uri uri, String rawSummary) {
        Toast.makeText(this, "RAW DNG saved (unprocessed sensor data)\n" + rawSummary, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ actions

    private void onShutter() {
        if (capturing || current == null || surfaceTexture == null) return;
        capturing = true;
        ProcessingParams snap = params.copy();
        int frames = settings.lowLight ? LOW_LIGHT_FRAMES : 1;
        pendingCaptureDescription = describeCapture(snap, false);
        shutter.setProgress(0f);
        renderer.requestCapture(frames, snap);
        if (settings.saveRaw && rawReady) {
            int sensorDeg = current.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                    ? (current.sensorOrientation - deviceDegrees + 360) % 360
                    : (current.sensorOrientation + deviceDegrees) % 360;
            camera.captureRaw(describeCapture(snap, true), ImageSaver.exifOrientationFor(sensorDeg));
        }
    }

    private void switchCamera() {
        if (cameras.size() < 2 || capturing) return;
        camIndex = (camIndex + 1) % cameras.size();
        irTest.cancel();
        renderer.setFastStats(false);
        if (pendingTestStart != null) { uiHandler.removeCallbacks(pendingTestStart); pendingTestStart = null; }
        preTestSettings = null;
        settings.manualFocus = false;
        maybeOpenCamera();
    }

    private void setMode(int mode) {
        params.mode = mode;
        for (int i = 0; i < modeChips.size(); i++) Ui.setChipSelected(modeChips.get(i), i == mode);
        paletteScroll.setVisibility(mode == ProcessingParams.MODE_FALSE_COLOUR ? View.VISIBLE : View.GONE);
        if (mode == ProcessingParams.MODE_IR_TEST) {
            startIrTest();
        } else if (irTest.isRunning() || pendingTestStart != null || preTestSettings != null) {
            irTest.cancel();
            renderer.setFastStats(false);
            restoreAfterIrTest();
            status.setVisibility(View.GONE);
        }
        pushParams();
    }

    private void setPalette(int palette) {
        params.palette = palette;
        for (int i = 0; i < paletteChips.size(); i++) Ui.setChipSelected(paletteChips.get(i), i == palette);
        pushParams();
    }

    /** Settings in force before IR TEST darkened the exposure; null when no test is running. */
    private CameraController.Settings preTestSettings;

    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingTestStart;

    private void startIrTest() {
        if (current == null) return;
        irTest.cancel();
        if (pendingTestStart != null) uiHandler.removeCallbacks(pendingTestStart);
        // 1) Under-expose by ~2 stops so ordinary bright areas don't clip; a remote's LED still will.
        if (preTestSettings == null) preTestSettings = settings.copy();
        final CameraController.Settings dark = preTestSettings.copy();
        dark.manualExposure = false;
        dark.lock3A = false;
        int evLo = current.evRange == null ? 0 : current.evRange.getLower();
        dark.evIndex = Math.max(evLo, Math.round(-2f / current.evStep));
        camera.updateSettings(dark);
        showStatus("IR REMOTE TEST · settling exposure…", false);
        // 2) Once auto-exposure has settled, freeze it (and white balance) so the exposure can't hunt
        //    during the test: hunting makes the edges of bright areas shimmer like a blinking LED.
        pendingTestStart = () -> {
            pendingTestStart = null;
            if (preTestSettings == null || params.mode != ProcessingParams.MODE_IR_TEST) return;
            CameraController.Settings locked = dark.copy();
            locked.lock3A = true;
            camera.updateSettings(locked);
            irTest.start();
            renderer.setFastStats(true);
        };
        uiHandler.postDelayed(pendingTestStart, 1200);
    }

    private void restoreAfterIrTest() {
        if (pendingTestStart != null) {
            uiHandler.removeCallbacks(pendingTestStart);
            pendingTestStart = null;
        }
        if (preTestSettings != null) {
            preTestSettings = null;
            pushSettings();
        }
    }

    private void finishIrTest() {
        renderer.setFastStats(false);
        restoreAfterIrTest();
        status.setVisibility(View.GONE);
        String summary = irTest.summary();
        if (irTest.valid() && current != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("irtest_" + current.id, (irTest.detected() ? "DETECTED" : "NOT DETECTED")
                            + " · " + irTest.events() + " events · "
                            + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()))
                    .apply();
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("IR test · camera " + (current == null ? "?" : current.id))
                .setMessage(summary + "\n\nDuring the test the exposure is lowered ~2 stops and locked; magenta marks clipped pixels. A real IR LED shows as a "
                        + "pulsing white/violet dot.")
                .setPositiveButton("Run again", (d, w) -> startIrTest())
                .setNegativeButton("Done", (d, w) -> setMode(ProcessingParams.MODE_IR_MONO))
                .show();
    }

    private void setIrFilter(boolean on) {
        irFilterOn = on;
        if (on) {
            // Behind a 720 nm filter every Bayer channel carries NIR, so average them for less noise.
            params.irWeights = new float[]{0.34f, 0.33f, 0.33f};
            if (current != null && current.manualPost) settings.awbOff = true;
            if (params.mode == ProcessingParams.MODE_NATURAL) setMode(ProcessingParams.MODE_IR_MONO);
            Toast.makeText(this, "Filter mode: use a tripod, Manual exposure ≥ 1/4 s, ISO 800+, or Low-light stacking.",
                    Toast.LENGTH_LONG).show();
        } else {
            params.irWeights = new float[]{0.60f, 0.30f, 0.10f};
        }
        pushParams();
        pushSettings();
        buildAdjustPanel();
    }

    private void pushParams() {
        renderer.setParams(params.copy());
        updateBadge();
    }

    private void pushSettings() {
        camera.updateSettings(settings.copy());
    }

    // ------------------------------------------------------------------ provenance

    /** What the pixels on screen actually are. ASCII only (it is also written into EXIF). */
    private String provenance(ProcessingParams p) {
        if (current == null) return "";
        if (current.irClass == CameraProbe.IrClass.NIR_SENSOR) return "GENUINE NIR SENSOR DATA";
        if (current.irClass == CameraProbe.IrClass.EXTERNAL) return "EXTERNAL CAMERA - NIR only if NoIR module";
        if (irFilterOn) return "RESIDUAL NIR via IR-pass filter - weak, ISP-processed";
        switch (p.mode) {
            case ProcessingParams.MODE_NATURAL: return "VISIBLE LIGHT";
            case ProcessingParams.MODE_IR_TEST: return "IR LEAK TEST - visible + residual NIR";
            default: return "SIMULATED IR LOOK - software on visible light";
        }
    }

    private int provenanceColour() {
        if (current == null) return Ui.NEUTRAL;
        if (current.genuineIr()) return Ui.GOOD;
        if (irFilterOn) return Ui.WARN;
        if (params.mode == ProcessingParams.MODE_NATURAL) return Ui.NEUTRAL;
        if (params.mode == ProcessingParams.MODE_IR_TEST) return Ui.ACCENT;
        return Ui.BAD;
    }

    private void updateBadge() {
        badge.setText("● " + provenance(params));
        int c = provenanceColour();
        badge.setTextColor(c);
        badge.setBackground(Ui.round(0xB3000000, Ui.dp(this, 10), c, Ui.dp(this, 1)));
    }

    private String describeCapture(ProcessingParams p, boolean raw) {
        StringBuilder b = new StringBuilder();
        if (raw) b.append("Provenance: UNPROCESSED SENSOR DATA (RAW, no software IR processing). ");
        else b.append("Provenance: ").append(provenance(p)).append(". ");
        b.append("Camera ").append(current.id).append(' ').append(current.facingLabel)
                .append(", CFA ").append(current.cfaLabel).append(", class: ").append(current.irClassLabel()).append(". ");
        b.append("IR-pass filter attached (user-declared): ").append(irFilterOn ? "yes" : "no").append(". ");
        b.append("Sensor: ").append(lastExposureNs > 0 ? CameraProbe.formatExposure(lastExposureNs) : "?")
                .append(" ISO ").append(lastIso)
                .append(settings.manualExposure ? " (manual)" : " (auto)")
                .append(settings.awbOff ? ", native colour balance" : ", auto WB")
                .append(settings.lowLight ? ", low-light" : "").append(". ");
        if (!raw) b.append("GPU processing: ").append(p.describe()).append(". Source: ISP-processed stream, not RAW.");
        return ImageSaver.ascii(b.toString());
    }

    private void updateHud() {
        StringBuilder b = new StringBuilder();
        if (lastExposureNs > 0) b.append(CameraProbe.formatExposure(lastExposureNs));
        if (lastIso > 0) b.append("  ISO ").append(lastIso);
        b.append(String.format(Locale.US, "  %.0f fps", lastFps));
        if (lastFocus >= 0) b.append(lastFocus < 0.01f ? "  focus ∞" : String.format(Locale.US, "  focus %.2fm", 1f / lastFocus));
        hud.setText(b.toString());
    }

    private void showStatus(String s, boolean clickable) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
        status.setClickable(clickable);
    }

    private void loadLatestThumb() {
        io.execute(() -> {
            Uri u = ImageSaver.latest(this);
            if (u == null) return;
            try {
                final Bitmap b = getContentResolver().loadThumbnail(u, new Size(160, 160), null);
                runOnUiThread(() -> galleryThumb.setImageBitmap(b));
            } catch (Exception ignored) {
            }
        });
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new IrRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- top bar
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 12);
        topBar.setPadding(pad, pad, pad, pad);

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        camButton = Ui.chip(this, "CAMERA");
        camButton.setTextColor(Ui.TEXT);
        camButton.setBackground(Ui.round(0xB3000000, Ui.dp(this, 18), Ui.STROKE, Ui.dp(this, 1)));
        camButton.setOnClickListener(v -> switchCamera());
        row1.addView(camButton, Ui.wrap());
        row1.addView(Ui.spacer(this, 1));
        TextView gallery = Ui.chip(this, "GALLERY");
        gallery.setTextColor(Ui.TEXT);
        gallery.setBackground(Ui.round(0xB3000000, Ui.dp(this, 18), Ui.STROKE, Ui.dp(this, 1)));
        gallery.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        row1.addView(gallery, Ui.wrap());
        TextView info = Ui.chip(this, "HARDWARE ⓘ");
        info.setTextColor(Ui.TEXT);
        info.setBackground(Ui.round(0xB3000000, Ui.dp(this, 18), Ui.STROKE, Ui.dp(this, 1)));
        info.setOnClickListener(v -> startActivity(new Intent(this, ProbeActivity.class)));
        LinearLayout.LayoutParams ilp = Ui.wrap();
        ilp.leftMargin = Ui.dp(this, 8);
        row1.addView(info, ilp);
        topBar.addView(row1, Ui.matchWidth());

        badge = Ui.text(this, "● …", 12, Ui.NEUTRAL, true);
        badge.setLetterSpacing(0.04f);
        badge.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        LinearLayout.LayoutParams blp = Ui.wrap();
        blp.topMargin = Ui.dp(this, 10);
        topBar.addView(badge, blp);

        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        hud = Ui.text(this, "", 12, Ui.TEXT2, false);
        hud.setTypeface(android.graphics.Typeface.MONOSPACE);
        row3.addView(hud, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        histogram = new HistogramView(this);
        row3.addView(histogram, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 44)));
        LinearLayout.LayoutParams r3 = Ui.matchWidth();
        r3.topMargin = Ui.dp(this, 8);
        topBar.addView(row3, r3);

        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // ---- centre status
        status = Ui.text(this, "", 14, Ui.TEXT, true);
        status.setGravity(Gravity.CENTER);
        int sp = Ui.dp(this, 16);
        status.setPadding(sp, sp, sp, sp);
        status.setBackground(Ui.round(0xD9000000, Ui.dp(this, 14), Ui.STROKE, Ui.dp(this, 1)));
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        slp.leftMargin = slp.rightMargin = Ui.dp(this, 32);
        root.addView(status, slp);

        // ---- bottom bar
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF20A0C0F, 0xB30A0C0F, 0x000A0C0F});
        bottomBar.setBackground(fade);
        bottomBar.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 12));

        adjustScroll = new ScrollView(this);
        adjustPanel = new LinearLayout(this);
        adjustPanel.setOrientation(LinearLayout.VERTICAL);
        adjustPanel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        adjustScroll.addView(adjustPanel);
        adjustScroll.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 18), Ui.STROKE, Ui.dp(this, 1)));
        adjustScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 300));
        alp.leftMargin = alp.rightMargin = Ui.dp(this, 10);
        alp.bottomMargin = Ui.dp(this, 10);
        bottomBar.addView(adjustScroll, alp);

        paletteScroll = new HorizontalScrollView(this);
        paletteScroll.setHorizontalScrollBarEnabled(false);
        paletteRow = new LinearLayout(this);
        paletteRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        String[] palettes = {"IRONBOW", "RAINBOW", "NIGHT GREEN"};
        for (int i = 0; i < palettes.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, palettes[i]);
            c.setOnClickListener(v -> setPalette(idx));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = Ui.dp(this, 8);
            paletteRow.addView(c, lp);
            paletteChips.add(c);
        }
        paletteScroll.addView(paletteRow);
        paletteScroll.setVisibility(View.GONE);
        bottomBar.addView(paletteScroll, Ui.matchWidth());

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        String[] modes = {"NATURAL", "IR MONO", "FALSE COLOUR", "CHANNEL SWAP", "IR TEST"};
        for (int i = 0; i < modes.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, modes[i]);
            c.setOnClickListener(v -> setMode(idx));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = Ui.dp(this, 8);
            modeRow.addView(c, lp);
            modeChips.add(c);
        }
        modeScroll.addView(modeRow);
        bottomBar.addView(modeScroll, Ui.matchWidth());

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), 0);
        galleryThumb = new ImageView(this);
        galleryThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        galleryThumb.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 12), Ui.STROKE, Ui.dp(this, 1)));
        galleryThumb.setClipToOutline(true);
        galleryThumb.setContentDescription("Gallery");
        galleryThumb.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        controls.addView(galleryThumb, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        controls.addView(Ui.spacer(this, 1));
        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> onShutter());
        controls.addView(shutter, new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 78)));
        controls.addView(Ui.spacer(this, 1));
        adjustButton = Ui.chip(this, "ADJUST");
        adjustButton.setOnClickListener(v -> {
            boolean show = adjustScroll.getVisibility() != View.VISIBLE;
            adjustScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            Ui.setChipSelected(adjustButton, show);
        });
        controls.addView(adjustButton, new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 40)));
        bottomBar.addView(controls, Ui.matchWidth());

        root.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topBar.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad);
            bottomBar.setPadding(bars.left, Ui.dp(this, 24), bars.right, Ui.dp(this, 12) + bars.bottom);
            return insets;
        });

        setContentView(root);
        setMode(ProcessingParams.MODE_NATURAL);
        setPalette(ProcessingParams.PALETTE_IRONBOW);
    }

    // ---- adjust panel

    private interface Mapper { float value(float t); float t(float v); }
    private interface Sink { void accept(float v); }
    private interface Fmt { String format(float v); }
    private interface BoolSink { void accept(boolean v); }

    private static Mapper linear(final float lo, final float hi) {
        return new Mapper() {
            public float value(float t) { return lo + t * (hi - lo); }
            public float t(float v) { return (v - lo) / (hi - lo); }
        };
    }

    private static Mapper logScale(final double lo, final double hi) {
        return new Mapper() {
            public float value(float t) { return (float) (lo * Math.pow(hi / lo, t)); }
            public float t(float v) { return (float) (Math.log(v / lo) / Math.log(hi / lo)); }
        };
    }

    private void buildAdjustPanel() {
        adjustPanel.removeAllViews();
        section("IMAGE PROCESSING · software");
        slider("Brightness", params.brightness, linear(-0.5f, 0.5f), v -> String.format(Locale.US, "%+.2f", v),
                v -> { params.brightness = v; pushParams(); });
        slider("Contrast", params.contrast, linear(0.5f, 2.5f), v -> String.format(Locale.US, "%.2f×", v),
                v -> { params.contrast = v; pushParams(); });
        slider("Gamma", params.gamma, linear(0.4f, 2.5f), v -> String.format(Locale.US, "%.2f", v),
                v -> { params.gamma = v; pushParams(); });
        slider("Enhance", params.enhance, linear(0f, 2f), v -> String.format(Locale.US, "%.2f", v),
                v -> { params.enhance = v; pushParams(); });

        if (current == null) return;
        final CameraProbe.Report r = current;
        section("SENSOR · hardware");
        toggle("Manual exposure", settings.manualExposure, r.manualSensor && r.exposureRange != null,
                r.manualSensor ? null : "This camera does not allow manual exposure for apps",
                v -> { settings.manualExposure = v; pushSettings(); buildAdjustPanel(); });
        if (settings.manualExposure && r.manualSensor && r.exposureRange != null && r.isoRange != null) {
            double eLo = Math.max(r.exposureRange.getLower(), 50_000L);
            double eHi = Math.min(r.exposureRange.getUpper(), 30_000_000_000L);
            settings.exposureNs = (long) Math.max(eLo, Math.min(eHi, settings.exposureNs));
            slider("Shutter", settings.exposureNs, logScale(eLo, eHi), v -> CameraProbe.formatExposure((long) v),
                    v -> { settings.exposureNs = (long) v; pushSettings(); });
            double iLo = r.isoRange.getLower(), iHi = r.isoRange.getUpper();
            settings.iso = (int) Math.max(iLo, Math.min(iHi, settings.iso));
            slider("ISO", settings.iso, logScale(iLo, iHi), v -> String.valueOf(Math.round(v)),
                    v -> { settings.iso = Math.round(v); pushSettings(); });
            note("Longest exposure this camera allows apps: " + CameraProbe.formatExposure(r.exposureRange.getUpper()));
        } else {
            final float evStep = r.evStep;
            int evLo = r.evRange == null ? 0 : r.evRange.getLower();
            int evHi = r.evRange == null ? 0 : r.evRange.getUpper();
            if (evHi > evLo) {
                slider("Exposure", settings.evIndex, linear(evLo, evHi),
                        v -> String.format(Locale.US, "%+.1f EV", Math.round(v) * evStep),
                        v -> { settings.evIndex = Math.round(v); pushSettings(); });
            }
        }
        boolean focusOk = r.manualSensor && r.minFocusDistance > 0f;
        toggle("Manual focus", settings.manualFocus, focusOk,
                "IR focuses slightly behind visible light. Pull focus a touch past the sharp point.",
                v -> { settings.manualFocus = v; pushSettings(); buildAdjustPanel(); });
        if (settings.manualFocus && focusOk) {
            slider("Focus", settings.focusDiopters, linear(0f, r.minFocusDistance),
                    v -> v < 0.01f ? "∞" : String.format(Locale.US, "%.2fm", 1f / v),
                    v -> { settings.focusDiopters = v; pushSettings(); });
        }
        toggle("Native colour balance (AWB off)", settings.awbOff, r.manualPost,
                r.manualPost ? "Unity channel gains, so IR isn't 'corrected' away" : "Not supported on this camera",
                v -> { settings.awbOff = v; pushSettings(); });
        toggle("Low-light mode", settings.lowLight, true,
                "Slower frame rate, high-quality noise reduction, and " + LOW_LIGHT_FRAMES + "-frame stacking on capture",
                v -> { settings.lowLight = v; pushSettings(); });
        toggle("Also save RAW (DNG)", settings.saveRaw, rawReady,
                rawReady ? "Untouched sensor data. The only genuinely unprocessed output."
                        : "This camera does not offer RAW to apps",
                v -> settings.saveRaw = v);

        section("INFRARED");
        toggle("IR-pass filter attached", irFilterOn, true,
                "Tick this only when a 720 nm (or deeper) filter physically covers the lens. The badge then reports residual NIR.",
                this::setIrFilter);
    }

    private void section(String title) {
        TextView t = Ui.text(this, title, 11, Ui.ACCENT, true);
        t.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams lp = Ui.matchWidth();
        lp.topMargin = Ui.dp(this, adjustPanel.getChildCount() == 0 ? 0 : 14);
        lp.bottomMargin = Ui.dp(this, 4);
        adjustPanel.addView(t, lp);
    }

    private void note(String s) {
        TextView t = Ui.text(this, s, 11, Ui.TEXT2, false);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        adjustPanel.addView(t, Ui.matchWidth());
    }

    private void slider(String label, float value, final Mapper m, final Fmt fmt, final Sink sink) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Ui.text(this, label, 13, Ui.TEXT, false);
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 92), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setProgress(Math.round(Math.max(0f, Math.min(1f, m.t(value))) * 1000));
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView val = Ui.text(this, fmt.format(value), 12, Ui.TEXT2, false);
        val.setGravity(Gravity.END);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(val, new LinearLayout.LayoutParams(Ui.dp(this, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = m.value(p / 1000f);
                val.setText(fmt.format(v));
                sink.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        adjustPanel.addView(row, Ui.matchWidth());
    }

    private void toggle(String label, boolean checked, boolean enabled, String hint, final BoolSink sink) {
        final Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(enabled ? Ui.TEXT : Ui.TEXT2);
        sw.setTextSize(13);
        sw.setChecked(checked && enabled);
        sw.setEnabled(enabled);
        sw.setThumbTintList(ColorStateList.valueOf(checked && enabled ? Ui.ACCENT : 0xFFB0B4B8));
        sw.setOnCheckedChangeListener((b, v) -> {
            sw.setThumbTintList(ColorStateList.valueOf(v ? Ui.ACCENT : 0xFFB0B4B8));
            sink.accept(v);
        });
        sw.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        adjustPanel.addView(sw, Ui.matchWidth());
        if (hint != null) note(hint);
    }
}
