package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.customAdapter.MsgAdapter;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.zzf.bluetoothsmp.R.string.ConnectTheInterrupt;

public class Liao_tian extends AppCompatActivity {
    static public List<Msg> msgList;
    private EditText inputText;
    RecyclerView msgRecyclerView;
    public String TAG = "Liao_tian";
    private String bluetoothAdd;
    private String UUID;
    String bluetoothName;
    String bluetoothUUid;
    String infoType;

    public Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    List<Msg> msgList = Liao_tian.msgList;
                    msgList.add((Msg) msg.obj);
                    Objects.requireNonNull(msgRecyclerView.getAdapter()).notifyItemChanged(msgList.size() - 1);
                    msgRecyclerView.scrollToPosition(msgList.size() - 1);
                    break;
                case 1:
                    ToastUtil.toastWord(Liao_tian.this, Liao_tian.this.getString(ConnectTheInterrupt));
                    exit();
                    break;
                default:
                    Log.e(TAG, "Unknown msg " + msg.what);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UUID = java.util.UUID.randomUUID().toString();
        msgList = new ArrayList<>();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_liao_tian);
        Toolbar toolbar = findViewById(R.id.lao_tian_toolbar);
        bluetoothName = getIntent().getStringExtra("bluetoothName");
        bluetoothAdd = getIntent().getStringExtra("bluetoothAdd");
        bluetoothUUid = getIntent().getStringExtra("bluetoothUUid");
        infoType = getIntent().getStringExtra("infoType");
        if (bluetoothName == null || bluetoothName.length() == 0) {
            bluetoothName = "无";
        }
        toolbar.setTitle(bluetoothName);
        setSupportActionBar(toolbar);
        inputText = findViewById(R.id.input_text);
        Button send = findViewById(R.id.send);
        msgRecyclerView = findViewById(R.id.msg_recycler_view);
        LinearLayoutManager linearLayout = new LinearLayoutManager(this);
        msgRecyclerView.setLayoutManager(linearLayout);
        initMsg();
        MsgAdapter adapter = new MsgAdapter(msgList);
        msgRecyclerView.setAdapter(adapter);
        Objects.requireNonNull(msgRecyclerView.getAdapter()).notifyItemChanged(msgList.size() - 1);
        msgRecyclerView.scrollToPosition(msgList.size() - 1);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BluetoothServiceConnect bluetoothServiceConnect = StaticObject.bluetoothSocketMap.get(bluetoothAdd);
                if (bluetoothServiceConnect == null) {
                    ToastUtil.toastWord(MyApplication.getContext(), "请连接后重试");
                    return;
                }
                String s = inputText.getText().toString();
                if (!"".equals(s)) {
                    Msg eventDatum = new Msg(s, Msg.TYPE_SENT, bluetoothAdd);
                    try {
                        eventDatum.setBluetoothName(bluetoothName);
                        eventDatum.setBluetoothAdd(bluetoothAdd);
                        eventDatum.setSendUuid(bluetoothUUid);
                        StaticObject.mTaskQueue.put(eventDatum);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    inputText.setText("");
                }
            }
        });
        //监听接受数据
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            if (bluetoothAdd.equals(msg.getBluetoothAdd())) {
                senHandlerMsg(0, msg);
            }
        }, UUID);
        //监听发送数据
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            Log.d(TAG, "onCreate: " + msg.toString());
            if (bluetoothAdd.equals(msg.getBluetoothAdd())) {
                senHandlerMsg(0, msg);
            }
        }, UUID);
        //监听断开连接
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            if (bluetoothAdd.equals(msg.getBluetoothAdd())) {
                senHandlerMsg(1, msg);
            }
        }, UUID);
    }


  /*  // 创建多选择框按钮
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.liao_tian, menu);
        return true;
    }*/


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        //return super.onOptionsItemSelected(item);
        if (item.getItemId() == R.id.Disconnect) {
            if ("0".equals(infoType)) {
                dialog(getString(R.string.bluetoothDisconnected));
            } else {
                dialog(getString(R.string.cutBluetooth));
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 是否触发按键为back键
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if ("0".equals(infoType)) {
                exit();
                return true;
            }
            dialog(getString(R.string.cutBluetooth));
            return true;
        } else {// 如果不是back键正常响应
            return super.onKeyDown(keyCode, event);
        }
    }

    public void initMsg() {
        if (bluetoothAdd == null && bluetoothUUid == null) {
            return;
        }
        List<MessageMapper> messageList = LitePal.where(" sendAdd =? and sendUuid = ?", bluetoothAdd, bluetoothUUid).order("sendTime ").find(MessageMapper.class);
        if (messageList != null && messageList.size() != 0) {
            for (MessageMapper ms : messageList) {

                Msg m = new Msg(ms.getMessage(), ms.getType(), ms.getSendAdd());
                if (Msg.TYPE_RECEIVED == ms.getType()) {
                    m.setBluetoothName(ms.getSendName());
                } else {
                    m.setBluetoothName(ms.getReceiveName());
                }
                m.setSendUuid(ms.getSendUuid());
                msgList.add(m);
            }

        }
    }

    public void dialog(String s) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(Liao_tian.this);
        dialog.setTitle(getString(R.string.tips));
        dialog.setMessage(s);
        dialog.setCancelable(false);
        dialog.setPositiveButton(getString(R.string.confirm), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                exit();
            }
        });
        dialog.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        dialog.show();
    }

    public void exit() {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(UUID);
        BluetoothServiceConnect remove = StaticObject.bluetoothSocketMap.remove(bluetoothAdd);
        if (remove != null) {
            remove.close();
        }
        finish();
    }


    public void senHandlerMsg(int what, Object m) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = m;
        mHandler.sendMessage(msg);
    }
}