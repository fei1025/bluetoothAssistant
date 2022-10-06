package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
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

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.customAdapter.MsgAdapter;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.example.bluetoothsmp.R.string.ConnectTheInterrupt;

public class Liao_tian extends AppCompatActivity {
    static public List<Msg> msgList;
    private EditText inputText;
    RecyclerView msgRecyclerView;
    public String TAG = "Liao_tian";
    private String bluetoothAdd;
    private String UUID;
    String bluetoothName;
    String bluetoothUUid;

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
        if(bluetoothName ==null || bluetoothName.length()==0){
            bluetoothName="无";
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
                if(bluetoothServiceConnect ==null){
                    ToastUtil.toastWord(MyApplication.getContext(),"请连接后重试");
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

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        //return super.onOptionsItemSelected(item);
        switch (item.getItemId()) {
            case R.id.Disconnect:
                dialog();
                break;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onBackPressed: 这里是返回按键是??????"+keyCode);
        // 是否触发按键为back键
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dialog();
            return true;
        } else {// 如果不是back键正常响应
            return super.onKeyDown(keyCode, event);
        }
    }
    public void initMsg(){
        List<MessageMapper> messageList = LitePal.where(" sendAdd =? and sendUuid = ?",bluetoothAdd,bluetoothUUid).order("sendTime ").find(MessageMapper.class);
        if(messageList !=null && messageList.size() !=0 ){
            for (MessageMapper ms: messageList){

                Msg m = new Msg(ms.getMessage(),ms.getType(),ms.getSendAdd());
                if(Msg.TYPE_RECEIVED==ms.getType()){
                    m.setBluetoothName(ms.getSendName());
                }else {
                    m.setBluetoothName(ms.getReceiveName());
                }
                m.setSendUuid(ms.getSendUuid());
                msgList.add(m);
            }

        }
    }

    public void dialog(){
        AlertDialog.Builder dialog = new AlertDialog.Builder(Liao_tian.this);
        dialog.setTitle("提示");
        dialog.setMessage("确定切断蓝牙,并返回吗?");
        dialog.setCancelable(false);
        dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                exit();
            }
        });
        dialog.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        dialog.show();
    }

    public void exit(){
        StaticObject.bluetoothEvent.deleteAllEventByUuid(UUID);
        BluetoothServiceConnect remove = StaticObject.bluetoothSocketMap.remove(bluetoothAdd);
        if (remove != null) {
            remove.close();
        }
        finish();
    }


    // 创建多选择框
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.liao_tian, menu);
        return true;
    }

    public void senHandlerMsg(int what, Object m) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = m;
        mHandler.sendMessage(msg);
    }
}