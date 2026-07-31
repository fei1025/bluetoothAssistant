package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class MacroExecutorTest {
    @Test
    public void executesStepsInOrderForEachRepeat() throws Exception {
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch complete = new CountDownLatch(4);
        MacroExecutor executor = new MacroExecutor();
        executor.execute(Arrays.asList(MacroStep.text("A"), MacroStep.delay(1),
                        MacroStep.text("B")), 2, step -> {
                    sent.add(step.getText());
                    complete.countDown();
                });

        assertTrue(complete.await(2, TimeUnit.SECONDS));
        executor.close();
        assertEquals(Arrays.asList("A", "B", "A", "B"), sent);
    }
}
