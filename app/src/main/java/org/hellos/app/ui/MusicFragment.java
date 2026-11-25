// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.databinding.FragmentMusicBinding;
import org.hellos.app.databinding.ItemHeaderBinding;
import org.hellos.app.databinding.ItemHintBinding;
import org.hellos.app.databinding.ItemMediaBinding;
import org.hellos.app.heos.ConnectionStatus;
import org.hellos.app.heos.FolderProgress;
import org.hellos.app.heos.HeosRepository;
import org.hellos.app.radio.RadioRepository;
import org.hellos.app.radio.Station;
import org.hellos.model.AddCriteria;
import org.hellos.model.BrowseResult;
import org.hellos.model.MediaItem;
import org.hellos.model.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The music tab. The start page shows the radio favorites, the station search and the local media
 * servers (DLNA/UPnP, USB) as seen by HEOS. Tapping a folder opens it, tapping a title or station
 * plays it; the menu of a title or album adds it to the queue, the star keeps a station.
 */
public final class MusicFragment extends Fragment {

    /** Load the next page when the user scrolls this close to the end. */
    private static final int PREFETCH_DISTANCE = 20;
    /** Search while typing after this pause, to keep the requests to radio-browser.info low. */
    private static final long SEARCH_DELAY_MS = 600;
    private static final int SEARCH_MIN_LENGTH = 2;

    @Nullable
    private FragmentMusicBinding binding;
    private HeosRepository repository;
    private RadioRepository radio;
    private MusicViewModel model;
    private RowAdapter adapter;
    private LinearLayoutManager layoutManager;
    private OnBackPressedCallback backCallback;
    /** The saved scroll position is restored only on the first render of a new view. */
    private boolean scrollRestored;
    /** Set while the search text is changed by the app, not by the user. */
    private boolean settingSearchText;
    private final Runnable delayedSearch = () -> search(false);

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
        radio = HellosApplication.radio(requireContext());
        model = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);

        adapter = new RowAdapter(this::onRowClicked, this::showItemMenu, this::toggleFavorite);
        layoutManager = new LinearLayoutManager(requireContext());
        b.list.setLayoutManager(layoutManager);
        b.list.setAdapter(adapter);
        b.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                MusicViewModel.Level level = model.levels().peek();
                if (level != null && level.kind == MusicViewModel.Kind.FOLDER && !level.complete
                        && !level.loading && level.error == null
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
        b.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!settingSearchText) {
                    b.search.removeCallbacks(delayedSearch);
                    b.search.postDelayed(delayedSearch, SEARCH_DELAY_MS);
                }
            }
        });
        b.search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                b.search.removeCallbacks(delayedSearch);
                search(true);
                hideKeyboard();
                return true;
            }
            return false;
        });
        repository.folderProgress().observe(getViewLifecycleOwner(), this::renderFolderProgress);
        radio.favorites().observe(getViewLifecycleOwner(), favorites -> render(null));
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
        if (binding != null) {
            binding.search.removeCallbacks(delayedSearch);
        }
        binding = null;
    }

    /** Starts at the start page on a new device, reloads a level that failed while offline. */
    private void onConnection(ConnectionStatus status) {
        if (!Objects.equals(status.host(), model.host())) {
            model.setHost(status.host());
            model.levels().clear();
        }
        if (model.levels().isEmpty()) {
            model.levels().push(MusicViewModel.Level.root(getString(R.string.tab_music)));
        }
        MusicViewModel.Level level = Objects.requireNonNull(model.levels().peek());
        if (status.state() == ConnectionStatus.State.CONNECTED && level.kind != MusicViewModel.Kind.RADIO_SEARCH
                && (level.error != null || (level.items.isEmpty() && !level.complete && !level.loading))) {
            level.reset();
            load(level);
        }
        if (!scrollRestored) {
            showSearchText(level);
        }
        render(scrollRestored ? null : level.scrollState);
        scrollRestored = true;
    }

    // ---- navigation ----------------------------------------------------------------------------

    private void onRowClicked(Row row) {
        if (row instanceof Row.Media media) {
            onItemClicked(media.item());
        } else if (row instanceof Row.StationRow station) {
            repository.playStation(station.station(), () -> radio.countClick(station.station()));
        } else if (row instanceof Row.SearchEntry) {
            open(MusicViewModel.Level.radioSearch(getString(R.string.radio_search)));
            if (binding != null) {
                binding.search.requestFocus();
                InputMethodManager input = requireContext().getSystemService(InputMethodManager.class);
                input.showSoftInput(binding.search, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void onItemClicked(MediaItem item) {
        MusicViewModel.Level current = Objects.requireNonNull(model.levels().peek());
        if (item.isSource() && item.sourceId().isPresent()) {
            open(new MusicViewModel.Level(item.name(), item.sourceId().getAsLong(), null));
        } else if (item.container() && item.containerId().isPresent()) {
            open(new MusicViewModel.Level(item.name(), current.sourceId, item.containerId().get()));
        } else if (item.playable()) {
            play(item, AddCriteria.PLAY_NOW);
        }
    }

    private void open(MusicViewModel.Level next) {
        MusicViewModel.Level current = Objects.requireNonNull(model.levels().peek());
        current.scrollState = layoutManager.onSaveInstanceState();
        model.levels().push(next);
        if (next.kind != MusicViewModel.Kind.RADIO_SEARCH) {
            load(next);
        }
        showSearchText(next);
        render(null);
    }

    private void goUp() {
        if (model.levels().size() > 1) {
            MusicViewModel.Level left = model.levels().pop();
            if (left.kind == MusicViewModel.Kind.RADIO_SEARCH) {
                radio.cancelSearch();
                hideKeyboard();
            }
            MusicViewModel.Level level = Objects.requireNonNull(model.levels().peek());
            showSearchText(level);
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
        if (level.kind != MusicViewModel.Kind.FOLDER || level.containerId == null) {
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

    // ---- radio ---------------------------------------------------------------------------------

    private void toggleFavorite(Station station) {
        if (!radio.isFavorite(station)) {
            radio.addFavorite(station, -1);
            return;
        }
        int position = radio.removeFavorite(station);
        if (binding != null) {
            Snackbar.make(binding.getRoot(), getString(R.string.radio_favorite_removed, station.name()),
                            Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_undo, v -> radio.addFavorite(station, position))
                    .show();
        }
    }

    /**
     * Searches with the text of the search field. While typing ({@code submitted == false}) only
     * from {@link #SEARCH_MIN_LENGTH} characters on and only if the text changed.
     */
    private void search(boolean submitted) {
        MusicViewModel.Level level = model.levels().peek();
        if (binding == null || level == null || level.kind != MusicViewModel.Kind.RADIO_SEARCH) {
            return;
        }
        String query = Objects.toString(binding.search.getText(), "").strip();
        if (query.isEmpty() || (!submitted && (query.length() < SEARCH_MIN_LENGTH || query.equals(level.query)))) {
            if (query.isEmpty()) {
                radio.cancelSearch();
                level.query = "";
                level.reset();
                render(null);
            }
            return;
        }
        level.query = query;
        level.loading = true;
        level.error = null;
        radio.search(query, stations -> {
            level.reset();
            level.stations.addAll(stations);
            level.complete = true;
            renderIfShown(level, true);
        }, error -> {
            level.loading = false;
            level.error = error;
            renderIfShown(level, false);
        });
        render(null);
    }

    /** Shows the search field with the text of the level, without starting a search. */
    private void showSearchText(MusicViewModel.Level level) {
        if (binding == null) {
            return;
        }
        boolean searchLevel = level.kind == MusicViewModel.Kind.RADIO_SEARCH;
        binding.searchLayout.setVisibility(searchLevel ? View.VISIBLE : View.GONE);
        settingSearchText = true;
        binding.search.setText(searchLevel ? level.query : "");
        settingSearchText = false;
    }

    private void hideKeyboard() {
        if (binding != null) {
            InputMethodManager input = requireContext().getSystemService(InputMethodManager.class);
            input.hideSoftInputFromWindow(binding.search.getWindowToken(), 0);
        }
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
            level.complete = level.kind == MusicViewModel.Kind.ROOT || level.containerId == null
                    || page.items().isEmpty() || (page.count() > 0 && level.items.size() >= page.count());
            renderIfShown(level, false);
        };
        Consumer<String> onError = error -> {
            level.loading = false;
            level.error = error;
            renderIfShown(level, false);
        };
        if (level.kind == MusicViewModel.Kind.ROOT) {
            repository.browseServers(onPage, onError);
        } else {
            repository.browse(level.sourceId, level.containerId, level.items.size(), onPage, onError);
        }
        renderIfShown(level, false);
    }

    private void renderIfShown(MusicViewModel.Level level, boolean scrollToTop) {
        if (binding != null && model.levels().peek() == level) {
            render(null);
            if (scrollToTop) {
                layoutManager.scrollToPosition(0);
            }
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

        List<Row> rows = new ArrayList<>();
        String emptyText = null;
        switch (level.kind) {
            case ROOT -> addRootRows(level, rows);
            case FOLDER -> {
                for (MediaItem item : level.items) {
                    rows.add(new Row.Media(item));
                }
                if (level.error != null) {
                    emptyText = getString(R.string.music_error, level.error);
                } else if (level.items.isEmpty() && level.complete) {
                    emptyText = getString(R.string.music_empty_folder);
                }
            }
            case RADIO_SEARCH -> {
                for (Station station : level.stations) {
                    rows.add(new Row.StationRow(station, radio.isFavorite(station)));
                }
                if (level.error != null) {
                    emptyText = getString(R.string.radio_search_error, level.error);
                } else if (level.query.isEmpty()) {
                    emptyText = getString(R.string.radio_search_intro);
                } else if (level.stations.isEmpty() && level.complete) {
                    emptyText = getString(R.string.radio_no_stations);
                }
            }
        }
        adapter.submitList(rows, () -> {
            if (scrollState != null) {
                layoutManager.onRestoreInstanceState(scrollState);
            }
        });

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
        binding.empty.setVisibility(emptyText != null && rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Radio favorites, the station search and the media servers. */
    private void addRootRows(MusicViewModel.Level level, List<Row> rows) {
        rows.add(new Row.Header(getString(R.string.radio_favorites)));
        List<Station> favorites = Objects.requireNonNullElse(radio.favorites().getValue(), List.of());
        if (favorites.isEmpty()) {
            rows.add(new Row.Hint(getString(R.string.radio_no_favorites)));
        }
        for (Station station : favorites) {
            rows.add(new Row.StationRow(station, true));
        }
        rows.add(new Row.SearchEntry(getString(R.string.radio_search)));

        rows.add(new Row.Header(getString(R.string.music_servers)));
        for (MediaItem item : level.items) {
            rows.add(new Row.Media(item));
        }
        if (level.error != null) {
            rows.add(new Row.Hint(getString(R.string.music_error, level.error)));
        } else if (level.items.isEmpty() && level.complete) {
            rows.add(new Row.Hint(getString(R.string.music_no_servers)));
        }
    }

    // ---- list ----------------------------------------------------------------------------------

    /** One row of the list. */
    private sealed interface Row {
        record Header(String text) implements Row { }

        record Hint(String text) implements Row { }

        record Media(MediaItem item) implements Row { }

        record StationRow(Station station, boolean favorite) implements Row { }

        record SearchEntry(String text) implements Row { }
    }

    private static final class RowAdapter extends ListAdapter<Row, RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_HINT = 1;
        private static final int TYPE_ITEM = 2;
        private static final float DISABLED_ALPHA = 0.4f;

        private final Consumer<Row> onClick;
        private final BiConsumer<MediaItem, View> onMore;
        private final Consumer<Station> onFavorite;

        RowAdapter(Consumer<Row> onClick, BiConsumer<MediaItem, View> onMore,
                   Consumer<Station> onFavorite) {
            super(new DiffUtil.ItemCallback<>() {
                // the rows are records, lint does not see their equals()
                @SuppressLint("DiffUtilEquals")
                @Override
                public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
                    if (a instanceof Row.Media x && b instanceof Row.Media y) {
                        MediaItem p = x.item();
                        MediaItem q = y.item();
                        return p.containerId().equals(q.containerId()) && p.mediaId().equals(q.mediaId())
                                && p.sourceId().equals(q.sourceId()) && p.name().equals(q.name());
                    }
                    if (a instanceof Row.StationRow x && b instanceof Row.StationRow y) {
                        return x.station().uuid().equals(y.station().uuid());
                    }
                    return a.equals(b);
                }

                @SuppressLint("DiffUtilEquals")
                @Override
                public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.equals(b);
                }
            });
            this.onClick = onClick;
            this.onMore = onMore;
            this.onFavorite = onFavorite;
        }

        @Override
        public int getItemViewType(int position) {
            Row row = getItem(position);
            return row instanceof Row.Header ? TYPE_HEADER : row instanceof Row.Hint ? TYPE_HINT : TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return switch (viewType) {
                case TYPE_HEADER -> new Holder(ItemHeaderBinding.inflate(inflater, parent, false).getRoot());
                case TYPE_HINT -> new Holder(ItemHintBinding.inflate(inflater, parent, false).getRoot());
                default -> new ItemHolder(ItemMediaBinding.inflate(inflater, parent, false));
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = getItem(position);
            if (row instanceof Row.Header header) {
                ((TextView) holder.itemView).setText(header.text());
            } else if (row instanceof Row.Hint hint) {
                ((TextView) holder.itemView).setText(hint.text());
            } else {
                ItemMediaBinding b = ((ItemHolder) holder).binding;
                holder.itemView.setAlpha(1f);
                holder.itemView.setOnClickListener(v -> onClick.accept(row));
                holder.itemView.setOnLongClickListener(null);
                b.more.setVisibility(View.GONE);
                b.favorite.setVisibility(View.GONE);
                b.open.setVisibility(View.GONE);
                b.tag.setVisibility(View.GONE);
                switch (row) {
                    case Row.Media media -> bindMedia(b, holder, media.item());
                    case Row.StationRow station -> bindStation(b, station);
                    case Row.SearchEntry entry -> {
                        setText(b, entry.text(), null);
                        b.cover.setImageResource(R.drawable.ic_search);
                        b.cover.setTag(null);
                        b.open.setVisibility(View.VISIBLE);
                    }
                    default -> { }
                }
            }
        }

        private void bindMedia(ItemMediaBinding b, RecyclerView.ViewHolder holder, MediaItem item) {
            setText(b, item.name(), item.artist().orElse(item.album().orElse(null)));
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
            holder.itemView.setOnLongClickListener(v -> {
                if (queueable) {
                    onMore.accept(item, b.more);
                    return true;
                }
                return false;
            });
        }

        private void bindStation(ItemMediaBinding b, Row.StationRow row) {
            Station station = row.station();
            setText(b, station.name(), station.detail());
            CoverLoader.load(b.cover, station.favicon(), R.drawable.ic_radio);
            b.tag.setText(station.formatTag());
            b.tag.setVisibility(View.VISIBLE);
            b.favorite.setVisibility(View.VISIBLE);
            b.favorite.setIconResource(row.favorite() ? R.drawable.ic_star : R.drawable.ic_star_border);
            b.favorite.setContentDescription(b.getRoot().getContext().getString(
                    row.favorite() ? R.string.radio_favorite_remove : R.string.radio_favorite_add));
            b.favorite.setOnClickListener(v -> onFavorite.accept(station));
        }

        private static void setText(ItemMediaBinding b, String name, @Nullable String detail) {
            b.name.setText(name);
            b.detail.setText(detail);
            b.detail.setVisibility(detail == null || detail.isBlank() ? View.GONE : View.VISIBLE);
        }

        private static final class Holder extends RecyclerView.ViewHolder {
            Holder(View view) {
                super(view);
            }
        }

        private static final class ItemHolder extends RecyclerView.ViewHolder {
            final ItemMediaBinding binding;

            ItemHolder(ItemMediaBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
