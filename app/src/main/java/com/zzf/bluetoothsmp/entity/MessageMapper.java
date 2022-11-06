package com.zzf.bluetoothsmp.entity;

import org.litepal.crud.LitePalSupport;

import java.util.Date;

public class MessageMapper extends LitePalSupport {
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
