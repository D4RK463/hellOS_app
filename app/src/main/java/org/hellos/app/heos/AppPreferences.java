// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/** The last used device and player, kept across app starts. */
final class AppPreferences {

    private static final String FILE = "hellos";
    private static final String KEY_HOST = "host";
    private static final String KEY_PLAYER = "selected_player";

    private final SharedPreferences preferences;

    AppPreferences(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Nullable
    String host() {
        return preferences.getString(KEY_HOST, null);
    }

    void setHost(String host) {
        preferences.edit().putString(KEY_HOST, host).apply();
    }

    @Nullable
    Long selectedPlayerId() {
        return preferences.contains(KEY_PLAYER) ? preferences.getLong(KEY_PLAYER, 0) : null;
    }

    void setSelectedPlayerId(@Nullable Long playerId) {
        if (playerId == null) {
            preferences.edit().remove(KEY_PLAYER).apply();
        } else {
            preferences.edit().putLong(KEY_PLAYER, playerId).apply();
        }
    }
}
