package com.irlab.camera;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Grid of everything IR Lab has saved, with each item labelled RAW (sensor data) or PROCESSED. */
public class GalleryActivity extends Activity {

    static final class Item {
        Uri uri;
        String name;
        boolean raw;
    }

    private final List<Item> items = new ArrayList<>();
    private final LruCache<Uri, Bitmap> thumbs = new LruCache<>(120);
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private GridView grid;
    private TextView empty;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        final int pad = Ui.dp(this, 16);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, "IR Lab gallery", 22, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView back = Ui.chip(this, "CAMERA");
        back.setOnClickListener(v -> finish());
        header.addView(back, Ui.wrap());
        root.addView(header, Ui.matchWidth());
        TextView legend = Ui.text(this, "RAW = unprocessed sensor data (DNG) · PROCESSED = ISP + software modes (JPEG)",
                12, Ui.TEXT2, false);
        legend.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        root.addView(legend, Ui.matchWidth());

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(Ui.dp(this, 6));
        grid.setVerticalSpacing(Ui.dp(this, 6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        body.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = Ui.text(this, "No captures yet.\nPhotos are saved to Pictures/IRLab.", 14, Ui.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(pad + b.left, pad + b.top, pad + b.right, b.bottom);
            return insets;
        });
        setContentView(root);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                Tile tile = convert instanceof Tile ? (Tile) convert : new Tile(GalleryActivity.this);
                tile.bind(items.get(pos));
                return tile;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> startActivity(new Intent(this, ViewerActivity.class)
                .setData(items.get(pos).uri)
                .putExtra(ViewerActivity.EXTRA_NAME, items.get(pos).name)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        items.clear();
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE};
        try (Cursor c = getContentResolver().query(ImageSaver.collection(), proj,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{ImageSaver.RELATIVE_DIR + "%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(ImageSaver.collection(), c.getLong(0));
                it.name = c.getString(1);
                String mime = c.getString(2);
                it.raw = (mime != null && mime.contains("dng")) || (it.name != null && it.name.endsWith(".dng"));
                items.add(it);
            }
        } catch (Exception ignored) {
        }
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private final class Tile extends FrameLayout {
        final ImageView image;
        final TextView label;
        Uri bound;

        Tile(Context c) {
            super(c);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Ui.CARD);
            addView(image, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            label = Ui.text(c, "", 10, Ui.TEXT, true);
            label.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            lp.setMargins(Ui.dp(c, 4), 0, 0, Ui.dp(c, 4));
            addView(label, lp);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w); // square tiles
        }

        void bind(Item it) {
            bound = it.uri;
            label.setText(it.raw ? "RAW DNG" : "PROCESSED");
            int colour = it.raw ? Ui.GOOD : Ui.WARN;
            label.setTextColor(colour);
            label.setBackground(Ui.round(0xCC000000, Ui.dp(getContext(), 6), colour, 1));
            Bitmap cached = thumbs.get(it.uri);
            image.setImageBitmap(cached);
            if (cached != null) return;
            final Uri uri = it.uri;
            loader.execute(() -> {
                Bitmap b = null;
                try {
                    b = getContentResolver().loadThumbnail(uri, new Size(320, 320), null);
                } catch (Exception ignored) {
                }
                if (b == null) return;
                thumbs.put(uri, b);
                final Bitmap fb = b;
                runOnUiThread(() -> { if (uri.equals(bound)) image.setImageBitmap(fb); });
            });
        }
    }
}
