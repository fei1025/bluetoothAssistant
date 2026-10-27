package com.zzf.bluetoothsmp.entity;

import org.litepal.crud.LitePalSupport;

import java.util.Date;
import android.util.Base64;

public class MessageMapper extends LitePalSupport {
    private long id;
    private String messageId, sessionId, textEncoding, sendStatus, sendError;
    private long requestId;
    // LitePal 3.2.3 supports String fields, but does not create columns for byte[].
    private String payloadBase64;
    public long getId() { return id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String value) { messageId = value; }
    public long getRequestId() { return requestId; }
    public void setRequestId(long value) { requestId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public byte[] getPayload() { return payloadBase64 == null ? null : Base64.decode(payloadBase64, Base64.NO_WRAP); }
    public void setPayload(byte[] value) { payloadBase64 = value == null ? null : Base64.encodeToString(value, Base64.NO_WRAP); }
    public String getPayloadBase64() { return payloadBase64; }
    public void setPayloadBase64(String value) { payloadBase64 = value; }
    public String getTextEncoding() { return textEncoding; }
    public void setTextEncoding(String value) { textEncoding = value; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String value) { sendStatus = value; }
    public String getSendError() { return sendError; }
    public void setSendError(String value) { sendError = value; }
    public String receiveAdd;
    public Date receiveTime;
    public String receiveName;

    public String sendUuid;
    public Date   sendTime;
    public String sendAdd;
    public String sendName;

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    private int type;

    public Date saveDate = new Date();

    public String message;



    public String getReceiveName() {
        return receiveName;
    }

    public void setReceiveName(String receiveName) {
        this.receiveName = receiveName;
    }

    public String getSendName() {
        return sendName;
    }

    public void setSendName(String sendName) {
        this.sendName = sendName;
    }

    public String getReceiveAdd() {
        return receiveAdd;
    }

    public void setReceiveAdd(String receiveAdd) {
        this.receiveAdd = receiveAdd;
    }


    public Date getReceiveTime() {
        return receiveTime;
    }

    public void setReceiveTime(Date receiveTime) {
        this.receiveTime = receiveTime;
    }

    public String getSendUuid() {
        return sendUuid;
    }

    public void setSendUuid(String sendUuid) {
        this.sendUuid = sendUuid;
    }

    public Date getSendTime() {
        return sendTime;
    }

    public void setSendTime(Date sendTime) {
        this.sendTime = sendTime;
    }

    public String getSendAdd() {
        return sendAdd;
    }

    public void setSendAdd(String sendAdd) {
        this.sendAdd = sendAdd;
    }

    public Date getSaveDate() {
        return saveDate;
    }

    public void setSaveDate(Date saveDate) {
        this.saveDate = saveDate;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "MessageMapper{" +
                "receiveAdd='" + receiveAdd + '\'' +
                ", receiveTime=" + receiveTime +
                ", receiveName='" + receiveName + '\'' +
                ", sendUuid='" + sendUuid + '\'' +
                ", sendTime=" + sendTime +
                ", sendAdd='" + sendAdd + '\'' +
                ", sendName='" + sendName + '\'' +
                ", type=" + type +
                ", saveDate=" + saveDate +
                ", message='" + message + '\'' +
                '}';
    }
}
