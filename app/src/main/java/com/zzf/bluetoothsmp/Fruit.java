package com.zzf.bluetoothsmp;


import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.annotation.NonNull;

import com.example.bluetoothsmp.R;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Fruit {
    static  Map<Integer,Integer> map = new HashMap<>();
    static  Map<Integer,Integer> stateMap = new HashMap<>();
    // BOND_BONDED 表示远程设备已绑定（配对）。
    //BOND_BONDING  表示正在与远程设备进行绑定（配对）。
    // BOND_NONE 表示远程设备未绑定（配对）

    //DEVICE_TYPE_CLASSIC  蓝牙设备类型，经典 - BR/EDR 设备
    //DEVICE_TYPE_DUAL 蓝牙设备类型，双模式 - BR/EDR/LE
    //DEVICE_TYPE_LE  蓝牙设备类型，低功耗 - 仅限 LE
    //DEVICE_TYPE_UNKNOWN 蓝牙设备类型，未知
    static {

        map.put(BluetoothDevice.DEVICE_TYPE_CLASSIC, R.string.BR_EDR);
        map.put(BluetoothDevice.DEVICE_TYPE_DUAL,R.string.BR_EDR_LE);
        map.put(BluetoothDevice.DEVICE_TYPE_LE,R.string.LE);
        map.put(BluetoothDevice.DEVICE_TYPE_UNKNOWN,R.string.unknown);
        stateMap.put(BluetoothDevice.BOND_BONDED,R.string.paired);
        stateMap.put(BluetoothDevice.BOND_BONDING,R.string.pairing);
        stateMap.put(BluetoothDevice.BOND_NONE,R.string.unpaired);
    }


    private String name;
    private String address;
    private String rssi;
    private Integer state;
    private String stateName;
    private BluetoothDevice bluetoothDevice;
    //是否可连接 默认为1 不可连接 0 可连接
    public  Integer isConnect =0;
    public Integer bluetoothType;
    public String bluetoothTypeName;
    private Context contex;

    public Fruit(@NonNull Context contex) {
        this.contex = contex;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRssi() {
        return rssi;
    }

    public void setRssi(String rssi) {
        this.rssi = rssi;
    }

    public BluetoothDevice getBluetoothDevice() {
        return bluetoothDevice;
    }

    public void setBluetoothDevice(BluetoothDevice bluetoothDevice) {
        this.bluetoothDevice = bluetoothDevice;
    }

    public Integer getBluetoothType() {
        return bluetoothType;
    }

    public void setBluetoothType(Integer bluetoothType) {
        this.bluetoothType = bluetoothType;
        this.bluetoothTypeName= contex.getString(map.get(bluetoothType));
    }

    public String getBluetoothTypeName() {
        return bluetoothTypeName;
    }

    public void setBluetoothTypeName(String bluetoothTypeName) {
        this.bluetoothTypeName = bluetoothTypeName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Fruit fruit = (Fruit) o;
        return Objects.equals(address, fruit.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address);
    }


    public Integer getState() {
        return state;
    }

    public void setState(Integer state) {
        this.state = state;
        this.stateName=contex.getString(stateMap.get(state));
    }

    public String getStateName() {
        return stateName;
    }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public Integer getIsConnect() {
        return isConnect;
    }

    public void setIsConnect(Integer isConnect) {
        this.isConnect = isConnect;
    }

    @Override
    public String toString() {
        return "Fruit{" +
                "name='" + name + '\'' +
                ", address='" + address + '\'' +
                ", rssi='" + rssi + '\'' +
                ", state=" + state +
                ", stateName='" + stateName + '\'' +
                ", bluetoothDevice=" + bluetoothDevice +
                ", isConnect=" + isConnect +
                ", bluetoothType=" + bluetoothType +
                ", bluetoothTypeName='" + bluetoothTypeName + '\'' +
                '}';
    }
}

