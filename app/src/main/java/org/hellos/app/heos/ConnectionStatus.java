// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import androidx.annotation.Nullable;

/**
 * State of the connection to the HEOS device as shown in the UI.
 *
 * @param host  the device, {@code null} if none has been chosen yet
 * @param error the reason of the last failure, if any
 */
public record ConnectionStatus(State state, @Nullable String host, @Nullable String error) {

    public enum State {
        /** No device chosen yet. */
        NO_DEVICE,
        CONNECTING,
        CONNECTED,
        /** The connection was lost, the library tries to connect again. */
        RECONNECTING,
        DISCONNECTED,
        /** The first connection attempt failed. */
        FAILED
    }

    static ConnectionStatus noDevice() {
        return new ConnectionStatus(State.NO_DEVICE, null, null);
    }
}
