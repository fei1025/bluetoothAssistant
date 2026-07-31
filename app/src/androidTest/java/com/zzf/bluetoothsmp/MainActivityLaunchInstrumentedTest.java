package com.zzf.bluetoothsmp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Verifies that the application shell can launch on a current Android runtime. */
@RunWith(AndroidJUnit4.class)
public class MainActivityLaunchInstrumentedTest {
    @Test
    public void mainActivityLaunchesOnCurrentAndroidRuntime() {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent launchIntent = new Intent(targetContext, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Activity activity = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(launchIntent);

        assertNotNull(activity);
        assertEquals(MainActivity.class.getName(), activity.getClass().getName());
        activity.finishAndRemoveTask();
    }
}
