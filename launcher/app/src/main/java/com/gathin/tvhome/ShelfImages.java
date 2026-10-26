package com.gathin.tvhome;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures for the top shelf cards. The only network use in the launcher: it fetches the poster or thumbnail URL an
 * app published for its own Watch Next / channel program, nothing else, and only when that card is shown. Pictures
 * are scaled down to card size and kept in a small memory cache; nothing is written to disk.
 */
final class ShelfImages {

    private static final String TAG = "TvHome.ShelfImages";
    private static final int MAX_BYTES = 4 * 1024 * 1024;   // bigger downloads are not a card picture
    private static final int TIMEOUT_MS = 6000;

    private final ContentResolver resolver;
    private final int targetWidth;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final android.content.Context ctx;

    ShelfImages(ContentResolver resolver, int targetWidthPx, android.content.Context ctx) {
        this.resolver = resolver;
        this.targetWidth = targetWidthPx;
        this.ctx = ctx.getApplicationContext();
    }

    /** Shows uri in view, now from the cache or later from a background load. The view's tag guards reuse. */
    void load(String uri, ImageView view) {
        view.setTag(uri);
        view.setImageBitmap(null);
        if (uri == null) return;
        Bitmap hit = cache.get(uri);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap bmp = diskGet(uri);   // posters come from flaky VOD CDNs: once fetched, never again
            // VOD image CDNs drop TLS handshakes at random: try a few times before giving up,
            // and remember the failure briefly so a card that is re-bound does not hammer the host.
            for (int attempt = 0; attempt < 3 && bmp == null; attempt++) {
                if (attempt > 0) try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                bmp = fetch(uri);
            }
            if (bmp == null) return;
            final Bitmap loaded = bmp;
            cache.put(uri, loaded);
            diskPut(uri, loaded);
            main.post(() -> {
                if (uri.equals(view.getTag())) view.setImageBitmap(loaded);
            });
        });
    }

    /** Shows uri in view, optionally blurred for the hero card's backdrop; blurred copies are cached per radius. */
    void load(String uri, ImageView view, int blurRadius) {
        if (blurRadius <= 0) {
            load(uri, view);
            return;
        }
        String key = uri == null ? null : uri + "#blur" + blurRadius;
        view.setTag(key);
        view.setImageDrawable(null);
        if (uri == null) return;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap base = cache.get(uri);
            if (base == null) base = diskGet(uri);
            for (int attempt = 0; attempt < 3 && base == null; attempt++) {
                if (attempt > 0) try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                base = fetch(uri);
            }
            if (base == null) return;
            cache.put(uri, base);
            diskPut(uri, base);
            Bitmap blurred = blur(base, blurRadius);
            final Bitmap out = blurred == null ? base : blurred;
            cache.put(key, out);
            main.post(() -> {
                if (key.equals(view.getTag())) view.setImageBitmap(out);
            });
        });
    }

    /** Gaussian blur through RenderScript; a null return just means "use it unblurred". */
    private Bitmap blur(Bitmap in, int radius) {
        try {
            android.renderscript.RenderScript rs = android.renderscript.RenderScript.create(ctx);
            Bitmap out = Bitmap.createBitmap(in.getWidth(), in.getHeight(), Bitmap.Config.ARGB_8888);
            android.renderscript.Allocation inAlloc = android.renderscript.Allocation.createFromBitmap(rs, in);
            android.renderscript.Allocation outAlloc = android.renderscript.Allocation.createFromBitmap(rs, out);
            android.renderscript.ScriptIntrinsicBlur script = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs));
            script.setRadius(Math.max(1, Math.min(25, radius)));
            script.setInput(inAlloc);
            script.forEach(outAlloc);
            outAlloc.copyTo(out);
            rs.destroy();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    void shutdown() {
        pool.shutdownNow();
        cache.evictAll();
    }

    private Bitmap fetch(String uri) {
        try {
            byte[] data = bytes(uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            o.inSampleSize = sampleSize(o.outWidth, targetWidth);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; cards are opaque
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no picture for a shelf card: " + e);
            return null;
        }
    }

    /** The largest power of two that keeps the decoded width at or above target. */
    static int sampleSize(int width, int target) {
        int s = 1;
        while (target > 0 && width / (s * 2) >= target) s *= 2;
        return s;
    }

    private byte[] bytes(String uri) throws IOException {
        String scheme = Uri.parse(uri).getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setUseCaches(false);
            try {
                if (con.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                try (InputStream in = con.getInputStream()) {
                    return readCapped(in);
                }
            } finally {
                con.disconnect();
            }
        }
        if ("content".equals(scheme) || "android.resource".equals(scheme) || "file".equals(scheme)) {
            try (InputStream in = resolver.openInputStream(Uri.parse(uri))) {
                return in == null ? null : readCapped(in);
            }
        }
        return null;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }
    /** Simple disk cache under filesDir/shelf-pics: hashed name, plain PNG. Survives restarts and dead CDNs. */
    private Bitmap diskGet(String uri) {
        try {
            java.io.File f = new java.io.File(new java.io.File(ctx.getFilesDir(), "shelf-pics"),
                    Integer.toHexString(uri.hashCode()) + ".png");
            if (!f.isFile()) return null;
            Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void diskPut(String uri, Bitmap bmp) {
        try {
            java.io.File dir = new java.io.File(ctx.getFilesDir(), "shelf-pics");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            java.io.File f = new java.io.File(dir, Integer.toHexString(uri.hashCode()) + ".png");
            if (f.isFile()) return;   // already cached
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception ignored) {
        }
    }
}
