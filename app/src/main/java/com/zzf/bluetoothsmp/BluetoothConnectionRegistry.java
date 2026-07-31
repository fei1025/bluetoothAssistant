package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes connection attempts independently from socket ownership. */
public final class BluetoothConnectionRegistry {
    private static final int MAX_LOG_ENTRIES_PER_ADDRESS = 100;
    private final Map<String, BluetoothConnectionState> states = new ConcurrentHashMap<>();
    private final Map<String, BluetoothConnectionErrorCode> errors = new ConcurrentHashMap<>();
    private final Map<String, Deque<BluetoothConnectionLogEntry>> logs = new ConcurrentHashMap<>();

    public synchronized boolean beginConnect(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null) return false;
        BluetoothConnectionState current = states.get(normalized);
        if (current == BluetoothConnectionState.PAIRING
                || current == BluetoothConnectionState.CONNECTING
                || current == BluetoothConnectionState.CONNECTED
                || current == BluetoothConnectionState.RECONNECTING
                || current == BluetoothConnectionState.DISCONNECTING) return false;
        errors.remove(normalized);
        set(normalized, BluetoothConnectionState.CONNECTING);
        return true;
    }

    public synchronized boolean claimReconnect(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null
                || states.get(normalized) != BluetoothConnectionState.RECONNECTING) {
            return false;
        }
        errors.remove(normalized);
        set(normalized, BluetoothConnectionState.CONNECTING);
        return true;
    }

    public synchronized void set(String address, BluetoothConnectionState state) {
        String normalized = normalizeAddress(address);
        if (normalized == null || state == null) {
            return;
        }
        BluetoothConnectionState previous = states.get(normalized);
        if (previous == state) {
            return;
        }
        states.put(normalized, state);
        appendLog(normalized, previous == null ? BluetoothConnectionState.IDLE : previous,
                state, BluetoothConnectionErrorCode.NONE, null);
        BluetoothTelemetry.updateConnectionContext(state, getError(normalized));
    }

    /**
     * Records a transport disconnect without clobbering an already scheduled reconnect.
     * The Bluetooth broadcast and the socket receive thread can report the same event
     * concurrently, so the reconnect state must win over a late DISCONNECTED update.
     */
    public synchronized boolean markDisconnectedUnlessReconnecting(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null
                || get(normalized) == BluetoothConnectionState.RECONNECTING) {
            return false;
        }
        set(normalized, BluetoothConnectionState.DISCONNECTED);
        return true;
    }

    public synchronized BluetoothConnectionState get(String address) {
        String normalized = normalizeAddress(address);
        BluetoothConnectionState state = normalized == null ? null : states.get(normalized);
        return state == null ? BluetoothConnectionState.IDLE : state;
    }

    public synchronized void setError(String address, BluetoothConnectionErrorCode error) {
        String normalized = normalizeAddress(address);
        if (normalized != null && error != null) {
            errors.put(normalized, error);
            BluetoothConnectionState state = get(normalized);
            appendLog(normalized, state, state, error, error.name());
            BluetoothTelemetry.updateConnectionContext(state, error);
        }
    }

    public synchronized BluetoothConnectionErrorCode getError(String address) {
        String normalized = normalizeAddress(address);
        BluetoothConnectionErrorCode error = normalized == null ? null : errors.get(normalized);
        return error == null ? BluetoothConnectionErrorCode.NONE : error;
    }

    public synchronized void clearError(String address) {
        String normalized = normalizeAddress(address);
        if (normalized != null) {
            errors.remove(normalized);
        }
    }

    public synchronized Set<String> getKnownAddresses() {
        return new HashSet<>(states.keySet());
    }

    public synchronized List<BluetoothConnectionLogEntry> getLogs(String address) {
        String normalized = normalizeAddress(address);
        Deque<BluetoothConnectionLogEntry> entries = normalized == null ? null : logs.get(normalized);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        states.clear();
        errors.clear();
        logs.clear();
    }

    private void appendLog(String address, BluetoothConnectionState fromState,
                           BluetoothConnectionState toState,
                           BluetoothConnectionErrorCode errorCode, String summary) {
        Deque<BluetoothConnectionLogEntry> entries = logs.get(address);
        if (entries == null) {
            entries = new ArrayDeque<>();
            logs.put(address, entries);
        }
        entries.addLast(new BluetoothConnectionLogEntry(
                System.currentTimeMillis(), address, fromState, toState,
                Thread.currentThread().getName(), errorCode, summary));
        while (entries.size() > MAX_LOG_ENTRIES_PER_ADDRESS) {
            entries.removeFirst();
        }
    }

    private static String normalizeAddress(String address) {
        return BluetoothAddressUtils.normalize(address);
    }
}
