package com.example.bikecomputer;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

public class BluetoothPermissionHelper {
    private final Context context;

    public BluetoothPermissionHelper(Context context) {
        this.context = context;
    }

    public boolean hasBluetoothPermissions() {
        return hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public boolean hasPermission(String permission) {
        return ActivityCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasBluetoothScanPermission() {
        return hasPermission(Manifest.permission.BLUETOOTH_SCAN);
    }

    public boolean hasBluetoothConnectPermission() {
        return hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
    }
}
