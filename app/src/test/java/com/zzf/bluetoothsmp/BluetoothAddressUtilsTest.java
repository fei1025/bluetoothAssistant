package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import org.junit.Test;

public class BluetoothAddressUtilsTest {

    @Test
    public void normalizeTrimsAndUppercasesAddress() {
        assertEquals("AA:BB:CC:DD:EE:FF",
                BluetoothAddressUtils.normalize(" aa:bb:cc:dd:ee:ff "));
    }

    @Test
    public void normalizeRejectsMissingAddress() {
        assertNull(BluetoothAddressUtils.normalize(null));
        assertNull(BluetoothAddressUtils.normalize("   "));
    }

    @Test
    public void maskHidesTheFirstThreeOctets() {
        assertEquals("XX:XX:XX:DD:EE:FF",
                BluetoothAddressUtils.mask("aa:bb:cc:dd:ee:ff"));
    }
}
