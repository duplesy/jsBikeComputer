package com.example.jsBikeComputer;

import android.Manifest;
import android.os.Build;
import android.bluetooth.BluetoothDevice;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.jsBikeComputer.databinding.ActivityMainBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity implements DeviceAdapter.OnDeviceClickListener {
    private static final String TAG = "BikeComputerBLE";

    // UUIDs for FIT file transfer service
    private static final UUID FIT_SERVICE_UUID = UUID.fromString("00001106-0000-1000-8000-00805f9b34fb");
    private static final UUID FIT_DATA_CHAR_UUID = UUID.fromString("00002AF9-0000-1000-8000-00805f9b34fb");
    private static final UUID FIT_CONTROL_CHAR_UUID = UUID.fromString("00002ACA-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int COMPANION_DEVICE_REQUEST = 101;
    private static final byte[] START_TRANSFER = "START".getBytes();
    private static final byte[] NEXT_CHUNK = "NEXT".getBytes();
    private static final byte[] DELETE_FILE = "DELETE".getBytes();
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final Handler reconnectionHandler = new Handler(Looper.getMainLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    Handler rssiHandler = new Handler(Looper.getMainLooper());
    private Button scan_button;
    private TextView statusText;
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            updateStatus("Advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            String error = "Failed to start advertising: " + errorCode;
            updateStatus(error);
        }
    };
    private ProgressBar scanProgressBar;
    private ProgressBar transferProgress;
    private ImageView signalStrengthImageView;
    private List<BluetoothDevice> discoveredDevices;
    private DeviceAdapter deviceAdapter;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic controlCharacteristic;
    private static FileTransferState deviceTransfers;
    private boolean isServerRunning = false;
    private BluetoothPermissionHelper permissionHelper;

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
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    if (device != null && device.getAddress() != null)
                    {
                        deviceAdapter.addDevice(device);
                        updateRssiUI(rssi); // Update UI with RSSI value
                        statusText.setText("Device found: " + device.getName());
                    }
                }
                else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    if (device != null && device.getAddress() != null) {
                        scanProgressBar.setVisibility(View.GONE);
                        scan_button.setEnabled(true);
                        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                        statusText.setText("Scan complete. " + discoveredDevices.size() + " devices found.");
                        if(bondState == BluetoothDevice.BOND_BONDED) {
                            statusText.setText("Paired successfully. Connected...");
                        }
                        else if(bondState == BluetoothDevice.BOND_NONE) {
                            statusText.setText("Pairing failed. Please try again.");
                        }
                    }
                }
            } catch (SecurityException e) {
                handleBluetoothError("Permission denied during device discovery: " + e.getMessage());
            }
        }
    };
    // CompanionDeviceManager for Android 16+
    private final ActivityResultLauncher<Intent> bluetoothEnableLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    statusText.setText("Bluetooth enabled");
                    setupBluetooth();
                } else {
                    statusText.setText("Bluetooth is required for device scanning");
                    Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initializeViews();
        setupBluetooth();
    }

    private void initializeViews() {
        scan_button = findViewById(R.id.scan);
        statusText = findViewById(R.id.statusText);
        scanProgressBar = findViewById(R.id.scanProgress);
        transferProgress = findViewById(R.id.transferProgress);
        signalStrengthImageView = findViewById(R.id.signal_strength);
        RecyclerView deviceList = findViewById(R.id.deviceList);

        discoveredDevices = new ArrayList<>();
        deviceAdapter = new DeviceAdapter(discoveredDevices, this);
        deviceList.setLayoutManager(new LinearLayoutManager(this));
        deviceList.setAdapter(deviceAdapter);

        //scan_button.setOnClickListener(v -> startDeviceDiscovery());
        deviceTransfers = new FileTransferState();

        setupBottomNavigation();
    }

    private void setupBottomNavigation() {
        BottomNavigationView navView = findViewById(R.id.nav_view);
        navView.setSelectedItemId(R.id.navigation_devices);
        navView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.navigation_connections) {
                startActivity(new Intent(getApplicationContext(), ConnectionsActivity.class));
                overridePendingTransition(0, 0);
                return true;
            } else return item.getItemId() == R.id.navigation_devices;
        });
    }

    private void setupBluetooth() {
        permissionHelper = new BluetoothPermissionHelper(this);

        bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!permissionHelper.hasBluetoothPermissions()) {
            permissionHelper.requestBluetoothPermissions();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            bluetoothEnableLauncher.launch(enableBtIntent);
            return;
        }

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Toast.makeText(this, "BLE advertising not supported", Toast.LENGTH_LONG).show();
            finish();
        }

        registerBluetoothReceivers();
        startServer();
    }

    private void registerBluetoothReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(discoveryReceiver, filter);
    }

    private void startServer() {
        if (!isServerRunning) {
            startAdvertising();
            setupGattServer();
            isServerRunning = true;
        }
    }

    private void stopServer() {
        if (isServerRunning) {
            if (advertiser != null && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                advertiser.stopAdvertising(advertiseCallback);
            }
            if (gattServer != null) {
                gattServer.close();
                gattServer = null;
            }
            isServerRunning = false;
            updateStatus("Server stopped");
        }
    }

    private void setupGattServer() {
        BluetoothGattService service = new BluetoothGattService(
                FIT_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Data Characteristic
        BluetoothGattCharacteristic dataCharacteristic = new BluetoothGattCharacteristic(
                FIT_DATA_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY);

        // Control Characteristic
        controlCharacteristic = new BluetoothGattCharacteristic(
                FIT_CONTROL_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY);

        // Add descriptor to data characteristic
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CLIENT_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        dataCharacteristic.addDescriptor(descriptor);

        service.addCharacteristic(dataCharacteristic);
        service.addCharacteristic(controlCharacteristic);

        gattServer = bluetoothManager.openGattServer(this, gattServerCallback);
        if (gattServer != null) {
            gattServer.addService(service);
        } else {
            handleBluetoothError("Failed to create GATT server");
        }
    }


    @SuppressLint("SetTextI18n")
    public void onDeviceClick(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions();
            return;
        }
    }

    @SuppressLint("SetTextI18n")
    public void bondDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions();
            return;
        }

        if (device == null) {
            Log.e("BluetoothActivity", "Attempted to connect to null device");
            return;
        }
        if(device.getAddress() == null)
        {
            Log.e("BluetoothActivity", "Attempted to connect to null device");
            return;
        }

        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            statusText.setText("Pairing with " +
                    (device.getName() != null ? device.getName() : "Unknown Device") + "...");
            device.createBond();
        }
    }

    private void requestBluetoothPermissions() {
        permissionHelper.requestBluetoothPermissions();
    }

    private void startAdvertising() {
        if (advertiser == null) return;

        BluetoothAdapter.getDefaultAdapter().setName("JSBikeComputer");

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(FIT_SERVICE_UUID))
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void handleBluetoothError(String error) {
        Log.e("BluetoothActivity", error);
        runOnUiThread(() -> {
            statusText.setText(error);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            scan_button.setEnabled(true);
            scanProgressBar.setVisibility(View.GONE);
        });
    }

    private void startRssiUpdates(BluetoothDevice device) {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
            requestBluetoothPermissions();
            return;
        }

        Runnable rssiRunnable = new Runnable() {
            @Override
            public void run() {
                if (device != null && gattServer != null) {
                    try {
                        // Create a GATT client connection for RSSI readings
                        BluetoothGatt gattClient = device.connectGatt(MainActivity.this, false, new BluetoothGattCallback() {
                            @Override
                            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    updateRssiUI(rssi);
                                }
                                gatt.close(); // Close the client after reading RSSI
                            }
                        });
                        if (ActivityCompat.checkSelfPermission(MainActivity.this,
                                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            gattClient.readRemoteRssi();
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security Exception reading RSSI", e);
                    }
                }
                rssiHandler.postDelayed(this, 1000);
            }
        };
    }

    private void stopRssiUpdates() {
        rssiHandler.removeCallbacksAndMessages(null);
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @SuppressLint("SetTextI18n")
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String deviceAddress = device.getAddress();

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    gattServer.setPreferredPhy(device, BluetoothDevice.PHY_LE_1M, BluetoothDevice.PHY_LE_1M,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                }
                startRssiUpdates(device);
                discoveredDevices.add(device);
                updateStatus("Device connected: " + device.getName());
                bondDevice(device);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    statusText.setText("Delay completed!");
                    startFileTransfer(device);
                }, 10000); // 3 second delay
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                discoveredDevices.remove(device);
                //saveReceivedData(device);
                Log.e(TAG, "Device disconnected: " + deviceAddress);
                Log.e(TAG, "Disconnect status code: " + status); // Important to see error code

                updateStatus("Device disconnected: " + device.getName());
                handleDisconnection(device);
                fileTransferAbort(device);
                stopRssiUpdates();
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            Log.d(TAG, "onDescriptorWriteRequest: " + descriptor.getUuid().toString());

            if (ActivityCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No BLUETOOTH_CONNECT permission");
                return;
            }

            if (descriptor.getUuid().equals(CLIENT_CONFIG_UUID)) {
                Log.d(TAG, "Client configuration descriptor write request");

                // Check if this is enabling notifications
                if (value.length == 2 && value[0] == 0x01 && value[1] == 0x00) {
                    Log.d(TAG, "Client is enabling notifications");

                    // Store the notification status for this device
                    BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                    if (characteristic != null) {
                        Log.d(TAG, "For characteristic: " + characteristic.getUuid().toString());
                    }
                }
            }

            // IMPORTANT: Always send a response if responseNeeded is true
            if (responseNeeded) {
                Log.d(TAG, "Sending response to descriptor write");
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            deviceTransfers.transferInProgress = true;
            processFileData(device, requestId, value, characteristic);

            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }
    };

    @SuppressLint("SetTextI18n")
    private void handleDisconnection(BluetoothDevice device) {
        if (deviceTransfers.transferInProgress) {
            // Handle interrupted transfer
            fileTransferAbort(device);
        }

        if (retryCount.get() < MAX_RETRY_ATTEMPTS && gattServer != null) {
            int delay = (retryCount.get() + 1) * 2000;
            stopServer();
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            startServer();
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

    private long calculateReconnectDelay() {
        // Implement exponential backoff logic
        return 5000; // Start with 5 seconds
    }

    private void startFileTransfer(BluetoothDevice device) {
        if (!deviceTransfers.transferInProgress) {
            deviceTransfers.transferInProgress = true;
            sendControlRequest(device, START_TRANSFER);
        }
    }

    private void processFileData(BluetoothDevice device, int requestId, byte[] chunk, BluetoothGattCharacteristic characteristic) {
        if (!deviceTransfers.transferInProgress) return;
        if (characteristic.getUuid().equals(FIT_DATA_CHAR_UUID)) {
            processFileChunk(device, requestId, chunk, characteristic);
        } else if (characteristic.getUuid().equals(FIT_CONTROL_CHAR_UUID)) {
            // Handle control messages
            handleControlMessage(device, requestId, chunk);
        }

    }

    private void processFileChunk(BluetoothDevice device, int requestId, byte[] chunk, BluetoothGattCharacteristic characteristic) {

        // Handle FIT file data chunk
        try {
            // Check if this is the last chunk
            if (isLastChunk(chunk)) {
                // Process last chunk
                deviceTransfers.fileBuffer.append(new String(chunk, 0, chunk.length - 1));
                deviceTransfers.totalBytesReceived += chunk.length;
                completeFileTransfer(device);
            } else {
                // Append chunk data
                deviceTransfers.fileBuffer.append(new String(chunk));
                deviceTransfers.totalBytesReceived += chunk.length;
                if (deviceTransfers.expectedFileSize > 0) {
                    int progress = (int) ((deviceTransfers.totalBytesReceived * 100) /
                            deviceTransfers.expectedFileSize);
                    updateTransferProgress(progress);
                }
                sendControlRequest(device, NEXT_CHUNK);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing file chunk", e);
            deviceTransfers.transferInProgress = false;
        }
    }

    private void handleControlMessage(BluetoothDevice device, int requestId, byte[] value) {

        String message = new String(value);

        switch (message) {
            case "START":
                // Initialize new transfer
                deviceTransfers.transferInProgress = true;
                deviceTransfers.fileBuffer = new StringBuilder();
                deviceTransfers.totalBytesReceived = 0;
                deviceTransfers.retryCount = 0;
                updateStatus("Starting file transfer from " + device.getAddress());
                break;

            case "SIZE":
                // Get expected file size
                try {
                    deviceTransfers.expectedFileSize = Long.parseLong(new String(value, 4,
                            value.length - 4));
                    updateStatus("Expected file size: " + deviceTransfers.expectedFileSize + " bytes");
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid file size format", e);
                }
                break;

            case "RETRY":
                handleRetry(device);
                break;

            case "ABORT":
                fileTransferAbort(device);
                break;
        }
    }

    private boolean isLastChunk(byte[] chunk) {
        // Check for EOF marker - customize based on your ESP32 implementation
        return chunk[chunk.length - 1] == -1; // Example EOF marker
    }

    private void sendControlRequest(BluetoothDevice device, byte[] message) {
        BluetoothGattService service = gattServer.getService(FIT_SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic controlChar =
                    service.getCharacteristic(FIT_CONTROL_CHAR_UUID);
            if (controlChar != null &&
                    ActivityCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                controlChar.setValue(message);
                gattServer.notifyCharacteristicChanged(
                        device,
                        controlCharacteristic,
                        false  // false means no confirmation needed
                );
            }
        }
    }

    private void completeFileTransfer(BluetoothDevice device) {

        String completeFile = deviceTransfers.fileBuffer.toString();

        // Process the complete file
        processCompleteFile(completeFile);
        deviceTransfers.transferInProgress = false;
        deviceTransfers.fileBuffer = new StringBuilder();
        deviceTransfers.totalBytesReceived = 0;
        updateTransferProgress(0);
        sendControlRequest(device, DELETE_FILE);
    }

    private void processCompleteFile(String fileContent) {
        // For files in app-specific storage (no permissions needed)

        // Handle the complete file content here
        try {
            String filename = "downloaded_file_" + System.currentTimeMillis() + ".tcx";
            FileOutputStream fos = openFileOutput(filename, Context.MODE_PRIVATE);
            fos.write(fileContent.getBytes());
            fos.close();

            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "File downloaded successfully",
                        Toast.LENGTH_SHORT).show();
            });
        } catch (IOException e) {
            Log.e(TAG, "Error saving file", e);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "Error saving file: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void handleTransferError(BluetoothDevice device) {
        updateStatus("Transfer error occurred. Attempting retry...");
        handleRetry(device);
    }

    private void handleRetry(BluetoothDevice device) {
        if (deviceTransfers.retryCount < 3) {
            deviceTransfers.retryCount++;
            updateStatus("Retry attempt " + deviceTransfers.retryCount + " for " + device.getAddress());

            // Clear partial data and request resend from last successful point
            deviceTransfers.fileBuffer.setLength((int) deviceTransfers.totalBytesReceived);

        } else {
            // Too many retries, abort transfer
            fileTransferAbort(device);
        }
    }

    private void fileTransferAbort(BluetoothDevice device) {
        updateStatus("Transfer aborted for " + device.getAddress());
        deviceTransfers.transferInProgress = false;

        deviceTransfers.fileBuffer = new StringBuilder();
        deviceTransfers.totalBytesReceived = 0;
        updateTransferProgress(0);
    }

    private void updateStatus(String message) {
        mainHandler.post(() -> {
            Log.d(TAG, message);
            statusText.setText(message);
        });
    }

    protected void onDestroy() {
        super.onDestroy();
        stopRssiUpdates();
        if (isServerRunning) {
            stopServer();
        }
        unregisterReceiver(discoveryReceiver);
    }

    private void updateTransferProgress(int progress) {
        mainHandler.post(() -> {
            transferProgress.setProgress(progress);
            if (progress > 0 && progress < 100) {
                transferProgress.setVisibility(View.VISIBLE);
            } else {
                transferProgress.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (permissionHelper.handlePermissionResult(requestCode, permissions, grantResults)) {
            // All permissions granted, proceed with Bluetooth setup
            setupBluetooth();
        } else {
            // Permissions denied, show explanation and request again or exit
            if (permissionHelper.shouldShowPermissionRationale()) {
                permissionHelper.showPermissionRationale((dialog, which) ->
                        permissionHelper.requestBluetoothPermissions());
            } else {
                Toast.makeText(this, "Bluetooth permissions are required for this app to function",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // File transfer states
    private class FileTransferState {
        StringBuilder fileBuffer = new StringBuilder();
        boolean transferInProgress = false;
        int retryCount = 0;
        long totalBytesReceived = 0;
        long expectedFileSize = 0;

    }




}