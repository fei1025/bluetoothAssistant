package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.customAdapter.FruitAdapter;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.utils.CheckUpdate;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import static com.example.bluetoothsmp.R.string.ConnectTheInterrupt;

public class MainActivity extends BaseActivity {

    private final Handler handler = new Handler();
    private BluetoothAdapter mBluetooth;
    RecyclerView mRecyclerView;
    public BluetoothObject bluetoothObject;
    private final List<Fruit> fruitList = new ArrayList<>();
    FruitAdapter adapter;
    private static final String TAG = "MainActivity";
    private final int mOpenCode = 0x01;
    public int scan = 1;
    private SwipeRefreshLayout swipeRefresh;


    @SuppressLint("ResourceAsColor")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new CheckUpdate().check(MainActivity.this);

        int defaultNightMode = AppCompatDelegate.getDefaultNightMode();
        Log.d(TAG, "当前什么模式:"+defaultNightMode);

        // 设置title
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mRecyclerView = findViewById(R.id.card_list);
        adapter = new FruitAdapter(fruitList);
        //设置大小固定
        // 说明 https://www.jianshu.com/p/582e88ee6aad/
        mRecyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setVisibility(View.GONE);
        //下拉刷新
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setColorSchemeColors(com.google.android.material.R.color.design_default_color_primary);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshFruits();


            }
        });
        adapter.setOnItemClickListener(new FruitAdapter.onItemDeleteListener() {
            @Override
            public void OnItemClick(int i) {
                bluetoothObject = new BluetoothObject();
                Fruit fruit = fruitList.get(i);
                BluetoothDevice bluetoothDevice = fruit.getBluetoothDevice();
                bluetoothObject.setBluetoothDevice(bluetoothDevice);
                try {
                    bluetoothObject.connect(MainActivity.this,mHandler);
                } catch (Exception e) {
                    ToastUtil.toastWord(MainActivity.this, getString(R.string.connect_fails));
                    e.printStackTrace();
                }
            }
        });
        cratePermission();
        //申请用户权限
        ActivityCompat.requestPermissions(MainActivity.this, mPermissionListnew, mOpenCode);

    }

/*    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int currentNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        Log.d(TAG, "当前什么模式: 这是日间模式22222222222222222222222");
        switch (currentNightMode) {
            case Configuration.UI_MODE_NIGHT_NO:
                Log.d(TAG, "当前什么模式: 这是日间模式");
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                Log.d(TAG, "当前什么模式: 夜间模式");
                break;
        }
    }*/

    private void refreshFruits() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                runOnUiThread(new Runnable() {
                    @SuppressLint("NotifyDataSetChanged")
                    @Override
                    public void run() {
                        unregisterReceiver(discoveryReceiver);
                        initBluetooth();
                        fruitList.clear();
                        adapter = new FruitAdapter(fruitList);
                        mRecyclerView.setAdapter(adapter);
                        swipeRefresh.setRefreshing(false);
                        //adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }


    public void onStart() {
        super.onStart();
        initBluetooth();
    }

    public void onRestart() {
        super.onRestart();

    }

    public void onPause() {
        super.onPause();
    }

    public void onStop() {
        super.onStop();
        fruitList.clear();
        adapter = new FruitAdapter(fruitList);
        mRecyclerView.setAdapter(adapter);
        //注销蓝牙设备搜索的广播接收器
        unregisterReceiver(discoveryReceiver);
    }

    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume:66666666666666666666666666666666666");


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


    // 权限回调
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        for (Integer permission : grantResults) {
            if (permission != 0) {
                SystemExit("未获取运行权限");
            }
        }

        if (requestCode == mOpenCode) {
            //初始化蓝牙
            initBluetooth();
            try {
                BluetoothService bluetoothService = new BluetoothService();
                //创建监听服务
                bluetoothService.createService(this, mBluetooth);
                sendEvent.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
            //开始扫描数据
            handler.postDelayed(runnable, 10L * scan);
        }
    }

    private final Runnable runnable = new Runnable() {
        public void run() {
            this.update();
            handler.postDelayed(this, 1000L * scan);// 间隔3秒
        }

        void update() {
            //刷新msg的内容
            beginDiscovery();
        }
    };

    @SuppressLint("MissingPermission")
    private void beginDiscovery() {
        if (!mBluetooth.isDiscovering()) {
            mBluetooth.startDiscovery();//开始扫描周围的蓝牙设备
        }
    }

    @SuppressLint("MissingPermission")
    private void initBluetooth() {
        //从系统服务中获取蓝牙管理器
        if (mBluetooth == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //从系统服务中获取蓝牙管理器
                BluetoothManager bm = getSystemService(BluetoothManager.class);
                mBluetooth = bm.getAdapter();
            } else {
                //获取系统默认的蓝牙适配器
                mBluetooth = BluetoothAdapter.getDefaultAdapter();
            }
            if (mBluetooth == null) {
                ToastUtil.toastWord(this, getString(R.string.BluetoothNotFound));
            }
        }
        //蓝牙服务未启动
        if (!mBluetooth.isEnabled()) {
            //启动服务
            boolean enable = mBluetooth.enable();
            if (!enable) {
                SystemExit(getString(R.string.initBluetooth));
            }
        }
        //需要过滤多个动作，则调用IntentFilter对象的addAction添加新动作
        IntentFilter discoveryFilter = new IntentFilter();
        //获取新的数据
        discoveryFilter.addAction(BluetoothDevice.ACTION_FOUND);
        //连接上了
        discoveryFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        //状态改变
        discoveryFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        //蓝牙连接状态更改
        discoveryFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        //蓝牙即将断开
        discoveryFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        //注册蓝牙设备搜索的广播接收器
        registerReceiver(discoveryReceiver, discoveryFilter);
    }

    @SuppressLint("MissingPermission")
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {

        @RequiresApi(api = Build.VERSION_CODES.R)
        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int bondState = device.getBondState();
            Fruit fruit = new Fruit();
            fruit.setAddress(device.getAddress());
            switch (action) {
                case BluetoothDevice.ACTION_FOUND:
                    //发现新的蓝牙设备
                    if (!fruitList.contains(fruit)) {
                   /*     ParcelUuid[] uuids = device.getUuids();
                        if (uuids != null) {
                            Log.d(TAG, "发现蓝牙设备::" + device.getAddress() + ":");
                            for (int i = 0; i < uuids.length; i++) {
                                Log.d(TAG, "发现蓝牙设备::" + device.getAddress() + ":" + uuids[i].getUuid().toString());
                                if (BluetoothObject.SPP_UUID.equalsIgnoreCase(uuids[i].getUuid().toString())) {
                                    fruit.setIsConnect(0);
                                    break;
                                }
                            }
                        }*/
                        fruit.setName(device.getName());
                        fruit.setRssi(device.getAlias());
                        fruit.setState(bondState);
                        short rssi = intent.getExtras().getShort(BluetoothDevice.EXTRA_RSSI);
                        fruit.setRssi(rssi + "");
                        fruit.setBluetoothDevice(device);
                        fruitList.add(fruit);
                        mRecyclerView.setAdapter(adapter);
                        mRecyclerView.setVisibility(View.VISIBLE);

                    }
                    break;
                //蓝牙状态修改
                //断开蓝牙连接
                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                case BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED:
                    if (bluetoothObject != null) {
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
                    }
                    //蓝牙状态修改
                case BluetoothDevice.ACTION_ACL_CONNECTED:
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    fruit.setState(bondState);
                    short rssi = intent.getExtras().getShort(BluetoothDevice.EXTRA_RSSI);
                    fruit.setRssi(rssi + "");
                    adapter.notifyDataSetChanged();
                    break;
            }
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 是否触发按键为back键
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
            dialog.setTitle("提示");
            dialog.setMessage("确定退出吗?");
            dialog.setCancelable(false);
            dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    onBackPressed();
                    finish();
                }
            });
            dialog.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });
            dialog.show();
            return true;
        } else {// 如果不是back键正常响应
            return super.onKeyDown(keyCode, event);
        }
    }

    public void SystemExit(String message) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
        dialog.setTitle("提示");
        dialog.setMessage(message);
        dialog.setCancelable(false);
        dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                System.exit(0);
            }
        });

        dialog.show();
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
                    String message =(String)msg.obj;
                    ToastUtil.toastWord(MainActivity.this,message);
                    break;
                default:
                    Log.e(TAG, "Unknown msg " + msg.what);
                    break;
            }
        }
    };


}