// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads cover art from HTTP(S) URLs into image views, with a small memory cache. */
final class CoverLoader {

    private static final String TAG = "CoverLoader";
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int TARGET_SIZE_PX = 640;
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) hellOS-app";

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "hellos-app-cover");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CoverLoader() {
    }

    /** Shows the image at {@code url}, or the placeholder while loading, on failure or without URL. */
    static void load(ImageView view, @Nullable String url, @DrawableRes int placeholder) {
        view.setTag(url);
        if (url == null || url.isBlank()) {
            view.setImageResource(placeholder);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(placeholder);
        LOADER.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap != null) {
                CACHE.put(url, bitmap);
            }
            MAIN.post(() -> {
                // the view may have been recycled for another item meanwhile
                if (bitmap != null && url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    @Nullable
    private static Bitmap download(String url) {
        try {
            byte[] data = fetch(url);
            if (data == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= TARGET_SIZE_PX) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Cover not loaded from " + url + ": " + e);
            return null;
        }
    }

    /**
     * The bytes at {@code url}. Redirects are followed here, because {@link HttpURLConnection} does
     * not follow them between HTTP and HTTPS, which many station logos need.
     */
    @Nullable
    private static byte[] fetch(String url) throws IOException {
        URL current = new URL(url);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            // some web servers refuse requests without a browser-like user agent
            connection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        return null;
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Cover not loaded from " + url + ": HTTP " + status);
                    return null;
                }
                try (InputStream in = connection.getInputStream()) {
                    return in.readNBytes(MAX_BYTES);
                }
            } finally {
                connection.disconnect();
            }
        }
        Log.d(TAG, "Cover not loaded from " + url + ": too many redirects");
        return null;
    }
}
