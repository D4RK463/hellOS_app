// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.databinding.ActivityMainBinding;
import org.hellos.app.heos.ConnectionStatus;
import org.hellos.app.heos.HeosRepository;
import org.hellos.model.PlayerInfo;

import java.util.List;
import java.util.Objects;

/**
 * Single activity: the connect screen, or the tabs Now playing, Music, Queue and Rooms with the
 * {@link ControlBar} (volume, input) below them. The toolbar shows the selected player and the
 * connection state.
 */
public final class MainActivity extends AppCompatActivity {

    private static final String CONNECT_TAG = "connect";

    private ActivityMainBinding binding;
    private HeosRepository repository;
    private ControlBar controlBar;
    @Nullable
    private Snackbar connectionSnackbar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        repository = HellosApplication.repository(this);

        controlBar = new ControlBar(binding.controlBar, repository, this);
        applyInsets();
        binding.bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateChrome);

        if (savedInstanceState == null) {
            if (repository.savedHost() == null) {
                showConnect();
            } else {
                showTab(R.id.tab_now_playing);
            }
        }

        repository.connection().observe(this, this::renderConnection);
        repository.selectedPlayer().observe(this, player -> {
            binding.toolbar.setTitle(player == null ? getString(R.string.app_name) : player.name());
            invalidateOptionsMenu();
        });
        repository.players().observe(this, players -> invalidateOptionsMenu());
        repository.messages().observe(this, oneShot -> {
            String text = oneShot.take();
            if (text != null) {
                snackbar(text, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    /** Keeps the content clear of the navigation bar and keyboard when the bottom bar is hidden. */
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.container, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            boolean bottomBarVisible = binding.bottomNav.getVisibility() == View.VISIBLE;
            view.setPadding(bars.left, 0, bars.right, bottomBarVisible ? 0 : bars.bottom);
            return insets;
        });
    }

    // ---- navigation ----------------------------------------------------------------------------

    private void showTab(@IdRes int tabId) {
        FragmentManager fragments = getSupportFragmentManager();
        Fragment current = fragments.findFragmentById(R.id.container);
        Fragment next;
        if (tabId == R.id.tab_music) {
            next = current instanceof MusicFragment ? null : new MusicFragment();
        } else if (tabId == R.id.tab_queue) {
            next = current instanceof QueueFragment ? null : new QueueFragment();
        } else if (tabId == R.id.tab_rooms) {
            next = current instanceof RoomsFragment ? null : new RoomsFragment();
        } else {
            next = current instanceof NowPlayingFragment ? null : new NowPlayingFragment();
        }
        if (next != null) {
            fragments.beginTransaction().replace(R.id.container, next).commit();
            fragments.executePendingTransactions();
        }
        if (binding.bottomNav.getSelectedItemId() != tabId) {
            binding.bottomNav.getMenu().findItem(tabId).setChecked(true);
        }
        updateChrome();
    }

    private void showConnect() {
        FragmentManager fragments = getSupportFragmentManager();
        if (fragments.findFragmentById(R.id.container) instanceof ConnectFragment) {
            return;
        }
        var transaction = fragments.beginTransaction().replace(R.id.container, new ConnectFragment());
        if (repository.savedHost() != null) {
            // a device is known: back returns to the tabs
            transaction.addToBackStack(CONNECT_TAG);
        }
        transaction.commit();
        fragments.executePendingTransactions();
        updateChrome();
    }

    /** Called by {@link ConnectFragment} when the user picked a device. */
    void onDeviceChosen(String host) {
        repository.connect(host);
        // opened from the menu: back to the tabs; first start: showTab replaces the connect screen
        getSupportFragmentManager().popBackStackImmediate(CONNECT_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        showTab(R.id.tab_now_playing);
    }

    private boolean isConnectShown() {
        return getSupportFragmentManager().findFragmentById(R.id.container) instanceof ConnectFragment;
    }

    private void updateChrome() {
        boolean connect = isConnectShown();
        binding.bottomNav.setVisibility(connect ? View.GONE : View.VISIBLE);
        controlBar.root().setVisibility(connect ? View.GONE : View.VISIBLE);
        ViewCompat.requestApplyInsets(binding.container);
        invalidateOptionsMenu();
    }

    // ---- toolbar -------------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean connect = isConnectShown();
        List<PlayerInfo> players = Objects.requireNonNullElse(repository.players().getValue(), List.of());
        menu.findItem(R.id.action_select_player).setVisible(!connect && players.size() > 1);
        menu.findItem(R.id.action_change_device).setVisible(!connect);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_select_player) {
            showPlayerChooser();
            return true;
        }
        if (item.getItemId() == R.id.action_change_device) {
            showConnect();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showPlayerChooser() {
        List<PlayerInfo> players = Objects.requireNonNullElse(repository.players().getValue(), List.of());
        PlayerInfo selected = repository.selectedPlayer().getValue();
        String[] names = new String[players.size()];
        int checked = -1;
        for (int i = 0; i < players.size(); i++) {
            names[i] = players.get(i).name();
            if (selected != null && players.get(i).playerId() == selected.playerId()) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_player_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    repository.selectPlayer(players.get(which).playerId());
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderConnection(ConnectionStatus status) {
        String host = status.host();
        String text = switch (status.state()) {
            case NO_DEVICE -> null;
            case CONNECTING -> getString(R.string.status_connecting, host);
            case CONNECTED -> getString(R.string.status_connected, host);
            case RECONNECTING -> getString(R.string.status_reconnecting);
            case DISCONNECTED -> getString(R.string.status_disconnected);
            case FAILED -> getString(R.string.status_failed, host);
        };
        binding.toolbar.setSubtitle(text);

        if (connectionSnackbar != null) {
            connectionSnackbar.dismiss();
            connectionSnackbar = null;
        }
        if (status.state() == ConnectionStatus.State.FAILED && !isConnectShown()) {
            String message = status.error() == null ? text : text + "\n" + status.error();
            connectionSnackbar = snackbar(message, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.action_retry, v -> repository.reconnect());
            connectionSnackbar.show();
        }
    }

    private Snackbar snackbar(String text, int duration) {
        Snackbar snackbar = Snackbar.make(binding.container, text, duration);
        if (controlBar.root().getVisibility() == View.VISIBLE) {
            snackbar.setAnchorView(controlBar.root());
        }
        return snackbar;
    }

    // ---- hardware volume keys control the selected player ------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && repository.volumeStep(true)) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && repository.volumeStep(false)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
