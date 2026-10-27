package com.hitmacreed.androidtvdebloater;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.util.DisplayMetrics;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The user's background photo. It is toned once when saved (dimmed, slightly desaturated) so
 * the home screen's text and tiles stay readable on any photo, and so the home screen only has to
 * load a ready-made JPEG. The photo stays sharp: the rest of the readability comes from gradients
 * behind the text (bg_wall_scrim, bg_shelf_shade), not from blurring the whole photo.
 */
final class WallpaperStore {

    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final String FILE = "wallpaper.jpg";
    private static final int MAX_WIDTH = 1920;
    private static final float DIM = 0.78f;
    private static final float SATURATION = 0.9f;

    private WallpaperStore() {}

    /** Device-protected storage: the launcher is directBootAware and may start before unlock. */
    private static File file(Context ctx) {
        return new File(ctx.createDeviceProtectedStorageContext().getFilesDir(), FILE);
    }

    static boolean exists(Context ctx) {
        return file(ctx).isFile();
    }

    static Bitmap load(Context ctx) {
        File f = file(ctx);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }

    /** Fits the photo to the screen, applies the readability filter and saves it. False if it isn't an image. */
    static boolean save(Context ctx, byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        if (w > MAX_WIDTH) {
            h = h * MAX_WIDTH / w;
            w = MAX_WIDTH;
        }

        // Decode at roughly screen size: a 48 MP phone photo at full size would not fit in memory.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) {
            opts.inSampleSize *= 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) return false;
        src = upright(src, data);

        Bitmap out = filter(src, w, h);
        src.recycle();
        File f = file(ctx);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.JPEG, 92, os);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            out.recycle();
        }
        return tmp.renameTo(f);
    }

    /** Phone cameras save portrait shots sideways and record the turn in EXIF. */
    private static Bitmap upright(Bitmap src, byte[] data) {
        int degrees;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: return src;
            }
        } catch (IOException e) {
            return src;
        }
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (turned != src) src.recycle();
        return turned;
    }

    /** Centre-crops to w x h, dims and slightly desaturates. */
    private static Bitmap filter(Bitmap src, int w, int h) {
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(DIM, DIM, DIM, 1f);
        cm.postConcat(dim);
        Paint tone = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        tone.setColorFilter(new ColorMatrixColorFilter(cm));

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // Transparent PNGs would otherwise turn black at random in the JPEG; give them a dark ground.
        c.drawColor(0xFF000000);
        c.drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h), tone);
        return out;
    }
}
