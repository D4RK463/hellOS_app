// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.databinding.FragmentRoomsBinding;
import org.hellos.app.heos.HeosRepository;
import org.hellos.app.heos.Volume;
import org.hellos.model.GroupInfo;
import org.hellos.model.PlayerInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** All players and groups: select a player, group and ungroup players, group volume. */
public final class RoomsFragment extends Fragment {

    @Nullable
    private FragmentRoomsBinding binding;
    private HeosRepository repository;
    private RoomsAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRoomsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentRoomsBinding b = binding;
        repository = HellosApplication.repository(requireContext());
        adapter = new RoomsAdapter(new RoomsAdapter.Actions() {
            @Override
            public void select(long playerId) {
                repository.selectPlayer(playerId);
            }

            @Override
            public void setGroupVolume(long groupId, int level) {
                repository.setGroupVolume(groupId, level);
            }

            @Override
            public void toggleGroupMute(long groupId) {
                repository.toggleGroupMute(groupId);
            }

            @Override
            public void ungroup(long groupId) {
                repository.ungroup(groupId);
            }
        });
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.groupPlayers.setOnClickListener(v -> showGroupDialog());

        var owner = getViewLifecycleOwner();
        repository.players().observe(owner, ignored -> render());
        repository.groups().observe(owner, ignored -> render());
        repository.groupVolumes().observe(owner, ignored -> render());
        repository.selectedPlayer().observe(owner, ignored -> render());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /** Groups first, each followed by its members; then the players that are not grouped. */
    private void render() {
        if (binding == null) {
            return;
        }
        List<PlayerInfo> players = Objects.requireNonNullElse(repository.players().getValue(), List.of());
        List<GroupInfo> groups = Objects.requireNonNullElse(repository.groups().getValue(), List.of());
        Map<Long, Volume> volumes = Objects.requireNonNullElse(repository.groupVolumes().getValue(), Map.of());
        PlayerInfo selected = repository.selectedPlayer().getValue();
        long selectedId = selected == null ? Long.MIN_VALUE : selected.playerId();

        List<RoomsAdapter.Row> rows = new ArrayList<>();
        Set<Long> grouped = new HashSet<>();
        for (GroupInfo group : groups) {
            rows.add(new RoomsAdapter.GroupRow(group, volumes.get(group.groupId())));
            for (long memberId : group.playerIds()) {
                for (PlayerInfo player : players) {
                    if (player.playerId() == memberId) {
                        rows.add(new RoomsAdapter.PlayerRow(player, memberId == selectedId, true));
                        grouped.add(memberId);
                    }
                }
            }
        }
        for (PlayerInfo player : players) {
            if (!grouped.contains(player.playerId())) {
                rows.add(new RoomsAdapter.PlayerRow(player, player.playerId() == selectedId, false));
            }
        }
        adapter.submitList(rows);
        binding.empty.setVisibility(players.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showGroupDialog() {
        List<PlayerInfo> players = Objects.requireNonNullElse(repository.players().getValue(), List.of());
        if (players.size() < 2) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.rooms_group_players)
                    .setMessage(R.string.rooms_group_need_devices)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        PlayerInfo selected = repository.selectedPlayer().getValue();
        String[] names = new String[players.size()];
        boolean[] checked = new boolean[players.size()];
        for (int i = 0; i < players.size(); i++) {
            names[i] = players.get(i).name();
            checked[i] = selected != null && players.get(i).playerId() == selected.playerId();
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rooms_group_dialog_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.rooms_group_players, (dialog, which) -> {
                    List<Long> ids = new ArrayList<>();
                    for (int i = 0; i < players.size(); i++) {
                        if (checked[i]) {
                            long id = players.get(i).playerId();
                            // the selected player leads the group, so its music keeps playing
                            if (selected != null && id == selected.playerId()) {
                                ids.add(0, id);
                            } else {
                                ids.add(id);
                            }
                        }
                    }
                    if (ids.size() < 2) {
                        Toast.makeText(requireContext(), R.string.rooms_group_need_two, Toast.LENGTH_SHORT).show();
                    } else {
                        repository.createGroup(ids);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
