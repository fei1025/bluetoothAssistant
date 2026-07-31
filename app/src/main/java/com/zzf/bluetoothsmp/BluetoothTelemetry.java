package com.zzf.bluetoothsmp;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Privacy-safe Firebase and Crashlytics integration for the Bluetooth lifecycle.
 *
 * <p>No address, UUID value, payload, device serial number, or user file content
 * is sent through this class. Payload traffic is represented only by counters
 * in a session summary.</p>
 */
public final class BluetoothTelemetry {
    private static final String TAG = "BluetoothTelemetry";
    private static final String DEFAULT_SPP_UUID = BluetoothObject.SPP_UUID;

    private static volatile FirebaseAnalytics analytics;
    private static volatile FirebaseCrashlytics crashlytics;

    private BluetoothTelemetry() {
    }

    public static void initialize(Context context) {
        if (context == null) {
            return;
        }
        try {
            analytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
            crashlytics = FirebaseCrashlytics.getInstance();
        } catch (RuntimeException error) {
            Log.w(TAG, "Firebase telemetry is unavailable", error);
        }
    }

    public static void logConnectAttempt(String mode, boolean bonded, String uuid,
                                         boolean autoReconnect, int attempt) {
        Bundle params = new Bundle();
        params.putString("mode", safeMode(mode));
        params.putBoolean("device_bonded", bonded);
        params.putString("uuid_type", uuidType(uuid));
        params.putBoolean("is_auto_reconnect", autoReconnect);
        params.putInt("attempt", Math.max(0, attempt));
        logEvent("connect_attempt", params);
    }

    public static void logConnectSuccess(String mode, boolean bonded, String uuid,
                                         boolean autoReconnect, int attempt,
                                         long durationMillis) {
        Bundle params = new Bundle();
        params.putString("mode", safeMode(mode));
        params.putLong("connect_duration_ms", Math.max(0L, durationMillis));
        params.putBoolean("device_bonded", bonded);
        params.putString("uuid_type", uuidType(uuid));
        params.putBoolean("is_auto_reconnect", autoReconnect);
        params.putInt("attempt", Math.max(0, attempt));
        logEvent("connect_success", params);
    }

    public static void logConnectFailed(String mode, BluetoothConnectionErrorCode code,
                                        Throwable error, boolean autoReconnect,
                                        int attempt, long durationMillis) {
        Bundle params = new Bundle();
        params.putString("mode", safeMode(mode));
        params.putString("error_code", code == null
                ? BluetoothConnectionErrorCode.UNKNOWN_ERROR.name() : code.name());
        params.putString("exception_type", error == null
                ? "none" : error.getClass().getSimpleName());
        params.putLong("connect_duration_ms", Math.max(0L, durationMillis));
        params.putBoolean("is_auto_reconnect", autoReconnect);
        params.putInt("attempt", Math.max(0, attempt));
        logEvent("connect_failed", params);
        recordNonFatal(error, code);
    }

    public static void logReconnectAttempt(int attempt, int maxAttempts, long delayMillis) {
        Bundle params = new Bundle();
        params.putInt("attempt", Math.max(0, attempt));
        params.putInt("max_attempts", Math.max(0, maxAttempts));
        params.putLong("delay_ms", Math.max(0L, delayMillis));
        logEvent("reconnect_attempt", params);
    }

    public static void logReconnectResult(String eventName, int attempt) {
        if (!"reconnect_success".equals(eventName) && !"reconnect_failed".equals(eventName)) {
            return;
        }
        Bundle params = new Bundle();
        params.putInt("attempt", Math.max(0, attempt));
        logEvent(eventName, params);
    }

    public static void logDisconnect() {
        logEvent("disconnect", null);
    }

    public static void logServerEvent(String eventName) {
        if (!"server_listen_start".equals(eventName)
                && !"server_client_connected".equals(eventName)
                && !"server_listen_failed".equals(eventName)) {
            return;
        }
        logEvent(eventName, null);
    }

    public static void logSessionSummary(String mode, long sentBytes, long receivedBytes,
                                         long sendCount, long receiveCount) {
        Bundle params = new Bundle();
        params.putString("mode", safeMode(mode));
        params.putLong("sent_bytes", Math.max(0L, sentBytes));
        params.putLong("received_bytes", Math.max(0L, receivedBytes));
        params.putLong("send_count", Math.max(0L, sendCount));
        params.putLong("receive_count", Math.max(0L, receiveCount));
        logEvent("data_session_summary", params);
    }

    public static void logUserAction(String eventName) {
        if ("log_exported".equals(eventName) || "diagnostic_report_copied".equals(eventName)) {
            logEvent(eventName, null);
        }
    }

    public static void logPermissionResult(String operation, boolean granted) {
        Bundle params = new Bundle();
        params.putString("operation", operation == null ? "unknown" : operation);
        params.putBoolean("granted", granted);
        logEvent(granted ? "bluetooth_permission_granted" : "bluetooth_permission_denied",
                params);
    }

    public static void updateConnectionContext(BluetoothConnectionState state,
                                               BluetoothConnectionErrorCode error) {
        FirebaseCrashlytics current = crashlytics;
        if (current == null) {
            return;
        }
        try {
            current.setCustomKey("connection_state", state == null ? "UNKNOWN" : state.name());
            current.setCustomKey("last_error_code", error == null ? "NONE" : error.name());
            current.setCustomKey("auto_reconnect_enabled",
                    StaticObject.reconnectManager.isGlobalEnabled());
        } catch (RuntimeException telemetryError) {
            Log.w(TAG, "Unable to update Crashlytics connection context", telemetryError);
        }
    }

    public static void recordNonFatal(Throwable error, BluetoothConnectionErrorCode code) {
        FirebaseCrashlytics current = crashlytics;
        if (current == null || error == null) {
            return;
        }
        try {
            current.setCustomKey("last_error_code", code == null
                    ? BluetoothConnectionErrorCode.UNKNOWN_ERROR.name() : code.name());
            current.recordException(error);
        } catch (RuntimeException telemetryError) {
            Log.w(TAG, "Unable to record Crashlytics exception", telemetryError);
        }
    }

    private static void logEvent(String eventName, Bundle params) {
        FirebaseAnalytics current = analytics;
        if (current == null || eventName == null) {
            return;
        }
        try {
            current.logEvent(eventName, params);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to log Firebase event " + eventName, error);
        }
    }

    private static String safeMode(String mode) {
        return mode == null || mode.trim().isEmpty() ? "spp" : mode;
    }

    private static String uuidType(String uuid) {
        return DEFAULT_SPP_UUID.equalsIgnoreCase(uuid) ? "default_spp" : "custom";
    }
}
