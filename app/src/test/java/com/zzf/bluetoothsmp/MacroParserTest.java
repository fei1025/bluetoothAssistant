package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

public class MacroParserTest {
    @Test
    public void parsesTextHexAndDelaySteps() {
        List<MacroStep> steps = MacroParser.parse(
                "# demo\nTEXT hello\nHEX 01 FF\nDELAY 20");

        assertEquals(3, steps.size());
        assertEquals(MacroStep.Type.TEXT, steps.get(0).getType());
        assertEquals("hello", steps.get(0).getText());
        assertArrayEquals(new byte[]{1, (byte) 0xFF}, steps.get(1).getBytes());
        assertEquals(20L, steps.get(2).getDelayMillis());
    }

    @Test
    public void parsesCommandsIndependentlyOfDefaultLocale() {
        List<MacroStep> steps = MacroParser.parse("text hello\nhex 0A");

        assertEquals(2, steps.size());
        assertEquals(MacroStep.Type.TEXT, steps.get(0).getType());
        assertArrayEquals(new byte[]{0x0A}, steps.get(1).getBytes());
    }

    @Test
    public void rejectsUnknownAndMalformedLines() {
        assertThrows(IllegalArgumentException.class, () -> MacroParser.parse("SEND hello"));
        assertThrows(IllegalArgumentException.class, () -> MacroParser.parse("HEX GG"));
        assertThrows(IllegalArgumentException.class, () -> MacroParser.parse("DELAY 60001"));
    }
}
