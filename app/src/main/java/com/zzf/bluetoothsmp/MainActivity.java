package com.zzf.bluetoothsmp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;

import com.example.bluetoothsmp.R;
import com.example.bluetoothsmp.databinding.ActivityHomeBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.zzf.bluetoothsmp.base.BaseActivity;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.utils.CheckUpdate;
import com.zzf.bluetoothsmp.utils.LanguageUtils;
import com.zzf.bluetoothsmp.utils.MonitorMessage;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.Locale;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import static com.example.bluetoothsmp.R.string.ConnectTheInterrupt;

public class MainActivity extends BaseActivity {

    private BluetoothAdapter mBluetooth;

    private static final String TAG = "MainActivity";
    private final int mOpenCode = 0x01;
    public int scan = 1;
    private boolean isCreate = false;
    private ActivityHomeBinding binding;
    private OnActivityDataChangedListener onActivityDataChangedListener;


    @SuppressLint("ResourceAsColor")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        new CheckUpdate().check(MainActivity.this);
        // ??????title
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        BottomNavigationView navView = findViewById(R.id.nav_view);
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_home, R.id.navigation_dashboard)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_activity_home);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.navView, navController);

        new MonitorMessage().MonitorAndSaveMse();

        cratePermission();
        isCreate = true;
        //??????????????????
        ActivityCompat.requestPermissions(MainActivity.this, mPermissionListnew, mOpenCode);
    }

    public static void actionActivity(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }


    @Override
    public int getLayoutId() {
        return R.layout.activity_main;
    }


    public void toSetLanguage(int type) {
        Locale locale;
        Context context = MyApplication.getAppContext();
        if (type == 0) {
            locale = LanguageUtils.getSystemLocale();
            LanguageUtils.saveAppLocaleLanguage(LanguageUtils.SYSTEM_LANGUAGE_TGA);
        } else if (type == 1) {
            locale = Locale.US;
            LanguageUtils.saveAppLocaleLanguage(locale.toLanguageTag());
        } else if (type == 2) {
            locale = Locale.SIMPLIFIED_CHINESE;
            LanguageUtils.saveAppLocaleLanguage(locale.toLanguageTag());
        } else {
            return;
        }
      /*  if (LanguageUtils.isSimpleLanguage(context, locale)) {
            Toast.makeText(context, "????????????????????????????????????", Toast.LENGTH_SHORT).show();
            return;
        }*/
        LanguageUtils.updateLanguage(context, locale);
        MainActivity.actionActivity(context);
    }






/*    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int currentNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        Log.d(TAG, "??????????????????: ??????????????????22222222222222222222222");
        switch (currentNightMode) {
            case Configuration.UI_MODE_NIGHT_NO:
                Log.d(TAG, "??????????????????: ??????????????????");
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                Log.d(TAG, "??????????????????: ????????????");
                break;
        }
    }*/

    public void onStart() {
        super.onStart();
        beginDiscovery();
    }

    public void onRestart() {
        super.onRestart();

    }

    public void onPause() {
        super.onPause();
    }

    @SuppressLint("MissingPermission")
    public void onStop() {
        super.onStop();
        // fruitList.clear();
        //adapter = new FruitAdapter(fruitList);
        // mRecyclerView.setAdapter(adapter);
        //??????????????????????????????????????????
        //unregisterReceiver(discoveryReceiver);
        if (mBluetooth.isDiscovering()) {
            //mBluetooth.startDiscovery();//?????????????????????????????????
            mBluetooth.cancelDiscovery();
        }
        isCreate = false;
    }

    public void onResume() {
        super.onResume();
    }


    Thread sendEvent = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                while (true) {
                    Msg take = StaticObject.mTaskQueue.take();
                    if (take != null) {
                        switch (take.getStateType()) {
                            case 0:
                                int type = take.getType();
                                if (0 == type) {
                                    StaticObject.bluetoothEvent.receiveMsg(take);
                                } else {
                                    StaticObject.bluetoothEvent.senMsg(take);
                                }
                                StaticObject.bluetoothEvent.AllMsg(take);
                                break;
                            case 1:
                                StaticObject.bluetoothEvent.notConnect(take);
                                break;
                            default:
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    });

    public interface OnActivityDataChangedListener {
        void addFruitData(Fruit string);
    }

    public void setOnActivityDataChangedListener(OnActivityDataChangedListener addFruitData) {
        this.onActivityDataChangedListener = addFruitData;
    }


    // ????????????
    @Override
    @SuppressLint("MissingPermission")
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (Integer permission : grantResults) {
            if (permission != 0) {
                SystemExit(getString(R.string.run));
                return;
            }
        }
        //???????????????????????????????????????
        if (mBluetooth == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //???????????????????????????????????????
                BluetoothManager bm = getSystemService(BluetoothManager.class);
                mBluetooth = bm.getAdapter();
            } else {
                //????????????????????????????????????
                mBluetooth = BluetoothAdapter.getDefaultAdapter();
            }
            if (mBluetooth == null) {
                SystemExit(getString(R.string.BluetoothNotFound));
                return;
            }
        }
        //?????????????????????
        if (!mBluetooth.isEnabled()) {
            boolean enable = mBluetooth.enable();
            if (!enable) {
                SystemExit(getString(R.string.initBluetooth));
                return;
            }
        }
        if (requestCode == mOpenCode) {
            //???????????????
            initBluetooth();
            try {
                BluetoothService bluetoothService = new BluetoothService();
                //??????????????????
                bluetoothService.createService(this, mBluetooth);
                sendEvent.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    @SuppressLint("MissingPermission")
    private void beginDiscovery() {
        if (mBluetooth != null && !mBluetooth.isDiscovering()) {
            mBluetooth.startDiscovery();//?????????????????????????????????
        }
    }

    @SuppressLint({"MissingPermission", "HardwareIds"})
    public void initBluetooth() {

        if (mBluetooth.getName() != null && mBluetooth.getName().length() > 0) {
            StaticObject.myBluetoothName = mBluetooth.getName();
        } else {
            StaticObject.myBluetoothName = mBluetooth.getAddress();
        }
        StaticObject.myBluetoothAdd = mBluetooth.getAddress();

        //????????????
        beginDiscovery();
        Set<BluetoothDevice> bondedDevices = mBluetooth.getBondedDevices();

        if (bondedDevices != null && bondedDevices.size() != 0) {
            for (BluetoothDevice device : bondedDevices) {
                Fruit fruit = new Fruit();
                fruit.setAddress(device.getAddress());
                fruit.setName(device.getName());
                fruit.setState(device.getBondState());
                fruit.setBluetoothType(device.getType());
                fruit.setBluetoothDevice(device);
                onActivityDataChangedListener.addFruitData(fruit);
            }
        }
        //????????????????????????????????????IntentFilter?????????addAction???????????????
        IntentFilter discoveryFilter = new IntentFilter();
        //??????????????????
        discoveryFilter.addAction(BluetoothDevice.ACTION_FOUND);
        //????????????
        discoveryFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        //????????????
        discoveryFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        //????????????????????????
        discoveryFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        //??????????????????
        discoveryFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        //??????????????????????????????????????????
        registerReceiver(discoveryReceiver, discoveryFilter);
    }

    @SuppressLint("MissingPermission")
    public final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {

        @RequiresApi(api = Build.VERSION_CODES.R)
        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int bondState = device.getBondState();
            Fruit fruit = new Fruit();
            fruit.setAddress(device.getAddress());

            //????????????????????????
            fruit.setName(device.getName());
            if (fruit.getName() == null || fruit.getName().length() == 0) {
                fruit.setName("N/A");
            }
            fruit.setState(bondState);
            fruit.setBluetoothType(device.getType());
            short rssi = intent.getExtras().getShort(BluetoothDevice.EXTRA_RSSI);
            fruit.setRssi(rssi + "");
            fruit.setBluetoothDevice(device);
            onActivityDataChangedListener.addFruitData(fruit);

            switch (action) {
                case BluetoothDevice.ACTION_FOUND:
                    break;
                //??????????????????
                //??????????????????
                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                case BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED:
                    BluetoothServiceConnect remove = StaticObject.bluetoothSocketMap.remove(device.getAddress());
                    if (remove != null) {
                        ToastUtil.toastWord(MainActivity.this, MainActivity.this.getString(ConnectTheInterrupt));
                        remove.close();
                        Msg m = new Msg(device.getAddress());
                        m.setStateType(1);
                        try {
                            StaticObject.mTaskQueue.put(m);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    //??????????????????
                case BluetoothDevice.ACTION_ACL_CONNECTED:
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
/*                    fruit.setState(bondState);
                    short rssi = intent.getExtras().getShort(BluetoothDevice.EXTRA_RSSI);
                    fruit.setRssi(rssi + "");
                    onActivityDataChangedListener.addFruitData(fruit);*/
                    //adapter.notifyDataSetChanged();
                    break;
            }
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ?????????????????????back???
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
            dialog.setTitle(getString(R.string.tips));
            dialog.setMessage(getString(R.string.out));
            dialog.setCancelable(false);
            dialog.setPositiveButton(getString(R.string.confirm), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    onBackPressed();
                    finish();
                }
            });
            dialog.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });
            dialog.show();
            return true;
        } else {// ????????????back???????????????
            return super.onKeyDown(keyCode, event);
        }
    }

    public void SystemExit(String message) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
        dialog.setTitle("??????");
        dialog.setMessage(message);
        dialog.setCancelable(false);
        dialog.setPositiveButton("??????", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                System.exit(0);
            }
        });
        dialog.create().show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int MY_REQUEST_CODE = 01;
        if (requestCode == MY_REQUEST_CODE) {
            if (resultCode != RESULT_OK) {

            }
        }
    }

    public Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    String message = (String) msg.obj;
                    ToastUtil.toastWord(MainActivity.this, message);
                    break;
                default:
                    Log.e(TAG, "Unknown msg " + msg.what);
                    break;
            }
        }
    };


    // ??????????????????
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.home_menu, menu);
        return true;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        //return super.onOptionsItemSelected(item);
        switch (item.getItemId()) {
            case R.id.Disconnect:
                break;
        }
        return true;
    }


    public BluetoothAdapter getmBluetooth() {
        return mBluetooth;
    }
}