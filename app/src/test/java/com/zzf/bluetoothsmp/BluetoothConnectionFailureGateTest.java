package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BluetoothConnectionFailureGateTest {
    @Test
    public void onlyFirstFailureIsReportedForOneAttempt() {
        BluetoothConnectionFailureGate gate = new BluetoothConnectionFailureGate();

        assertTrue(gate.tryReport());
        assertFalse(gate.tryReport());
    }

    @Test
    public void resetStartsANewAttempt() {
        BluetoothConnectionFailureGate gate = new BluetoothConnectionFailureGate();
        assertTrue(gate.tryReport());

        gate.reset();

        assertTrue(gate.tryReport());
    }
}
