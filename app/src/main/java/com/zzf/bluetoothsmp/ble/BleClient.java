package com.zzf.bluetoothsmp.ble;

import android.app.Application;

/** Existing BLE screen facade; protocol scheduling is shared with plugin transports. */
public final class BleClient extends BleController {
    public BleClient(Application application) { super(application); }
    BleClient(Application application, BleGattConnection.Factory factory,
              BleGattConnection.Gate foreground, BleGattConnection.Gate permission,
              long operationTimeoutMillis, long connectTimeoutMillis) {
        super(application, factory, foreground, permission, operationTimeoutMillis, connectTimeoutMillis);
    }
}
