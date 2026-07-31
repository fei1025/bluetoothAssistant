package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public class BluetoothConnectionErrorCodeTest {
    @Test
    public void classifiesPermissionAndUuidErrors() {
        assertEquals(BluetoothConnectionErrorCode.PERMISSION_DENIED,
                BluetoothConnectionErrorCode.classify(new SecurityException("permission")));
        assertEquals(BluetoothConnectionErrorCode.INVALID_UUID,
                BluetoothConnectionErrorCode.classify(new IllegalArgumentException("uuid")));
    }

    @Test
    public void classifiesSocketFailures() {
        assertEquals(BluetoothConnectionErrorCode.CONNECTION_REFUSED,
                BluetoothConnectionErrorCode.classifySocketConnect(
                        new IOException("Connection refused")));
        assertEquals(BluetoothConnectionErrorCode.SOCKET_CONNECT_FAILED,
                BluetoothConnectionErrorCode.classifySocketConnect(
                        new IOException("native failure")));
    }

    @Test
    public void registryStoresLastErrorAndClearsItForNewAttempt() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        String address = "00:11:22:33:44:55";
        registry.setError(address, BluetoothConnectionErrorCode.CONNECTION_REFUSED);
        assertEquals(BluetoothConnectionErrorCode.CONNECTION_REFUSED, registry.getError(address));
        registry.set(address, BluetoothConnectionState.FAILED);
        assertEquals(BluetoothConnectionErrorCode.CONNECTION_REFUSED, registry.getError(address));
        registry.beginConnect(address);
        assertEquals(BluetoothConnectionErrorCode.NONE, registry.getError(address));
    }
}
