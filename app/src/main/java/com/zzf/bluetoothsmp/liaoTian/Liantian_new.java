package com.zzf.bluetoothsmp.liaoTian;

import static com.example.bluetoothsmp.R.string.ConnectTheInterrupt;

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

import com.example.bluetoothsmp.R;
import com.example.bluetoothsmp.databinding.ActivityLiantianNewBinding;
import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayoutMediator;
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

public class Liantian_new extends AppCompatActivity {

    private ActivityLiantianNewBinding binding;

    public static List<Fragment> listFragment = new ArrayList<>();

    public static final int[] TAB_TITLES = new int[]{R.string.chat, R.string.keyboard};


    public String TAG = "liantian_new";
/*
    private String bluetoothAdd;
*/
    public String UUID;
/*    String bluetoothName;
    String bluetoothUUid;*/
    String infoType;

    BluetoothDrive drive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLiantianNewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        ViewPager2 viewPager = findViewById(R.id.view_pager);
        SectionsPagerAdapter adapter = new SectionsPagerAdapter(this);
        viewPager.setAdapter(adapter);
        TabLayout tabs = binding.tabs;

        new TabLayoutMediator(tabs, viewPager, new TabLayoutMediator.TabConfigurationStrategy() {
            @Override
            public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
                // tab:当前处于选中状态的Tab对象
                // position:当前Tab所处的位置
                tab.setText(TAB_TITLES[position]);
            }
        }).attach();// 不要忘记attach()，否则没效果

        Toolbar toolbar = findViewById(R.id.lao_tian_toolbar);
        toolbar.setTitle("demo");
        setSupportActionBar(toolbar);
        initData();
        initFragment();
    }

    public void initFragment() {
        listFragment.clear();
        listFragment.add(new ChatModeFragment(drive));
        listFragment.add(new KeyboardFragment(drive));
    }
    private void initData(){
        UUID = java.util.UUID.randomUUID().toString();
        Toolbar toolbar = findViewById(R.id.lao_tian_toolbar);

        drive = (BluetoothDrive) getIntent().getSerializableExtra("BluetoothDrive");
        String bluetoothName=null;
        if(drive !=null){
         bluetoothName=drive.getDriveName();
        infoType = getIntent().getStringExtra("infoType");
        }
        if (bluetoothName == null || bluetoothName.length() == 0) {
            bluetoothName = "无";
        }
        toolbar.setTitle(bluetoothName);


/*        bluetoothName = getIntent().getStringExtra("bluetoothName");
        bluetoothAdd = getIntent().getStringExtra("bluetoothAdd");
        bluetoothUUid = getIntent().getStringExtra("bluetoothUUid");*/

        //setSupportActionBar(toolbar);



    }
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.liao_tian, menu);
        return true;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        //return super.onOptionsItemSelected(item);
        switch (item.getItemId()) {
            case R.id.Disconnect:
                if ("0".equals(infoType)) {
                    dialog(getString(R.string.bluetoothDisconnected));
                } else {
                    dialog(getString(R.string.cutBluetooth));
                }
                break;
 /*           case R.id.keyboardModer:
                // Intent liaoTian = new Intent(this, keyboard.class);
                Intent liaoTian = new Intent(this, Liantian_new.class);
                this.startActivity(liaoTian);*/
        }
        return true;
    }

    public void senMsg(String s){
        BluetoothServiceConnect bluetoothServiceConnect = StaticObject.bluetoothSocketMap.get(drive.getDriveAdd());
        if (bluetoothServiceConnect == null) {
            ToastUtil.toastWord(MyApplication.getContext(), "请连接后重试");
            return;
        }
        if (!"".equals(s)) {
            Msg eventDatum = new Msg(s, Msg.TYPE_SENT, drive.getDriveAdd());
            try {
                eventDatum.setBluetoothName(drive.getDriveName());
                eventDatum.setBluetoothAdd(drive.getDriveAdd());
                eventDatum.setSendUuid(drive.getUuid());
                StaticObject.mTaskQueue.put(eventDatum);
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "senMsg: 发送信息异常", e);
            }
        }
    }



    public void exit() {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(UUID);
        BluetoothServiceConnect remove = StaticObject.bluetoothSocketMap.remove(drive.getDriveAdd());
        if (remove != null) {
            remove.close();
        }
        finish();
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 是否触发按键为back键
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if ("0".equals(infoType)) {exit();
                return true;
            }
            dialog(getString(R.string.cutBluetooth));
            return true;
        } else {// 如果不是back键正常响应
            return super.onKeyDown(keyCode, event);
        }
    }
    public void dialog(String s) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(Liantian_new.this);
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



    public BluetoothDrive getDrive() {
        return drive;
    }
}