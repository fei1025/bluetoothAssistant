package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class BluetoothConnectionRegistryTest {
    private static final String ADDRESS = "00:11:22:33:44:55";

    @Test
    public void duplicateConnectionAttemptIsRejected() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        assertTrue(registry.beginConnect(ADDRESS));
        assertFalse(registry.beginConnect(ADDRESS));
        assertEquals(BluetoothConnectionState.CONNECTING, registry.get(ADDRESS));
        assertEquals(BluetoothConnectionState.CONNECTING,
                registry.getLogs(ADDRESS).get(0).getToState());
    }

    @Test
    public void reconnectClaimLogsTransitionAndClearsPreviousError() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.RECONNECTING);
        registry.setError(ADDRESS, BluetoothConnectionErrorCode.SOCKET_CREATE_FAILED);

        assertTrue(registry.claimReconnect(ADDRESS));
        assertEquals(BluetoothConnectionState.CONNECTING, registry.get(ADDRESS));
        assertEquals(BluetoothConnectionErrorCode.NONE, registry.getError(ADDRESS));
        assertEquals(BluetoothConnectionState.CONNECTING,
                registry.getLogs(ADDRESS).get(2).getToState());
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

    @Test
    public void lateDisconnectDoesNotOverwriteReconnectState() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.RECONNECTING);

        assertFalse(registry.markDisconnectedUnlessReconnecting(ADDRESS));
        assertEquals(BluetoothConnectionState.RECONNECTING, registry.get(ADDRESS));
    }

    @Test
    public void disconnectMarksConnectedDeviceDisconnected() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.CONNECTED);

        assertTrue(registry.markDisconnectedUnlessReconnecting(ADDRESS));
        assertEquals(BluetoothConnectionState.DISCONNECTED, registry.get(ADDRESS));
    }

    @Test
    public void pairingDeviceCannotStartAnotherConnection() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.PAIRING);
        assertFalse(registry.beginConnect(ADDRESS));
    }

    @Test
    public void normalizesAddressAcrossConnectionStateAndLogs() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        String lowerCaseAddress = " 00:aa:bb:cc:dd:ee ";

        assertTrue(registry.beginConnect(lowerCaseAddress));
        assertEquals(BluetoothConnectionState.CONNECTING, registry.get("00:AA:BB:CC:DD:EE"));
        assertFalse(registry.beginConnect("00:AA:BB:CC:DD:EE"));
        assertEquals(1, registry.getLogs("00:aa:bb:cc:dd:ee").size());
    }

    @Test
    public void exposesKnownAddressesForDiagnostics() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.FAILED);
        assertTrue(registry.getKnownAddresses().contains(ADDRESS));
        registry.clear();
        assertFalse(registry.getKnownAddresses().contains(ADDRESS));
    }

    @Test
    public void recordsStateTransitionsAndErrorsWithThreadContext() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.CONNECTING);
        registry.set(ADDRESS, BluetoothConnectionState.CONNECTED);
        registry.setError(ADDRESS, BluetoothConnectionErrorCode.WRITE_FAILED);

        List<BluetoothConnectionLogEntry> entries = registry.getLogs(ADDRESS);
        assertEquals(3, entries.size());
        assertEquals(BluetoothConnectionState.IDLE, entries.get(0).getFromState());
        assertEquals(BluetoothConnectionState.CONNECTING, entries.get(0).getToState());
        assertEquals(BluetoothConnectionErrorCode.WRITE_FAILED,
                entries.get(2).getErrorCode());
        assertTrue(entries.get(1).getThreadName() != null
                && !entries.get(1).getThreadName().isEmpty());
    }

    @Test
    public void clearingErrorPreservesConnectionState() {
        BluetoothConnectionRegistry registry = new BluetoothConnectionRegistry();
        registry.set(ADDRESS, BluetoothConnectionState.CONNECTING);
        registry.setError(ADDRESS, BluetoothConnectionErrorCode.UNKNOWN_ERROR);

        registry.clearError(ADDRESS);

        assertEquals(BluetoothConnectionState.CONNECTING, registry.get(ADDRESS));
        assertEquals(BluetoothConnectionErrorCode.NONE, registry.getError(ADDRESS));
    }
}
