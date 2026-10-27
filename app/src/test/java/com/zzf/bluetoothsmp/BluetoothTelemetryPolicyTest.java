package com.zzf.bluetoothsmp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BluetoothTelemetryPolicyTest {

    @Test
    public void expectedBluetoothFailuresAreNotCrashlyticsNonFatals() {
        assertFalse(BluetoothTelemetryPolicy.shouldRecordNonFatal(new IOException("closed")));
        assertFalse(BluetoothTelemetryPolicy.shouldRecordNonFatal(
                new SecurityException("permission")));
        assertFalse(BluetoothTelemetryPolicy.shouldRecordNonFatal(
                new IllegalArgumentException("uuid")));
        assertFalse(BluetoothTelemetryPolicy.shouldRecordNonFatal(null));
    }

    @Test
    public void unexpectedRuntimeFailureIsRecorded() {
        assertTrue(BluetoothTelemetryPolicy.shouldRecordNonFatal(
                new IllegalStateException("broken invariant")));
    }

    @Test
    public void uuidTypeDoesNotMisclassifyUnresolvedUuidAsCustom() {
        String defaultUuid = "00001101-0000-1000-8000-00805F9B34FB";

        assertEquals("unknown", BluetoothTelemetryPolicy.uuidType(null, defaultUuid));
        assertEquals("unknown", BluetoothTelemetryPolicy.uuidType("  ", defaultUuid));
        assertEquals("default_spp", BluetoothTelemetryPolicy.uuidType(defaultUuid, defaultUuid));
        assertEquals("custom", BluetoothTelemetryPolicy.uuidType(
                "12345678-1234-1234-1234-123456789abc", defaultUuid));
    }

    @Test
    public void elapsedTimeNeverBecomesNegative() {
        assertEquals(0L, BluetoothTelemetryPolicy.elapsedMillis(0L, 100L));
        assertEquals(0L, BluetoothTelemetryPolicy.elapsedMillis(200L, 100L));
        assertEquals(150L, BluetoothTelemetryPolicy.elapsedMillis(100L, 250L));
    }
}
