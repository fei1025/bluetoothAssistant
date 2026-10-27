package com.zzf.bluetoothsmp.utils;

import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;

/** Listener callbacks only snapshot/enqueue; the repository owns database IO. */
public class MonitorMessage {
    public String MonitorAndSaveMse() {
        String listenerId = java.util.UUID.randomUUID().toString();
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE,
                event -> HistoryRepository.record((Msg) event.getEventData()[0]), listenerId);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND,
                event -> HistoryRepository.record((Msg) event.getEventData()[0]), listenerId);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND_STATUS,
                event -> HistoryRepository.updateStatus((Msg) event.getEventData()[0]), listenerId);
        return listenerId;
    }
    public void saveMsg(Msg message) { HistoryRepository.record(message); }
}
