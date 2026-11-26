// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.avr.SoundModeNames;
import org.hellos.app.databinding.FragmentNowPlayingBinding;
import org.hellos.app.heos.HeosRepository;
import org.hellos.app.heos.Progress;
import org.hellos.app.heos.SoundModeState;
import org.hellos.model.NowPlayingMedia;
import org.hellos.model.PlayMode;
import org.hellos.model.PlayState;
import org.hellos.model.PlayerInfo;
import org.hellos.model.RepeatMode;

import java.util.List;

/** Cover, title and playback controls of the selected player. Volume and input: {@link ControlBar}. */
public final class NowPlayingFragment extends Fragment {

    private static final float INACTIVE_ALPHA = 0.4f;
    /** Longest time the buttons stay locked if the device does not report the change. */
    private static final long LOCK_TIMEOUT_MS = 4000;
    private static final int LOCK_NONE = 0;
    /** Next/previous pressed: locked until the device reports the new title. */
    private static final int LOCK_TRACK = 1;
    /** Play/pause pressed: locked until the device reports the new play state. */
    private static final int LOCK_PLAY_STATE = 2;

    @Nullable
    private FragmentNowPlayingBinding binding;
    private HeosRepository repository;
    private int lock = LOCK_NONE;
    private final Runnable unlockAfterTimeout = () -> unlock(lock);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNowPlayingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentNowPlayingBinding b = binding;
        repository = HellosApplication.repository(requireContext());

        b.playPause.setOnClickListener(v -> {
            lock(LOCK_PLAY_STATE);
            repository.togglePlayPause();
        });
        b.previous.setOnClickListener(v -> {
            lock(LOCK_TRACK);
            repository.previous();
        });
        b.next.setOnClickListener(v -> {
            lock(LOCK_TRACK);
            repository.next();
        });
        b.shuffle.setOnClickListener(v -> repository.toggleShuffle());
        b.repeat.setOnClickListener(v -> repository.cycleRepeat());
        // read the mode again before showing the choice, it may have been changed with the remote
        b.soundMode.setOnClickListener(v -> repository.refreshSoundMode(this::showSoundModes));

        var owner = getViewLifecycleOwner();
        repository.selectedPlayer().observe(owner, this::renderPlayer);
        repository.nowPlaying().observe(owner, this::renderMedia);
        repository.playState().observe(owner, this::renderPlayState);
        repository.progress().observe(owner, this::renderProgress);
        repository.playMode().observe(owner, this::renderPlayMode);
        repository.soundMode().observe(owner, this::renderSoundMode);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding.getRoot().removeCallbacks(unlockAfterTimeout);
        lock = LOCK_NONE;
        binding = null;
    }

    /**
     * Locks play/pause, next and previous until the device reports the result, so a second tap
     * does not skip twice while the device is still switching.
     */
    private void lock(int reason) {
        if (binding == null) {
            return;
        }
        lock = reason;
        setTransportEnabled(false);
        binding.getRoot().removeCallbacks(unlockAfterTimeout);
        binding.getRoot().postDelayed(unlockAfterTimeout, LOCK_TIMEOUT_MS);
    }

    private void unlock(int reason) {
        if (binding == null || lock == LOCK_NONE || lock != reason) {
            return;
        }
        lock = LOCK_NONE;
        binding.getRoot().removeCallbacks(unlockAfterTimeout);
        setTransportEnabled(repository.selectedPlayer().getValue() != null);
    }

    private void setTransportEnabled(boolean enabled) {
        if (binding != null) {
            binding.playPause.setEnabled(enabled);
            binding.previous.setEnabled(enabled);
            binding.next.setEnabled(enabled);
        }
    }

    private void renderPlayer(@Nullable PlayerInfo player) {
        if (binding == null) {
            return;
        }
        boolean enabled = player != null;
        binding.shuffle.setEnabled(enabled);
        binding.repeat.setEnabled(enabled);
        setTransportEnabled(enabled && lock == LOCK_NONE);
        if (player == null) {
            binding.title.setText(R.string.no_player);
        }
    }

    private void renderMedia(@Nullable NowPlayingMedia media) {
        if (binding == null) {
            return;
        }
        unlock(LOCK_TRACK);
        if (media == null || media.isEmpty()) {
            binding.title.setText(R.string.nothing_playing);
            binding.artist.setText(null);
            binding.album.setText(null);
            CoverLoader.load(binding.cover, null, R.drawable.ic_music_note);
            return;
        }
        String station = media.isStation() ? media.station().filter(s -> !s.isBlank()).orElse(null) : null;
        if (station != null) {
            // radio: the station name is the title; below it the song and artist, if the device reports any
            binding.title.setText(station);
            binding.artist.setText(radioLine(media.song().orElse(null), station));
            binding.album.setText(radioLine(media.artist().orElse(null), station));
        } else {
            String title = media.song().filter(s -> !s.isBlank()).orElse(media.station().orElse(""));
            binding.title.setText(title);
            binding.artist.setText(media.artist().orElse(null));
            String album = media.album().orElse(null);
            binding.album.setText(album != null && !album.equals(title) ? album : null);
        }
        CoverLoader.load(binding.cover, media.imageUrl().orElse(null), R.drawable.ic_music_note);
    }

    /** A song or artist text of a radio station, or {@code null} if it only repeats the station or a URL. */
    @Nullable
    private static String radioLine(@Nullable String text, String station) {
        if (text == null || text.isBlank() || text.strip().equalsIgnoreCase(station.strip())) {
            return null;
        }
        String lower = text.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? null : text.strip();
    }

    private void renderPlayState(PlayState state) {
        unlock(LOCK_PLAY_STATE);
        if (binding != null) {
            binding.playPause.setIconResource(state == PlayState.PLAY ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private void renderProgress(Progress progress) {
        if (binding == null) {
            return;
        }
        int visibility = progress.hasDuration() ? View.VISIBLE : View.INVISIBLE;
        binding.progress.setVisibility(visibility);
        binding.position.setVisibility(visibility);
        binding.duration.setVisibility(visibility);
        if (progress.hasDuration()) {
            binding.progress.setMax((int) (progress.durationMs() / 1000));
            binding.progress.setProgressCompat((int) (progress.positionMs() / 1000), false);
            binding.position.setText(Formatting.time(progress.positionMs()));
            binding.duration.setText(Formatting.time(progress.durationMs()));
        }
    }

    private void renderPlayMode(PlayMode mode) {
        if (binding == null) {
            return;
        }
        binding.shuffle.setAlpha(mode.shuffle() ? 1f : INACTIVE_ALPHA);
        binding.repeat.setAlpha(mode.repeat() == RepeatMode.OFF ? INACTIVE_ALPHA : 1f);
        binding.repeat.setIconResource(mode.repeat() == RepeatMode.ON_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
    }

    private void renderSoundMode(@Nullable SoundModeState state) {
        if (binding == null) {
            return;
        }
        binding.soundMode.setVisibility(state == null ? View.GONE : View.VISIBLE);
        if (state != null) {
            binding.soundMode.setText(getString(R.string.sound_mode_button, SoundModeNames.label(state.current())));
            binding.soundMode.setEnabled(!state.busy());
        }
    }

    private void showSoundModes(SoundModeState state) {
        if (binding == null || !isAdded() || state.available().isEmpty()) {
            return;
        }
        List<String> modes = state.available();
        String[] labels = new String[modes.size()];
        for (int i = 0; i < modes.size(); i++) {
            labels[i] = SoundModeNames.label(modes.get(i));
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sound_mode_dialog_title)
                .setSingleChoiceItems(labels, modes.indexOf(state.current()), (dialog, which) -> {
                    dialog.dismiss();
                    if (!modes.get(which).equals(state.current())) {
                        repository.setSoundMode(modes.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
