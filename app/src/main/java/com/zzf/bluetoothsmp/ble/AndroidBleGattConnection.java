package com.zzf.bluetoothsmp.ble;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import com.zzf.bluetoothsmp.BluetoothPermissionUtils;
import java.util.List;

@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
final class AndroidBleGattConnection implements BleGattConnection {
    private BluetoothGatt gatt;
    private final Events events;

    static BleGattConnection open(Context context, String address, Events events) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("BLUETOOTH_DISABLED");
        if (BluetoothPermissionUtils.hasScanPermission(context)) adapter.cancelDiscovery();
        AndroidBleGattConnection connection = new AndroidBleGattConnection(events);
        connection.gatt = adapter.getRemoteDevice(address).connectGatt(context, false,
                connection.callback, BluetoothDevice.TRANSPORT_LE);
        return connection.gatt == null ? null : connection;
    }

    private AndroidBleGattConnection(Events events) { this.events = events; }
    @Override public boolean discoverServices() { return gatt.discoverServices(); }
    @Override public List<BluetoothGattService> getServices() { return gatt.getServices(); }
    @Override public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) { return gatt.readCharacteristic(characteristic); }
    @Override public boolean readDescriptor(BluetoothGattDescriptor descriptor) { return gatt.readDescriptor(descriptor); }
    @Override public int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int type) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeCharacteristic(characteristic, value, type);
        characteristic.setWriteType(type);
        characteristic.setValue(value);
        return gatt.writeCharacteristic(characteristic) ? 0 : -1;
    }
    @Override public int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(descriptor, value);
        descriptor.setValue(value);
        return gatt.writeDescriptor(descriptor) ? 0 : -1;
    }
    @Override public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) { return gatt.setCharacteristicNotification(characteristic, enabled); }
    @Override public boolean requestMtu(int mtu) { return gatt.requestMtu(mtu); }
    @Override public boolean readRemoteRssi() { return gatt.readRemoteRssi(); }
    @Override public boolean requestConnectionPriority(int priority) { return gatt.requestConnectionPriority(priority); }
    @Override public void disconnect() { gatt.disconnect(); }
    @Override public void close() { gatt.close(); }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        private BleGattConnection source() { return AndroidBleGattConnection.this; }
        @Override public void onConnectionStateChange(BluetoothGatt ignored, int status, int state) { events.connectionState(source(), status, state); }
        @Override public void onServicesDiscovered(BluetoothGatt ignored, int status) { events.servicesDiscovered(source(), status); }
        @Override public void onCharacteristicRead(BluetoothGatt ignored, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            events.characteristicRead(source(), characteristic, value, status);
        }
        @Override public void onCharacteristicRead(BluetoothGatt ignored, BluetoothGattCharacteristic characteristic, int status) {
            if (Build.VERSION.SDK_INT < 33) events.characteristicRead(source(), characteristic, characteristic.getValue(), status);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt ignored, BluetoothGattCharacteristic characteristic, int status) { events.characteristicWritten(source(), characteristic, status); }
        @Override public void onCharacteristicChanged(BluetoothGatt ignored, BluetoothGattCharacteristic characteristic, byte[] value) { events.notification(source(), characteristic, value); }
        @Override public void onCharacteristicChanged(BluetoothGatt ignored, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) events.notification(source(), characteristic, characteristic.getValue());
        }
        @Override public void onDescriptorRead(BluetoothGatt ignored, BluetoothGattDescriptor descriptor, int status, byte[] value) { events.descriptorRead(source(), descriptor, value, status); }
        @Override public void onDescriptorRead(BluetoothGatt ignored, BluetoothGattDescriptor descriptor, int status) {
            if (Build.VERSION.SDK_INT < 33) events.descriptorRead(source(), descriptor, descriptor.getValue(), status);
        }
        @Override public void onDescriptorWrite(BluetoothGatt ignored, BluetoothGattDescriptor descriptor, int status) { events.descriptorWritten(source(), descriptor, status); }
        @Override public void onMtuChanged(BluetoothGatt ignored, int mtu, int status) { events.mtuChanged(source(), mtu, status); }
        @Override public void onReadRemoteRssi(BluetoothGatt ignored, int rssi, int status) { events.rssiRead(source(), rssi, status); }
        @Override public void onServiceChanged(BluetoothGatt ignored) { events.servicesChanged(source()); }
    };
}
