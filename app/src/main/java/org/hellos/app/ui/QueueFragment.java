// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.databinding.FragmentQueueBinding;
import org.hellos.app.databinding.ItemQueueBinding;
import org.hellos.app.heos.HeosRepository;
import org.hellos.model.NowPlayingMedia;
import org.hellos.model.PlayerInfo;
import org.hellos.model.QueueItem;

import java.util.List;
import java.util.function.IntConsumer;

/** The queue of the selected player: tap plays a title, swipe removes it. */
public final class QueueFragment extends Fragment {

    @Nullable
    private FragmentQueueBinding binding;
    private HeosRepository repository;
    private QueueAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentQueueBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentQueueBinding b = binding;
        repository = HellosApplication.repository(requireContext());
        adapter = new QueueAdapter(queueId -> repository.playQueueItem(queueId));
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    repository.removeFromQueue(adapter.getCurrentList().get(position).queueId());
                    // the item stays until the device reports the new queue
                    adapter.notifyItemChanged(position);
                }
            }
        }).attachToRecyclerView(b.list);

        b.clear.setOnClickListener(v -> confirmClear());

        var owner = getViewLifecycleOwner();
        repository.queue().observe(owner, this::renderQueue);
        repository.nowPlaying().observe(owner, this::renderNowPlaying);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void renderQueue(List<QueueItem> queue) {
        if (binding == null) {
            return;
        }
        adapter.submitList(queue);
        binding.empty.setVisibility(queue.isEmpty() ? View.VISIBLE : View.GONE);
        binding.clear.setEnabled(!queue.isEmpty());
        binding.count.setText(getResources().getQuantityString(R.plurals.queue_count, queue.size(), queue.size()));
    }

    private void renderNowPlaying(@Nullable NowPlayingMedia media) {
        adapter.setCurrentQueueId(media == null || media.queueId().isEmpty() ? -1 : media.queueId().getAsInt());
    }

    private void confirmClear() {
        PlayerInfo player = repository.selectedPlayer().getValue();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.queue_clear_title)
                .setMessage(getString(R.string.queue_clear_message, player == null ? "" : player.name()))
                .setPositiveButton(R.string.queue_clear, (dialog, which) -> repository.clearQueue())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static final class QueueAdapter extends ListAdapter<QueueItem, QueueAdapter.Holder> {

        private final IntConsumer onClick;
        private int currentQueueId = -1;

        QueueAdapter(IntConsumer onClick) {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull QueueItem a, @NonNull QueueItem b) {
                    return a.queueId() == b.queueId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull QueueItem a, @NonNull QueueItem b) {
                    return a.equals(b);
                }
            });
            this.onClick = onClick;
        }

        void setCurrentQueueId(int queueId) {
            if (queueId != currentQueueId) {
                currentQueueId = queueId;
                notifyItemRangeChanged(0, getItemCount());
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemQueueBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            QueueItem item = getItem(position);
            boolean current = item.queueId() == currentQueueId;
            holder.binding.song.setText(item.song());
            holder.binding.song.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            holder.binding.artist.setText(item.artist().orElse(null));
            holder.binding.playing.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            CoverLoader.load(holder.binding.cover, item.imageUrl().orElse(null), R.drawable.ic_music_note);
            holder.itemView.setOnClickListener(v -> onClick.accept(item.queueId()));
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemQueueBinding binding;

            Holder(ItemQueueBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
