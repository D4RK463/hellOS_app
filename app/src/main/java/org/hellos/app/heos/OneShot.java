// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import androidx.annotation.Nullable;

/** A value that is handled only once, e.g. a message that must not be shown again after rotation. */
public final class OneShot<T> {

    private final T value;
    private boolean handled;

    public OneShot(T value) {
        this.value = value;
    }

    /** Returns the value the first time, {@code null} afterwards. */
    @Nullable
    public T take() {
        if (handled) {
            return null;
        }
        handled = true;
        return value;
    }
}
