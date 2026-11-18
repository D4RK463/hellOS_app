// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import java.util.Locale;

final class Formatting {

    private Formatting() {
    }

    /** 83_000 -> "1:23", 3_723_000 -> "1:02:03". */
    static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
