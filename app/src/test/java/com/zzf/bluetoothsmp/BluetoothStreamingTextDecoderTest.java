package com.zzf.bluetoothsmp;

import java.nio.charset.Charset;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothStreamingTextDecoderTest {
    @Test public void utf8CharacterSurvivesEveryReadBoundary() {
        checkEverySplit("A中文🙂B", BluetoothTextEncoding.UTF_8, Charset.forName("UTF-8"));
    }
    @Test public void gbkCharacterSurvivesEveryReadBoundary() {
        checkEverySplit("A中文B", BluetoothTextEncoding.GBK, Charset.forName("GBK"));
    }
    @Test public void decoderStateNeverCrossesSessions() {
        BluetoothEncodingUtils.StreamingTextDecoder oldSession = new BluetoothEncodingUtils.StreamingTextDecoder(BluetoothTextEncoding.UTF_8);
        assertEquals("", oldSession.decode(new byte[]{(byte) 0xE4}));
        BluetoothEncodingUtils.StreamingTextDecoder newSession = new BluetoothEncodingUtils.StreamingTextDecoder(BluetoothTextEncoding.UTF_8);
        assertEquals("ok", newSession.decode(new byte[]{'o', 'k'}));
    }
    private void checkEverySplit(String text, BluetoothTextEncoding encoding, Charset charset) {
        byte[] bytes = text.getBytes(charset);
        for (int split = 1; split < bytes.length; split++) {
            BluetoothEncodingUtils.StreamingTextDecoder decoder = new BluetoothEncodingUtils.StreamingTextDecoder(encoding);
            String decoded = decoder.decode(Arrays.copyOf(bytes, split))
                    + decoder.decode(Arrays.copyOfRange(bytes, split, bytes.length));
            assertEquals("split=" + split, text, decoded);
        }
    }
}
