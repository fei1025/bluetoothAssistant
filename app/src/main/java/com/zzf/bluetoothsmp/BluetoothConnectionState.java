package com.zzf.bluetoothsmp;

/** Runtime lifecycle of one remote classic Bluetooth device. */
public enum BluetoothConnectionState {
    IDLE, PAIRING, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, DISCONNECTED, FAILED
}
