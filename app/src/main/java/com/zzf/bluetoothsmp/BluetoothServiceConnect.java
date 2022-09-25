package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BluetoothServiceConnect {
    public BluetoothSocket bluetoothSocket;
    private BufferedOutputStream bufferedOutputStream;
    private BufferedInputStream bufferedInputStream;
    private String bluetoothName;
    private String bluetoothAdd;
    private String UUid;


    @SuppressLint("MissingPermission")
    public void start(Context context, BluetoothSocket bluetoothSocket ) {
        if (bluetoothSocket == null) {
            return;
        }
        UUid= UUID.randomUUID().toString();
        try {
            this.bluetoothSocket = bluetoothSocket;
            this.bufferedOutputStream = new BufferedOutputStream(bluetoothSocket.getOutputStream());
            this.bufferedInputStream = new BufferedInputStream(bluetoothSocket.getInputStream());
            bluetoothName = bluetoothSocket.getRemoteDevice().getName();
            bluetoothAdd=bluetoothSocket.getRemoteDevice().getAddress();
            BluetoothServiceConnect bluetoothServiceConnect = StaticObject.bluetoothSocketMap.get(bluetoothSocket.getRemoteDevice().getAddress());
            if(bluetoothServiceConnect !=null ){
                return;
            }
            StaticObject.bluetoothSocketMap.put(bluetoothSocket.getRemoteDevice().getAddress(), this);
            receiveMsg.start();
            //监听发送数据事件
            StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, l -> {
                Msg eventDatum = (Msg) l.getEventData()[0];
                if (!bluetoothAdd.equals(eventDatum.getBluetoothAdd())) {
                    return;
                }
                try {
                    bufferedOutputStream.write(eventDatum.getContent().getBytes(StandardCharsets.UTF_8));
                    bufferedOutputStream.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            },"");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public Thread receiveMsg = new Thread(new Runnable() {
        @Override
        public void run() {
            while (true) {
                byte[] buf = new byte[1024];
                int readLine = 0;
                try {
                    while (!Thread.currentThread().isInterrupted() && ((readLine = bufferedInputStream.read(buf)) != -1)) {
                        String info = new String(buf, 0, readLine);
                        Msg m = new Msg(info, Msg.TYPE_RECEIVED,bluetoothAdd);
                        StaticObject.mTaskQueue.put(m);
                    }
                } catch (Exception e) {
                    Msg m = new Msg(bluetoothAdd);
                    m.setStateType(1);
                    try {
                        StaticObject.mTaskQueue.put(m);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                    close();
                    e.printStackTrace();
                    return;
                }
            }
        }
    });


    public void close() {
        try {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(UUid);
            receiveMsg.interrupt();
            if (bufferedOutputStream != null) {
                bufferedOutputStream.close();
            }
            if (bufferedInputStream != null) {
                bufferedInputStream.close();
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
