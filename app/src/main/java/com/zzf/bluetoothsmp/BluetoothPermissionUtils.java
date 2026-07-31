package com.zzf.bluetoothsmp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Centralizes the runtime permissions required by the classic SPP app. */
public final class BluetoothPermissionUtils {
    private BluetoothPermissionUtils() {
    }

    public static boolean hasRequiredPermissions(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
                    && isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
                    && isGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return true;
    }

    public static boolean hasScanPermission(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isGranted(context, Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return true;
    }

    public static boolean hasConnectPermission(Context context) {
        return context != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || isGranted(context, Manifest.permission.BLUETOOTH_CONNECT));
    }

    public static boolean hasAdvertisePermission(Context context) {
        return context != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || isGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE));
    }

    public static boolean hasServerPermissions(Context context) {
        return hasConnectPermission(context) && hasAdvertisePermission(context);
    }

    public static String[] scanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[0];
    }

    public static String[] connectPermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_CONNECT} : new String[0];
    }

    public static String[] serverPermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE} : new String[0];
    }

    private static boolean isGranted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
