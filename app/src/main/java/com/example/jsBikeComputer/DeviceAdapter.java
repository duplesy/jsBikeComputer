// DeviceAdapter.java
package com.example.bikecomputer;

import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.Manifest;
import com.example.jsBikeComputer.R;
import androidx.core.app.ActivityCompat;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Context;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;


public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {
    private List<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, Integer> deviceRssiMap;
    private final OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    public DeviceAdapter(List<BluetoothDevice> devices, OnDeviceClickListener listener) {
        this.listener = listener;
        this.devices = devices;
        this.deviceRssiMap = new HashMap<>();
    }

    public void addDevice(BluetoothDevice device) {
        if (!devices.contains(device)) {
            devices.add(device);
            notifyItemInserted(devices.size() - 1);
        }
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.device_item, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        BluetoothDevice result = devices.get(position);
        holder.bind(result);
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public void updateDevices(List<BluetoothDevice> newDevices) {
        devices = new ArrayList<>(newDevices);
        notifyDataSetChanged();
    }

    class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView nameText;
        private final TextView addressText;
        private final TextView rssiText;
        private final ProgressBar signalStrength;
        private int rssi;
        final ImageView signalIcon;

        DeviceViewHolder(View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.device_name);
            addressText = itemView.findViewById(R.id.device_address);
            rssiText = itemView.findViewById(R.id.rssi_text);
            signalStrength = itemView.findViewById(R.id.signal_strength);
            signalIcon = itemView.findViewById(R.id.signal_icon);
        }

        void bind(BluetoothDevice device) {
            if (ActivityCompat.checkSelfPermission(itemView.getContext(), 
            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            {
                String name = device.getName();
                nameText.setText(name != null ? name : "Unknown Device");
                addressText.setText(device.getAddress());
                
                rssi = deviceRssiMap.get(device);
                rssiText.setText(rssi + " dBm");
                
                // Convert RSSI to signal strength percentage
                int signalPercentage = Math.min(100, Math.max(0, (rssi + 100) * 2));
                signalStrength.setProgress(signalPercentage);

                itemView.setOnClickListener(v -> listener.onDeviceClick(device));
            }
        }
    }

    private void updateSignalUI(@NonNull DeviceViewHolder holder, int rssi) {
        int signalTextResId;
        int signalIconRes;

        if (rssi >= -50) {
            signalTextResId = R.string.signal_excellent;
            signalIconRes = R.drawable.ic_signal_3;
        } else if (rssi >= -60) {
            signalTextResId = R.string.signal_good;
            signalIconRes = R.drawable.ic_signal_2;
        } else if (rssi >= -70) {
            signalTextResId = R.string.signal_fair;
            signalIconRes = R.drawable.ic_signal_1;
        } else {
            signalTextResId = R.string.signal_poor;
            signalIconRes = R.drawable.ic_signal_0;
        }

        holder.signalIcon.setImageResource(signalIconRes);
    }
}