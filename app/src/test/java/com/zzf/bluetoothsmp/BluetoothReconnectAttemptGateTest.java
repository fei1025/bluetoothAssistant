package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BluetoothReconnectAttemptGateTest {
    @Test
    public void onlyCurrentAttemptCanBeClaimedAndFailedOnce() {
        BluetoothReconnectAttemptGate gate = new BluetoothReconnectAttemptGate();
        gate.schedule(1);

        assertTrue(gate.claim(1));
        assertFalse(gate.claim(1));
        assertFalse(gate.fail(2));
        assertTrue(gate.fail(1));
        assertFalse(gate.fail(1));
    }

    @Test
    public void staleCallbackCannotClaimAfterNextAttemptIsScheduled() {
        BluetoothReconnectAttemptGate gate = new BluetoothReconnectAttemptGate();
        gate.schedule(1);
        assertTrue(gate.claim(1));
        assertTrue(gate.fail(1));

        gate.schedule(2);
        assertFalse(gate.claim(1));
        assertTrue(gate.claim(2));
    }

    @Test
    public void cancelInvalidatesDelayedCallback() {
        BluetoothReconnectAttemptGate gate = new BluetoothReconnectAttemptGate();
        gate.schedule(1);
        gate.cancel();

        assertFalse(gate.claim(1));
        assertFalse(gate.fail(1));
    }

    @Test
    public void completedAttemptInvalidatesItsDelayedCallback() {
        BluetoothReconnectAttemptGate gate = new BluetoothReconnectAttemptGate();
        gate.schedule(1);
        assertTrue(gate.claim(1));
        assertTrue(gate.complete(1));

        assertFalse(gate.claim(1));
        assertFalse(gate.complete(1));
    }
}
