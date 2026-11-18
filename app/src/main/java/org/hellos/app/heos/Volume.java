// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

/** Volume level (0..100) and mute state of a player or group. */
public record Volume(int level, boolean muted) {
}
