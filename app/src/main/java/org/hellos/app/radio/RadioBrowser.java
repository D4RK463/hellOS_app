// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.radio;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Blocking client for the radio-browser.info API (no account, no API key). The API runs on several
 * mirrors; the list is read from {@code all.api.radio-browser.info} and a failing mirror is skipped.
 * Must not be called on the main thread.
 *
 * @see <a href="https://api.radio-browser.info/">API documentation</a>
 */
final class RadioBrowser {

    private static final String TAG = "RadioBrowser";
    private static final String ALL_SERVERS = "all.api.radio-browser.info";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** Stations requested per search. */
    private static final int SEARCH_LIMIT = 150;

    private final JsonMapper mapper;
    private final String userAgent;
    /** Mirrors in the order they are tried; the first one that answered stays in front. */
    @Nullable
    private List<String> servers;

    RadioBrowser(JsonMapper mapper, String userAgent) {
        this.mapper = mapper;
        this.userAgent = userAgent;
    }

    /** Stations whose name contains {@code query}, most popular first. */
    List<Station> search(String query) throws IOException {
        String path = "/json/stations/search?name=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&hidebroken=true&order=clickcount&reverse=true&limit=" + SEARCH_LIMIT;
        JsonNode result = get(path);
        List<Station> stations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : result) {
            Station station = Station.fromApi(node);
            if (station != null && seen.add(station.uuid())) {
                stations.add(station);
            }
        }
        return stations;
    }

    /** Tells radio-browser that the station was played (its popularity ranking); errors are ignored. */
    void countClick(String uuid) {
        try {
            get("/json/url/" + URLEncoder.encode(uuid, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Click not counted: " + e);
        }
    }

    private synchronized List<String> servers() {
        if (servers == null) {
            List<String> list = new ArrayList<>();
            try {
                for (JsonNode node : request(ALL_SERVERS, "/json/servers")) {
                    String name = node.path("name").asString("").strip().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".api.radio-browser.info") && !list.contains(name)) {
                        list.add(name);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "Server list not loaded: " + e);
            }
            Collections.shuffle(list);
            // the round-robin name as last resort, also if the list could not be loaded
            list.add(ALL_SERVERS);
            servers = list;
        }
        return servers;
    }

    private JsonNode get(String path) throws IOException {
        IOException last = null;
        for (String server : new ArrayList<>(servers())) {
            try {
                JsonNode result = request(server, path);
                preferServer(server);
                return result;
            } catch (IOException e) {
                Log.d(TAG, server + " failed: " + e);
                last = e;
            }
        }
        throw last != null ? last : new IOException("No radio-browser server reachable");
    }

    private synchronized void preferServer(String server) {
        if (servers != null && servers.remove(server)) {
            servers.add(0, server);
        }
    }

    private JsonNode request(String server, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("https://" + server + path).toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return mapper.readTree(in.readNBytes(MAX_BYTES));
            } catch (JacksonException e) {
                throw new IOException("Invalid response", e);
            }
        } finally {
            connection.disconnect();
        }
    }
}
