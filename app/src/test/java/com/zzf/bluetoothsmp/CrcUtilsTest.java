package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;

import com.zzf.bluetoothsmp.utils.CrcUtils;

import org.junit.Test;

public class CrcUtilsTest {
    private static final byte[] VECTOR = "123456789".getBytes();

    @Test
    public void calculatesCommonCheckValues() {
        assertEquals(0xDD, CrcUtils.calculate(VECTOR, CrcUtils.Algorithm.SUM8));
        assertEquals(0x31, CrcUtils.calculate(VECTOR, CrcUtils.Algorithm.XOR));
        assertEquals(0xF4, CrcUtils.calculate(VECTOR, CrcUtils.Algorithm.CRC8));
        assertEquals(0x4B37, CrcUtils.calculate(VECTOR, CrcUtils.Algorithm.CRC16_MODBUS));
        assertEquals(0x29B1, CrcUtils.calculate(VECTOR, CrcUtils.Algorithm.CRC16_CCITT));
    }

    @Test
    public void appendsModbusInWireOrderByDefault() {
        assertEquals("31 32 33 34 35 36 37 38 39 37 4B",
                CrcUtils.appendChecksumHex("31 32 33 34 35 36 37 38 39",
                        CrcUtils.Algorithm.CRC16_MODBUS, true));
        assertEquals("31 32 33 34 35 36 37 38 39 4B 37",
                CrcUtils.appendChecksumHex("31 32 33 34 35 36 37 38 39",
                        CrcUtils.Algorithm.CRC16_MODBUS, false));
    }
}
