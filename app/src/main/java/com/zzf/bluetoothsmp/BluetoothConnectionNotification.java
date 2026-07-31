package com.zzf.bluetoothsmp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Per-device notification used when a background connection must not open a chat screen. */
public final class BluetoothConnectionNotification {
    private static final String CHANNEL_ID = "bluetooth_incoming_connection";
    private static final int BASE_ID = 2700;

    private BluetoothConnectionNotification() {
    }

    public static void show(Context context, String deviceName, String address, String uuid) {
        if (context == null || address == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        createChannel(appContext);
        String displayName = deviceName == null || deviceName.trim().isEmpty()
                ? address : deviceName;
        Intent launch = new Intent(appContext, MainActivity.class)
                .setAction("com.zzf.bluetoothsmp.action.OPEN_INCOMING_CONNECTION")
                .putExtra("incomingBluetoothName", displayName)
                .putExtra("incomingBluetoothAdd", address)
                .putExtra("incomingBluetoothUuid", uuid)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(appContext,
                notificationId(address), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_bluetooth)
                .setContentTitle(appContext.getString(R.string.incoming_connection_title))
                .setContentText(appContext.getString(R.string.incoming_connection_text, displayName))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        appContext.getString(R.string.incoming_connection_text, displayName)))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            NotificationManagerCompat.from(appContext).notify(notificationId(address), builder.build());
        } catch (SecurityException ignored) {
            // Android 13+ may deny POST_NOTIFICATIONS; the connection itself remains active.
        }
    }

    private static int notificationId(String address) {
        String compact = address.replace(":", "");
        if (compact.length() >= 6) {
            try {
                return BASE_ID + Integer.parseInt(
                        compact.substring(compact.length() - 6), 16);
            } catch (NumberFormatException ignored) {
                // Fall through to a bounded hash for non-standard addresses.
            }
        }
        return BASE_ID + (address.hashCode() & 0x3FFFFFFF);
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.incoming_connection_title),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.incoming_connection_text, "Bluetooth"));
        manager.createNotificationChannel(channel);
    }
}
