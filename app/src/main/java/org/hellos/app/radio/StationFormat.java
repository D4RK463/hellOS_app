// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.radio;

import java.util.Locale;

/**
 * Describes the stream format of a station. Nothing is filtered: whether a HEOS device plays a
 * stream is decided by the device, which reports an error otherwise.
 */
final class StationFormat {

    /** HEOS firmware limits the URL of {@code play_stream} to about 255 characters. */
    static final int MAX_URL_LENGTH = 255;

    private StationFormat() {
    }

    /** E.g. {@code MP3 · HTTP}, {@code AAC · HTTPS · HLS}, {@code ? · HTTP · URL > 255}. */
    static String tag(Station station) {
        String lower = station.url().toLowerCase(Locale.ROOT);
        String codec = station.codec();
        StringBuilder tag = new StringBuilder(codec.isEmpty() || codec.equals("UNKNOWN") ? "?" : codec);
        tag.append(" · ").append(lower.startsWith("https://") ? "HTTPS" : lower.startsWith("http://") ? "HTTP" : "?");
        if (station.hls() || lower.contains(".m3u8")) {
            tag.append(" · HLS");
        }
        if (station.url().length() > MAX_URL_LENGTH) {
            tag.append(" · URL > ").append(MAX_URL_LENGTH);
        }
        return tag.toString();
    }
}
