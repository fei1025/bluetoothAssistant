package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.event.EventDispatcher;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.loading.WeiboDialogUtils;
import com.zzf.bluetoothsmp.utils.StringUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import static com.zzf.bluetoothsmp.R.string.bluetoothConnection;

import org.litepal.LitePal;

public class BluetoothObject extends EventDispatcher {
    static final String TAG = "BluetoothObject";
    public static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB";

    private BluetoothDevice bluetoothDevice;
    private BufferedOutputStream bufferedOutputStream;
    private BufferedInputStream bufferedInputStream;
    private Handler mHandler;
    private Context mcontex;
    private Context lanLaContex;
    BluetoothSocket insecureRfcommSocketToServiceRecord;
    private RecyclerView msgRecyclerView;
    private Dialog loadingDialog;
    private boolean connectStart = false;
    private volatile boolean shouldStop = false; // 用于安全停止线程的标志

    public Context getLanLaContex() {
        return lanLaContex;
    }

    public void setLanLaContex(Context lanLaContex) {
        this.lanLaContex = lanLaContex;
    }

    public void connect(@NonNull Context contex, Handler mHandler) {
        mcontex = contex;
        shouldStop = false; // 重置停止标志
        loadingDialog = WeiboDialogUtils.createLoadingDialog(mcontex, contex.getString(bluetoothConnection));
        connectFlag.start();
        checkConnect.start();
        Waiting.start();
        this.mHandler = mHandler;

    }

    /**
     * 安全地停止所有线程
     * 使用 interrupt() 方法而不是已废弃的 stop() 方法
     */
    private void stopThreads() {
        shouldStop = true;
        if (connectFlag != null && connectFlag.isAlive()) {
            connectFlag.interrupt();
        }
        if (checkConnect != null && checkConnect.isAlive()) {
            checkConnect.interrupt();
        }
    }

    private Thread Waiting = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                Thread.sleep(1000L * 15);
                if (!connectStart) {
                    WeiboDialogUtils.closeDialog(loadingDialog);
                    senHandlerMessage(0, mcontex.getString(R.string.connect_fails));
                    stopThreads();
                    connectStart = false;

                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    });


    public void senHandlerMessage(Integer what, Object obj) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }


    private Thread checkConnect = new Thread(new Runnable() {
        @Override
        public void run() {
            while (!shouldStop && !Thread.currentThread().isInterrupted()) {
                if (connectStart) {
                    WeiboDialogUtils.closeDialog(loadingDialog);
                    return;
                }
                try {
                    Thread.sleep(100); // 避免 CPU 空转
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    });
    private final Thread connectFlag = new Thread(new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            try {
                // 检查是否应该停止
                if (shouldStop || Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                //BluetoothBle bluetoothBle = new BluetoothBle(bluetoothDevice);
                SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);
                if (first!=null && StringUtils.isNotEmpty(first.getClientSpp())) {
                    String clientSpp = first.getClientSpp();
                    try {
                        UUID uuid = UUID.fromString(clientSpp);
                        insecureRfcommSocketToServiceRecord = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(uuid);
                    } catch (Exception e) {
                        WeiboDialogUtils.closeDialog(loadingDialog);
                        connectStart = false;
                        senHandlerMessage(0, mcontex.getString(R.string.bluetooth_port_error));
                        e.printStackTrace();
                        return;
                    }
                } else {
                    insecureRfcommSocketToServiceRecord = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID));
                }

                // 再次检查是否应该停止
                if (shouldStop || Thread.currentThread().isInterrupted()) {
                    return;
                }

                insecureRfcommSocketToServiceRecord.connect();
                
                // 连接后再次检查
                if (shouldStop || Thread.currentThread().isInterrupted()) {
                    if (insecureRfcommSocketToServiceRecord != null && insecureRfcommSocketToServiceRecord.isConnected()) {
                        try {
                            insecureRfcommSocketToServiceRecord.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    return;
                }
                
                BluetoothServiceConnect bluetoothServiceConnect = new BluetoothServiceConnect();
                bluetoothServiceConnect.start(mcontex, insecureRfcommSocketToServiceRecord, SPP_UUID);
                connectStart = true;
                //Intent liaoTian = new Intent(mcontex, Liao_tian.class);
                Intent liaoTian = new Intent(mcontex, Liantian_new.class);
                String name = bluetoothDevice.getName();
                if (name == null || name.length() == 0) {
                    name = bluetoothDevice.getAddress();
                }
                liaoTian.putExtra("bluetoothName", name);
                liaoTian.putExtra("bluetoothAdd", bluetoothDevice.getAddress());
                liaoTian.putExtra("bluetoothUUid", SPP_UUID);
                BluetoothDrive drive = new BluetoothDrive();
                drive.setDriveName(name);
                drive.setDriveAdd(bluetoothDevice.getAddress());
                drive.setUuid(SPP_UUID);
                liaoTian.putExtra("BluetoothDrive", drive);
                mcontex.startActivity(liaoTian);
            } catch (Exception e) {
                WeiboDialogUtils.closeDialog(loadingDialog);
                connectStart = false;
                senHandlerMessage(0, mcontex.getString(R.string.connect_fails));
                e.printStackTrace();
            }
        }
    });

    public BluetoothDevice getBluetoothDevice() {
        return bluetoothDevice;
    }

    public void setBluetoothDevice(BluetoothDevice bluetoothDevice) {
        this.bluetoothDevice = bluetoothDevice;
    }

    public Handler getmHandler() {
        return mHandler;
    }

    public void setmHandler(Handler mHandler) {
        this.mHandler = mHandler;
    }

    public RecyclerView getMsgRecyclerView() {
        return msgRecyclerView;
    }

    public void setMsgRecyclerView(RecyclerView msgRecyclerView) {
        this.msgRecyclerView = msgRecyclerView;
    }

    public boolean isConnectStart() {
        return connectStart;
    }

    public void setConnectStart(boolean connectStart) {
        this.connectStart = connectStart;
    }

    public BufferedOutputStream getBufferedOutputStream() {
        return bufferedOutputStream;
    }

    public void setBufferedOutputStream(BufferedOutputStream bufferedOutputStream) {
        this.bufferedOutputStream = bufferedOutputStream;
    }

    public BufferedInputStream getBufferedInputStream() {
        return bufferedInputStream;
    }

    public void setBufferedInputStream(BufferedInputStream bufferedInputStream) {
        this.bufferedInputStream = bufferedInputStream;
    }
}
