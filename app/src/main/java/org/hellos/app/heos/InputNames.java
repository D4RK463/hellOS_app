// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import org.hellos.model.Input;

import java.util.Locale;

/** Readable names for {@link Input}, used when the device does not report its inputs itself. */
final class InputNames {

    private InputNames() {
    }

    static String label(Input input) {
        return switch (input) {
            case HDMI_ARC_1 -> "HDMI ARC";
            case TV_AUDIO -> "TV Audio";
            case CABLE_SAT -> "Cable/Sat";
            case BLURAY -> "Blu-ray";
            case MEDIA_PLAYER -> "Media Player";
            case HD_RADIO -> "HD Radio";
            case USB_DAC -> "USB DAC";
            case AUX_SINGLE -> "AUX";
            case AUX_8K -> "AUX 8K";
            case DVD, CD, TV -> input.name();
            default -> generic(input);
        };
    }

    /** HDMI_IN_1 -> "HDMI 1", OPTICAL_IN_2 -> "Optical 2", SOURCE_3 -> "Source 3", AUX1 -> "AUX 1". */
    private static String generic(Input input) {
        String[] words = input.name().replace("_IN_", "_").replaceAll("([A-Z])(\\d)", "$1_$2").split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (label.length() > 0) {
                label.append(' ');
            }
            if (word.length() <= 4 && !word.equals("LINE") && !word.equals("GAME") && !word.equals("COAX")) {
                label.append(word); // HDMI, AUX, USB, numbers
            } else {
                label.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return label.toString();
    }
}
