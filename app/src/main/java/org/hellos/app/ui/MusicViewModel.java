// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Parcelable;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

import org.hellos.model.MediaItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The folder levels of the music tab. Scoped to the activity, so the position survives switching
 * tabs (the tab fragments are recreated on every switch).
 */
public final class MusicViewModel extends ViewModel {

    /** One folder level: the list of servers, the top of a server, or a folder within a server. */
    static final class Level {
        final String title;
        /** The list of servers, not a server. Source ids can be negative, so this is a flag of its own. */
        final boolean serverList;
        /** The server; unused for the list of servers. */
        final long sourceId;
        @Nullable
        final String containerId;
        final List<MediaItem> items = new ArrayList<>();
        /** Total number of items as reported by the device, {@code 0} if unknown. */
        int count;
        boolean complete;
        boolean loading;
        @Nullable
        String error;
        /** Scroll position, kept while a deeper level is shown. */
        @Nullable
        Parcelable scrollState;

        static Level servers(String title) {
            return new Level(title, true, 0, null);
        }

        Level(String title, long sourceId, @Nullable String containerId) {
            this(title, false, sourceId, containerId);
        }

        private Level(String title, boolean serverList, long sourceId, @Nullable String containerId) {
            this.title = title;
            this.serverList = serverList;
            this.sourceId = sourceId;
            this.containerId = containerId;
        }

        boolean isServerList() {
            return serverList;
        }

        void reset() {
            items.clear();
            count = 0;
            complete = false;
            loading = false;
            error = null;
        }
    }

    private final Deque<Level> levels = new ArrayDeque<>();
    /** The device the levels belong to; they are reset when the user switches devices. */
    @Nullable
    private String host;

    Deque<Level> levels() {
        return levels;
    }

    @Nullable
    String host() {
        return host;
    }

    void setHost(@Nullable String host) {
        this.host = host;
    }
}
