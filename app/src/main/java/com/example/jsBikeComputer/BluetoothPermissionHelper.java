package com.example.jsBikeComputer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

public class BluetoothPermissionHelper {
    private final Context context;

    private static final String TAG = "BikeComputerBLE";

    private final Activity activity;
    private static final int PERMISSION_REQUEST_CODE = 1;

    private static final String[] BLUETOOTH_PERMISSIONS = {
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
    };

    public BluetoothPermissionHelper(Context context) {
        this.context = context;
        this.activity = (Activity) context;
    }

    public boolean hasBluetoothPermissions() {
        // Different checks based on Android version
        if (Build.VERSION.SDK_INT >= 34) { // Android 16/V
            return hasBluetoothScanPermission() &&
                    hasBluetoothConnectPermission() &&
                    hasBluetoothAdvertisePermission() &&
                    hasBluetoothLocationPermission();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            return hasBluetoothScanPermission() &&
                    hasBluetoothConnectPermission() &&
                    hasBluetoothAdvertisePermission();
        } else {
            return hasPermission(Manifest.permission.BLUETOOTH) &&
                    hasPermission(Manifest.permission.BLUETOOTH_ADMIN) &&
                    hasBluetoothLocationPermission();
        }
    }

    public boolean hasPermission(String permission) {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            // On older versions, BLE scanning requires location permission
            return hasBluetoothLocationPermission();
        }
    }

    public boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            return hasPermission(Manifest.permission.BLUETOOTH);
        }
    }

    public boolean hasBluetoothAdvertisePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            return hasPermission(Manifest.permission.BLUETOOTH_ADMIN);
        }
    }

    public boolean hasBluetoothLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public boolean handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    public boolean shouldShowPermissionRationale() {
        String[] permissions = getRequiredPermissions();
        for (String permission : permissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                return true;
            }
        }
        return false;
    }

    public void showPermissionRationale(DialogInterface.OnClickListener listener) {
        new AlertDialog.Builder(activity)
                .setTitle("Bluetooth Permissions Required")
                .setMessage("This app needs Bluetooth permissions to scan for and connect to your bike computer. These permissions allow the app to transfer workout data from your device.")
                .setPositiveButton("Grant Permission", listener)
                .setNegativeButton("Cancel", (dialog, which) -> activity.finish())
                .create()
                .show();
    }

    public void requestBluetoothPermissions() {
        String[] permissions = getRequiredPermissions();
        Log.d(TAG, "Requesting permissions for Android " + Build.VERSION.SDK_INT);
        for (String permission : permissions) {
            Log.d(TAG, "Requesting: " + permission);
        }
        ActivityCompat.requestPermissions(activity, permissions, PERMISSION_REQUEST_CODE);
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 34) { // Android 16
            // Add new Android 16 specific permissions if any
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else { // Older versions
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        return permissions.toArray(new String[0]);
    }
}
