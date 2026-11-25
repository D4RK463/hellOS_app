// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Parcelable;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

import org.hellos.app.radio.Station;
import org.hellos.model.MediaItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The levels of the music tab. Scoped to the activity, so the position survives switching
 * tabs (the tab fragments are recreated on every switch).
 */
public final class MusicViewModel extends ViewModel {

    /** What a level shows. */
    enum Kind {
        /** Radio favorites, the station search entry and the media servers. */
        ROOT,
        /** The top or a folder of a media server. */
        FOLDER,
        /** Station search on radio-browser.info. */
        RADIO_SEARCH
    }

    /** One level: the start page, the top of a server, a folder within a server, or the station search. */
    static final class Level {
        final String title;
        final Kind kind;
        /** The server; only for {@link Kind#FOLDER}. Source ids can be negative. */
        final long sourceId;
        @Nullable
        final String containerId;
        /** Media servers (root) or the content of a folder. */
        final List<MediaItem> items = new ArrayList<>();
        /** Search result of {@link Kind#RADIO_SEARCH}. */
        final List<Station> stations = new ArrayList<>();
        /** The search text of {@link Kind#RADIO_SEARCH}. */
        String query = "";
        /** Total number of items as reported by the device, {@code 0} if unknown. */
        int count;
        boolean complete;
        boolean loading;
        @Nullable
        String error;
        /** Scroll position, kept while a deeper level is shown. */
        @Nullable
        Parcelable scrollState;

        static Level root(String title) {
            return new Level(title, Kind.ROOT, 0, null);
        }

        static Level radioSearch(String title) {
            return new Level(title, Kind.RADIO_SEARCH, 0, null);
        }

        Level(String title, long sourceId, @Nullable String containerId) {
            this(title, Kind.FOLDER, sourceId, containerId);
        }

        private Level(String title, Kind kind, long sourceId, @Nullable String containerId) {
            this.title = title;
            this.kind = kind;
            this.sourceId = sourceId;
            this.containerId = containerId;
        }

        void reset() {
            items.clear();
            stations.clear();
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
