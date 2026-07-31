package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;

/** Keeps the SPP listener and reconnect coordinator alive while the app is backgrounded. */
public class BluetoothConnectionForegroundService extends Service {
    private static final String TAG = "BluetoothFgService";
    private static final String CHANNEL_ID = "bluetooth_connection";
    private static final int NOTIFICATION_ID = 2401;
    private static final String ACTION_START = "com.zzf.bluetoothsmp.action.START_CONNECTION_SERVICE";
    private static final String ACTION_STOP = "com.zzf.bluetoothsmp.action.STOP_CONNECTION_SERVICE";
    private static final String ACTION_DISCONNECT =
            "com.zzf.bluetoothsmp.action.DISCONNECT_CONNECTIONS";

    public static void start(Context context) {
        Intent intent = new Intent(context, BluetoothConnectionForegroundService.class)
                .setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start Bluetooth foreground service", error);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, BluetoothConnectionForegroundService.class)
                .setAction(ACTION_STOP);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            StaticObject.stopBluetoothService();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException error) {
            // Android may reject foreground promotion because of a revoked
            // permission, an unavailable service type, or background-start
            // policy. Do not let that exception crash the host process.
            Log.e(TAG, "Unable to promote Bluetooth service to foreground", error);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        StaticObject.ensureMessageDispatcher();
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            StaticObject.closeAllConnections(true);
            return START_STICKY;
        }
        restoreBluetoothRuntimeIfNeeded();
        return START_STICKY;
    }

    @SuppressLint({"MissingPermission", "HardwareIds"})
    private void restoreBluetoothRuntimeIfNeeded() {
        // The Activity owns initialization while visible. After process recreation,
        // this service must restore the listener and reconnect coordinator itself.
        if (MyApplication.isAppInForeground()) {
            return;
        }
        if (!BluetoothPermissionUtils.hasConnectPermission(this)) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        try {
            StaticObject.myBluetoothName = adapter.getName();
            if (StaticObject.myBluetoothName == null
                    || StaticObject.myBluetoothName.trim().isEmpty()) {
                StaticObject.myBluetoothName = adapter.getAddress();
            }
            StaticObject.myBluetoothAdd = adapter.getAddress();
            StaticObject.ensureMessageMonitor();
            StaticObject.ensureBluetoothService(getApplicationContext(), adapter);
            StaticObject.reconnectManager.restorePendingConnections();
        } catch (SecurityException | java.io.IOException error) {
            Log.e(TAG, "Unable to restore Bluetooth runtime", error);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        StaticObject.stopBluetoothService();
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent disconnectIntent = new Intent(this, BluetoothConnectionForegroundService.class)
                .setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getService(this, 2402,
                disconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_bluetooth)
                .setContentTitle(getString(R.string.connection_service_notification_title))
                .setContentText(getString(R.string.connection_service_notification_text))
                .setContentIntent(pendingIntent)
                .addAction(new NotificationCompat.Action.Builder(
                        R.drawable.icon_bluetooth,
                        getString(R.string.connection_service_disconnect),
                        disconnectPendingIntent).build())
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.connection_service_notification_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.connection_service_notification_text));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
