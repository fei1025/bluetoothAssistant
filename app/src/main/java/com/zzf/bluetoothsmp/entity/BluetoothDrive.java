package com.zzf.bluetoothsmp.entity;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.litepal.crud.LitePalSupport;

import java.io.ByteArrayOutputStream;
import java.util.Date;

public class BluetoothDrive extends LitePalSupport {
    private String driveAdd;
    private String driveName;
    private Bitmap systemImg;
    private Bitmap myImg;
    private String LastReceiveMsg;
    private String LastSendMsg;
    private String uuid;
    private Date senDate;
    private byte[] systemImgByte;


    public byte[] getSystemImgByte() {
        return systemImgByte;
    }

    public void setSystemImgByte(byte[] systemImgByte) {
        this.systemImgByte = systemImgByte;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public Date getSenDate() {
        return senDate;
    }

    public void setSenDate(Date senDate) {
        this.senDate = senDate;
    }

    public String getLastReceiveMsg() {
        return LastReceiveMsg;
    }

    public void setLastReceiveMsg(String lastReceiveMsg) {
        LastReceiveMsg = lastReceiveMsg;
    }

    public String getLastSendMsg() {
        return LastSendMsg;
    }

    public void setLastSendMsg(String lastSendMsg) {
        LastSendMsg = lastSendMsg;
    }

    public String getDriveAdd() {
        return driveAdd;
    }

    public void setDriveAdd(String driveAdd) {
        this.driveAdd = driveAdd;
    }

    public String getDriveName() {
        return driveName;
    }

    public void setDriveName(String driveName) {
        this.driveName = driveName;
    }

    public Bitmap getSystemImg() {
        if (systemImg == null) {
            if (systemImgByte != null && systemImgByte.length !=0) {
                Bitmap bms = BitmapFactory.decodeByteArray(systemImgByte, 0, systemImgByte.length);
                return bms;
            }
        }
        return systemImg;
    }

    public void setSystemImg(Bitmap systemImg) {
        this.systemImg = systemImg;
        if(systemImg != null){
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            systemImg.compress(Bitmap.CompressFormat.PNG, 100, baos);
            this.systemImgByte = baos.toByteArray();
        }

    }

    public Bitmap getMyImg() {
        return myImg;
    }

    public void setMyImg(Bitmap myImg) {
        this.myImg = myImg;
    }
}
