// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.hellos.HeosSystem;
import org.hellos.Player;
import org.hellos.app.R;
import org.hellos.app.avr.AudioConfig;
import org.hellos.app.avr.AvrAudioClient;
import org.hellos.app.avr.SoundModeNames;
import org.hellos.app.radio.Station;
import org.hellos.cli.HeosCommandException;
import org.hellos.cli.HeosTimeoutException;
import org.hellos.connection.ConnectionState;
import org.hellos.event.HeosEvent;
import org.hellos.model.AddCriteria;
import org.hellos.model.BrowseResult;
import org.hellos.model.GroupInfo;
import org.hellos.model.Input;
import org.hellos.model.MediaItem;
import org.hellos.model.MediaType;
import org.hellos.model.MusicSources;
import org.hellos.model.NowPlayingMedia;
import org.hellos.model.PlayMode;
import org.hellos.model.PlayState;
import org.hellos.model.PlayerInfo;
import org.hellos.model.QueueItem;
import org.hellos.model.Range;
import org.hellos.model.RepeatMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Holds the connection to the HEOS system and the state of the selected player.
 *
 * <p>All public methods must be called on the main thread. Results and events of the library arrive
 * on its own threads and are posted to the main thread before they touch any state. The connection
 * is only open while the app is in the foreground ({@link #onStart} / {@link #onStop}).
 */
public final class HeosRepository implements DefaultLifecycleObserver {

    private static final String TAG = "HeosRepository";
    private static final int VOLUME_KEY_STEP = 2;
    /** Page size when browsing folders of a media server. */
    public static final int BROWSE_PAGE_SIZE = 100;
    /** Time the AVR needs after an input change before it reports the mode of the new input. */
    private static final long SOUND_MODE_AFTER_INPUT_MS = 1500;

    private final Context context;
    private final AppPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "hellos-app-io");
        thread.setDaemon(true);
        return thread;
    });
    /** Blocking HTTP requests to the AVR (sound mode), one after another. */
    private final ExecutorService avrIo = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "hellos-app-avr");
        thread.setDaemon(true);
        return thread;
    });
    /** Only used on {@link #avrIo}; kept so the device description is read once per host. */
    @Nullable
    private AvrAudioClient avrClient;
    /** Incremented on every sound mode request, so only the newest answer is shown. */
    private int soundModeRequest;

    @Nullable
    private HeosSystem heos;
    /** Incremented on every connect, so callbacks of an old connection are ignored. */
    private int generation;
    /** The generation in which the selected player was last loaded completely. */
    private int loadedGeneration = -1;
    private boolean started;

    private final MutableLiveData<ConnectionStatus> connection = new MutableLiveData<>();
    private final MutableLiveData<List<PlayerInfo>> players = new MutableLiveData<>(List.of());
    private final MutableLiveData<List<GroupInfo>> groups = new MutableLiveData<>(List.of());
    private final MutableLiveData<Map<Long, Volume>> groupVolumes = new MutableLiveData<>(Map.of());
    private final MutableLiveData<PlayerInfo> selectedPlayer = new MutableLiveData<>();
    private final MutableLiveData<NowPlayingMedia> nowPlaying = new MutableLiveData<>();
    private final MutableLiveData<PlayState> playState = new MutableLiveData<>(PlayState.STOP);
    private final MutableLiveData<Volume> volume = new MutableLiveData<>(new Volume(0, false));
    private final MutableLiveData<Progress> progress = new MutableLiveData<>(Progress.NONE);
    private final MutableLiveData<PlayMode> playMode = new MutableLiveData<>(new PlayMode(RepeatMode.OFF, false));
    private final MutableLiveData<List<QueueItem>> queue = new MutableLiveData<>(List.of());
    private final MutableLiveData<OneShot<String>> messages = new MutableLiveData<>();
    private final MutableLiveData<FolderProgress> folderProgress = new MutableLiveData<>();
    private final MutableLiveData<SoundModeState> soundMode = new MutableLiveData<>();

    /**
     * The radio station last started from the app. HEOS only knows the stream URL, so its name and
     * logo are added to the now playing media while the player plays it.
     */
    @Nullable
    private Station stream;
    private long streamPlayerId;
    /** Source and media id of the stream as reported by the device; {@code null} until known. */
    @Nullable
    private String streamKey;

    public HeosRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = new AppPreferences(this.context);
        connection.setValue(preferences.host() == null
                ? ConnectionStatus.noDevice()
                : new ConnectionStatus(ConnectionStatus.State.DISCONNECTED, preferences.host(), null));
    }

    // ---- observable state ----------------------------------------------------------------------

    public LiveData<ConnectionStatus> connection() { return connection; }
    public LiveData<List<PlayerInfo>> players() { return players; }
    public LiveData<List<GroupInfo>> groups() { return groups; }
    public LiveData<Map<Long, Volume>> groupVolumes() { return groupVolumes; }
    public LiveData<PlayerInfo> selectedPlayer() { return selectedPlayer; }
    public LiveData<NowPlayingMedia> nowPlaying() { return nowPlaying; }
    public LiveData<PlayState> playState() { return playState; }
    public LiveData<Volume> volume() { return volume; }
    public LiveData<Progress> progress() { return progress; }
    public LiveData<PlayMode> playMode() { return playMode; }
    public LiveData<List<QueueItem>> queue() { return queue; }
    /** Progress of {@link #playFolder}, {@code null} while no folder is being added. */
    public LiveData<FolderProgress> folderProgress() { return folderProgress; }
    /** Sound mode of the selected player; {@code null} if it is not an AVR with sound modes. */
    public LiveData<SoundModeState> soundMode() { return soundMode; }
    /** Errors and hints to show once, e.g. in a snackbar. */
    public LiveData<OneShot<String>> messages() { return messages; }

    @Nullable
    public String savedHost() {
        return preferences.host();
    }

    public boolean isConnected() {
        ConnectionStatus status = connection.getValue();
        return status != null && status.state() == ConnectionStatus.State.CONNECTED;
    }

    // ---- lifecycle: connected only while the app is visible -----------------------------------

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        started = true;
        String host = preferences.host();
        if (host != null && heos == null) {
            open(host);
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        started = false;
        close();
        String host = preferences.host();
        connection.setValue(host == null
                ? ConnectionStatus.noDevice()
                : new ConnectionStatus(ConnectionStatus.State.DISCONNECTED, host, null));
    }

    // ---- connection ----------------------------------------------------------------------------

    /** Connects to a new device and remembers it for the next start. */
    public void connect(String host) {
        String trimmed = Objects.requireNonNull(host, "host").trim();
        if (!trimmed.equals(preferences.host())) {
            preferences.setSelectedPlayerId(null);
        }
        preferences.setHost(trimmed);
        open(trimmed);
    }

    /** Connects again to the remembered device, e.g. after a failed attempt. */
    public void reconnect() {
        String host = preferences.host();
        if (host != null) {
            open(host);
        }
    }

    private void open(String host) {
        close();
        int gen = ++generation;
        HeosSystem system = HeosSystem.builder().host(host).build();
        heos = system;
        connection.setValue(new ConnectionStatus(ConnectionStatus.State.CONNECTING, host, null));

        system.addConnectionListener((state, h, cause) ->
                onMain(gen, () -> onConnectionStateChanged(state, h, cause)));
        system.addEventListener(event -> onMain(gen, () -> onEvent(event)));
        system.connect().whenComplete((s, error) -> onMain(gen, () -> {
            if (error != null) {
                Log.w(TAG, "Connecting to " + host + " failed", error);
                connection.setValue(new ConnectionStatus(ConnectionStatus.State.FAILED, host, describe(error)));
            } else {
                connection.setValue(new ConnectionStatus(ConnectionStatus.State.CONNECTED, host, null));
                refreshAll();
            }
        }));
    }

    private void close() {
        generation++;
        folderProgress.setValue(null);
        HeosSystem old = heos;
        heos = null;
        if (old != null) {
            // closing joins the library's threads, keep it off the main thread
            io.execute(old::close);
        }
    }

    private void onConnectionStateChanged(ConnectionState state, String host, @Nullable Exception cause) {
        ConnectionStatus previous = connection.getValue();
        ConnectionStatus.State mapped = switch (state) {
            case CONNECTING -> ConnectionStatus.State.CONNECTING;
            case CONNECTED -> ConnectionStatus.State.CONNECTED;
            case RECONNECTING -> ConnectionStatus.State.RECONNECTING;
            case DISCONNECTED, CLOSED -> ConnectionStatus.State.DISCONNECTED;
        };
        connection.setValue(new ConnectionStatus(mapped, host, cause == null ? null : describe(cause)));
        if (mapped == ConnectionStatus.State.CONNECTED && previous != null
                && previous.state() == ConnectionStatus.State.RECONNECTING) {
            // the device may have changed anything while we were away
            refreshAll();
        }
    }

    // ---- change events -------------------------------------------------------------------------

    private void onEvent(HeosEvent event) {
        long selected = selectedId();
        switch (event) {
            case HeosEvent.PlayersChanged e -> refreshPlayers();
            case HeosEvent.GroupsChanged e -> {
                refreshGroups();
                refreshPlayers();
            }
            case HeosEvent.PlayerStateChanged e when e.playerId() == selected -> playState.setValue(e.state());
            case HeosEvent.PlayerNowPlayingChanged e when e.playerId() == selected -> refreshNowPlaying();
            case HeosEvent.PlayerNowPlayingProgress e when e.playerId() == selected ->
                    progress.setValue(new Progress(e.position().toMillis(), e.duration().toMillis()));
            case HeosEvent.PlayerPlaybackError e when e.playerId() == selected -> {
                Station station = stream;
                message(station != null && streamPlayerId == e.playerId()
                        ? context.getString(R.string.radio_playback_error, station.name(), station.formatTag(), e.error())
                        : context.getString(R.string.error_playback, e.error()));
            }
            case HeosEvent.PlayerQueueChanged e when e.playerId() == selected -> refreshQueue();
            case HeosEvent.PlayerVolumeChanged e when e.playerId() == selected ->
                    volume.setValue(new Volume(e.level(), e.muted()));
            case HeosEvent.RepeatModeChanged e when e.playerId() == selected ->
                    playMode.setValue(currentPlayMode().withRepeat(e.repeat()));
            case HeosEvent.ShuffleModeChanged e when e.playerId() == selected ->
                    playMode.setValue(currentPlayMode().withShuffle(e.shuffle()));
            case HeosEvent.GroupVolumeChanged e -> putGroupVolume(e.groupId(), new Volume(e.level(), e.muted()));
            default -> {
                // events of other players, sign-in changes and unknown events are not shown
            }
        }
    }

    // ---- refresh -------------------------------------------------------------------------------

    private void refreshAll() {
        refreshPlayers();
        refreshGroups();
    }

    private void refreshPlayers() {
        HeosSystem system = heos;
        if (system == null) {
            return;
        }
        load(system.players(), list -> {
            players.setValue(list);
            Long saved = preferences.selectedPlayerId();
            PlayerInfo selection = null;
            for (PlayerInfo info : list) {
                if (saved != null && info.playerId() == saved) {
                    selection = info;
                }
            }
            if (selection == null && !list.isEmpty()) {
                selection = list.get(0);
            }
            PlayerInfo previous = selectedPlayer.getValue();
            selectedPlayer.setValue(selection);
            if (selection == null) {
                return;
            }
            preferences.setSelectedPlayerId(selection.playerId());
            boolean changed = previous == null || previous.playerId() != selection.playerId();
            if (changed || loadedGeneration != generation) {
                refreshSelectedPlayer();
            }
        });
    }

    private void refreshGroups() {
        HeosSystem system = heos;
        if (system == null) {
            return;
        }
        load(system.groups(), list -> {
            groups.setValue(list);
            Map<Long, Volume> kept = new HashMap<>();
            Map<Long, Volume> current = groupVolumes.getValue();
            for (GroupInfo group : list) {
                if (current != null && current.containsKey(group.groupId())) {
                    kept.put(group.groupId(), current.get(group.groupId()));
                }
                loadGroupVolume(system, group.groupId());
            }
            groupVolumes.setValue(Collections.unmodifiableMap(kept));
        });
    }

    private void loadGroupVolume(HeosSystem system, long groupId) {
        var group = system.group(groupId);
        load(group.volume().thenCombine(group.isMuted(), Volume::new), v -> putGroupVolume(groupId, v));
    }

    private void putGroupVolume(long groupId, Volume value) {
        Map<Long, Volume> copy = new HashMap<>(Objects.requireNonNullElse(groupVolumes.getValue(), Map.of()));
        copy.put(groupId, value);
        groupVolumes.setValue(Collections.unmodifiableMap(copy));
    }

    private void refreshSelectedPlayer() {
        Player player = player();
        if (player == null) {
            return;
        }
        loadedGeneration = generation;
        progress.setValue(Progress.NONE);
        refreshNowPlaying();
        load(player.playState(), playState::setValue);
        load(player.volume().thenCombine(player.isMuted(), Volume::new), volume::setValue);
        load(player.playMode(), playMode::setValue);
        refreshQueue();
        refreshSoundMode(null);
    }

    private void refreshNowPlaying() {
        Player player = player();
        if (player != null) {
            load(player.nowPlaying(), media -> nowPlaying.setValue(withStation(media)));
        }
    }

    private void refreshQueue() {
        Player player = player();
        if (player != null) {
            load(player.queue(), queue::setValue);
        }
    }

    // ---- player selection ----------------------------------------------------------------------

    public void selectPlayer(long playerId) {
        List<PlayerInfo> list = Objects.requireNonNullElse(players.getValue(), List.of());
        for (PlayerInfo info : list) {
            if (info.playerId() == playerId) {
                preferences.setSelectedPlayerId(playerId);
                selectedPlayer.setValue(info);
                nowPlaying.setValue(null);
                queue.setValue(List.of());
                soundMode.setValue(null);
                refreshSelectedPlayer();
                return;
            }
        }
    }

    // ---- playback ------------------------------------------------------------------------------

    public void togglePlayPause() {
        Player player = player();
        if (player != null) {
            run(playState.getValue() == PlayState.PLAY ? player.pause() : player.play());
        }
    }

    public void next() {
        Player player = player();
        if (player != null) {
            run(player.next());
        }
    }

    public void previous() {
        Player player = player();
        if (player != null) {
            run(player.previous());
        }
    }

    public void toggleShuffle() {
        Player player = player();
        if (player != null) {
            run(player.setShuffle(!currentPlayMode().shuffle()));
        }
    }

    /** Off, repeat all, repeat one, off, ... */
    public void cycleRepeat() {
        Player player = player();
        if (player != null) {
            RepeatMode next = switch (currentPlayMode().repeat()) {
                case OFF -> RepeatMode.ON_ALL;
                case ON_ALL -> RepeatMode.ON_ONE;
                case ON_ONE -> RepeatMode.OFF;
            };
            run(player.setRepeat(next));
        }
    }

    // ---- volume --------------------------------------------------------------------------------

    public void setVolume(int level) {
        Player player = player();
        if (player != null) {
            run(player.setVolume(Math.max(0, Math.min(100, level))));
        }
    }

    public void toggleMute() {
        Player player = player();
        if (player != null) {
            run(player.toggleMute());
        }
    }

    /** Hardware volume keys. Returns false if there is no player to control. */
    public boolean volumeStep(boolean up) {
        Player player = player();
        if (player == null || !isConnected()) {
            return false;
        }
        run(up ? player.volumeUp(VOLUME_KEY_STEP) : player.volumeDown(VOLUME_KEY_STEP));
        return true;
    }

    // ---- queue ---------------------------------------------------------------------------------

    public void playQueueItem(int queueId) {
        Player player = player();
        if (player != null) {
            run(player.playQueueItem(queueId));
        }
    }

    public void removeFromQueue(int queueId) {
        Player player = player();
        if (player != null) {
            run(player.removeFromQueue(queueId));
        }
    }

    public void clearQueue() {
        Player player = player();
        if (player != null) {
            run(player.clearQueue());
        }
    }

    // ---- inputs --------------------------------------------------------------------------------

    /**
     * Loads the inputs that can be played on the selected player: the inputs of all HEOS devices
     * as reported by the device ({@link MusicSources#AUX_INPUTS}). If the device reports none, all
     * known input names are offered instead.
     */
    public void loadInputs(Consumer<List<InputChoice>> callback) {
        HeosSystem system = heos;
        Player player = player();
        if (system == null || player == null) {
            return;
        }
        CompletableFuture<List<InputChoice>> choices = system.browse().browse(MusicSources.AUX_INPUTS)
                .thenCompose(devices -> {
                    List<CompletableFuture<List<InputChoice>>> perDevice = new ArrayList<>();
                    boolean severalDevices = devices.items().size() > 1;
                    for (MediaItem device : devices.items()) {
                        if (device.sourceId().isEmpty()) {
                            continue;
                        }
                        perDevice.add(system.browse().browse(device.sourceId().getAsLong())
                                .thenApply(inputs -> toChoices(player, inputs, severalDevices ? device.name() : null)));
                    }
                    return CompletableFuture.allOf(perDevice.toArray(CompletableFuture[]::new))
                            .thenApply(done -> {
                                List<InputChoice> all = new ArrayList<>();
                                for (CompletableFuture<List<InputChoice>> f : perDevice) {
                                    all.addAll(f.join());
                                }
                                return all;
                            });
                })
                .exceptionally(error -> {
                    Log.w(TAG, "Browsing inputs failed, offering all known inputs", error);
                    return List.of();
                });
        load(choices, list -> callback.accept(list.isEmpty() ? allKnownInputs(player) : list));
    }

    private List<InputChoice> toChoices(Player player, BrowseResult inputs, @Nullable String deviceName) {
        List<InputChoice> result = new ArrayList<>();
        for (MediaItem item : inputs.items()) {
            if (item.isInput()) {
                String label = deviceName == null ? item.name() : deviceName + " – " + item.name();
                result.add(new InputChoice(label, () -> player.play(inputs, item)));
            }
        }
        return result;
    }

    private List<InputChoice> allKnownInputs(Player player) {
        List<InputChoice> result = new ArrayList<>();
        for (Input input : Input.values()) {
            result.add(new InputChoice(InputNames.label(input), () -> player.playInput(input)));
        }
        return result;
    }

    public void playInput(InputChoice choice) {
        // the AVR keeps a sound mode per input: read it again once the input has switched
        load(choice.play().get(), ignored -> {
            int gen = generation;
            main.postDelayed(() -> {
                if (gen == generation && started) {
                    refreshSoundMode(null);
                }
            }, SOUND_MODE_AFTER_INPUT_MS);
        });
    }

    // ---- sound mode (HEOS AVR, over its UPnP service, not over HEOS) --------------------------

    /**
     * Reads the sound mode of the selected player. Players that are no AVR (or do not answer)
     * get {@code null}, which hides the sound mode in the UI; no message is shown for them.
     *
     * @param onLoaded called with the new state if it could be read, may be {@code null}
     */
    public void refreshSoundMode(@Nullable Consumer<SoundModeState> onLoaded) {
        PlayerInfo selected = selectedPlayer.getValue();
        String host = selected == null ? null : selected.ipAddress().filter(ip -> !ip.isBlank()).orElse(null);
        if (host == null) {
            soundMode.setValue(null);
            return;
        }
        long playerId = selected.playerId();
        int request = ++soundModeRequest;
        int gen = generation;
        avrIo.execute(() -> {
            AudioConfig config;
            try {
                config = avrClient(host).audioConfig();
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "No sound mode from " + host + ": " + e);
                config = null;
            }
            AudioConfig result = config;
            onMain(gen, () -> {
                if (request != soundModeRequest || selectedId() != playerId) {
                    return;
                }
                if (result == null || !result.hasSoundModes() || result.soundMode().isEmpty()) {
                    soundMode.setValue(null);
                    return;
                }
                SoundModeState state = new SoundModeState(result.soundMode(), result.availableSoundModes(), false);
                soundMode.setValue(state);
                if (onLoaded != null) {
                    onLoaded.accept(state);
                }
            });
        });
    }

    /** Sets the sound mode of the selected AVR and reports if the device did not apply it. */
    public void setSoundMode(String mode) {
        PlayerInfo selected = selectedPlayer.getValue();
        SoundModeState current = soundMode.getValue();
        String host = selected == null ? null : selected.ipAddress().orElse(null);
        if (host == null || current == null || current.busy()) {
            return;
        }
        long playerId = selected.playerId();
        int request = ++soundModeRequest;
        int gen = generation;
        soundMode.setValue(current.withBusy(true));
        avrIo.execute(() -> {
            AudioConfig after = null;
            String error = null;
            try {
                after = avrClient(host).setSoundMode(mode);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Setting sound mode " + mode + " on " + host + " failed", e);
                error = describe(e);
            }
            AudioConfig result = after;
            String failure = error;
            onMain(gen, () -> {
                if (request != soundModeRequest || selectedId() != playerId) {
                    return;
                }
                if (result == null) {
                    soundMode.setValue(current.withBusy(false));
                    message(context.getString(R.string.sound_mode_error, failure));
                    return;
                }
                soundMode.setValue(new SoundModeState(result.soundMode(), result.availableSoundModes(), false));
                if (!mode.equals(result.soundMode())) {
                    message(context.getString(R.string.sound_mode_not_applied, SoundModeNames.label(mode)));
                }
            });
        });
    }

    /** Only call on {@link #avrIo}. */
    private AvrAudioClient avrClient(String host) {
        AvrAudioClient client = avrClient;
        if (client == null || !client.host().equals(host.trim())) {
            client = new AvrAudioClient(host);
            avrClient = client;
        }
        return client;
    }

    // ---- music: local media servers (DLNA/UPnP, USB), no HEOS account needed -----------------

    /** The media servers the HEOS devices see in the network. */
    public void browseServers(Consumer<BrowseResult> onResult, Consumer<String> onError) {
        HeosSystem system = heos;
        if (system == null) {
            onError.accept(context.getString(R.string.status_disconnected));
            return;
        }
        load(system.browse().browse(MusicSources.LOCAL_MUSIC), onResult, onError);
    }

    /**
     * One page of a folder. {@code containerId == null} is the top level of a server, which is
     * returned completely (ranges are not supported there).
     */
    public void browse(long sourceId, @Nullable String containerId, int start,
                       Consumer<BrowseResult> onResult, Consumer<String> onError) {
        HeosSystem system = heos;
        if (system == null) {
            onError.accept(context.getString(R.string.status_disconnected));
            return;
        }
        CompletableFuture<BrowseResult> page = containerId == null
                ? system.browse().browse(sourceId)
                : system.browse().browse(sourceId, containerId, Range.ofSize(start, BROWSE_PAGE_SIZE));
        load(page, onResult, onError);
    }

    /** Plays or queues a title or a whole folder/album on the selected player. */
    public void play(long sourceId, @Nullable String parentContainerId, MediaItem item, AddCriteria criteria) {
        Player player = player();
        if (player == null) {
            return;
        }
        CompletableFuture<Void> command;
        try {
            command = player.play(sourceId, parentContainerId, item, criteria);
        } catch (IllegalArgumentException e) {
            message(context.getString(R.string.music_not_playable, item.name()));
            return;
        }
        load(command, ignored -> {
            if (criteria == AddCriteria.PLAY_NEXT || criteria == AddCriteria.ADD_TO_END) {
                message(context.getString(R.string.music_added, item.name()));
            }
        }, this::message);
    }

    // ---- internet radio: plays a stream URL, no HEOS account needed ----------------------------

    /** Plays the station on the selected player; {@code onStarted} runs once the device accepted it. */
    public void playStation(Station station, Runnable onStarted) {
        Player player = player();
        if (player == null) {
            message(context.getString(R.string.no_player));
            return;
        }
        long playerId = selectedId();
        // the device may take a while to open the stream; the command times out after 15 s
        message(context.getString(R.string.radio_starting, station.name()));
        int gen = generation;
        player.playUrl(station.url()).whenComplete((ignored, error) -> onMain(gen, () -> {
            if (error != null) {
                Log.w(TAG, "Station not played: " + station.url(), error);
                message(context.getString(R.string.radio_play_failed, station.name(), station.formatTag(),
                        describePlayError(error)));
                return;
            }
            stream = station;
            streamPlayerId = playerId;
            streamKey = null;
            refreshNowPlaying();
            onStarted.run();
        }));
    }

    /** The reason why a stream was refused, readable for users; the HEOS details for testing. */
    private String describePlayError(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof HeosTimeoutException) {
            return context.getString(R.string.radio_error_timeout);
        }
        if (cause instanceof HeosCommandException command) {
            String details = command.errorText() == null || command.errorText().isBlank()
                    ? "eid " + command.errorId()
                    : command.errorText() + ", eid " + command.errorId();
            return context.getString(R.string.radio_error_refused, details);
        }
        return describe(error);
    }

    /** Adds name and logo of the station started from the app, see {@link #stream}. */
    @Nullable
    private NowPlayingMedia withStation(@Nullable NowPlayingMedia media) {
        Station station = stream;
        if (station == null || media == null || media.isEmpty() || selectedId() != streamPlayerId) {
            return media;
        }
        String key = media.sourceId() + "|" + media.mediaId().orElse("");
        if (streamKey == null || media.mediaId().filter(station.url()::equals).isPresent()) {
            // the first media reported after the device accepted the stream is the stream
            streamKey = key;
        } else if (!streamKey.equals(key)) {
            stream = null;
            return media;
        }
        Optional<String> image = media.imageUrl().filter(url -> !url.isBlank());
        if (image.isEmpty() && !station.favicon().isEmpty()) {
            image = Optional.of(station.favicon());
        }
        return new NowPlayingMedia(MediaType.STATION, media.song(), media.album(), media.artist(),
                Optional.of(station.name()), image, media.mediaId(), media.queueId(), media.sourceId(),
                media.albumId(), media.options());
    }

    /**
     * Plays or appends all titles of a folder in their order. Only one folder at a time: while
     * {@link #folderProgress()} is not {@code null}, further calls are ignored.
     *
     * <p>First the folder is added as a whole (one command). Many DLNA servers (e.g. MiniDLNA)
     * mark folders as not playable and the device refuses that; then the titles are added one
     * after another, which takes a moment per title.
     *
     * @param replace {@code true}: replace the queue and play; {@code false}: add to the end
     */
    public void playFolder(long sourceId, String containerId, boolean replace) {
        HeosSystem system = heos;
        Player player = player();
        if (system == null || player == null || folderProgress.getValue() != null) {
            return;
        }
        int gen = generation;
        folderProgress.setValue(new FolderProgress(0, 0));
        AddCriteria first = replace ? AddCriteria.REPLACE_AND_PLAY : AddCriteria.ADD_TO_END;

        CompletableFuture<Integer> added = system.browse().browseAll(sourceId, containerId).thenCompose(folder -> {
            List<MediaItem> titles = new ArrayList<>();
            for (MediaItem item : folder.items()) {
                if (item.playable() && !item.container() && item.mediaId().isPresent()) {
                    titles.add(item);
                }
            }
            int total = titles.size();
            onMain(gen, () -> folderProgress.setValue(new FolderProgress(0, total)));
            if (total == 0) {
                return CompletableFuture.completedFuture(0);
            }
            return player.addContainerToQueue(sourceId, containerId, first)
                    .thenApply(done -> total)
                    .exceptionallyCompose(refused -> {
                        Log.d(TAG, "Folder not added as a whole, adding " + total + " titles one by one: " + refused);
                        return addOneByOne(gen, player, sourceId, containerId, titles, replace);
                    });
        });
        load(added, count -> {
            folderProgress.setValue(null);
            if (!replace && count > 0) {
                message(context.getResources().getQuantityString(R.plurals.music_all_added, count, count));
            }
        }, error -> {
            folderProgress.setValue(null);
            message(error);
        });
    }

    private CompletableFuture<Integer> addOneByOne(int gen, Player player, long sourceId, String containerId,
                                                   List<MediaItem> titles, boolean replace) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < titles.size(); i++) {
            MediaItem title = titles.get(i);
            int number = i + 1;
            AddCriteria criteria = replace && i == 0 ? AddCriteria.REPLACE_AND_PLAY : AddCriteria.ADD_TO_END;
            chain = chain
                    .thenCompose(done -> player.play(sourceId, containerId, title, criteria))
                    .thenRun(() -> onMain(gen, () -> folderProgress.setValue(new FolderProgress(number, titles.size()))));
        }
        return chain.thenApply(done -> titles.size());
    }

    // ---- groups --------------------------------------------------------------------------------

    /** Groups the given players; the first one becomes the leader. */
    public void createGroup(List<Long> playerIds) {
        HeosSystem system = heos;
        if (system != null && playerIds.size() >= 2) {
            run(system.setGroup(playerIds));
        }
    }

    public void ungroup(long groupId) {
        HeosSystem system = heos;
        if (system != null) {
            run(system.ungroup(groupId));
        }
    }

    public void setGroupVolume(long groupId, int level) {
        HeosSystem system = heos;
        if (system != null) {
            run(system.group(groupId).setVolume(Math.max(0, Math.min(100, level))));
        }
    }

    public void toggleGroupMute(long groupId) {
        HeosSystem system = heos;
        if (system != null) {
            run(system.group(groupId).toggleMute());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    @Nullable
    private Player player() {
        HeosSystem system = heos;
        PlayerInfo selected = selectedPlayer.getValue();
        return system == null || selected == null ? null : system.player(selected.playerId());
    }

    private long selectedId() {
        PlayerInfo selected = selectedPlayer.getValue();
        return selected == null ? Long.MIN_VALUE : selected.playerId();
    }

    private PlayMode currentPlayMode() {
        return Objects.requireNonNullElse(playMode.getValue(), new PlayMode(RepeatMode.OFF, false));
    }

    /** Runs a command; only failures are reported, the new state arrives as change event. */
    private void run(CompletableFuture<?> command) {
        load(command, ignored -> { });
    }

    /** Delivers the result on the main thread unless the connection changed in the meantime. */
    private <T> void load(CompletableFuture<T> future, Consumer<T> onResult) {
        load(future, onResult, this::message);
    }

    private <T> void load(CompletableFuture<T> future, Consumer<T> onResult, Consumer<String> onError) {
        int gen = generation;
        future.whenComplete((value, error) -> onMain(gen, () -> {
            if (error != null) {
                Log.w(TAG, "HEOS command failed", error);
                onError.accept(describe(error));
            } else {
                onResult.accept(value);
            }
        }));
    }

    private void onMain(int gen, Runnable action) {
        main.post(() -> {
            if (gen == generation && started) {
                action.run();
            }
        });
    }

    private void message(String text) {
        messages.setValue(new OneShot<>(text));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String describe(Throwable error) {
        Throwable cause = unwrap(error);
        String text = cause.getMessage();
        return text == null || text.isBlank() ? context.getString(R.string.error_unknown, cause.getClass().getSimpleName()) : text;
    }
}
