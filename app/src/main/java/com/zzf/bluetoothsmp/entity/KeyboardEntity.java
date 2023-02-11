package com.zzf.bluetoothsmp.entity;

public class KeyboardEntity {

    // 按钮id
    private  int  buttonId;
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
}
