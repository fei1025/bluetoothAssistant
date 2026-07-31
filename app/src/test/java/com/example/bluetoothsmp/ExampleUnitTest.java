package com.example.bluetoothsmp;

import org.junit.Test;

import static org.junit.Assert.*;

import java.util.UUID;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }


    @Test
    public void sppUuid_isValid(){
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FC");
        assertEquals("00001101-0000-1000-8000-00805f9b34fc", uuid.toString());
    }
}
