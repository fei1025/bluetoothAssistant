package com.zzf.bluetoothsmp.utils;

import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.entity.MessageMapper;

public class MonitorMessage {


    public void MonitorAndSaveMse(){
       String UUID = java.util.UUID.randomUUID().toString();

        //监听接受数据
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            MessageMapper messageMapper= new MessageMapper();
            messageMapper.setSendName(msg.getBluetoothName());
            messageMapper.setSendAdd(msg.getBluetoothAdd());
            messageMapper.setSendUuid(msg.getSendUuid());
            messageMapper.setMessage(msg.getContent());
            messageMapper.setSendTime(msg.getSenTime());
            messageMapper.setReceiveName(StaticObject.myBluetoothName);
            messageMapper.setReceiveAdd(StaticObject.myBluetoothAdd);
            messageMapper.setType(Msg.TYPE_RECEIVED);
            messageMapper.save();
        }, UUID);
        //监听发送数据
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            MessageMapper messageMapper= new MessageMapper();
            messageMapper.setSendName(msg.getBluetoothName());
            messageMapper.setSendAdd(msg.getBluetoothAdd());
            messageMapper.setSendUuid(msg.getSendUuid());
            messageMapper.setMessage(msg.getContent());
            messageMapper.setSendTime(msg.getSenTime());
            messageMapper.setReceiveName(StaticObject.myBluetoothName);
            messageMapper.setReceiveAdd(StaticObject.myBluetoothAdd);
            messageMapper.setType(Msg.TYPE_SENT);
            messageMapper.save();
        }, UUID);
    }
}
