// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.avr;

import java.util.List;
import java.util.Objects;

/**
 * Audio configuration of a HEOS AVR as reported by {@code GetAudioConfig}.
 *
 * @param soundMode          the current sound mode, e.g. {@code DIRECT}; empty if not reported
 * @param availableSoundModes the modes the device offers, in its order; empty if it has none
 * @param document           the unescaped {@code <AudioConfig>} document, sent back with only the
 *                           sound mode changed
 */
public record AudioConfig(String soundMode, List<String> availableSoundModes, String document) {

    public AudioConfig {
        Objects.requireNonNull(soundMode, "soundMode");
        availableSoundModes = List.copyOf(availableSoundModes);
        Objects.requireNonNull(document, "document");
    }

    /** True if the device offers sound modes to choose from. */
    public boolean hasSoundModes() {
        return !availableSoundModes.isEmpty();
    }
}
