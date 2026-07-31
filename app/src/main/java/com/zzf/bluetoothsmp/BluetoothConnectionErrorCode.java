package com.zzf.bluetoothsmp;

import java.io.IOException;
import java.util.Locale;

/** Stable, user-facing categories for failures in the classic SPP lifecycle. */
public enum BluetoothConnectionErrorCode {
    NONE,
    BLUETOOTH_NOT_SUPPORTED,
    BLUETOOTH_DISABLED,
    PERMISSION_DENIED,
    DEVICE_NOT_PAIRED,
    PAIRING_FAILED,
    INVALID_UUID,
    CONNECTION_TIMEOUT,
    CONNECTION_REFUSED,
    CONNECTION_CLOSED,
    SOCKET_CREATE_FAILED,
    SOCKET_CONNECT_FAILED,
    STREAM_OPEN_FAILED,
    READ_FAILED,
    WRITE_FAILED,
    FRAME_TOO_LARGE,
    SERVER_LISTEN_FAILED,
    SERVER_ACCEPT_FAILED,
    UNKNOWN_ERROR;

    public static BluetoothConnectionErrorCode classify(Throwable error) {
        if (error == null) {
            return UNKNOWN_ERROR;
        }
        if (error instanceof SecurityException) {
            return PERMISSION_DENIED;
        }
        if (error instanceof IllegalArgumentException) {
            return INVALID_UUID;
        }
        String message = error.getMessage();
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) {
            return CONNECTION_TIMEOUT;
        }
        if (text.contains("refused") || text.contains("reject")) {
            return CONNECTION_REFUSED;
        }
        if (text.contains("closed") || text.contains("reset")) {
            return CONNECTION_CLOSED;
        }
        if (error instanceof IOException) {
            return SOCKET_CONNECT_FAILED;
        }
        return UNKNOWN_ERROR;
    }

    public static BluetoothConnectionErrorCode classifySocketConnect(Throwable error) {
        BluetoothConnectionErrorCode code = classify(error);
        return code == UNKNOWN_ERROR ? SOCKET_CONNECT_FAILED : code;
    }

    public static BluetoothConnectionErrorCode classifyRead(Throwable error) {
        return error instanceof SecurityException ? PERMISSION_DENIED : READ_FAILED;
    }

    public static BluetoothConnectionErrorCode classifyWrite(Throwable error) {
        return error instanceof SecurityException ? PERMISSION_DENIED : WRITE_FAILED;
    }
}
