package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BluetoothConnectionAttemptRegistryTest {
    @Test
    public void keepsAttemptAcrossAddressFormattingAndRemovesOnlyOwner() {
        BluetoothConnectionAttemptRegistry registry = new BluetoothConnectionAttemptRegistry();
        BluetoothObject first = new BluetoothObject();
        BluetoothObject other = new BluetoothObject();

        registry.register(" 00:11:22:33:44:55 ", first);

        assertEquals(first, registry.get("00:11:22:33:44:55"));
        registry.remove("00:11:22:33:44:55", other);
        assertEquals(first, registry.get("00:11:22:33:44:55"));
        registry.remove("00:11:22:33:44:55", first);
        assertNull(registry.get("00:11:22:33:44:55"));
    }
}
