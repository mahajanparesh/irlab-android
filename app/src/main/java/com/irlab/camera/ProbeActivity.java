package com.irlab.camera;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Hardware compatibility report: what each camera reports through Camera2, whether it can capture
 * genuine infrared, the stored results of the empirical IR remote test, and what to buy if the
 * built-in hardware is unsuitable. Refreshes live when a USB camera is attached or removed.
 */
public class ProbeActivity extends Activity {
    static final String EXTRA_FIRST_RUN = "first_run";
    private static final int REQ_CAMERA = 11;

    private LinearLayout content;
    private CameraManager mgr;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<CameraProbe.Report> reports;
    private final Runnable refreshTask = this::refresh;

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override public void onCameraAvailable(String id) { scheduleRefresh(); }
        @Override public void onCameraUnavailable(String id) { scheduleRefresh(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int pad = Ui.dp(this, 16);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom);
            return insets;
        });
        setContentView(scroll);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mgr.registerAvailabilityCallback(availability, handler);
        refresh();
    }

    @Override
    protected void onPause() {
        mgr.unregisterAvailabilityCallback(availability);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, 400);
    }

    private void refresh() {
        reports = CameraProbe.probeAll(mgr);
        content.removeAllViews();
        PackageManager pm = getPackageManager();
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        TextView title = Ui.text(this, "Infrared hardware report", 24, Ui.TEXT, true);
        content.addView(title, Ui.matchWidth());
        TextView sub = Ui.text(this, Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " · " + reports.size() + " camera entries visible to apps", 13, Ui.TEXT2, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        content.addView(sub, Ui.matchWidth());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            LinearLayout warn = Ui.card(this);
            warn.addView(Ui.text(this, "Camera permission not granted. Some characteristics are hidden until it is.",
                    13, Ui.WARN, false));
            content.addView(warn);
        }

        boolean anyGenuine = false;
        for (CameraProbe.Report r : reports) if (r.genuineIr()) anyGenuine = true;

        LinearLayout verdict = Ui.card(this);
        TextView vTitle = Ui.text(this, anyGenuine ? "GENUINE IR CAPABLE" : "NO DEDICATED IR CAMERA", 12,
                anyGenuine ? Ui.GOOD : Ui.WARN, true);
        vTitle.setLetterSpacing(0.1f);
        verdict.addView(vTitle);
        TextView vBody = Ui.text(this, CameraProbe.overallVerdict(reports, pm), 14, Ui.TEXT, false);
        vBody.setPadding(0, Ui.dp(this, 8), 0, 0);
        vBody.setLineSpacing(0, 1.15f);
        verdict.addView(vBody);
        content.addView(verdict);

        for (CameraProbe.Report r : reports) {
            LinearLayout card = Ui.card(this);
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            String name = (r.parentId == null ? "Camera " : "Physical camera ") + r.id
                    + (r.parentId == null ? "" : "  (inside " + r.parentId + ")");
            head.addView(Ui.text(this, name, 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            int colour = r.genuineIr() ? Ui.GOOD : (r.irClass == CameraProbe.IrClass.RGB_IR_CUT ? Ui.WARN : Ui.ACCENT);
            TextView pill = Ui.text(this, r.irClassLabel(), 11, colour, true);
            pill.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            pill.setBackground(Ui.round(0x00000000, Ui.dp(this, 10), colour, Ui.dp(this, 1)));
            head.addView(pill, Ui.wrap());
            card.addView(head, Ui.matchWidth());

            TextView det = Ui.text(this, CameraProbe.details(r), 12, Ui.TEXT2, false);
            det.setTypeface(android.graphics.Typeface.MONOSPACE);
            det.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            card.addView(det);

            TextView verdictText = Ui.text(this, r.verdict, 13, Ui.TEXT, false);
            verdictText.setLineSpacing(0, 1.12f);
            card.addView(verdictText);

            if (r.parentId == null) {
                String test = prefs.getString("irtest_" + r.id, null);
                TextView t = Ui.text(this, test == null
                        ? "IR remote test: not run yet (camera → IR TEST)"
                        : "IR remote test: " + test, 12,
                        test == null ? Ui.TEXT2 : (test.startsWith("DETECTED") ? Ui.GOOD : Ui.WARN), true);
                t.setPadding(0, Ui.dp(this, 10), 0, 0);
                card.addView(t);
            } else {
                TextView t = Ui.text(this, "Physical sub-camera: the OS picks it automatically through the logical "
                        + "camera above. Its characteristics are shown for completeness.", 12, Ui.TEXT2, false);
                t.setPadding(0, Ui.dp(this, 10), 0, 0);
                card.addView(t);
            }
            content.addView(card);
        }

        addInfoCard("WHAT IS GENUINE, WHAT IS SOFTWARE",
                "• RAW DNG: untouched sensor values. The only output that's guaranteed unprocessed.\n"
                        + "• Processed JPEG: the camera ISP's output (demosaic, noise reduction, tone curve) plus this app's "
                        + "GPU processing. The status badge and the saved file's EXIF description say which.\n"
                        + "• IR MONO / FALSE COLOUR / CHANNEL SWAP on an RGB camera without an IR-pass filter are "
                        + "visualisations of visible light. They look like IR photos but contain no infrared information.\n"
                        + "• With a 720 nm IR-pass filter over the lens, the light that reaches the sensor is near-IR "
                        + "that leaked through the internal IR-cut filter: genuine IR, but faint (expect 1/4 s to several "
                        + "seconds on a tripod).");

        addInfoCard("HOW TO GET GENUINE IR WITH THIS PHONE",
                "1. Clip-on 720 nm IR-pass filter over the main camera (cheapest, works with this app).\n"
                        + "2. Tripod or a stable surface. Turn on Manual exposure (longest shutter you can get), ISO 800-3200, "
                        + "or Low-light mode for 8-frame stacking.\n"
                        + "3. Turn on 'IR-pass filter attached' and 'Native colour balance'. Save RAW, then check the R/G/B "
                        + "channel means reported after capture. Real NIR raises all three channels together, with red "
                        + "strongest near 720 nm.\n"
                        + "4. Use IR TEST with a TV remote to compare which camera module leaks the most IR (front cameras "
                        + "often leak more).");

        addInfoCard("EXTERNAL CAMERAS FOR REAL INFRARED",
                "Near-IR (750-1000 nm, night vision, foliage glow):\n"
                        + "• USB-C / OTG UVC 'NoIR' or day/night modules with 850 nm or 940 nm IR LEDs, e.g. Arducam "
                        + "Day/Night USB cameras (OV2710 / IMX291 variants), ELP NoIR USB modules. They give real-time "
                        + "genuine NIR video. If this phone exposes them through Camera2 they appear above as an EXTERNAL "
                        + "camera and work in this app. Otherwise use a UVC app (e.g. 'USB Camera') with the same module.\n\n"
                        + "Thermal / long-wave IR (8-14 µm, heat):\n"
                        + "• USB-C thermal cameras such as InfiRay P2 Pro, TOPDON TC001 or FLIR ONE (USB-C). This is a "
                        + "different band from near-IR photography. No phone image sensor can see it. These use their "
                        + "own apps/SDKs.\n\n"
                        + "Full-spectrum conversion (removing the IR-cut filter) isn't practical on a sealed phone camera "
                        + "module. A converted mirrorless camera is the alternative for serious IR photography.");

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = Ui.button(this, "Copy report", false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("IR Lab report", CameraProbe.toText(reports, getPackageManager())));
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = Ui.dp(this, 8);
        actions.addView(copy, lp);
        TextView go = Ui.button(this, getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false) ? "Open camera" : "Back to camera", true);
        go.setOnClickListener(v -> finish());
        actions.addView(go, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams alp = Ui.matchWidth();
        alp.topMargin = Ui.dp(this, 4);
        content.addView(actions, alp);
    }

    private void addInfoCard(String title, String body) {
        LinearLayout card = Ui.card(this);
        TextView t = Ui.text(this, title, 12, Ui.ACCENT, true);
        t.setLetterSpacing(0.1f);
        card.addView(t);
        TextView b = Ui.text(this, body, 13, Ui.TEXT, false);
        b.setLineSpacing(0, 1.15f);
        b.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(b);
        content.addView(card);
    }
}
