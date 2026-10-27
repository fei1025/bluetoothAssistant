package com.zzf.bluetoothsmp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Process-local reservations cover attempts and connected transports, never transfer implicitly. */
public final class BluetoothSessionOwners {
    private static final Map<String, Object> owners = new HashMap<>();
    private BluetoothSessionOwners() { }
    private static String key(String transport, String address) { return transport + ":" + address.toUpperCase(Locale.ROOT); }
    public static synchronized boolean acquire(String transport, String address, Object owner) {
        String key = key(transport, address); Object current = owners.get(key);
        if (current != null && current != owner) return false;
        owners.put(key, owner); return true;
    }
    public static synchronized boolean heldByOther(String transport, String address, Object owner) {
        Object current = owners.get(key(transport, address)); return current != null && current != owner;
    }
    public static synchronized void release(String transport, String address, Object owner) {
        String key = key(transport, address); if (owners.get(key) == owner) owners.remove(key);
    }
}
