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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.billing.SupporterBillingManager;
import com.zzf.bluetoothsmp.databinding.ActivityHomeBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.zzf.bluetoothsmp.base.BaseActivity;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.CheckUpdate;
import com.zzf.bluetoothsmp.utils.LanguageUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import static com.zzf.bluetoothsmp.R.string.ConnectTheInterrupt;

public class MainActivity extends BaseActivity {

    private BluetoothAdapter mBluetooth;

    private static final String TAG = "MainActivity";
    private static final int REQ_ENABLE_BT = 0x11;
    private static final int REQ_DISCOVERABLE_BT = 0x12;
    private static final int REQ_MENU_ENABLE_BT = 0x13;
    private static final int REQ_MENU_DISCOVERABLE_BT = 0x14;
    private static final int REQ_POST_NOTIFICATIONS = 0x15;
    private static final int DISCOVERABLE_DURATION_SECONDS = 300;
    private final int mOpenCode = 0x01;
    public int scan = 1;
    private boolean isCreate = false;
    private boolean bluetoothInitCompleted = false;
    private boolean initializationPromptInProgress = false;
    private boolean receiverRegistered = false;
    private ActivityHomeBinding binding;
    private OnActivityDataChangedListener onActivityDataChangedListener;
    private MenuItem discoverableMenuItem;
    private MenuItem supporterBadgeMenuItem;
    private AlertDialog supporterDialog;
    private SupporterBillingManager supporterBillingManager;
    private SupporterBillingManager.Snapshot supporterSnapshot;
    public    BluetoothService bluetoothService;


    @SuppressLint("ResourceAsColor")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);



        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.requestApplyInsets(binding.getRoot());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showExitDialog();
            }
        });
        new CheckUpdate().check(MainActivity.this);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) BottomNavigationView navView = findViewById(R.id.nav_view);
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_home, R.id.navigation_service, R.id.navigation_dashboard)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_activity_home);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.navView, navController);

        supporterBillingManager = new SupporterBillingManager(this);
        supporterBillingManager.setListener(snapshot -> {
            supporterSnapshot = snapshot;
            updateSupporterUi();
        });
        supporterBillingManager.start();

        StaticObject.ensureMessageMonitor();

        cratePermission();
        isCreate = true;
        initBluetoothAdapter();
        if (hasBluetoothRuntimePermissions()) {
            ensureBluetoothEnabledThenInit();
        }
        new Handler(Looper.getMainLooper()).post(() ->
                handleIncomingConnectionIntent(getIntent()));
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
            Toast.makeText(context, "选择的语言和当前语言相同", Toast.LENGTH_SHORT).show();
            return;
        }*/
        LanguageUtils.updateLanguage(context, locale);
        MainActivity.actionActivity(context);
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

    public void onStart() {
        super.onStart();
        if (bluetoothInitCompleted) {
            beginDiscovery();
        }
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
        //注销蓝牙设备搜索的广播接收器
        //unregisterReceiver(discoveryReceiver);
        if (mBluetooth != null && BluetoothPermissionUtils.hasScanPermission(this)
                && mBluetooth.isDiscovering()) {
            //mBluetooth.startDiscovery();//开始扫描周围的蓝牙设备
            mBluetooth.cancelDiscovery();
        }
        isCreate = false;
    }

    public void onResume() {
        super.onResume();
        if (supporterBillingManager != null) {
            supporterBillingManager.syncPurchases();
        }
        if (!hasBluetoothRuntimePermissions()) {
            updateDiscoverableMenuItem();
            return;
        }
        if (mBluetooth == null) {
            initBluetoothAdapter();
        }
        if (mBluetooth != null && mBluetooth.isEnabled() && !bluetoothInitCompleted
                && !initializationPromptInProgress) {
            requestDiscoverableIfNeededThenContinueInit();
        } else if (mBluetooth != null && mBluetooth.isEnabled()
                && bluetoothInitCompleted
                && (bluetoothService == null || !bluetoothService.isRunning())) {
            restartBluetoothListenerIfNeeded();
        }
    }

    @SuppressLint("MissingPermission")
    private void restartBluetoothListenerIfNeeded() {
        try {
            bluetoothService = StaticObject.ensureBluetoothService(this, mBluetooth);
            StaticObject.ensureMessageDispatcher();
            StaticObject.ensureMessageMonitor();
            requestNotificationPermissionIfNeeded();
            BluetoothConnectionForegroundService.start(this);
            StaticObject.reconnectManager.restorePendingConnections();
            Log.i(TAG, "Restarted stopped Bluetooth SPP listener after returning to foreground");
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Unable to restart Bluetooth SPP listener", error);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingConnectionIntent(intent);
    }

    private void handleIncomingConnectionIntent(Intent intent) {
        if (intent == null
                || !"com.zzf.bluetoothsmp.action.OPEN_INCOMING_CONNECTION".equals(intent.getAction())) {
            return;
        }
        String address = intent.getStringExtra("incomingBluetoothAdd");
        if (address == null || StaticObject.bluetoothSocketMap.get(address) == null) {
            return;
        }
        BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(address);
        String name = intent.getStringExtra("incomingBluetoothName");
        BluetoothDrive drive = new BluetoothDrive();
        drive.setDriveName(name == null ? address : name);
        drive.setDriveAdd(address);
        drive.setUuid(connection.getSendUuid() == null
                ? intent.getStringExtra("incomingBluetoothUuid") : connection.getSendUuid());
        Intent chatIntent = new Intent(this, Liantian_new.class);
        chatIntent.putExtra("BluetoothDrive", drive);
        chatIntent.putExtra("bluetoothName", drive.getDriveName());
        chatIntent.putExtra("bluetoothAdd", address);
        chatIntent.putExtra("bluetoothUUid", drive.getUuid());
        startActivity(chatIntent);
    }


    public interface OnActivityDataChangedListener {
        void addFruitData(Fruit string);
    }

    public void setOnActivityDataChangedListener(OnActivityDataChangedListener addFruitData) {
        this.onActivityDataChangedListener = addFruitData;
    }


    // 权限回调
    @Override
    @SuppressLint("MissingPermission")
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != mOpenCode) {
            return;
        }
        BluetoothTelemetry.logPermissionResult("server", hasBluetoothRuntimePermissions());
        if (!hasBluetoothRuntimePermissions()) {
            showBluetoothPermissionRequiredDialog();
            updateDiscoverableMenuItem();
            return;
        }
        initBluetoothAdapter();
        updateDiscoverableMenuItem();
        ensureBluetoothEnabledThenInit();
    }

    private void requestBluetoothPermissions() {
        String[] permissions = BluetoothPermissionUtils.serverPermissions();
        if (permissions.length == 0) {
            initBluetoothAdapter();
            ensureBluetoothEnabledThenInit();
            return;
        }
        ActivityCompat.requestPermissions(this, permissions, mOpenCode);
    }

    private void showBluetoothPermissionRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bluetooth_permission_required_title)
                .setMessage(R.string.bluetooth_permission_required_message)
                .setCancelable(true)
                .setPositiveButton(R.string.permission_retry,
                        (dialog, which) -> requestBluetoothPermissions())
                .setNeutralButton(R.string.permission_settings,
                        (dialog, which) -> openApplicationSettings())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openApplicationSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @SuppressLint("MissingPermission")
    private void initBluetoothAdapter() {
        if (mBluetooth != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            mBluetooth = bm.getAdapter();
        } else {
            mBluetooth = BluetoothAdapter.getDefaultAdapter();
        }
        if (mBluetooth == null) {
            SystemExit(getString(R.string.BluetoothNotFound));
        }
    }

    @SuppressLint("MissingPermission")
    private void ensureBluetoothEnabledThenInit() {
        if (mBluetooth == null) {
            return;
        }
        if (!mBluetooth.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            initializationPromptInProgress = true;
            startActivityForResult(enableIntent, REQ_ENABLE_BT);
            return;
        }
        requestDiscoverableIfNeededThenContinueInit();
    }

    @SuppressLint("MissingPermission")
    private void requestDiscoverableIfNeededThenContinueInit() {
        if (mBluetooth == null) {
            continueBluetoothInit();
            return;
        }
        if (!hasBluetoothRuntimePermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (mBluetooth.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissions();
                return;
            }
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS);
            initializationPromptInProgress = true;
            startActivityForResult(discoverableIntent, REQ_DISCOVERABLE_BT);
            return;
        }
        continueBluetoothInit();
    }

    private void continueBluetoothInit() {
        if (bluetoothInitCompleted) {
            return;
        }
        //初始化蓝牙
        initBluetooth();
        try {
            // 创建或复用进程级监听服务；前台服务在进程重启后可能先于 Activity 恢复它。
            bluetoothService = StaticObject.ensureBluetoothService(this, mBluetooth);
            requestNotificationPermissionIfNeeded();
            BluetoothConnectionForegroundService.start(this);
            StaticObject.ensureMessageDispatcher();
            bluetoothInitCompleted = true;
            StaticObject.reconnectManager.restorePendingConnections();
        } catch (Exception e) {
            bluetoothInitCompleted = false;
            Log.e(TAG, "Unable to initialize Bluetooth SPP runtime", e);
            ToastUtil.toastWord(this, getString(R.string.bluetooth_port_error));
        }
    }


    @SuppressLint("MissingPermission")
    public void startBluetoothDiscovery() {
        if (mBluetooth != null && BluetoothPermissionUtils.hasScanPermission(this)
                && !mBluetooth.isDiscovering()) {
            mBluetooth.startDiscovery();//开始扫描周围的蓝牙设备
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
        }
    }

    @SuppressLint("MissingPermission")
    private void beginDiscovery() {
        startBluetoothDiscovery();
    }

    @SuppressLint({"MissingPermission", "HardwareIds"})
    public void initBluetooth() {
        if (BluetoothPermissionUtils.hasConnectPermission(this)) {
            if (mBluetooth.getName() != null && mBluetooth.getName().length() > 0) {
                StaticObject.myBluetoothName = mBluetooth.getName();
            } else {
                StaticObject.myBluetoothName = mBluetooth.getAddress();
            }
            StaticObject.myBluetoothAdd = mBluetooth.getAddress();
        }

        //开始扫描
        beginDiscovery();
        if (!BluetoothPermissionUtils.hasConnectPermission(this)) {
            registerDiscoveryReceiver();
            return;
        }
        Set<BluetoothDevice> bondedDevices = mBluetooth.getBondedDevices();

        if (bondedDevices != null && bondedDevices.size() != 0) {
            for (BluetoothDevice device : bondedDevices) {
                Fruit fruit = new Fruit(this);
                fruit.setAddress(device.getAddress());
                fruit.setName(device.getName());
                fruit.setState(device.getBondState());
                fruit.setBluetoothType(device.getType());
                fruit.setBluetoothDevice(device);
                if (onActivityDataChangedListener != null) {
                    onActivityDataChangedListener.addFruitData(fruit);
                }
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
        discoveryFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        discoveryFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        discoveryFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        //注册蓝牙设备搜索的广播接收器
        registerDiscoveryReceiver(discoveryFilter);
    }

    private void registerDiscoveryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerDiscoveryReceiver(filter);
    }

    private void registerDiscoveryReceiver(IntentFilter filter) {
        if (!receiverRegistered) {
            registerReceiver(discoveryReceiver, filter);
            receiverRegistered = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver);
            receiverRegistered = false;
        }
        if (!isChangingConfigurations()) {
            // The foreground service may have restored the process-level listener
            // while this Activity field was still null. Always stop through the
            // shared owner instead of relying on the Activity-local reference.
            StaticObject.stopBluetoothService();
            bluetoothService = null;
            BluetoothConnectionForegroundService.stop(this);
            StaticObject.closeAllConnections();
            StaticObject.stopMessageMonitor();
            StaticObject.stopMessageDispatcher();
        }
        if (supporterDialog != null) {
            supporterDialog.dismiss();
            supporterDialog = null;
        }
        if (supporterBillingManager != null) {
            supporterBillingManager.close();
        }
        super.onDestroy();
    }

    @SuppressLint("MissingPermission")
    public final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {

        @RequiresApi(api = Build.VERSION_CODES.R)
        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                Log.w(TAG, "Ignoring Bluetooth broadcast without action");
                return;
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                handleBluetoothStateChanged(intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR));
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)
                    || BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) {
                Log.w(TAG, "Ignoring Bluetooth broadcast without device: " + action);
                return;
            }
            int bondState = BluetoothDevice.BOND_NONE;
            try {
                bondState = device.getBondState();
            } catch (SecurityException ignored) {
                // A scan result can be displayed before connect permission is granted.
            }
            Fruit fruit = new Fruit(MainActivity.this);
            String address;
            try {
                address = device.getAddress();
            } catch (SecurityException error) {
                Log.w(TAG, "Unable to read scanned Bluetooth address", error);
                return;
            }
            fruit.setAddress(address);

            //发现新的蓝牙设备
            String deviceName = null;
            try {
                deviceName = device.getName();
            } catch (SecurityException ignored) {
                // Name is optional until the user grants connect permission.
            }
            fruit.setName(deviceName);
            if (fruit.getName() == null || fruit.getName().length() == 0) {
                fruit.setName("N/A");
            }
            fruit.setState(bondState);
            try {
                fruit.setBluetoothType(device.getType());
            } catch (SecurityException ignored) {
                fruit.setBluetoothType(BluetoothDevice.DEVICE_TYPE_UNKNOWN);
            }
            if (BluetoothDevice.ACTION_FOUND.equals(action)
                    && intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                fruit.setRssi(String.valueOf(rssi));
            }
            fruit.setBluetoothDevice(device);
            if (onActivityDataChangedListener != null) {
                onActivityDataChangedListener.addFruitData(fruit);
            }

            switch (action) {
                case BluetoothDevice.ACTION_FOUND:
                    break;
                //蓝牙状态修改
                //断开蓝牙连接
                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                case BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED:
                    BluetoothServiceConnect remove = StaticObject.bluetoothSocketMap.remove(device.getAddress());
                    if (remove != null) {
                        ToastUtil.toastWord(MainActivity.this, MainActivity.this.getString(ConnectTheInterrupt));
                        remove.closeAfterUnexpectedDisconnect();
                    }
                    StaticObject.connectionRegistry.markDisconnectedUnlessReconnecting(
                            device.getAddress());
                    break;
                    //蓝牙状态修改
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

    @SuppressLint("MissingPermission")
    private void handleBluetoothStateChanged(int state) {
        if (state == BluetoothAdapter.STATE_OFF
                || state == BluetoothAdapter.STATE_TURNING_OFF) {
            if (mBluetooth != null && BluetoothPermissionUtils.hasScanPermission(this)
                    && mBluetooth.isDiscovering()) {
                mBluetooth.cancelDiscovery();
            }
            // Do not depend on the Activity-local field: the foreground service
            // can own the restored listener after a process/activity recreation.
            StaticObject.stopBluetoothService();
            bluetoothService = null;
            BluetoothConnectionForegroundService.stop(this);
            StaticObject.closeAllConnections(true);
            bluetoothInitCompleted = false;
            return;
        }
        if (state == BluetoothAdapter.STATE_ON && !bluetoothInitCompleted
                && hasBluetoothRuntimePermissions()) {
            continueBluetoothInit();
        }
    }

    private void showExitDialog() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
        dialog.setTitle(getString(R.string.tips));
        dialog.setMessage(getString(R.string.out));
        dialog.setCancelable(false);
        dialog.setPositiveButton(getString(R.string.confirm), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        dialog.setNegativeButton(getString(R.string.cancel), null);
        dialog.show();
    }

    public void SystemExit(String message) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this);
        dialog.setTitle("提示");
        dialog.setMessage(message);
        dialog.setCancelable(false);
        dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finishAffinity();
            }
        });
        dialog.create().show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MENU_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                requestDiscoverableFromMenu();
            } else {
                updateDiscoverableMenuItem();
            }
            return;
        }
        if (requestCode == REQ_MENU_DISCOVERABLE_BT) {
            updateDiscoverableMenuItem();
            if (resultCode == RESULT_CANCELED) {
                ToastUtil.toastWord(this, getString(R.string.discoverable_request_denied));
            }
            return;
        }
        if (requestCode == REQ_ENABLE_BT) {
            initializationPromptInProgress = false;
            if (resultCode == RESULT_OK) {
                requestDiscoverableIfNeededThenContinueInit();
            } else {
                SystemExit(getString(R.string.initBluetooth));
            }
            return;
        }
        if (requestCode == REQ_DISCOVERABLE_BT) {
            initializationPromptInProgress = false;
            continueBluetoothInit();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.home_menu, menu);
        discoverableMenuItem = menu.findItem(R.id.home_discoverable);
        supporterBadgeMenuItem = menu.findItem(R.id.supporter_badge_status);
        updateDiscoverableMenuItem();
        updateSupporterUi();
        MenuItem languageItem = menu.findItem(R.id.bt_menu_language);
        Locale prefAppLocale = LanguageUtils.getCurrentAppLocale();
        String language = prefAppLocale.getLanguage();
        if ("zh".equals(language)) {
            languageItem.setIcon(R.drawable.ic_en);
        } else if ("en".equals(language)) {
            languageItem.setIcon(R.drawable.ic_zh);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.home_discoverable) {
            requestDiscoverableFromMenu();
            return true;
        } else if (itemId == R.id.support_developer) {
            showSupporterDialog();
            return true;
        } else if (itemId == R.id.bt_menu_language) {
            Locale prefAppLocale = LanguageUtils.getCurrentAppLocale();
            String language = prefAppLocale.getLanguage();
            if ("zh".equals(language)) {
                toSetLanguage(1);
                item.setIcon(R.drawable.ic_en);
            } else if ("en".equals(language)) {
                toSetLanguage(0);
                item.setIcon(R.drawable.ic_zh);
            }
            return true;
        } else if (itemId == R.id.ys || itemId == R.id.me) {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://fei1025.github.io/privacy-policie/bluetto/"));
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSupporterDialog() {
        if (supporterDialog != null && supporterDialog.isShowing()) {
            updateSupporterUi();
            return;
        }
        supporterDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.supporter_dialog_title)
                .setMessage(getSupporterMessage())
                .setPositiveButton(R.string.supporter_buy, null)
                .setNeutralButton(R.string.supporter_restore, null)
                .setNegativeButton(R.string.close, null)
                .create();
        supporterDialog.setOnShowListener(dialog -> {
            Button purchaseButton = supporterDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            purchaseButton.setOnClickListener(view -> {
                if (supporterBillingManager != null) {
                    supporterBillingManager.launchPurchase(MainActivity.this);
                }
            });
            Button restoreButton = supporterDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            restoreButton.setOnClickListener(view -> {
                if (supporterBillingManager != null) {
                    supporterBillingManager.syncPurchases();
                }
            });
            updateSupporterUi();
        });
        supporterDialog.setOnDismissListener(dialog -> supporterDialog = null);
        supporterDialog.show();
    }

    private void updateSupporterUi() {
        boolean owned = supporterSnapshot != null && supporterSnapshot.isOwned();
        if (supporterBadgeMenuItem != null) {
            supporterBadgeMenuItem.setVisible(owned);
        }
        if (supporterDialog == null || !supporterDialog.isShowing()) {
            return;
        }
        supporterDialog.setMessage(getSupporterMessage());
        Button purchaseButton = supporterDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button restoreButton = supporterDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (purchaseButton == null || restoreButton == null) {
            return;
        }
        purchaseButton.setVisibility(owned ? View.GONE : View.VISIBLE);
        restoreButton.setVisibility(owned ? View.GONE : View.VISIBLE);
        purchaseButton.setEnabled(supporterSnapshot != null
                && supporterSnapshot.getState() == SupporterBillingManager.State.AVAILABLE);
    }

    private String getSupporterMessage() {
        if (supporterSnapshot == null) {
            return getString(R.string.supporter_loading);
        }
        if (supporterSnapshot.isOwned()) {
            return getString(R.string.supporter_badge_unlocked);
        }
        switch (supporterSnapshot.getState()) {
            case AVAILABLE:
                String price = supporterSnapshot.getFormattedPrice();
                return price == null
                        ? getString(R.string.supporter_loading)
                        : getString(R.string.supporter_purchase_description, price);
            case PENDING:
                return getString(R.string.supporter_pending);
            case CONNECTING:
                return getString(R.string.supporter_loading);
            case UNAVAILABLE:
            case ERROR:
            default:
                return getString(R.string.supporter_unavailable);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestDiscoverableFromMenu() {
        initBluetoothAdapter();
        if (mBluetooth == null) {
            updateDiscoverableMenuItem();
            return;
        }
        if (!hasBluetoothRuntimePermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (!mBluetooth.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQ_MENU_ENABLE_BT);
            return;
        }
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                DISCOVERABLE_DURATION_SECONDS);
        startActivityForResult(discoverableIntent, REQ_MENU_DISCOVERABLE_BT);
    }

    @SuppressLint("MissingPermission")
    private void updateDiscoverableMenuItem() {
        if (discoverableMenuItem == null) {
            return;
        }
        BluetoothAdapter adapter = mBluetooth;
        if (adapter == null) {
            BluetoothManager bluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager != null) {
                adapter = bluetoothManager.getAdapter();
            }
        }
        if (adapter == null) {
            discoverableMenuItem.setEnabled(false);
            discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
            discoverableMenuItem.setTitle(R.string.discoverable_status_unavailable);
            return;
        }

        if (!hasBluetoothRuntimePermissions()) {
            discoverableMenuItem.setEnabled(false);
            discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
            discoverableMenuItem.setTitle(R.string.discoverable_status_unavailable);
            return;
        }

        discoverableMenuItem.setEnabled(true);
        if (!adapter.isEnabled()) {
            discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
            discoverableMenuItem.setTitle(R.string.discoverable_status_unavailable);
            return;
        }

        boolean discoverable = adapter.getScanMode()
                == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        discoverableMenuItem.setIcon(discoverable
                ? R.drawable.ic_visibility
                : R.drawable.ic_visibility_off);
        discoverableMenuItem.setTitle(discoverable
                ? R.string.renew_discoverable
                : R.string.open_discoverable);
    }

    private boolean hasBluetoothRuntimePermissions() {
        return BluetoothPermissionUtils.hasServerPermissions(this);
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


    public BluetoothAdapter getmBluetooth() {
        return mBluetooth;
    }


}
