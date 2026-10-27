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
    private String messageId;
    private volatile String sendStatus;
    private String sessionId;
    private long sendStatusVersion;
    private com.zzf.bluetoothsmp.BluetoothTextEncoding textEncoding;
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public long getSendStatusVersion() { return sendStatusVersion; }
    public void setSendStatusVersion(long value) { sendStatusVersion = value; }
    public com.zzf.bluetoothsmp.BluetoothTextEncoding getTextEncoding() { return textEncoding; }
    public void setTextEncoding(com.zzf.bluetoothsmp.BluetoothTextEncoding value) { textEncoding = value; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String id) { messageId = id; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String status) { sendStatus = status; }
    public long estimatedBytes() {
        return 256L + (rawBytes == null ? 0 : rawBytes.length)
                + chars(content) + chars(hexContent) + chars(asciiContent);
    }
    private static long chars(String value) { return value == null ? 0 : value.length() * 2L; }

    public LogItem() {
    }

    public LogItem(String content, byte[] rawBytes, int type, String bluetoothAddress) {
        this.content = content;
        this.rawBytes = rawBytes == null ? null : rawBytes.clone();
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
        return rawBytes == null ? null : rawBytes.clone();
    }

    public void setRawBytes(byte[] rawBytes) {
        this.rawBytes = rawBytes == null ? null : rawBytes.clone();
    }

    public boolean isSent() {
        return type == TYPE_SENT;
    }

    public boolean isReceived() {
        return type == TYPE_RECEIVED;
    }
}
