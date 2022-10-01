package com.zzf.bluetoothsmp.entity;

import android.graphics.Bitmap;

import org.litepal.crud.LitePalSupport;

import java.util.Date;

public class BluetoothDrive extends LitePalSupport {
    private String driveAdd;
    private String driveName;
    private Bitmap systemImg;
    private Bitmap myImg;
    private String LastReceiveMsg;
    private String LastSendMsg;
    private Date senDate;


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
        return systemImg;
    }

    public void setSystemImg(Bitmap systemImg) {
        this.systemImg = systemImg;
    }

    public Bitmap getMyImg() {
        return myImg;
    }

    public void setMyImg(Bitmap myImg) {
        this.myImg = myImg;
    }
}
