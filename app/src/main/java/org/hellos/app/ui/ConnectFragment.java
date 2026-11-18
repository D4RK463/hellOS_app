// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.hellos.app.HellosApplication;
import org.hellos.app.R;
import org.hellos.app.databinding.FragmentConnectBinding;
import org.hellos.app.databinding.ItemDeviceBinding;
import org.hellos.app.heos.DeviceDiscovery;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Choose the HEOS device: found by discovery or entered by IP address. */
public final class ConnectFragment extends Fragment {

    @Nullable
    private FragmentConnectBinding binding;
    private DeviceAdapter adapter;
    private boolean searching;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentConnectBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentConnectBinding b = binding();
        String saved = HellosApplication.repository(requireContext()).savedHost();
        if (saved != null && savedInstanceState == null) {
            b.host.setText(saved);
        }
        adapter = new DeviceAdapter(device -> choose(device.host()));
        b.devices.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.devices.setAdapter(adapter);

        b.connect.setOnClickListener(v -> connectToEnteredHost());
        b.host.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectToEnteredHost();
                return true;
            }
            return false;
        });
        b.search.setOnClickListener(v -> search());
        search();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private FragmentConnectBinding binding() {
        if (binding == null) {
            throw new IllegalStateException("view not created");
        }
        return binding;
    }

    private void connectToEnteredHost() {
        Editable text = binding().host.getText();
        String host = text == null ? "" : text.toString().trim();
        if (host.isEmpty()) {
            binding().hostLayout.setError(getString(R.string.connect_host_empty));
            return;
        }
        binding().hostLayout.setError(null);
        choose(host);
    }

    private void choose(String host) {
        ((MainActivity) requireActivity()).onDeviceChosen(host);
    }

    private void search() {
        if (searching) {
            return;
        }
        searching = true;
        adapter.clear();
        binding().searching.setVisibility(View.VISIBLE);
        binding().devicesEmpty.setVisibility(View.GONE);
        binding().search.setEnabled(false);
        new DeviceDiscovery(requireContext()).search(
                device -> {
                    if (binding != null) {
                        adapter.add(device);
                    }
                },
                error -> {
                    searching = false;
                    if (binding == null) {
                        return;
                    }
                    binding.searching.setVisibility(View.GONE);
                    binding.search.setEnabled(true);
                    if (adapter.getItemCount() == 0) {
                        binding.devicesEmpty.setText(error == null
                                ? getString(R.string.discovery_empty)
                                : getString(R.string.discovery_failed, String.valueOf(error.getMessage())));
                        binding.devicesEmpty.setVisibility(View.VISIBLE);
                    }
                });
    }

    private static final class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

        private final List<DeviceDiscovery.FoundDevice> devices = new ArrayList<>();
        private final Consumer<DeviceDiscovery.FoundDevice> onClick;

        DeviceAdapter(Consumer<DeviceDiscovery.FoundDevice> onClick) {
            this.onClick = onClick;
        }

        void add(DeviceDiscovery.FoundDevice device) {
            for (DeviceDiscovery.FoundDevice known : devices) {
                if (known.host().equals(device.host())) {
                    return;
                }
            }
            devices.add(device);
            notifyItemInserted(devices.size() - 1);
        }

        void clear() {
            int count = devices.size();
            devices.clear();
            notifyItemRangeRemoved(0, count);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            DeviceDiscovery.FoundDevice device = devices.get(position);
            holder.binding.name.setText(device.name() != null ? device.name() : device.host());
            holder.binding.detail.setText(device.model() != null
                    ? device.model() + " · " + device.host()
                    : device.host());
            holder.itemView.setOnClickListener(v -> onClick.accept(device));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemDeviceBinding binding;

            Holder(ItemDeviceBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
