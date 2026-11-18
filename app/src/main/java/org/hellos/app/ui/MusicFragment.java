// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.databinding.FragmentMusicBinding;
import org.hellos.app.databinding.ItemMediaBinding;
import org.hellos.app.heos.ConnectionStatus;
import org.hellos.app.heos.FolderProgress;
import org.hellos.app.heos.HeosRepository;
import org.hellos.model.AddCriteria;
import org.hellos.model.BrowseResult;
import org.hellos.model.MediaItem;
import org.hellos.model.MediaType;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Browses the local media servers (DLNA/UPnP, USB) through HEOS. Tapping a folder opens it, tapping
 * a title plays it; the menu of a title or album adds it to the queue.
 */
public final class MusicFragment extends Fragment {

    /** Load the next page when the user scrolls this close to the end. */
    private static final int PREFETCH_DISTANCE = 20;

    @Nullable
    private FragmentMusicBinding binding;
    private HeosRepository repository;
    private MusicViewModel model;
    private MediaAdapter adapter;
    private LinearLayoutManager layoutManager;
    private OnBackPressedCallback backCallback;
    /** The saved scroll position is restored only on the first render of a new view. */
    private boolean scrollRestored;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMusicBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentMusicBinding b = binding;
        repository = HellosApplication.repository(requireContext());
        model = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);

        adapter = new MediaAdapter(this::onItemClicked, this::showItemMenu);
        layoutManager = new LinearLayoutManager(requireContext());
        b.list.setLayoutManager(layoutManager);
        b.list.setAdapter(adapter);
        b.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                MusicViewModel.Level level = model.levels().peek();
                if (level != null && !level.complete && !level.loading && level.error == null
                        && layoutManager.findLastVisibleItemPosition() >= level.items.size() - PREFETCH_DISTANCE) {
                    load(level);
                }
            }
        });
        b.up.setOnClickListener(v -> goUp());
        b.folderActions.shrink();
        b.folderActions.setOnClickListener(v -> setFolderMenuOpen(b.playAll.getVisibility() != View.VISIBLE));
        b.playAll.setOnClickListener(v -> playFolder(true));
        b.addAll.setOnClickListener(v -> playFolder(false));
        repository.folderProgress().observe(getViewLifecycleOwner(), this::renderFolderProgress);
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                goUp();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        repository.connection().observe(getViewLifecycleOwner(), this::onConnection);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MusicViewModel.Level level = model.levels().peek();
        if (level != null) {
            level.scrollState = layoutManager.onSaveInstanceState();
        }
        binding = null;
    }

    /** Starts at the server list on a new device, reloads a level that failed while offline. */
    private void onConnection(ConnectionStatus status) {
        if (!Objects.equals(status.host(), model.host())) {
            model.setHost(status.host());
            model.levels().clear();
        }
        if (model.levels().isEmpty()) {
            model.levels().push(MusicViewModel.Level.servers(getString(R.string.music_servers)));
        }
        MusicViewModel.Level level = Objects.requireNonNull(model.levels().peek());
        if (status.state() == ConnectionStatus.State.CONNECTED
                && (level.error != null || (level.items.isEmpty() && !level.complete && !level.loading))) {
            level.reset();
            load(level);
        }
        render(scrollRestored ? null : level.scrollState);
        scrollRestored = true;
    }

    // ---- navigation ----------------------------------------------------------------------------

    private void onItemClicked(MediaItem item) {
        MusicViewModel.Level current = Objects.requireNonNull(model.levels().peek());
        MusicViewModel.Level next = null;
        if (item.isSource() && item.sourceId().isPresent()) {
            next = new MusicViewModel.Level(item.name(), item.sourceId().getAsLong(), null);
        } else if (item.container() && item.containerId().isPresent()) {
            next = new MusicViewModel.Level(item.name(), current.sourceId, item.containerId().get());
        } else if (item.playable()) {
            play(item, AddCriteria.PLAY_NOW);
        }
        if (next != null) {
            current.scrollState = layoutManager.onSaveInstanceState();
            model.levels().push(next);
            load(next);
            render(null);
        }
    }

    private void goUp() {
        if (model.levels().size() > 1) {
            model.levels().pop();
            MusicViewModel.Level level = Objects.requireNonNull(model.levels().peek());
            render(level.scrollState);
        }
    }

    private void showItemMenu(MediaItem item, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.menu_media_item);
        menu.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            AddCriteria criteria = id == R.id.action_play_next ? AddCriteria.PLAY_NEXT
                    : id == R.id.action_add_to_end ? AddCriteria.ADD_TO_END
                    : id == R.id.action_replace ? AddCriteria.REPLACE_AND_PLAY
                    : AddCriteria.PLAY_NOW;
            play(item, criteria);
            return true;
        });
        menu.show();
    }

    /** The two folder actions open upwards above the + button, which turns into ×. */
    private void setFolderMenuOpen(boolean open) {
        if (binding == null) {
            return;
        }
        binding.playAll.setVisibility(open ? View.VISIBLE : View.GONE);
        binding.addAll.setVisibility(open ? View.VISIBLE : View.GONE);
        binding.folderActions.setIconResource(open ? R.drawable.ic_close : R.drawable.ic_add);
    }

    /** Whole folder, see {@link HeosRepository#playFolder}. */
    private void playFolder(boolean replace) {
        setFolderMenuOpen(false);
        MusicViewModel.Level level = Objects.requireNonNull(model.levels().peek());
        if (level.containerId != null) {
            repository.playFolder(level.sourceId, level.containerId, replace);
        }
    }

    /** While a folder is added the button is locked and shows the progress. */
    private void renderFolderProgress(@Nullable FolderProgress progress) {
        if (binding == null) {
            return;
        }
        if (progress == null) {
            binding.folderActions.setEnabled(true);
            binding.folderActions.setText(null);
            binding.folderActions.setIconResource(R.drawable.ic_add);
            binding.folderActions.shrink();
            MusicViewModel.Level level = model.levels().peek();
            binding.folderFabGroup.setVisibility(level != null && hasTitles(level) ? View.VISIBLE : View.GONE);
        } else {
            binding.folderFabGroup.setVisibility(View.VISIBLE);
            setFolderMenuOpen(false);
            binding.folderActions.setEnabled(false);
            binding.folderActions.setText(progress.total() == 0
                    ? getString(R.string.music_adding_unknown)
                    : getString(R.string.music_adding, progress.done(), progress.total()));
            binding.folderActions.extend();
        }
    }

    /** A folder of a server that contains at least one playable title. */
    private static boolean hasTitles(MusicViewModel.Level level) {
        if (level.isServerList() || level.containerId == null) {
            return false;
        }
        for (MediaItem item : level.items) {
            if (item.playable() && !item.container()) {
                return true;
            }
        }
        return false;
    }

    private void play(MediaItem item, AddCriteria criteria) {
        MusicViewModel.Level level = Objects.requireNonNull(model.levels().peek());
        repository.play(level.sourceId, level.containerId, item, criteria);
    }

    // ---- loading -------------------------------------------------------------------------------

    private void load(MusicViewModel.Level level) {
        level.loading = true;
        level.error = null;
        Consumer<BrowseResult> onPage = page -> {
            level.loading = false;
            level.items.addAll(page.items());
            level.count = page.count();
            // top levels come complete; folders are paged until the count is reached or a page is empty
            level.complete = level.isServerList() || level.containerId == null || page.items().isEmpty()
                    || (page.count() > 0 && level.items.size() >= page.count());
            renderIfShown(level);
        };
        Consumer<String> onError = error -> {
            level.loading = false;
            level.error = error;
            renderIfShown(level);
        };
        if (level.isServerList()) {
            repository.browseServers(onPage, onError);
        } else {
            repository.browse(level.sourceId, level.containerId, level.items.size(), onPage, onError);
        }
        renderIfShown(level);
    }

    private void renderIfShown(MusicViewModel.Level level) {
        if (binding != null && model.levels().peek() == level) {
            render(null);
        }
    }

    private void render(@Nullable Parcelable scrollState) {
        if (binding == null) {
            return;
        }
        MusicViewModel.Level level = model.levels().peek();
        if (level == null) {
            return;
        }
        boolean nested = model.levels().size() > 1;
        backCallback.setEnabled(nested);
        binding.up.setVisibility(nested ? View.VISIBLE : View.GONE);
        binding.location.setText(level.title);
        binding.loading.setVisibility(level.loading ? View.VISIBLE : View.INVISIBLE);
        adapter.submitList(new ArrayList<>(level.items), () -> {
            if (scrollState != null) {
                layoutManager.onRestoreInstanceState(scrollState);
            }
        });

        String emptyText = null;
        if (level.error != null) {
            emptyText = getString(R.string.music_error, level.error);
        } else if (level.items.isEmpty() && level.complete) {
            emptyText = getString(level.isServerList() ? R.string.music_no_servers : R.string.music_empty_folder);
        }
        // also shown while adding, so the progress stays visible when the user browses on
        boolean folderActions = hasTitles(level) || repository.folderProgress().getValue() != null;
        if (!folderActions) {
            setFolderMenuOpen(false);
        }
        binding.folderFabGroup.setVisibility(folderActions ? View.VISIBLE : View.GONE);
        // keep the last rows reachable above the button
        binding.list.setPadding(0, 0, 0, folderActions
                ? getResources().getDimensionPixelSize(R.dimen.fab_list_padding) : 0);

        binding.empty.setText(emptyText);
        binding.empty.setVisibility(emptyText != null && level.items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---- list ----------------------------------------------------------------------------------

    private static final class MediaAdapter extends ListAdapter<MediaItem, MediaAdapter.Holder> {

        private static final float DISABLED_ALPHA = 0.4f;

        private final Consumer<MediaItem> onClick;
        private final BiConsumer<MediaItem, View> onMore;

        MediaAdapter(Consumer<MediaItem> onClick, BiConsumer<MediaItem, View> onMore) {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull MediaItem a, @NonNull MediaItem b) {
                    return a.containerId().equals(b.containerId()) && a.mediaId().equals(b.mediaId())
                            && a.sourceId().equals(b.sourceId()) && a.name().equals(b.name());
                }

                @Override
                public boolean areContentsTheSame(@NonNull MediaItem a, @NonNull MediaItem b) {
                    return a.equals(b);
                }
            });
            this.onClick = onClick;
            this.onMore = onMore;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemMediaBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MediaItem item = getItem(position);
            ItemMediaBinding b = holder.binding;
            b.name.setText(item.name());
            String detail = item.artist().orElse(item.album().orElse(null));
            b.detail.setText(detail);
            b.detail.setVisibility(detail == null || detail.isBlank() ? View.GONE : View.VISIBLE);

            boolean browsable = item.isSource() || item.container();
            int placeholder = item.isSource() ? R.drawable.ic_server
                    : item.type() == MediaType.SONG ? R.drawable.ic_music_note
                    : R.drawable.ic_folder;
            CoverLoader.load(b.cover, item.imageUrl().orElse(null), placeholder);

            // titles and playable albums/folders get the queue menu, other folders a chevron
            boolean queueable = item.playable() && !item.isSource();
            b.more.setVisibility(queueable ? View.VISIBLE : View.GONE);
            b.open.setVisibility(!queueable && browsable ? View.VISIBLE : View.GONE);
            b.more.setOnClickListener(v -> onMore.accept(item, v));
            // e.g. video files on a DLNA server: listed by HEOS, but not playable
            holder.itemView.setAlpha(browsable || item.playable() ? 1f : DISABLED_ALPHA);
            holder.itemView.setOnClickListener(v -> onClick.accept(item));
            holder.itemView.setOnLongClickListener(v -> {
                if (queueable) {
                    onMore.accept(item, b.more);
                    return true;
                }
                return false;
            });
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemMediaBinding binding;

            Holder(ItemMediaBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
