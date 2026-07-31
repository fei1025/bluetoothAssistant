package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class BluetoothTextFrameEncoderTest {
    private static final BluetoothTextEncoding UTF8 = BluetoothTextEncoding.UTF_8;

    @Test
    public void appendsConfiguredDelimiter() {
        assertArrayEquals(new byte[]{'O', 'K', '\n'}, BluetoothTextFrameEncoder.encode(
                "OK", new BluetoothFrameConfig(BluetoothFrameMode.LF, 64, 1,
                        new byte[]{'\n'}, 0), UTF8));
        assertArrayEquals(new byte[]{'O', 'K', '\r'}, BluetoothTextFrameEncoder.encode(
                "OK", new BluetoothFrameConfig(BluetoothFrameMode.CR, 64, 1,
                        new byte[]{'\r'}, 0), UTF8));
    }

    @Test
    public void rawAndTimeoutDoNotAddDelimiter() {
        assertArrayEquals(new byte[]{'O', 'K'}, BluetoothTextFrameEncoder.encode(
                "OK", new BluetoothFrameConfig(BluetoothFrameMode.RAW, 64, 1,
                        new byte[0], 0), UTF8));
        assertArrayEquals(new byte[]{'O', 'K'}, BluetoothTextFrameEncoder.encode(
                "OK", new BluetoothFrameConfig(BluetoothFrameMode.TIMEOUT, 64, 1,
                        new byte[0], 100), UTF8));
    }

    @Test
    public void fixedLengthRequiresEncodedByteLength() {
        BluetoothFrameConfig config = new BluetoothFrameConfig(BluetoothFrameMode.FIXED_LENGTH,
                64, 2, new byte[0], 0);
        assertArrayEquals(new byte[]{'O', 'K'}, BluetoothTextFrameEncoder.encode(
                "OK", config, UTF8));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixedLengthRejectsMismatchedText() {
        BluetoothTextFrameEncoder.encode("中文", new BluetoothFrameConfig(
                BluetoothFrameMode.FIXED_LENGTH, 64, 2, new byte[0], 0), UTF8);
    }

    @Test
    public void customDelimiterAndHexEncodingAreAppliedTogether() {
        assertArrayEquals(new byte[]{0x01, 0x02, 0x7e}, BluetoothTextFrameEncoder.encode(
                "01 02", new BluetoothFrameConfig(BluetoothFrameMode.CUSTOM, 64, 1,
                        new byte[]{0x7e}, 0), BluetoothTextEncoding.HEX));
    }
}
