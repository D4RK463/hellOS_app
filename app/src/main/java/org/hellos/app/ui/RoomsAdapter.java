// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.slider.Slider;

import org.hellos.app.R;
import org.hellos.app.databinding.ItemGroupBinding;
import org.hellos.app.databinding.ItemPlayerBinding;
import org.hellos.app.heos.Volume;
import org.hellos.model.GroupInfo;
import org.hellos.model.PlayerInfo;

/** Rows of the rooms screen: group cards and players. */
final class RoomsAdapter extends ListAdapter<RoomsAdapter.Row, RecyclerView.ViewHolder> {

    sealed interface Row permits GroupRow, PlayerRow {
        String key();

        /** Implemented by the records; declared here so DiffUtil compares contents. */
        @Override
        boolean equals(Object other);
    }

    /** @param volume {@code null} while not loaded yet */
    record GroupRow(GroupInfo group, @Nullable Volume volume) implements Row {
        @Override
        public String key() {
            return "g" + group.groupId();
        }
    }

    record PlayerRow(PlayerInfo player, boolean selected, boolean inGroup) implements Row {
        @Override
        public String key() {
            return "p" + player.playerId();
        }
    }

    interface Actions {
        void select(long playerId);

        void setGroupVolume(long groupId, int level);

        void toggleGroupMute(long groupId);

        void ungroup(long groupId);
    }

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_PLAYER = 1;

    private final Actions actions;

    RoomsAdapter(Actions actions) {
        super(new DiffUtil.ItemCallback<>() {
            @Override
            public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
                return a.key().equals(b.key());
            }

            @Override
            public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                return a.equals(b);
            }
        });
        this.actions = actions;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position) instanceof GroupRow ? TYPE_GROUP : TYPE_PLAYER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return viewType == TYPE_GROUP
                ? new GroupHolder(ItemGroupBinding.inflate(inflater, parent, false))
                : new PlayerHolder(ItemPlayerBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        switch (getItem(position)) {
            case GroupRow row -> ((GroupHolder) holder).bind(row);
            case PlayerRow row -> ((PlayerHolder) holder).bind(row);
        }
    }

    private final class GroupHolder extends RecyclerView.ViewHolder {
        private final ItemGroupBinding binding;
        private long groupId;
        private boolean dragging;

        GroupHolder(ItemGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.volume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                    dragging = true;
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    dragging = false;
                    actions.setGroupVolume(groupId, (int) slider.getValue());
                }
            });
            binding.mute.setOnClickListener(v -> actions.toggleGroupMute(groupId));
            binding.ungroup.setOnClickListener(v -> actions.ungroup(groupId));
        }

        void bind(GroupRow row) {
            GroupInfo group = row.group();
            groupId = group.groupId();
            Resources res = itemView.getResources();
            binding.name.setText(group.name());
            binding.members.setText(res.getQuantityString(R.plurals.rooms_group_members,
                    group.members().size(), group.members().size()));
            Volume volume = row.volume();
            binding.volume.setEnabled(volume != null);
            binding.mute.setEnabled(volume != null);
            if (volume != null) {
                if (!dragging) {
                    binding.volume.setValue(Math.max(0, Math.min(100, volume.level())));
                }
                binding.mute.setIconResource(volume.muted() ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
            }
            // tapping the card controls the group through its leader
            itemView.setOnClickListener(v -> group.leader().ifPresent(l -> actions.select(l.playerId())));
        }
    }

    private final class PlayerHolder extends RecyclerView.ViewHolder {
        private final ItemPlayerBinding binding;
        private final int basePaddingStart;

        PlayerHolder(ItemPlayerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            this.basePaddingStart = binding.getRoot().getPaddingStart();
        }

        void bind(PlayerRow row) {
            PlayerInfo player = row.player();
            binding.name.setText(player.name());
            binding.model.setText(player.model());
            binding.selected.setVisibility(row.selected() ? View.VISIBLE : View.INVISIBLE);
            View root = binding.getRoot();
            int indent = row.inGroup() ? root.getResources().getDimensionPixelSize(R.dimen.group_member_indent) : 0;
            root.setPaddingRelative(basePaddingStart + indent, root.getPaddingTop(), root.getPaddingEnd(),
                    root.getPaddingBottom());
            root.setOnClickListener(v -> actions.select(player.playerId()));
        }
    }
}
