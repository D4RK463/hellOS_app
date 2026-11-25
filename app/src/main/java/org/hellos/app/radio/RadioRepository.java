// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Internet radio: station search on radio-browser.info and the favorites, which are stored on the
 * phone only. Playing a station is done by the HEOS device, see
 * {@link org.hellos.app.heos.HeosRepository#playStation}. All public methods must be called on the
 * main thread; results are delivered on the main thread.
 */
public final class RadioRepository {

    private static final String TAG = "RadioRepository";
    private static final String FILE = "radio";
    private static final String KEY_FAVORITES = "favorites";

    private final SharedPreferences preferences;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RadioBrowser browser;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "hellos-app-radio");
        thread.setDaemon(true);
        return thread;
    });
    private final MutableLiveData<List<Station>> favorites = new MutableLiveData<>(List.of());
    /** Incremented on every search, so results of an older search are dropped. */
    private int searchGeneration;

    public RadioRepository(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        browser = new RadioBrowser(mapper, "hellOSapp/" + versionName(app));
        favorites.setValue(readFavorites());
    }

    // ---- search --------------------------------------------------------------------------------

    /** Searches stations by name; only the result of the latest search is delivered. */
    public void search(String query, Consumer<List<Station>> onResult, Consumer<String> onError) {
        int gen = ++searchGeneration;
        io.execute(() -> {
            try {
                List<Station> result = browser.search(query);
                main.post(() -> {
                    if (gen == searchGeneration) {
                        onResult.accept(result);
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Search failed", e);
                String text = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
                main.post(() -> {
                    if (gen == searchGeneration) {
                        onError.accept(text);
                    }
                });
            }
        });
    }

    /** Drops the result of a running search. */
    public void cancelSearch() {
        searchGeneration++;
    }

    /** Counts the play on radio-browser.info (station popularity), in the background. */
    public void countClick(Station station) {
        io.execute(() -> browser.countClick(station.uuid()));
    }

    // ---- favorites -----------------------------------------------------------------------------

    public LiveData<List<Station>> favorites() {
        return favorites;
    }

    public boolean isFavorite(Station station) {
        return indexOf(current(), station.uuid()) >= 0;
    }

    /** Adds the station at the end, or at {@code position} (e.g. to undo a removal). */
    public void addFavorite(Station station, int position) {
        List<Station> list = new ArrayList<>(current());
        if (indexOf(list, station.uuid()) >= 0) {
            return;
        }
        list.add(position < 0 || position > list.size() ? list.size() : position, station);
        setFavorites(list);
    }

    /** Removes the station; returns its former position or {@code -1}. */
    public int removeFavorite(Station station) {
        List<Station> list = new ArrayList<>(current());
        int index = indexOf(list, station.uuid());
        if (index >= 0) {
            list.remove(index);
            setFavorites(list);
        }
        return index;
    }

    private List<Station> current() {
        return Objects.requireNonNullElse(favorites.getValue(), List.of());
    }

    private static int indexOf(List<Station> list, String uuid) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uuid().equals(uuid)) {
                return i;
            }
        }
        return -1;
    }

    private void setFavorites(List<Station> list) {
        favorites.setValue(List.copyOf(list));
        ArrayNode array = mapper.createArrayNode();
        for (Station station : list) {
            station.toJson(array.addObject());
        }
        preferences.edit().putString(KEY_FAVORITES, mapper.writeValueAsString(array)).apply();
    }

    private List<Station> readFavorites() {
        String json = preferences.getString(KEY_FAVORITES, null);
        if (json == null) {
            return List.of();
        }
        List<Station> list = new ArrayList<>();
        try {
            for (JsonNode node : mapper.readTree(json)) {
                Station station = Station.fromJson(node);
                if (station != null) {
                    list.add(station);
                }
            }
        } catch (JacksonException e) {
            Log.w(TAG, "Favorites not readable", e);
        }
        return List.copyOf(list);
    }

    private static String versionName(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "unknown" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
