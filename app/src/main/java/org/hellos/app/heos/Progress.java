// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

/** Playback position of the current title; a duration of 0 means unknown (e.g. radio). */
public record Progress(long positionMs, long durationMs) {

    public static final Progress NONE = new Progress(0, 0);

    public boolean hasDuration() {
        return durationMs > 0;
    }
}
