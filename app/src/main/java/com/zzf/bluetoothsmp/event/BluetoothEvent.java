package com.zzf.bluetoothsmp.event;

import com.zzf.bluetoothsmp.entity.Msg;

public class BluetoothEvent extends EventDispatcher {

    public void senMsg(Msg msg) {
        dispatchEvent(BluetoothType.SEND, msg);
    }

    public void receiveMsg(Msg msg) {
        dispatchEvent(BluetoothType.RECEIVE, msg);
    }

    public void notConnect(Msg msg) {
        dispatchEvent(BluetoothType.NOT_CONNECT, msg);
    }

    public void AllMsg(Msg msg) {
        dispatchEvent(BluetoothType.All_MSG, msg);

    }

    public void disconnectConnect(Msg msg) {
        dispatchEvent(BluetoothType.NOT_CONNECT, msg);

    }
}
