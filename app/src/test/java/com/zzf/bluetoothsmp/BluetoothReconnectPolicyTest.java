package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BluetoothReconnectPolicyTest {
    @Test
    public void usesThePlannedBackoffSequence() {
        assertEquals(1_000L, BluetoothReconnectPolicy.delayForAttempt(1));
        assertEquals(2_000L, BluetoothReconnectPolicy.delayForAttempt(2));
        assertEquals(5_000L, BluetoothReconnectPolicy.delayForAttempt(3));
        assertEquals(10_000L, BluetoothReconnectPolicy.delayForAttempt(4));
        assertEquals(30_000L, BluetoothReconnectPolicy.delayForAttempt(5));
        assertEquals(30_000L, BluetoothReconnectPolicy.delayForAttempt(6));
    }
}
