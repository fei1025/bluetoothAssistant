package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BluetoothConnectionRegistryTest {
    private static final String ADDRESS = "00:11:22:33:44:55";

    @Test
    public void duplicateConnectionAttemptIsRejected() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        assertTrue(registry.beginConnect(ADDRESS));
        assertFalse(registry.beginConnect(ADDRESS));
        assertEquals(BluetoothConnectionState.CONNECTING, registry.get(ADDRESS));
    }

    @Test
    public void failedOrDisconnectedDeviceCanConnectAgain() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        assertTrue(registry.beginConnect(ADDRESS));
        registry.set(ADDRESS, BluetoothConnectionState.FAILED);
        assertTrue(registry.beginConnect(ADDRESS));
        registry.set(ADDRESS, BluetoothConnectionState.DISCONNECTED);
        assertTrue(registry.beginConnect(ADDRESS));
    }

    @Test
    public void connectedDeviceCannotStartAnotherConnection() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.CONNECTED);
        assertFalse(registry.beginConnect(ADDRESS));
    }
}
