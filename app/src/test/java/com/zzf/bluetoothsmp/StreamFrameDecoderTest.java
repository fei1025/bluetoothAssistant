package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class StreamFrameDecoderTest {

    @Test
    public void lfModeHandlesSplitAndCombinedFrames() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.LF, 64, 1, new byte[]{'\n'}, 0));

        assertTrue(decoder.append(bytes("one\ntwo"), 7).size() == 1);
        List<byte[]> frames = decoder.append(bytes("\n"), 1);

        assertEquals(1, frames.size());
        assertArrayEquals(bytes("two"), frames.get(0));
    }

    @Test
    public void fixedLengthModeSplitsAndKeepsPartialTail() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.FIXED_LENGTH, 64, 3, new byte[0], 0));

        List<byte[]> frames = decoder.append(new byte[]{1, 2, 3, 4}, 4);

        assertEquals(1, frames.size());
        assertArrayEquals(new byte[]{1, 2, 3}, frames.get(0));
        assertEquals(1, decoder.getBufferedByteCount());
    }

    @Test
    public void customDelimiterWorksAcrossReads() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.CUSTOM, 64, 1, new byte[]{0x55, 0x66}, 0));

        assertTrue(decoder.append(new byte[]{1, 2, 0x55}, 3).isEmpty());
        List<byte[]> frames = decoder.append(new byte[]{0x66, 3}, 2);

        assertEquals(1, frames.size());
        assertArrayEquals(new byte[]{1, 2}, frames.get(0));
        assertEquals(1, decoder.getBufferedByteCount());
    }

    @Test
    public void crModePreservesEmptyFramesAndPayload() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.CR, 64, 1, new byte[]{'\r'}, 0));

        List<byte[]> frames = decoder.append(bytes("\rvalue\r"), 7);

        assertEquals(2, frames.size());
        assertArrayEquals(new byte[0], frames.get(0));
        assertArrayEquals(bytes("value"), frames.get(1));
    }

    @Test
    public void timeoutModeFlushesPartialFrameOnlyAfterDeadline() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.TIMEOUT, 64, 1, new byte[0], 100));

        assertTrue(decoder.append(bytes("partial"), 7, 1000).isEmpty());
        assertTrue(decoder.pollTimeout(1099).isEmpty());
        List<byte[]> frames = decoder.pollTimeout(1100);

        assertEquals(1, frames.size());
        assertArrayEquals(bytes("partial"), frames.get(0));
    }

    @Test
    public void rawModePreservesBytesInBoundedChunks() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.RAW, 2, 1, new byte[0], 0));

        List<byte[]> frames = decoder.append(new byte[]{1, 2, 3, 4, 5}, 5);

        assertEquals(3, frames.size());
        assertArrayEquals(new byte[]{1, 2}, frames.get(0));
        assertArrayEquals(new byte[]{3, 4}, frames.get(1));
        assertArrayEquals(new byte[]{5}, frames.get(2));
    }

    @Test
    public void oversizedDelimitedFrameRecoversAtNextDelimiter() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.LF, 3, 1, new byte[]{'\n'}, 0));

        decoder.append(bytes("123456\n"), 7);
        List<byte[]> frames = decoder.append(bytes("ok\n"), 3);

        assertEquals(1, decoder.getDroppedFrameCount());
        assertEquals(1, frames.size());
        assertArrayEquals(bytes("ok"), frames.get(0));
    }

    @Test
    public void tenMegabytesWithoutDelimiterRemainBounded() {
        StreamFrameDecoder decoder = new StreamFrameDecoder(new BluetoothFrameConfig(
                BluetoothFrameMode.LF, 64 * 1024, 1, new byte[]{'\n'}, 0));
        byte[] payload = new byte[10 * 1024 * 1024];

        assertTrue(decoder.append(payload, payload.length, 1000L).isEmpty());
        assertTrue(decoder.getBufferedByteCount() <= 64 * 1024);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
