package com.zzf.bluetoothsmp.liaoTian;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatSessionIdentityTest {
    @Test
    public void interleavedDevicesAndReconnectsCannotEnterAnotherTranscript() {
        ChatSessionIdentity first = new ChatSessionIdentity("AA", "service", "session-1");
        ChatSessionIdentity replacement = new ChatSessionIdentity("AA", "service", "session-2");
        assertTrue(first.accepts("AA", "service", "session-1"));
        assertFalse(first.accepts("BB", "service", "session-1"));
        assertFalse(first.accepts("AA", "another-service", "session-1"));
        assertFalse(first.accepts("AA", "service", "session-2"));
        assertFalse(replacement.accepts("AA", "service", "session-1"));
        assertTrue(replacement.accepts("AA", "service", "session-2"));
    }

    @Test
    public void delayedDisconnectCannotCloseReplacementAndLegacyIsRejected() {
        ChatSessionIdentity replacement = new ChatSessionIdentity("AA", "service", "session-2");
        assertFalse(replacement.acceptsDisconnect("AA", "session-1"));
        assertFalse(replacement.acceptsDisconnect("AA", null));
        assertFalse(replacement.acceptsDisconnect("BB", "session-2"));
        assertTrue(replacement.acceptsDisconnect("AA", "session-2"));
    }

    @Test
    public void readOnlyHistoryDoesNotSubscribeToAnyLiveSession() {
        ChatSessionIdentity history = new ChatSessionIdentity("AA", "service", null);
        assertFalse(history.accepts("AA", "service", "session-1"));
        assertFalse(history.accepts("AA", "service", null));
        assertFalse(history.acceptsDisconnect("AA", "session-1"));
    }
}
