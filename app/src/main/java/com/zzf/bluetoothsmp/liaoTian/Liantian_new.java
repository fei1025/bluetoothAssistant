package com.zzf.bluetoothsmp.liaoTian;

import static com.zzf.bluetoothsmp.R.string.ConnectTheInterrupt;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.databinding.ActivityLiantianNewBinding;
import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
import com.zzf.bluetoothsmp.fragment.DebugFragment;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Liantian_new extends AppCompatActivity {

    private ActivityLiantianNewBinding binding;

    public static final int[] TAB_TITLES = new int[]{R.string.chat, R.string.keyboard, R.string.debug};


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
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        binding = ActivityLiantianNewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
        applyEdgeToEdgeForChatPage();
        initData();

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
    }

    private void applyEdgeToEdgeForChatPage() {
        View appBar = findViewById(R.id.chat_appbar);
        View pager = findViewById(R.id.view_pager);
        if (appBar == null || pager == null) {
            return;
        }
        final int appBarPaddingStart = appBar.getPaddingStart();
        final int appBarPaddingTop = appBar.getPaddingTop();
        final int appBarPaddingEnd = appBar.getPaddingEnd();
        final int appBarPaddingBottom = appBar.getPaddingBottom();
        final int pagerPaddingStart = pager.getPaddingStart();
        final int pagerPaddingTop = pager.getPaddingTop();
        final int pagerPaddingEnd = pager.getPaddingEnd();
        final int pagerPaddingBottom = pager.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());

            appBar.setPaddingRelative(
                    appBarPaddingStart,
                    appBarPaddingTop + safeInsets.top,
                    appBarPaddingEnd,
                    appBarPaddingBottom
            );

            pager.setPaddingRelative(
                    pagerPaddingStart + safeInsets.left,
                    pagerPaddingTop,
                    pagerPaddingEnd + safeInsets.right,
                    pagerPaddingBottom + safeInsets.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
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

    public void senMsg(String s){
        System.out.println("-----------------------------------");
        System.out.println(s);
        BluetoothServiceConnect bluetoothServiceConnect = StaticObject.bluetoothSocketMap.get(drive.getDriveAdd());
        if (bluetoothServiceConnect == null) {
            ToastUtil.toastWord(MyApplication.getContext(), "请连接后重试");
            return;
        }
        if (!"".equals(s)) {
            try {
                byte[] payload = bluetoothServiceConnect.encodeTextPayload(s);
                Msg eventDatum = new Msg(payload, Msg.TYPE_SENT, drive.getDriveAdd());
                eventDatum.setContent(s);
                eventDatum.setBluetoothName(drive.getDriveName());
                eventDatum.setBluetoothAdd(drive.getDriveAdd());
                eventDatum.setSendUuid(drive.getUuid());
                StaticObject.mTaskQueue.put(eventDatum);
            } catch (IllegalArgumentException error) {
                ToastUtil.toastWord(this, error.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "senMsg: 发送信息异常", e);
            }
        }
    }



    public void exit() {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(UUID);
        BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(drive.getDriveAdd());
        if (connection != null) {
            // Let the session remove itself so the connection registry also reaches
            // DISCONNECTED; removing the map entry first would hide that transition.
            connection.close();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(UUID);
        super.onDestroy();
        binding = null;
    }


    private void handleBackPressed() {
        if ("0".equals(infoType)) {
            exit();
        } else {
            dialog(getString(R.string.cutBluetooth));
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
