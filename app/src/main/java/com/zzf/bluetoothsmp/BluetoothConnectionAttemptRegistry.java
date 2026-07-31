package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps in-flight client attempts independent from a Fragment view lifecycle. */
public final class BluetoothConnectionAttemptRegistry {
    private final Map<String, BluetoothObject> attempts = new ConcurrentHashMap<>();

    public synchronized void register(String address, BluetoothObject attempt) {
        String normalized = normalize(address);
        if (normalized != null && attempt != null) {
            attempts.put(normalized, attempt);
        }
    }

    public synchronized BluetoothObject get(String address) {
        String normalized = normalize(address);
        return normalized == null ? null : attempts.get(normalized);
    }

    public synchronized void remove(String address, BluetoothObject attempt) {
        String normalized = normalize(address);
        if (normalized == null || attempt == null) {
            return;
        }
        BluetoothObject current = attempts.get(normalized);
        if (current == attempt) {
            attempts.remove(normalized);
        }
    }

    public synchronized List<BluetoothObject> snapshot() {
        if (attempts.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(attempts.values());
    }

    public void updateHandlers(Handler handler) {
        for (BluetoothObject attempt : snapshot()) {
            attempt.setmHandler(handler);
        }
    }

    public synchronized void clear() {
        attempts.clear();
    }

    private static String normalize(String address) {
        return BluetoothAddressUtils.normalize(address);
    }
}
