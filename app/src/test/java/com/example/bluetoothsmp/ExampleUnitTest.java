package com.example.bluetoothsmp;

import android.os.Build;

import org.junit.Ignore;
import org.junit.Test;

import androidx.annotation.RequiresApi;

import static org.junit.Assert.*;

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
    @Ignore
    public void test01() throws InterruptedException {
        System.out.println("555555555555555555555");
        receiveMsg.start();
        Thread.sleep(50L);
        receiveMsg.interrupt();
    }



    public Thread receiveMsg = new Thread(new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.S)
        @Override
        public void run() {
            while (true) {

                System.out.println(Thread.currentThread().isInterrupted());

            }

        }
    });
}