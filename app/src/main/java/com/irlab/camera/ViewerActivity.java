package com.irlab.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Collections;

/** Full-screen view of one capture with its embedded provenance record. */
public class ViewerActivity extends Activity {
    static final String EXTRA_NAME = "name";
    private static final int REQ_DELETE = 21;

    private Uri uri;
    private ImageView image;
    private TextView info;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        uri = getIntent().getData();
        String name = getIntent().getStringExtra(EXTRA_NAME);
        final boolean raw = name != null && name.endsWith(".dng");

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        final int pad = Ui.dp(this, 12);

        TextView title = Ui.text(this, name == null ? "" : name, 13, Ui.TEXT, true);
        title.setPadding(0, 0, 0, Ui.dp(this, 6));
        root.addView(title, Ui.matchWidth());
        TextView kind = Ui.text(this, raw ? "RAW DNG · unprocessed sensor data" : "PROCESSED JPEG · ISP + IR Lab GPU processing",
                12, raw ? Ui.GOOD : Ui.WARN, true);
        root.addView(kind, Ui.matchWidth());

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        ilp.topMargin = ilp.bottomMargin = Ui.dp(this, 10);
        root.addView(image, ilp);

        ScrollView infoScroll = new ScrollView(this);
        info = Ui.text(this, "Reading metadata…", 12, Ui.TEXT2, false);
        info.setTextIsSelectable(true);
        infoScroll.addView(info);
        root.addView(infoScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 130)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView share = Ui.button(this, "Share", false);
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType(raw ? "image/x-adobe-dng" : "image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")));
        TextView open = Ui.button(this, "Open with…", false);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, raw ? "image/x-adobe-dng" : "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
            }
        });
        TextView del = Ui.button(this, "Delete", false);
        del.setTextColor(Ui.BAD);
        del.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        blp.leftMargin = blp.rightMargin = Ui.dp(this, 4);
        actions.addView(share, blp);
        actions.addView(open, new LinearLayout.LayoutParams(blp));
        actions.addView(del, new LinearLayout.LayoutParams(blp));
        root.addView(actions, Ui.matchWidth());

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom);
            return insets;
        });
        setContentView(root);
        load(raw);
    }

    private void load(final boolean raw) {
        new Thread(() -> {
            Bitmap bmp = null;
            String err = null;
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                bmp = ImageDecoder.decodeBitmap(src, (decoder, imgInfo, s) -> {
                    int w = imgInfo.getSize().getWidth(), h = imgInfo.getSize().getHeight();
                    int scale = Math.max(1, Math.max(w, h) / 2400);
                    decoder.setTargetSize(w / scale, h / scale);
                });
            } catch (Exception e) {
                err = raw ? "This device's decoder can't preview the DNG. Open it in Lightroom, Snapseed or Expert RAW."
                        : "Preview failed: " + e.getMessage();
            }
            StringBuilder meta = new StringBuilder();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    String d = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
                    meta.append(d == null ? "No provenance description embedded." : d.replace(". ", ".\n"));
                    String make = exif.getAttribute(ExifInterface.TAG_MAKE), model = exif.getAttribute(ExifInterface.TAG_MODEL);
                    if (make != null || model != null) meta.append("\n\nDevice: ").append(make).append(' ').append(model);
                    String exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
                    String iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                    if (exp != null) meta.append("\nExposure: ").append(exp).append(" s");
                    if (iso != null) meta.append("  ISO ").append(iso);
                }
            } catch (Exception e) {
                meta.append("Metadata unavailable: ").append(e.getMessage());
            }
            final Bitmap fb = bmp;
            final String fe = err;
            final String fm = meta.toString();
            runOnUiThread(() -> {
                if (fb != null) image.setImageBitmap(fb);
                info.setText(fe == null ? fm : fe + "\n\n" + fm);
            });
        }).start();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete this capture?")
                .setMessage("The file is removed from Pictures/IRLab. This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> delete())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete() {
        try {
            getContentResolver().delete(uri, null, null);
            finish();
        } catch (RecoverableSecurityException e) {
            // e.g. a file saved by a previous install of the app: ask the system for consent
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception ex) {
                Toast.makeText(this, "Delete failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_DELETE && result == RESULT_OK) finish();
    }
}
