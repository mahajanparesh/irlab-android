package com.irlab.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.camera2.DngCreator;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes captures to the shared gallery (Pictures/IRLab) through MediaStore. Every file carries its
 * provenance: the file name ends in _SW (software-processed) or _RAW (unprocessed sensor data), and the
 * EXIF/DNG description records the camera, IR classification and full processing chain.
 */
final class ImageSaver {
    static final String RELATIVE_DIR = Environment.DIRECTORY_PICTURES + "/IRLab";

    private ImageSaver() {}

    static Uri collection() {
        return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    static String newName(String tag, String ext) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return "IRLAB_" + ts + "_" + tag + "." + ext;
    }

    static Uri saveJpeg(Context ctx, Bitmap bmp, String name, String description, int exifOrientation)
            throws IOException {
        File tmp = new File(ctx.getCacheDir(), "capture_tmp.jpg");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
        }
        String now = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date());
        ExifInterface exif = new ExifInterface(tmp.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, ascii(description));
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, ascii(description));
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "IR Lab 1.0");
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
        exif.setAttribute(ExifInterface.TAG_DATETIME, now);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now);
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
        exif.saveAttributes();

        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = baseValues(name, "image/jpeg", description);
        Uri uri = cr.insert(collection(), v);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(tmp)) {
            if (os == null) throw new IOException("Cannot open output stream");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        publish(cr, uri);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return uri;
    }

    static Uri saveDng(Context ctx, DngCreator dng, Image image, String name, String description) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(collection(), baseValues(name, "image/x-adobe-dng", description));
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Cannot open output stream");
            dng.writeImage(os, image);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        publish(cr, uri);
        return uri;
    }

    /** Most recent IR Lab image, or null. */
    static Uri latest(Context ctx) {
        String[] proj = {MediaStore.Images.Media._ID};
        try (Cursor c = ctx.getContentResolver().query(collection(), proj,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{RELATIVE_DIR + "%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(collection(), c.getLong(0));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ContentValues baseValues(String name, String mime, String description) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        v.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Images.ImageColumns.DESCRIPTION, ascii(description));
        return v;
    }

    private static void publish(ContentResolver cr, Uri uri) {
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
    }

    /** EXIF ASCII fields must be 7-bit. */
    static String ascii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 128 ? c : '-');
        }
        return b.toString();
    }

    /**
     * Android's DNG decoder ignores the DNG orientation tag, so RAW previews come out in sensor
     * orientation. Rotate by the stored tag, unless the bitmap already looks rotated.
     */
    static Bitmap applyRawOrientation(Context ctx, Uri uri, Bitmap bmp) {
        if (bmp == null) return null;
        int degrees = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return bmp;
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Exception e) {
            return bmp;
        }
        if (degrees == 0) return bmp;
        if ((degrees == 90 || degrees == 270) && bmp.getHeight() > bmp.getWidth()) return bmp; // already upright
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    static int exifOrientationFor(int degrees) {
        switch ((degrees % 360 + 360) % 360) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }
}
