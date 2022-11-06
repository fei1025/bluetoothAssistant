package com.zzf.bluetoothsmp.utils;

import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.entity.MessageMapper;

import org.litepal.LitePal;

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
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.All_MSG,l->{
            Msg msg = (Msg) l.getEventData()[0];
            saveMsg(msg);
        },UUID);
    }



    public synchronized void  saveMsg(Msg msg){
        BluetoothDrive messageList = LitePal.where("driveAdd = ? and uuid = ? ",msg.getBluetoothAdd(),msg.getSendUuid()).findFirst(BluetoothDrive.class);
        if(messageList == null ){
            messageList=new BluetoothDrive();
            messageList.setDriveAdd(msg.getBluetoothAdd());
            messageList.setUuid(msg.getSendUuid());
            messageList.setDriveName(msg.getBluetoothName());
        }
        messageList.setLastReceiveMsg(msg.getContent());
        messageList.setSenDate(msg.getSenTime());
        messageList.save();
    }
}
