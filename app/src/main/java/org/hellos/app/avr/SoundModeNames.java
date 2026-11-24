// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.avr;

import java.util.Locale;
import java.util.Map;

/** Readable names for the sound modes reported by the HEOS AVR. */
public final class SoundModeNames {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("AUTO", "Auto"),
            Map.entry("DIRECT", "Direct"),
            Map.entry("PURE_DIRECT", "Pure Direct"),
            Map.entry("STEREO", "Stereo"),
            Map.entry("VIRTUAL", "Virtual"),
            Map.entry("MULTI_CH_STEREO", "Multi Channel Stereo"),
            Map.entry("DOLBY_PRO_LOGIC_MUSIC", "Dolby Pro Logic Music"),
            Map.entry("DOLBY_PRO_LOGIC_MOVIE", "Dolby Pro Logic Movie"),
            Map.entry("DTS_NEO_CINEMA", "DTS Neo:6 Cinema"),
            Map.entry("DTS_NEO_MUSIC", "DTS Neo:6 Music"));

    private SoundModeNames() {
    }

    /** E.g. {@code DOLBY_PRO_LOGIC_MUSIC} → "Dolby Pro Logic Music"; unknown modes are title-cased. */
    public static String label(String mode) {
        String known = NAMES.get(mode);
        if (known != null) {
            return known;
        }
        StringBuilder label = new StringBuilder();
        for (String word : mode.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }
}
