package com.zzf.bluetoothsmp.ble;

import com.zzf.bluetoothsmp.BluetoothTextEncoding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlePayloadTest {
    @Test public void packetLimitsHandleDefaultNegotiatedAndAndroid14Mtu() {
        assertEquals(20, BlePayload.chunkSize(23));
        assertEquals(244, BlePayload.chunkSize(247));
        assertEquals(512, BlePayload.chunkSize(517));
    }

    @Test public void segmentationReassemblesExactBinaryWithNoImplicitLineEnding() {
        byte[] payload = new byte[65536];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        for (int mtu : new int[]{23, 247, 517}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int offset = 0; offset < payload.length;) {
                byte[] chunk = BlePayload.chunk(payload, offset, mtu);
                assertTrue(chunk.length <= mtu - 3);
                assertTrue(chunk.length <= 512);
                out.write(chunk, 0, chunk.length);
                offset += chunk.length;
            }
            assertArrayEquals(payload, out.toByteArray());
        }
    }

    @Test public void lineEndingsAreExplicitAndNeverModifyHex() {
        assertArrayEquals(new byte[]{0, (byte) 255}, BlePayload.encode("00 FF", BluetoothTextEncoding.HEX, "\r\n"));
        assertArrayEquals("你好\r\n".getBytes(StandardCharsets.UTF_8), BlePayload.encode("你好", BluetoothTextEncoding.UTF_8, "\r\n"));
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), BlePayload.encode("hello", BluetoothTextEncoding.ASCII, ""));
    }

    @Test public void sizeLimitIncludesTextSuffixAndRejectsInvalidHex() {
        char[] chars = new char[65536]; Arrays.fill(chars, 'a');
        assertEquals(65536, BlePayload.encode(new String(chars), BluetoothTextEncoding.UTF_8, "").length);
        rejects(() -> BlePayload.encode(new String(chars), BluetoothTextEncoding.UTF_8, "\n"));
        rejects(() -> BlePayload.encode("F", BluetoothTextEncoding.HEX, ""));
        rejects(() -> BlePayload.encode("0G", BluetoothTextEncoding.HEX, ""));
        rejects(() -> BlePayload.encode("", BluetoothTextEncoding.UTF_8, ""));
    }

    @Test public void accepts16And32BitAndFullUuids() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BlePayload.uuid("FFE0").toString());
        assertEquals("12345678-0000-1000-8000-00805f9b34fb", BlePayload.uuid("12345678").toString());
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BlePayload.uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e").toString());
        rejects(() -> BlePayload.uuid("invalid"));
    }

    private void rejects(Runnable action) {
        try { action.run(); fail("Expected invalid input rejection"); }
        catch (IllegalArgumentException expected) { }
    }
}
