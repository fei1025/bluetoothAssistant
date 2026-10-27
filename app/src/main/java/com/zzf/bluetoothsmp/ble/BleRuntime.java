package com.zzf.bluetoothsmp.ble;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Application foreground teardown also covers retained BLE screens and screen lock. */
public final class BleRuntime {
    private static final Set<BleController> clients = Collections.newSetFromMap(new WeakHashMap<>());
    private BleRuntime() { }
    static synchronized void register(BleController client) { clients.add(client); }
    static synchronized void unregister(BleController client) { clients.remove(client); }
    public static synchronized void suspendAll() {
        for (BleController client : clients) client.suspend();
    }
}
