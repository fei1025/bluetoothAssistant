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
import androidx.appcompat.widget.Toolbar;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayoutMediator;
import com.zzf.bluetoothsmp.BluetoothServiceConnect;
import com.zzf.bluetoothsmp.MyApplication;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.base.BaseActivity;
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

public class Liantian_new extends BaseActivity {

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
    private BluetoothServiceConnect sessionConnection;
    private String sessionId;
    private static final String STATE_SESSION_ID = "chat_transport_session";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLiantianNewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
        applyEdgeToEdgeForChatPage();
        initData();
        sessionId = savedInstanceState == null ? null : savedInstanceState.getString(STATE_SESSION_ID);
        if (drive == null || drive.getDriveAdd() == null) {
            finish();
            return;
        }
        BluetoothServiceConnect current = StaticObject.bluetoothSocketMap.get(drive.getDriveAdd());
        if (current != null && (sessionId == null || sessionId.equals(current.getSessionId()))) {
            sessionConnection = current;
            sessionId = current.getSessionId();
            drive.setUuid(current.getSendUuid());
        }

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
            bluetoothName = drive == null ? getString(R.string.unknown) : drive.getDriveAdd();
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

    public void senMsg(String text) {
        sendText(text);
    }

    public boolean sendText(String text) {
        BluetoothServiceConnect connection = sessionConnection;
        if (drive == null || connection == null
                || StaticObject.bluetoothSocketMap.get(drive.getDriveAdd()) != connection) {
            ToastUtil.toastWord(this, getString(R.string.chat_connection_ended));
            return false;
        }
        if (text == null || text.isEmpty()) return false;
        try {
            Msg message = new Msg(connection.encodeTextPayload(text), Msg.TYPE_SENT, drive.getDriveAdd());
            message.setContent(text);
            if (!connection.bindOutboundMessage(message)) {
                ToastUtil.toastWord(this, getString(R.string.chat_connection_ended));
                return false;
            }
            if (!StaticObject.submitMessage(message)) {
                ToastUtil.toastWord(this, getString(R.string.send_queue_full));
                return false;
            }
            return true;
        } catch (IllegalArgumentException error) {
            ToastUtil.toastWord(this, error.getMessage());
            return false;
        }
    }



    public void exit() {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(UUID);
        BluetoothServiceConnect connection = sessionConnection;
        if (connection != null) {
            // Let the session remove itself so the connection registry also reaches
            // DISCONNECTED; removing the map entry first would hide that transition.
            connection.close();
        }
        finish();
    }

    /** A remote/stale disconnect finishes this screen without closing a replacement transport. */
    public void finishSession() {
        finish();
    }

    public String getSessionId() { return sessionId; }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessionId != null && (sessionConnection == null
                || StaticObject.bluetoothSocketMap.get(drive.getDriveAdd()) != sessionConnection)) {
            finishSession();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString(STATE_SESSION_ID, sessionId);
        super.onSaveInstanceState(state);
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
