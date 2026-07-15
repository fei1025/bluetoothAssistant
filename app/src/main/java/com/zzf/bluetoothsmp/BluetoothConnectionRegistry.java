package com.zzf.bluetoothsmp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes connection attempts independently from socket ownership. */
public final class BluetoothConnectionRegistry {
    private final Map<String, BluetoothConnectionState> states = new ConcurrentHashMap<>();

    public synchronized boolean beginConnect(String address) {
        if (address == null || address.isEmpty()) return false;
        BluetoothConnectionState current = states.get(address);
        if (current == BluetoothConnectionState.CONNECTING
                || current == BluetoothConnectionState.CONNECTED
                || current == BluetoothConnectionState.RECONNECTING
                || current == BluetoothConnectionState.DISCONNECTING) return false;
        states.put(address, BluetoothConnectionState.CONNECTING);
        return true;
    }

    public synchronized void set(String address, BluetoothConnectionState state) {
        if (address != null && !address.isEmpty() && state != null) states.put(address, state);
    }

    public synchronized BluetoothConnectionState get(String address) {
        BluetoothConnectionState state = states.get(address);
        return state == null ? BluetoothConnectionState.IDLE : state;
    }

    public synchronized void clear() {
        states.clear();
    }
}
