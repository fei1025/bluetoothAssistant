package com.zzf.bluetoothsmp.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import java.util.List;

/** Platform boundary: session ownership and ATT scheduling stay in BleClient. */
interface BleGattConnection {
    interface Factory { BleGattConnection open(String address, Events events); }
    interface Gate { boolean allowed(); }
    interface Events {
        void connectionState(BleGattConnection source, int status, int state);
        void servicesDiscovered(BleGattConnection source, int status);
        void characteristicRead(BleGattConnection source, BluetoothGattCharacteristic characteristic, byte[] value, int status);
        void characteristicWritten(BleGattConnection source, BluetoothGattCharacteristic characteristic, int status);
        void notification(BleGattConnection source, BluetoothGattCharacteristic characteristic, byte[] value);
        void descriptorRead(BleGattConnection source, BluetoothGattDescriptor descriptor, byte[] value, int status);
        void descriptorWritten(BleGattConnection source, BluetoothGattDescriptor descriptor, int status);
        void mtuChanged(BleGattConnection source, int mtu, int status);
        void rssiRead(BleGattConnection source, int rssi, int status);
        void servicesChanged(BleGattConnection source);
    }
    boolean discoverServices();
    List<BluetoothGattService> getServices();
    boolean readCharacteristic(BluetoothGattCharacteristic characteristic);
    boolean readDescriptor(BluetoothGattDescriptor descriptor);
    int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int type);
    int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value);
    boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled);
    boolean requestMtu(int mtu);
    boolean readRemoteRssi();
    boolean requestConnectionPriority(int priority);
    void disconnect();
    void close();
}
