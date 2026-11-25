// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.radio;

import androidx.annotation.Nullable;

import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An internet radio station from radio-browser.info.
 *
 * @param uuid        {@code stationuuid}, stable id of the station
 * @param name        station name
 * @param url         stream URL; {@code url_resolved}, i.e. playlists (.pls/.m3u) already resolved
 * @param favicon     logo URL, may be empty
 * @param codec       e.g. {@code MP3}, {@code AAC}; may be empty
 * @param bitrate     kbit/s, {@code 0} if unknown
 * @param countryCode ISO 3166-1 alpha-2, may be empty
 * @param hls         HTTP live streaming (.m3u8)
 */
public record Station(String uuid, String name, String url, String favicon, String codec,
                      int bitrate, String countryCode, boolean hls) {

    /** From a station object of the radio-browser API, or {@code null} without uuid or URL. */
    @Nullable
    static Station fromApi(JsonNode node) {
        String uuid = text(node, "stationuuid");
        String url = text(node, "url_resolved");
        if (url.isEmpty()) {
            url = text(node, "url");
        }
        // a space would end the URL in the HEOS command
        url = url.replace(" ", "%20");
        if (uuid.isEmpty() || url.isEmpty()) {
            return null;
        }
        String name = text(node, "name");
        return new Station(uuid, name.isEmpty() ? url : name, url, text(node, "favicon"),
                text(node, "codec").toUpperCase(Locale.ROOT), node.path("bitrate").asInt(0),
                text(node, "countrycode").toUpperCase(Locale.ROOT), node.path("hls").asInt(0) == 1);
    }

    /** From the form written by {@link #toJson}. */
    @Nullable
    static Station fromJson(JsonNode node) {
        String uuid = text(node, "uuid");
        String url = text(node, "url");
        if (uuid.isEmpty() || url.isEmpty()) {
            return null;
        }
        return new Station(uuid, text(node, "name"), url, text(node, "favicon"), text(node, "codec"),
                node.path("bitrate").asInt(0), text(node, "countryCode"), node.path("hls").asBoolean(false));
    }

    void toJson(ObjectNode node) {
        node.put("uuid", uuid);
        node.put("name", name);
        node.put("url", url);
        node.put("favicon", favicon);
        node.put("codec", codec);
        node.put("bitrate", bitrate);
        node.put("countryCode", countryCode);
        node.put("hls", hls);
    }

    /** Short description for lists, e.g. {@code DE · 128 kbit/s}. */
    public String detail() {
        StringBuilder text = new StringBuilder(countryCode);
        if (bitrate > 0) {
            text.append(text.length() > 0 ? " · " : "").append(bitrate).append(" kbit/s");
        }
        return text.toString();
    }

    /** The stream format, e.g. {@code MP3 · HTTP}, see {@link StationFormat#tag}. */
    public String formatTag() {
        return StationFormat.tag(this);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString().strip();
    }
}
