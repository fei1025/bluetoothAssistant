package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.Msg;
import org.junit.Test;
import static org.junit.Assert.*;

public class MsgSendIdentityTest {
    @Test public void identitiesAreUniqueAndTerminalResultCannotBeOverwritten() {
        Msg first = new Msg("a", Msg.TYPE_SENT, "AA");
        Msg second = new Msg("b", Msg.TYPE_SENT, "AA");
        assertNotEquals(first.getMessageId(), second.getMessageId());
        assertNotEquals(first.getRequestId(), second.getRequestId());
        assertTrue(first.updateSendStatus(BluetoothSendStatus.QUEUED, null));
        assertTrue(first.updateSendStatus(BluetoothSendStatus.SENDING, null));
        assertTrue(first.updateSendStatus(BluetoothSendStatus.FAILED, "write failed"));
        assertFalse(first.updateSendStatus(BluetoothSendStatus.QUEUED, null));
        assertFalse(first.updateSendStatus(BluetoothSendStatus.SENT, null));
        assertEquals(BluetoothSendStatus.FAILED, first.getSendStatus());
        assertEquals("write failed", first.getSendError());
        assertEquals(3L, first.getSendStatusVersion());
    }
    @Test public void aMessageCanOnlyBeSubmittedOnce() {
        Msg message = new Msg("a", Msg.TYPE_SENT, "AA");
        assertTrue(message.claimSubmission());
        assertFalse(message.claimSubmission());
    }
}
