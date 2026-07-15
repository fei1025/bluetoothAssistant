package com.zzf.bluetoothsmp.entity;

import java.util.Date;

/**
 * Log entry for debug data display
 */
public class LogItem {
    public static final int TYPE_SENT = 1;
    public static final int TYPE_RECEIVED = 0;

    private String content;
    private String hexContent;
    private String asciiContent;
    private int type;
    private Date timestamp;
    private String bluetoothAddress;
    private byte[] rawBytes;

    public LogItem() {
    }

    public LogItem(String content, byte[] rawBytes, int type, String bluetoothAddress) {
        this.content = content;
        this.rawBytes = rawBytes;
        this.type = type;
        this.bluetoothAddress = bluetoothAddress;
        this.timestamp = new Date();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getHexContent() {
        return hexContent;
    }

    public void setHexContent(String hexContent) {
        this.hexContent = hexContent;
    }

    public String getAsciiContent() {
        return asciiContent;
    }

    public void setAsciiContent(String asciiContent) {
        this.asciiContent = asciiContent;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getBluetoothAddress() {
        return bluetoothAddress;
    }

    public void setBluetoothAddress(String bluetoothAddress) {
        this.bluetoothAddress = bluetoothAddress;
    }

    public byte[] getRawBytes() {
        return rawBytes;
    }

    public void setRawBytes(byte[] rawBytes) {
        this.rawBytes = rawBytes;
    }

    public boolean isSent() {
        return type == TYPE_SENT;
    }

    public boolean isReceived() {
        return type == TYPE_RECEIVED;
    }
}
