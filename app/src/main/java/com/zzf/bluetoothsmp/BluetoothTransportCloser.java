package com.zzf.bluetoothsmp;

import java.io.Closeable;
import java.io.IOException;

/** Closes the transport before acquiring any buffered stream's I/O lock. */
final class BluetoothTransportCloser {
    private BluetoothTransportCloser() {
    }

    static void close(Closeable transport, Closeable output, Closeable input) {
        // BluetoothSocket.close aborts blocked reads/writes. Closing buffered output
        // first would flush while another writer still owns its monitor.
        closeQuietly(transport);
        closeQuietly(output);
        closeQuietly(input);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
