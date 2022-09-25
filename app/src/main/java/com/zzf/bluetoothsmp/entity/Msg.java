package com.zzf.bluetoothsmp.entity;

public class Msg  implements Comparable<Msg>{

    public static final int TYPE_RECEIVED =0;
    public  static final int TYPE_SENT =1;
    public String bluetoothAdd;
    public String bluetoothName;
    private String content;
    private int type;
    // 0 默认值 连接正常 1:连接失败
    private int stateType=0;

    public Msg(String bluetoothAdd) {
        this.bluetoothAdd = bluetoothAdd;
    }

    public Msg(String content, int type,String bluetoothAdd) {
        this.content = content;
        this.type = type;
        this.bluetoothAdd = bluetoothAdd;
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
    public int compareTo(Msg o) {
        return -1;
    }

    @Override
    public String toString() {
        return "Msg{" +
                "bluetoothAdd='" + bluetoothAdd + '\'' +
                ", content='" + content + '\'' +
                ", type=" + type +
                ", stateType=" + stateType +
                '}';
    }
}
