package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothServiceConnect {
    private static final String TAG = "BluetoothSession";

    public BluetoothSocket bluetoothSocket;
    private BufferedOutputStream bufferedOutputStream;
    private BufferedInputStream bufferedInputStream;
    private String bluetoothName;
    private String bluetoothAdd;
    private String listenerUuid;
    private String sendUuid;
    private Thread receiveThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);

    @SuppressLint("MissingPermission")
    public boolean start(Context context, BluetoothSocket socket, String sendUuid) {
        if (socket == null) {
            return false;
        }

        String address = socket.getRemoteDevice().getAddress();
        listenerUuid = UUID.randomUUID().toString();
        this.sendUuid = sendUuid;
        bluetoothSocket = socket;
        bluetoothAdd = address;
        bluetoothName = socket.getRemoteDevice().getName();

        try {
            bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
            bufferedInputStream = new BufferedInputStream(socket.getInputStream());
            BluetoothServiceConnect existing = StaticObject.bluetoothSocketMap.putIfAbsent(address, this);
            if (existing != null) {
                closeQuietly(bufferedOutputStream);
                closeQuietly(bufferedInputStream);
                closeSocketQuietly(socket);
                closed.set(true);
                return existing.isConnected();
            }
            StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, event -> {
                Msg msg = (Msg) event.getEventData()[0];
                if (!bluetoothAdd.equals(msg.getBluetoothAdd()) || closed.get()) {
                    return;
                }
                try {
                    bufferedOutputStream.write(msg.getPayloadOrUtf8());
                    bufferedOutputStream.flush();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send to " + bluetoothAdd, e);
                    notifyDisconnect();
                    close();
                }
            }, listenerUuid);

            StaticObject.connectionRegistry.set(address, BluetoothConnectionState.CONNECTED);

            receiveThread = new Thread(this::receiveLoop, "bt-receive-" + address);
            receiveThread.start();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to initialize session " + address, e);
            close();
            return false;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1024];
        CrlfFrameDecoder decoder = new CrlfFrameDecoder();
        try {
            int read;
            while (!closed.get() && (read = bufferedInputStream.read(buffer)) != -1) {
                for (byte[] frame : decoder.append(buffer, read)) {
                    Msg msg = new Msg(frame, Msg.TYPE_RECEIVED, bluetoothAdd);
                    msg.setContent(new String(frame, StandardCharsets.UTF_8));
                    msg.setSendUuid(sendUuid);
                    msg.setBluetoothName(bluetoothName);
                    StaticObject.mTaskQueue.put(msg);
                }
            }
            if (!closed.get()) {
                notifyDisconnect();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                Log.e(TAG, "Receive failed for " + bluetoothAdd, e);
                notifyDisconnect();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    private void notifyDisconnect() {
        if (!disconnectNotified.compareAndSet(false, true) || bluetoothAdd == null) {
            return;
        }
        Msg msg = new Msg(bluetoothAdd);
        msg.setStateType(1);
        StaticObject.mTaskQueue.offer(msg);
    }

    public boolean isConnected() {
        return !closed.get() && bluetoothSocket != null && bluetoothSocket.isConnected();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (listenerUuid != null) {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerUuid);
        }
        if (bluetoothAdd != null) {
            StaticObject.bluetoothSocketMap.remove(bluetoothAdd, this);
            StaticObject.connectionRegistry.set(bluetoothAdd, BluetoothConnectionState.DISCONNECTED);
        }
        closeQuietly(bufferedOutputStream);
        closeQuietly(bufferedInputStream);
        closeSocketQuietly(bluetoothSocket);
        if (receiveThread != null && receiveThread != Thread.currentThread()) {
            receiveThread.interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocketQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
