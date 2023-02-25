package com.zzf.bluetoothsmp.entity;

import org.litepal.crud.LitePalSupport;

public class KeyboardEntity  extends LitePalSupport {

    // 按钮id
    private  int  buttonId;

    private String driveAdd;

    private String buttonName;
    //按下事件
    private String actionDown;
    //抬起事件
    private String  actionUp;

    public int getButtonId() {
        return buttonId;
    }

    public void setButtonId(int buttonId) {
        this.buttonId = buttonId;
    }

    public String getActionDown() {
        return actionDown;
    }

    public void setActionDown(String actionDown) {
        this.actionDown = actionDown;
    }

    public String getActionUp() {
        return actionUp;
    }

    public void setActionUp(String actionUp) {
        this.actionUp = actionUp;
    }

    public String getButtonName() {
        return buttonName;
    }

    public void setButtonName(String buttonName) {
        this.buttonName = buttonName;
    }

    public String getDriveAdd() {
        return driveAdd;
    }

    public void setDriveAdd(String driveAdd) {
        this.driveAdd = driveAdd;
    }
}
