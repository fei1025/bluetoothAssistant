package com.zzf.bluetoothsmp;
import com.zzf.bluetoothsmp.utils.HistoryStatusPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryStatusPolicyTest {
    @Test public void completedSendNeverRegressesToAnIntermediateOrDifferentOutcome() {
        for (String outcome : new String[]{"SENT", "FAILED", "CANCELED"}) {
            assertFalse(HistoryStatusPolicy.shouldReplace(outcome, "QUEUED"));
            assertFalse(HistoryStatusPolicy.shouldReplace(outcome, "SENDING"));
            assertTrue(HistoryStatusPolicy.shouldReplace(outcome, outcome));
        }
        assertFalse(HistoryStatusPolicy.shouldReplace("SENT", "FAILED"));
    }
    @Test public void progressAndLegacyUnknownStateCanAdvance() {
        assertTrue(HistoryStatusPolicy.shouldReplace(null, "SENT"));
        assertTrue(HistoryStatusPolicy.shouldReplace("QUEUED", "SENDING"));
        assertTrue(HistoryStatusPolicy.shouldReplace("SENDING", "FAILED"));
        assertFalse(HistoryStatusPolicy.shouldReplace("SENDING", "QUEUED"));
        assertFalse(HistoryStatusPolicy.shouldReplace("SENT", null));
    }
}
