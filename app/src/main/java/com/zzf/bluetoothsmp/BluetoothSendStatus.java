package com.zzf.bluetoothsmp;

public enum BluetoothSendStatus {
    QUEUED,
    SENDING,
    SENT,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == SENT || this == FAILED || this == CANCELED;
    }
}
