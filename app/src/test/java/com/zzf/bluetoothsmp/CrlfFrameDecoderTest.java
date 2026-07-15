package com.zzf.bluetoothsmp;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CrlfFrameDecoderTest {
    @Test
    public void decodesMultipleFramesInOneRead() {
        CrlfFrameDecoder decoder = new CrlfFrameDecoder();

        List<byte[]> frames = decoder.append("one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8), 10);

        assertEquals(2, frames.size());
        assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), frames.get(0));
        assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), frames.get(1));
    }

    @Test
    public void preservesFrameAcrossReadsAndSplitDelimiter() {
        CrlfFrameDecoder decoder = new CrlfFrameDecoder();

        assertTrue(decoder.append("你".getBytes(StandardCharsets.UTF_8), 3).isEmpty());
        byte[] second = "好\r".getBytes(StandardCharsets.UTF_8);
        assertTrue(decoder.append(second, second.length).isEmpty());
        byte[] third = "\n".getBytes(StandardCharsets.UTF_8);
        List<byte[]> frames = decoder.append(third, third.length);

        assertEquals(1, frames.size());
        assertArrayEquals("你好".getBytes(StandardCharsets.UTF_8), frames.get(0));
    }

    @Test
    public void keepsStandaloneCarriageReturnAsPayload() {
        CrlfFrameDecoder decoder = new CrlfFrameDecoder();
        byte[] input = "a\rb\r\n".getBytes(StandardCharsets.UTF_8);

        List<byte[]> frames = decoder.append(input, input.length);

        assertEquals(1, frames.size());
        assertArrayEquals("a\rb".getBytes(StandardCharsets.UTF_8), frames.get(0));
    }
}
