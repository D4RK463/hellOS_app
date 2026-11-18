// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import org.hellos.app.R;
import org.hellos.app.databinding.ViewControlBarBinding;
import org.hellos.app.heos.HeosRepository;
import org.hellos.app.heos.InputChoice;
import org.hellos.app.heos.Volume;
import org.hellos.model.PlayerInfo;

import java.util.List;

/** Volume, mute and input of the selected player; shown above the bottom navigation on all tabs. */
final class ControlBar {

    private final ViewControlBarBinding binding;
    private final HeosRepository repository;
    private final Context context;
    private boolean draggingVolume;

    ControlBar(ViewControlBarBinding binding, HeosRepository repository, LifecycleOwner owner) {
        this.binding = binding;
        this.repository = repository;
        this.context = binding.getRoot().getContext();

        binding.mute.setOnClickListener(v -> repository.toggleMute());
        binding.input.setOnClickListener(v -> repository.loadInputs(this::showInputs));
        binding.volume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                draggingVolume = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                draggingVolume = false;
                repository.setVolume((int) slider.getValue());
            }
        });

        repository.selectedPlayer().observe(owner, this::renderPlayer);
        repository.volume().observe(owner, this::renderVolume);
    }

    View root() {
        return binding.getRoot();
    }

    private void renderPlayer(@Nullable PlayerInfo player) {
        boolean enabled = player != null;
        binding.mute.setEnabled(enabled);
        binding.volume.setEnabled(enabled);
        binding.input.setEnabled(enabled);
    }

    private void renderVolume(Volume volume) {
        if (!draggingVolume) {
            binding.volume.setValue(Math.max(0, Math.min(100, volume.level())));
        }
        binding.mute.setIconResource(volume.muted() ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
    }

    private void showInputs(List<InputChoice> inputs) {
        if (inputs.isEmpty() || !binding.getRoot().isAttachedToWindow()) {
            return;
        }
        String[] labels = new String[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            labels[i] = inputs.get(i).label();
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.input_dialog_title)
                .setItems(labels, (dialog, which) -> repository.playInput(inputs.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
