package com.zzf.bluetoothsmp.liaoTian;

import static com.example.bluetoothsmp.R.string.ConnectTheInterrupt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluetoothsmp.R;
import com.example.bluetoothsmp.databinding.FragmentLiantianNewBinding;
import com.zzf.bluetoothsmp.BluetoothServiceConnect;
import com.zzf.bluetoothsmp.MyApplication;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.customAdapter.MsgAdapter;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A placeholder fragment containing a simple view.
 */
public class ChatModeFragment extends Fragment {

    private static final String ARG_SECTION_NUMBER = "section_number";
    private  String TAG="ChatModeFragment";

    private FragmentLiantianNewBinding binding;
    private EditText inputText;
    BluetoothDrive drive;
    RecyclerView msgRecyclerView;
    static public List<Msg> msgList;
    private Liantian_new liantian_new;


    public ChatModeFragment(BluetoothDrive drive) {
        this.drive = drive;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {

        binding = FragmentLiantianNewBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        msgList = new ArrayList<>();
        liantian_new=(Liantian_new)getActivity();
        drive=liantian_new.getDrive();
        initView(root);
        initData(root);
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }


    public void initView(View root ){
        msgRecyclerView = root.findViewById(R.id.msg_recycler_view);
       LinearLayoutManager linearLayout = new LinearLayoutManager(getContext());
        msgRecyclerView.setLayoutManager(linearLayout);
        initMsg();
        MsgAdapter adapter = new MsgAdapter(msgList);
        msgRecyclerView.setAdapter(adapter);
        Objects.requireNonNull(msgRecyclerView.getAdapter()).notifyItemChanged(msgList.size() - 1);
        msgRecyclerView.scrollToPosition(msgList.size() - 1);
    }


    public void initData(View root){

        String bluetoothAdd=drive.getDriveAdd();
        //监听接受数据
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            if (bluetoothAdd.equals(msg.getBluetoothAdd())) {
                senHandlerMsg(0, msg);
            }
        }, liantian_new.UUID);
        //监听发送数据
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            Log.d(TAG, "onCreate: " + msg.toString());
            if (bluetoothAdd.equals(msg.getBluetoothAdd())) {
                senHandlerMsg(0, msg);
            }
        }, liantian_new.UUID);
        //监听断开连接
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, l -> {
            Msg msg = (Msg) l.getEventData()[0];
            if (bluetoothAdd.equals(msg.getBluetoothAdd())) {
                senHandlerMsg(1, msg);
            }
        }, liantian_new.UUID);


        inputText = root.findViewById(R.id.input_text);
        Button send = root.findViewById(R.id.send);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BluetoothServiceConnect bluetoothServiceConnect = StaticObject.bluetoothSocketMap.get(drive.getDriveAdd());
                if (bluetoothServiceConnect == null) {
                    ToastUtil.toastWord(MyApplication.getContext(), "请连接后重试");
                    return;
                }
                String s = inputText.getText().toString();
                if (!"".equals(s)) {
                    Msg eventDatum = new Msg(s, Msg.TYPE_SENT, drive.getDriveAdd());
                    try {
                        eventDatum.setBluetoothName(drive.getDriveName());
                        eventDatum.setBluetoothAdd(drive.getDriveAdd());
                        eventDatum.setSendUuid(drive.getUuid());
                        StaticObject.mTaskQueue.put(eventDatum);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    inputText.setText("");
                }
            }
        });
    }


    public void initMsg() {
        if(drive==null ||drive.getDriveAdd() == null || drive.getUuid() == null){
            return;
        }
        List<MessageMapper> messageList = LitePal.where(" sendAdd =? and sendUuid = ?", drive.getDriveAdd(), drive.getUuid()).order("sendTime ").find(MessageMapper.class);
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

    public Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    List<Msg> msgList = ChatModeFragment.msgList;
                    msgList.add((Msg) msg.obj);
                    Objects.requireNonNull(msgRecyclerView.getAdapter()).notifyItemChanged(msgList.size() - 1);
                    msgRecyclerView.scrollToPosition(msgList.size() - 1);
                    break;
                case 1:
                    ToastUtil.toastWord(getContext(), getContext().getString(ConnectTheInterrupt));
                    liantian_new.exit();
                    break;
                default:
                    Log.e(TAG, "Unknown msg " + msg.what);
                    break;
            }
        }
    };
    public void senHandlerMsg(int what, Object m) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = m;
        mHandler.sendMessage(msg);
    }


}