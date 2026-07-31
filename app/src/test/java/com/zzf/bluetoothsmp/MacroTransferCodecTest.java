package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.zzf.bluetoothsmp.entity.CommandMacroEntity;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class MacroTransferCodecTest {
    @Test
    public void roundTripPreservesUnicodeAndMultilineScript() {
        CommandMacroEntity macro = new CommandMacroEntity();
        macro.setName("温度读取");
        macro.setScript("TEXT 查询\nHEX 01 FF\nDELAY 20");
        macro.setRepeatCount(3);

        String exported = MacroTransferCodec.exportMacros(Arrays.asList(macro));
        List<CommandMacroEntity> imported = MacroTransferCodec.importMacros(exported);

        assertEquals(1, imported.size());
        assertEquals("温度读取", imported.get(0).getName());
        assertEquals(macro.getScript(), imported.get(0).getScript());
        assertEquals(3, imported.get(0).getRepeatCount());
    }

    @Test
    public void rejectsMalformedOrInvalidMacroExports() {
        assertThrows(IllegalArgumentException.class,
                () -> MacroTransferCodec.importMacros("wrong\n"));
        assertThrows(IllegalArgumentException.class,
                () -> MacroTransferCodec.importMacros(
                        "SPP_MACRO_EXPORT_V1\n00\t1\tZZ\n"));
        assertThrows(IllegalArgumentException.class,
                () -> MacroTransferCodec.importMacros(
                        "SPP_MACRO_EXPORT_V1\n4E\t101\t544558542061\n"));
    }
}
