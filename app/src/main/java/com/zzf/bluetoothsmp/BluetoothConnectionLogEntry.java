package com.zzf.bluetoothsmp;

public final class BluetoothConnectionLogEntry {
    private final long timestampMillis;
    private final String address;
    private final BluetoothConnectionState fromState;
    private final BluetoothConnectionState toState;
    private final String threadName;
    private final BluetoothConnectionErrorCode errorCode;
    private final String summary;

    public BluetoothConnectionLogEntry(long timestampMillis, String address,
                                       BluetoothConnectionState fromState,
                                       BluetoothConnectionState toState,
                                       String threadName,
                                       BluetoothConnectionErrorCode errorCode,
                                       String summary) {
        this.timestampMillis = timestampMillis;
        this.address = address;
        this.fromState = fromState;
        this.toState = toState;
        this.threadName = threadName;
        this.errorCode = errorCode;
        this.summary = summary;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getAddress() {
        return address;
    }

    public BluetoothConnectionState getFromState() {
        return fromState;
    }

    public BluetoothConnectionState getToState() {
        return toState;
    }

    public String getThreadName() {
        return threadName;
    }

    public BluetoothConnectionErrorCode getErrorCode() {
        return errorCode;
    }

    public String getSummary() {
        return summary;
    }
}
