package com.zzf.bluetoothsmp.entity;

import com.zzf.bluetoothsmp.BluetoothSendBatch;
import com.zzf.bluetoothsmp.BluetoothForegroundGate;
import com.zzf.bluetoothsmp.BluetoothRequestIds;
import com.zzf.bluetoothsmp.BluetoothSendStatus;
import com.zzf.bluetoothsmp.BluetoothTextEncoding;

import java.util.Date;
import java.nio.charset.StandardCharsets;

public class Msg {
    private final String messageId;
    private final long requestId;
    private String sessionId;
    private BluetoothTextEncoding textEncoding = BluetoothTextEncoding.UTF_8;
    private volatile BluetoothSendStatus sendStatus;
    private volatile String sendError;
    private volatile long sendStatusVersion;
    private final java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean transportAdmitted = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean historyRequested = new java.util.concurrent.atomic.AtomicBoolean();

    public static final int TYPE_RECEIVED =0;
    public  static final int TYPE_SENT =1;
    //这是发送方的地址
    public String bluetoothAdd;
    //这里是发送方的名字
    public String bluetoothName;
    private String content;
    private byte[] payload;
    private int type;
    private int segmentSize;
    private long segmentIntervalMillis;
    private boolean persistHistory = true;
    private BluetoothSendBatch sendBatch;
    private long foregroundGeneration = BluetoothForegroundGate.APP.currentGeneration();
    //发送方的uuid
    public String sendUuid;

    public Date senTime =new Date();

    // 0 默认值 连接正常 1:连接失败
    private int stateType=0;

    public Msg(String bluetoothAdd) {
        this(null, 0L, null, null, TYPE_RECEIVED, bluetoothAdd);
    }

    public Date getSenTime() {
        return senTime;
    }

    public void setSenTime(Date senTime) {
        this.senTime = senTime;
    }

    public Msg(String content, int type, String bluetoothAdd) {
        this(null, 0L, null, content, type, bluetoothAdd);
    }

    public Msg(byte[] payload, int type, String bluetoothAdd) {
        this(null, 0L, payload, payload == null ? null : new String(payload, StandardCharsets.UTF_8), type, bluetoothAdd);
    }

    private Msg(String savedId, long savedRequestId, byte[] bytes, String content, int type, String address) {
        messageId = savedId == null || savedId.isEmpty() ? java.util.UUID.randomUUID().toString() : savedId;
        requestId = savedId == null ? BluetoothRequestIds.next() : savedRequestId;
        payload = bytes == null ? null : bytes.clone();
        this.content = content;
        this.type = type;
        bluetoothAdd = address;
    }

    /** Restore stable history identity without making that old row a new send request. */
    public static Msg restored(byte[] payload, String content, int type, String address,
                               String messageId, long requestId) {
        Msg message = new Msg(messageId, requestId, payload, content, type, address);
        message.submitted.set(true);
        return message;
    }

    public byte[] getPayload() {
        return payload == null ? null : payload.clone();
    }

    public int getPayloadLength() { return payload == null ? 0 : payload.length; }
    public String getMessageId() { return messageId; }
    public long getRequestId() { return requestId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public BluetoothTextEncoding getTextEncoding() { return textEncoding; }
    public void setTextEncoding(BluetoothTextEncoding value) { textEncoding = value; }
    public BluetoothSendStatus getSendStatus() { return sendStatus; }
    public String getSendError() { return sendError; }
    public long getSendStatusVersion() { return sendStatusVersion; }
    public boolean claimSubmission() { return submitted.compareAndSet(false, true); }
    public boolean claimTransportAdmission() { return transportAdmitted.compareAndSet(false, true); }
    public boolean hasTransportAdmission() { return transportAdmitted.get(); }
    public void markHistoryRecordRequested() { historyRequested.set(true); }
    public boolean isHistoryRecordRequested() { return historyRequested.get(); }
    public boolean hasPayload() { return payload != null; }
    public void setPayload(byte[] value) { payload = value == null ? null : value.clone(); }

    /** Terminal results cannot be overwritten by a delayed queue notification. */
    public synchronized boolean updateSendStatus(BluetoothSendStatus value, String error) {
        if (value == null || value == sendStatus || (sendStatus != null && sendStatus.isTerminal())) {
            return false;
        }
        if (value == BluetoothSendStatus.QUEUED && sendStatus != null) return false;
        sendError = error;
        sendStatus = value;
        sendStatusVersion++;
        return true;
    }

    /** Heap budget includes the raw bytes, display text and message bookkeeping. */
    public long estimatedQueueBytes() {
        return 128L + getPayloadLength() + (content == null ? 0L : 2L * content.length());
    }

    public byte[] getPayloadOrUtf8() {
        return payload != null ? payload.clone()
                : (content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    public String getSendUuid() {
        return sendUuid;
    }

    public void setSendUuid(String sendUuid) {
        this.sendUuid = sendUuid;
    }

    public String getBluetoothName() {
        return bluetoothName;
    }

    public void setBluetoothName(String bluetoothName) {
        this.bluetoothName = bluetoothName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getSegmentSize() {
        return segmentSize;
    }

    public void setSegmentSize(int segmentSize) {
        this.segmentSize = segmentSize;
    }

    public long getSegmentIntervalMillis() {
        return segmentIntervalMillis;
    }

    public void setSegmentIntervalMillis(long segmentIntervalMillis) {
        this.segmentIntervalMillis = segmentIntervalMillis;
    }

    public boolean isPersistHistory() {
        return persistHistory;
    }

    public BluetoothSendBatch getSendBatch() {
        return sendBatch;
    }

    public void setSendBatch(BluetoothSendBatch sendBatch) {
        this.sendBatch = sendBatch;
    }

    public boolean isSendCanceled() {
        return sendBatch != null && sendBatch.isCanceled();
    }

    public long getForegroundGeneration() {
        return foregroundGeneration;
    }

    /** Async producers bind to the epoch captured when their operation started. */
    public void setForegroundGeneration(long foregroundGeneration) {
        this.foregroundGeneration = foregroundGeneration;
    }

    public boolean isForegroundGenerationCurrent() {
        return BluetoothForegroundGate.APP.isCurrent(foregroundGeneration);
    }

    public void setPersistHistory(boolean persistHistory) {
        this.persistHistory = persistHistory;
    }

    public String getBluetoothAdd() {
        return bluetoothAdd;
    }

    public void setBluetoothAdd(String bluetoothAdd) {
        this.bluetoothAdd = bluetoothAdd;
    }

    public int getStateType() {
        return stateType;
    }

    public void setStateType(int stateType) {
        this.stateType = stateType;
    }

    @Override
    public String toString() {
        return "Msg{" +
                "bluetoothAdd='" + bluetoothAdd + '\'' +
                ", bluetoothName='" + bluetoothName + '\'' +
                ", content='" + content + '\'' +
                ", type=" + type +
                ", sendUuid='" + sendUuid + '\'' +
                ", senTime=" + senTime +
                ", stateType=" + stateType +
                '}';
    }
}
