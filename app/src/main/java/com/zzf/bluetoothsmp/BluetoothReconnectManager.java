package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local coordinator for client reconnects. It owns one pending task per
 * MAC address and keeps the user's last successful UUID in local preferences.
 */
public final class BluetoothReconnectManager {
    private static final String TAG = "BluetoothReconnect";
    private static final String PREFS = "bluetooth_reconnect";
    private static final String ENABLED_PREFIX = "enabled.";
    private static final String UUID_PREFIX = "uuid.";
    private static final String GLOBAL_ENABLED = "global_enabled";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ReconnectTask> tasks = new ConcurrentHashMap<>();
    private Context context;
    private SharedPreferences preferences;

    public synchronized void initialize(Context appContext) {
        if (context != null) {
            return;
        }
        context = appContext.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void remember(BluetoothDevice device, String uuid) {
        if (context == null || device == null || uuid == null) {
            return;
        }
        String address = normalizeAddress(device.getAddress());
        if (address == null) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putString(UUID_PREFIX + address, uuid);
        if (!preferences.contains(ENABLED_PREFIX + address)) {
            editor.putBoolean(ENABLED_PREFIX + address, isGlobalEnabled());
        }
        editor.apply();
        cancelPending(address);
    }

    public synchronized void disable(String address) {
        String normalized = normalizeAddress(address);
        if (preferences != null && normalized != null) {
            preferences.edit().putBoolean(ENABLED_PREFIX + normalized, false).apply();
        }
        cancelPending(normalized);
        cancelInFlightReconnect(normalized);
        if (normalized != null
                && StaticObject.connectionRegistry.get(normalized)
                == BluetoothConnectionState.RECONNECTING) {
            StaticObject.connectionRegistry.set(normalized, BluetoothConnectionState.DISCONNECTED);
        }
    }

    public synchronized boolean isDeviceReconnectEnabled(String address) {
        String normalized = normalizeAddress(address);
        return preferences != null && normalized != null
                && preferences.getBoolean(ENABLED_PREFIX + normalized, false);
    }

    /** Enables/disables one device and optionally starts its first reconnect attempt. */
    public synchronized void setDeviceReconnectEnabled(String address, boolean enabled) {
        String normalized = normalizeAddress(address);
        if (preferences == null || normalized == null) {
            return;
        }
        preferences.edit().putBoolean(ENABLED_PREFIX + normalized, enabled).apply();
        if (!enabled) {
            cancelPending(normalized);
            cancelInFlightReconnect(normalized);
            if (StaticObject.connectionRegistry.get(normalized)
                    == BluetoothConnectionState.RECONNECTING) {
                StaticObject.connectionRegistry.set(normalized,
                        BluetoothConnectionState.DISCONNECTED);
            }
            return;
        }
        BluetoothConnectionState state = StaticObject.connectionRegistry.get(normalized);
        if (isGlobalEnabled() && state != BluetoothConnectionState.CONNECTED
                && state != BluetoothConnectionState.CONNECTING
                && state != BluetoothConnectionState.PAIRING
                && state != BluetoothConnectionState.RECONNECTING) {
            onUnexpectedDisconnect(normalized);
        }
    }

    public synchronized boolean isGlobalEnabled() {
        return preferences == null || preferences.getBoolean(GLOBAL_ENABLED, true);
    }

    public synchronized void setGlobalEnabled(boolean enabled) {
        if (preferences != null) {
            preferences.edit().putBoolean(GLOBAL_ENABLED, enabled).apply();
        }
        if (!enabled) {
            cancelAll();
        } else {
            restorePendingConnections();
        }
    }

    public synchronized void onUnexpectedDisconnect(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null || preferences == null || !isGlobalEnabled()
                || !preferences.getBoolean(ENABLED_PREFIX + normalized, false)
                || tasks.containsKey(normalized)) {
            return;
        }
        ReconnectTask task = new ReconnectTask(normalized);
        tasks.put(normalized, task);
        StaticObject.connectionRegistry.set(normalized, BluetoothConnectionState.RECONNECTING);
        schedule(task, 1);
    }

    /** Restores user-enabled reconnect tasks after the process was recreated. */
    public synchronized void restorePendingConnections() {
        if (preferences == null || !isGlobalEnabled()) {
            return;
        }
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(ENABLED_PREFIX) || !Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            String address = normalizeAddress(key.substring(ENABLED_PREFIX.length()));
            if (address == null || address.isEmpty() || tasks.containsKey(address)) {
                continue;
            }
            BluetoothConnectionState state = StaticObject.connectionRegistry.get(address);
            if (state == BluetoothConnectionState.CONNECTED
                    || state == BluetoothConnectionState.CONNECTING
                    || state == BluetoothConnectionState.RECONNECTING) {
                continue;
            }
            ReconnectTask task = new ReconnectTask(address);
            tasks.put(address, task);
            StaticObject.connectionRegistry.set(address, BluetoothConnectionState.RECONNECTING);
            schedule(task, 1);
        }
    }

    public synchronized void onAttemptFailed(String address) {
        String normalized = normalizeAddress(address);
        ReconnectTask task = normalized == null ? null : tasks.get(normalized);
        if (task == null) {
            return;
        }
        onAttemptFailed(normalized, task.attempt);
    }

    public synchronized void onAttemptFailed(String address, int attempt) {
        String normalized = normalizeAddress(address);
        ReconnectTask task = normalized == null ? null : tasks.get(normalized);
        if (task == null || !task.gate.fail(attempt)) {
            return;
        }
        BluetoothConnectionErrorCode error = StaticObject.connectionRegistry.getError(normalized);
        if (error == BluetoothConnectionErrorCode.PERMISSION_DENIED
                || error == BluetoothConnectionErrorCode.BLUETOOTH_DISABLED
                || error == BluetoothConnectionErrorCode.INVALID_UUID
                || error == BluetoothConnectionErrorCode.PAIRING_FAILED) {
            tasks.remove(normalized);
            StaticObject.connectionRegistry.set(normalized, BluetoothConnectionState.FAILED);
            BluetoothTelemetry.logReconnectResult("reconnect_failed", attempt);
            return;
        }
        int nextAttempt = task.attempt + 1;
        if (nextAttempt > BluetoothReconnectPolicy.DEFAULT_MAX_ATTEMPTS) {
            tasks.remove(normalized);
            StaticObject.connectionRegistry.set(normalized, BluetoothConnectionState.FAILED);
            BluetoothTelemetry.logReconnectResult("reconnect_failed", attempt);
            return;
        }
        task.attempt = nextAttempt;
        StaticObject.connectionRegistry.set(normalized, BluetoothConnectionState.RECONNECTING);
        schedule(task, nextAttempt);
    }

    public synchronized void onConnected(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null) {
            return;
        }
        cancelPending(normalized);
    }

    /**
     * A remote device can complete an inbound server connection while a client
     * reconnect attempt for the same MAC is still in Socket.connect(). The
     * inbound session is authoritative; cancel only the reconnect attempt and
     * restore CONNECTED after cancellation has finished.
     */
    public synchronized void onIncomingConnected(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null) {
            return;
        }
        cancelPending(normalized);
        cancelInFlightReconnect(normalized);
        StaticObject.connectionRegistry.set(normalized, BluetoothConnectionState.CONNECTED);
    }

    /** Accepts success only from the reconnect attempt currently in flight. */
    public synchronized boolean onConnected(String address, int attempt) {
        String normalized = normalizeAddress(address);
        ReconnectTask task = normalized == null ? null : tasks.get(normalized);
        if (task == null || !task.gate.complete(attempt)) {
            if (task != null) {
                StaticObject.connectionRegistry.set(normalized,
                        BluetoothConnectionState.RECONNECTING);
            }
            return false;
        }
        tasks.remove(normalized);
        BluetoothTelemetry.logReconnectResult("reconnect_success", attempt);
        return true;
    }

    /** Cancels the specific in-flight reconnect attempt without changing the saved preference. */
    public synchronized void cancelAttempt(String address, int attempt) {
        String normalized = normalizeAddress(address);
        ReconnectTask task = normalized == null ? null : tasks.get(normalized);
        if (task == null || (attempt > 0 && task.attempt != attempt)) {
            return;
        }
        task.gate.cancel();
        removeScheduledCallback(task);
        if (tasks.get(normalized) == task) {
            tasks.remove(normalized);
        }
        BluetoothConnectionState state = StaticObject.connectionRegistry.get(normalized);
        if (state == BluetoothConnectionState.CONNECTING
                || state == BluetoothConnectionState.RECONNECTING) {
            StaticObject.connectionRegistry.set(normalized,
                    BluetoothConnectionState.DISCONNECTED);
        }
    }

    public synchronized int getPendingAttempt(String address) {
        String normalized = normalizeAddress(address);
        ReconnectTask task = normalized == null ? null : tasks.get(normalized);
        return task == null ? 0 : Math.max(1, task.attempt);
    }

    public synchronized void cancelAll() {
        for (String address : tasks.keySet()) {
            cancelPending(address);
            if (StaticObject.connectionRegistry.get(address)
                    == BluetoothConnectionState.RECONNECTING) {
                StaticObject.connectionRegistry.set(address, BluetoothConnectionState.DISCONNECTED);
            }
        }
        tasks.clear();
        for (BluetoothObject attempt : StaticObject.connectionAttemptRegistry.snapshot()) {
            if (attempt.isReconnectAttempt()) {
                attempt.cancelConnectForLifecycle();
            }
        }
    }

    private void cancelInFlightReconnect(String address) {
        if (address == null) {
            return;
        }
        BluetoothObject attempt = StaticObject.connectionAttemptRegistry.get(address);
        if (attempt != null && attempt.isReconnectAttempt()) {
            attempt.cancelConnectForLifecycle();
        }
    }

    private static String normalizeAddress(String address) {
        return BluetoothAddressUtils.normalize(address);
    }

    private void schedule(ReconnectTask task, int attempt) {
        task.attempt = attempt;
        task.gate.schedule(attempt);
        Runnable runnable = () -> attempt(task, attempt);
        task.scheduledRunnable = runnable;
        long delayMillis = BluetoothReconnectPolicy.delayForAttempt(attempt);
        BluetoothTelemetry.logReconnectAttempt(attempt,
                BluetoothReconnectPolicy.DEFAULT_MAX_ATTEMPTS, delayMillis);
        handler.postDelayed(runnable, delayMillis);
    }

    @SuppressLint("MissingPermission")
    private void attempt(ReconnectTask task, int attempt) {
        synchronized (this) {
            if (tasks.get(task.address) != task || context == null) {
                return;
            }
            task.attempt = attempt;
            if (!task.gate.claim(attempt)) {
                return;
            }
            if (!StaticObject.connectionRegistry.claimReconnect(task.address)) {
                task.gate.cancel();
                tasks.remove(task.address);
                return;
            }
            task.scheduledRunnable = null;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                StaticObject.connectionRegistry.setError(task.address,
                        BluetoothConnectionErrorCode.BLUETOOTH_DISABLED);
                onAttemptFailed(task.address, attempt);
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(task.address);
            String uuid = preferences.getString(UUID_PREFIX + task.address, BluetoothObject.SPP_UUID);
            BluetoothObject object = new BluetoothObject();
            object.setBluetoothDevice(device);
            object.connectForReconnect(context, handler, uuid, attempt);
        } catch (SecurityException error) {
            StaticObject.connectionRegistry.setError(task.address,
                    BluetoothConnectionErrorCode.PERMISSION_DENIED);
            onAttemptFailed(task.address, attempt);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to start reconnect for " + task.address, error);
            StaticObject.connectionRegistry.setError(task.address,
                    BluetoothConnectionErrorCode.SOCKET_CREATE_FAILED);
            onAttemptFailed(task.address, attempt);
        }
    }

    private synchronized void cancelPending(String address) {
        if (address == null) {
            return;
        }
        ReconnectTask task = tasks.remove(address);
        if (task != null) {
            task.gate.cancel();
            removeScheduledCallback(task);
        }
    }

    private void removeScheduledCallback(ReconnectTask task) {
        if (task != null && task.scheduledRunnable != null) {
            handler.removeCallbacks(task.scheduledRunnable);
            task.scheduledRunnable = null;
        }
    }

    private static final class ReconnectTask {
        private final String address;
        private final BluetoothReconnectAttemptGate gate = new BluetoothReconnectAttemptGate();
        private int attempt;
        private Runnable scheduledRunnable;

        private ReconnectTask(String address) {
            this.address = address;
        }
    }
}
