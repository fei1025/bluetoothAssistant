package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zzf.bluetoothsmp.entity.Msg;

import org.junit.Test;

public class MsgHistoryPolicyTest {
    @Test
    public void messagesPersistHistoryByDefault() {
        Msg message = new Msg("payload", Msg.TYPE_SENT, "00:11:22:33:44:55");

        assertTrue(message.isPersistHistory());
    }

    @Test
    public void macroLikeMessageCanOptOutOfHistory() {
        Msg message = new Msg("payload", Msg.TYPE_SENT, "00:11:22:33:44:55");
        message.setPersistHistory(false);

        assertFalse(message.isPersistHistory());
    }
}
