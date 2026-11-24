// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import java.util.List;

/**
 * Sound mode of the selected player, if it is an AVR that reports sound modes.
 *
 * @param current   the current mode, e.g. {@code DIRECT}
 * @param available the modes the device offers
 * @param busy      true while a new mode is being set
 */
public record SoundModeState(String current, List<String> available, boolean busy) {

    public SoundModeState {
        available = List.copyOf(available);
    }

    SoundModeState withBusy(boolean value) {
        return new SoundModeState(current, available, value);
    }
}
