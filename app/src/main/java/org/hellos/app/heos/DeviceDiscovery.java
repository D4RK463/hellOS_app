// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.hellos.discovery.DiscoveredDevice;
import org.hellos.discovery.HeosDiscovery;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds HEOS devices in the local network (SSDP via hellOS) and looks up their names from the
 * UPnP device description. Callbacks run on the main thread.
 */
public final class DeviceDiscovery {

    private static final String TAG = "DeviceDiscovery";
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final int HTTP_TIMEOUT_MS = 2000;
    private static final Pattern FRIENDLY_NAME = Pattern.compile("<friendlyName>([^<]+)</friendlyName>");
    private static final Pattern MODEL_NAME = Pattern.compile("<modelName>([^<]+)</modelName>");

    /** A device that answered. {@code name} and {@code model} may be {@code null}. */
    public record FoundDevice(String host, @Nullable String name, @Nullable String model) {
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService describe = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "hellos-app-describe");
        thread.setDaemon(true);
        return thread;
    });

    public DeviceDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Searches for {@link #TIMEOUT}. {@code onFound} is called once per device, {@code onDone} at
     * the end with {@code null} or the error.
     */
    public void search(Consumer<FoundDevice> onFound, Consumer<Throwable> onDone) {
        // Android drops multicast packets unless an app holds a multicast lock.
        WifiManager wifi = context.getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("hellos-discovery");
        if (lock != null) {
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        HeosDiscovery.builder().timeout(TIMEOUT).build()
                .discoverAll(device -> describe.execute(() -> {
                    FoundDevice found = describe(device);
                    main.post(() -> onFound.accept(found));
                }))
                .whenComplete((devices, error) -> {
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                    main.post(() -> onDone.accept(error));
                });
    }

    private FoundDevice describe(DiscoveredDevice device) {
        String name = null;
        String model = null;
        URI location = device.locationUri().orElse(null);
        if (location != null && "http".equalsIgnoreCase(location.getScheme())) {
            try {
                String xml = fetch(location);
                name = first(FRIENDLY_NAME, xml);
                model = first(MODEL_NAME, xml);
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "No device description from " + location + ": " + e);
            }
        }
        return new FoundDevice(device.host(), name, model);
    }

    private static String fetch(URI location) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) location.toURL().openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            byte[] body = in.readNBytes(64 * 1024);
            return new String(body, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
