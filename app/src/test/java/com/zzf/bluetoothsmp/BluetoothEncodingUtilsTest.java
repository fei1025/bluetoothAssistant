package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class BluetoothEncodingUtilsTest {

    @Test
    public void utf8RoundTripUsesDefaultBehavior() {
        String value = "蓝牙";
        assertEquals(value, BluetoothEncodingUtils.decode(
                BluetoothEncodingUtils.encode(value, BluetoothTextEncoding.UTF_8),
                BluetoothTextEncoding.UTF_8));
    }

    @Test
    public void gbkRoundTripSupportsChineseText() {
        String value = "测试";
        assertEquals(value, BluetoothEncodingUtils.decode(
                BluetoothEncodingUtils.encode(value, BluetoothTextEncoding.GBK),
                BluetoothTextEncoding.GBK));
    }

    @Test
    public void hexEncodingUsesRawBytesWhenInputIsValidHex() {
        assertArrayEquals(new byte[]{0x01, (byte) 0xFF},
                BluetoothEncodingUtils.encode("01 FF", BluetoothTextEncoding.HEX));
        assertEquals("01 FF", BluetoothEncodingUtils.decode(
                new byte[]{0x01, (byte) 0xFF}, BluetoothTextEncoding.HEX));
    }

    @Test(expected = IllegalArgumentException.class)
    public void hexEncodingRejectsInvalidInputInsteadOfFallingBackToText() {
        BluetoothEncodingUtils.encode("not-hex", BluetoothTextEncoding.HEX);
    }

    @Test
    public void asciiEncodingUsesAsciiCharset() {
        assertArrayEquals("ABC".getBytes(StandardCharsets.US_ASCII),
                BluetoothEncodingUtils.encode("ABC", BluetoothTextEncoding.ASCII));
    }
}
