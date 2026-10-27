package com.zzf.bluetoothsmp.entity;

import com.zzf.bluetoothsmp.KeyboardSlotResolver;

import org.litepal.crud.LitePalSupport;

public class KeyboardEntity extends LitePalSupport implements KeyboardSlotResolver.Entry {

    // Stable logical slot. Legacy rows may still contain verified old R.id values.
    private  int  buttonId;

    private String driveAdd;

    private String buttonName;
    //按下事件
    private String actionDown;
    //抬起事件
    private String  actionUp;

    @Override
    public long getKeyboardRecordId() {
        return getBaseObjId();
    }

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
