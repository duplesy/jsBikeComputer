package com.example.jsBikeComputer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.jsBikeComputer.databinding.ActivityMainBinding;
import com.example.jsBikeComputer.R;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BluetoothActivity extends AppCompatActivity implements DeviceAdapter.OnDeviceClickListener {

    private Button scan_button;
    private TextView statusText;
    private ProgressBar scanProgressBar;
    private ImageView signalStrengthImageView;
    private List<BluetoothDevice> discoveredDevices;
    private DeviceAdapter deviceAdapter;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothPermissionHelper permissionHelper;
    private BluetoothGatt bluetoothGatt;
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final Handler reconnectionHandler = new Handler(Looper.getMainLooper());


    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initializeViews();
        setupBluetooth();
        registerBluetoothReceivers();
    }

    private void initializeViews() {
        scan_button = findViewById(R.id.scan);
        statusText = findViewById(R.id.statusText);
        signalStrengthImageView = findViewById(R.id.signal_strength);
        RecyclerView deviceList = findViewById(R.id.deviceList);
        scanProgressBar = findViewById(R.id.scanProgress);

        BottomNavigationView navView = findViewById(R.id.nav_view);
        navView.setSelectedItemId(R.id.navigation_devices);

        discoveredDevices = new ArrayList<>();
        deviceAdapter = new DeviceAdapter(discoveredDevices, this);
        deviceList.setLayoutManager(new LinearLayoutManager(this));
        deviceList.setAdapter(deviceAdapter);

        scan_button.setOnClickListener(v -> startDeviceDiscovery());

        navView.setOnItemSelectedListener(item -> {
            if(item.getItemId() == R.id.navigation_connections) {
                startActivity(new Intent(getApplicationContext(), ConnectionsActivity.class));
                overridePendingTransition(0, 0);
                return true;
            } else if(item.getItemId() == R.id.navigation_devices) {
                return true;
            }
            return false;
        });
    }

   
    private void setupBluetooth() {
        permissionHelper = new BluetoothPermissionHelper(this);

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                bluetoothEnableLauncher.launch(enableBtIntent);
            } else {
                requestBluetoothPermissions();
            }
        }
    }

    private void registerBluetoothReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(discoveryReceiver, filter);
    }

    @SuppressLint("SetTextI18n")
    private void startDeviceDiscovery() 
    {
        scan_button.setEnabled(false);
        scanProgressBar.setVisibility(View.VISIBLE);
        statusText.setText("Scanning for devices...");
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onDeviceClick(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions();
            return;
        }

        if (device == null) {
            Log.e("BluetoothActivity", "Attempted to connect to null device");
            return;
        }

        bluetoothAdapter.cancelDiscovery();

        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            statusText.setText("Pairing with " +
                    (device.getName() != null ? device.getName() : "Unknown Device") + "...");
            device.createBond();
            registerPairingReceiver(device);
        } else {
            connectToDevice(device);
        }
    }

    private void requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                },
                REQUEST_BLUETOOTH_PERMISSIONS);
    }

    private void registerPairingReceiver(final BluetoothDevice deviceToPair) {
        if (deviceToPair == null || deviceToPair.getAddress() == null) {
            Log.e("BluetoothActivity", "Invalid device for pairing");
            return;
        }

        BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device != null && device.getAddress() != null &&
                            deviceToPair.getAddress().equals(device.getAddress())) {
                        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                        handleBondStateChange(device, bondState);
                    }
                }
            }
        };
        registerReceiver(pairingReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    @SuppressLint("SetTextI18n")
    private void handleBondStateChange(BluetoothDevice device, int bondState) {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
            requestBluetoothPermissions();
            return;
        }

        try {
            switch (bondState) {
                case BluetoothDevice.BOND_BONDED:
                    statusText.setText("Paired successfully. Connecting...");
                    connectToDevice(device);
                    break;
                case BluetoothDevice.BOND_NONE:
                    statusText.setText("Pairing failed. Please try again.");
                    break;
            }
        } catch (SecurityException e) {
            handleBluetoothError("Permission denied during bonding: " + e.getMessage());
        }
    }

    @SuppressLint("SetTextI18n")
    private void connectToDevice(BluetoothDevice device) {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
            requestBluetoothPermissions();
            return;
        }

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
            statusText.setText("Connecting to " + device.getName() + "...");
        } catch (SecurityException e) {
            handleBluetoothError("Permission denied while connecting: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("SetTextI18n")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> {
                    statusText.setText("Connected to device");
                    retryCount.set(0);
                });
                startRssiUpdates();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleDisconnection();
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> updateRssiUI(rssi));
            }
        }
    };

    @SuppressLint("SetTextI18n")
    private final ActivityResultLauncher<Intent> bluetoothEnableLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    statusText.setText("Bluetooth enabled");
                    startDeviceDiscovery();
                } else {
                    statusText.setText("Bluetooth is required for device scanning");
                    Toast.makeText(this, "Bluetooth is required for this app to function",
                            Toast.LENGTH_LONG).show();
                }
            }
    );

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!permissionHelper.hasBluetoothConnectPermission()) {
                requestBluetoothPermissions();
                return;
            }

            try {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device != null && device.getName() != null && !discoveredDevices.contains(device)) {
                        deviceAdapter.addDevice(device);
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    scanProgressBar.setVisibility(View.GONE);
                    scan_button.setEnabled(true);
                    statusText.setText("Scan complete. " + discoveredDevices.size() + " devices found.");
                }
            } catch (SecurityException e) {
                handleBluetoothError("Permission denied during device discovery: " + e.getMessage());
            }
        }
    };

    private void handleBluetoothError(String error) {
        Log.e("BluetoothActivity", error);
        runOnUiThread(() -> {
            statusText.setText(error);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            scan_button.setEnabled(true);
            scanProgressBar.setVisibility(View.GONE);
        });
    }


    private void startRssiUpdates() {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
            requestBluetoothPermissions();
            return;
        }

        Handler rssiHandler = new Handler(Looper.getMainLooper());
        Runnable rssiRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (bluetoothGatt != null && ActivityCompat.checkSelfPermission(
                        BluetoothActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt.readRemoteRssi();
                        // Check connection state using non-deprecated method
                        if (bluetoothGatt != null &&
                                bluetoothGatt.connect()) {  // This implicitly checks if we're still connected
                            rssiHandler.postDelayed(this, 1000);
                        }
                    }
                } catch (SecurityException e) {
                    handleBluetoothError("Permission denied while reading RSSI: " + e.getMessage());
                }
            }
        };
        rssiHandler.postDelayed(rssiRunnable, 1000);
    }

    @SuppressLint("SetTextI18n")
    private void handleDisconnection() {
        if (retryCount.get() < MAX_RETRY_ATTEMPTS && bluetoothGatt != null) {
            int delay = (retryCount.get() + 1) * 2000;
            runOnUiThread(() -> statusText.setText("Connection lost. Retrying in "
                    + (delay / 1000) + " seconds..."));
            reconnectionHandler.postDelayed(this::retryConnection, delay);
            retryCount.incrementAndGet();
        } else {
            runOnUiThread(() -> {
                statusText.setText("Connection failed after " + MAX_RETRY_ATTEMPTS + " attempts");
                scan_button.setEnabled(true);
            });
        }
    }

    private void retryConnection() {
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.connect();
            }
        }
    }
    
    @SuppressLint("SetTextI18n")
    private void updateRssiUI(int rssi) {
        String signalStrength;
        int signalIcon;

        if (rssi > -50) {
            signalStrength = "Excellent";
            signalIcon = R.drawable.ic_signal_3;
        } else if (rssi > -70) {
            signalStrength = "Good";
            signalIcon = R.drawable.ic_signal_2;
        } else if (rssi > -90) {
            signalStrength = "Fair";
            signalIcon = R.drawable.ic_signal_1;
        } else {
            signalStrength = "Poor";
            signalIcon = R.drawable.ic_signal_0;
        }

        signalStrengthImageView.setImageResource(signalIcon);
        statusText.setText("Signal Strength: " + signalStrength + " (" + rssi + " dBm)");
    }
}