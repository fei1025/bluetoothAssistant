package com.zzf.bluetoothsmp.fragment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.databinding.FragmentHomeBinding;
import com.zzf.bluetoothsmp.BluetoothConnectionState;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.BluetoothPermissionUtils;
import com.zzf.bluetoothsmp.BluetoothServiceConnect;
import com.zzf.bluetoothsmp.BluetoothTelemetry;
import com.zzf.bluetoothsmp.BluetoothDeviceProfileStore;
import com.zzf.bluetoothsmp.Fruit;
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.customAdapter.FruitAdapter;
import com.zzf.bluetoothsmp.entity.BluetoothDeviceProfileEntity;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.myLayout.MySwipeRefreshLayout;
import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;
import com.zzf.bluetoothsmp.utils.LanguageUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import com.google.android.material.switchmaterial.SwitchMaterial;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class HomeFragment extends BaseFragment {

    @Override
    protected String getAnalyticsScreenName() {
        return "home";
    }

    private final String TAG = "HomeFragment";
    private static final int REQUEST_ENABLE_BLUETOOTH = 0x33;
    private static final int REQUEST_DISCOVERABLE = 0x32;
    private static final int REQUEST_CONNECT_PERMISSION = 0x36;
    private static final int DISCOVERABLE_DURATION_SECONDS = 300;

    private FragmentHomeBinding binding;
    private RecyclerView mRecyclerView;
    private FruitAdapter adapter;
    private MySwipeRefreshLayout swipeRefresh;
    private final List<Fruit> fruitList = new ArrayList<>();
    private MainActivity mainActivity;
    private BluetoothObject bluetoothObject;
    private Date uploadTime = new Date(0);
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private boolean scanInProgress;
    private final Runnable scanTimeout = () -> finishScan(true);
    private final Runnable connectionStateRefresh = new Runnable() {
        @Override
        public void run() {
            refreshConnectionStates();
            if (isAdded()) {
                scanHandler.postDelayed(this, 500L);
            }
        }
    };
    private MenuItem discoverableMenuItem;
    private boolean scanModeReceiverRegistered;
    private boolean discoveryStateReceiverRegistered;
    private Fruit pendingConnectFruit;
    private int viewGeneration;
    private int profileLoadRequest;
    private long profileWriteSequence;
    private final Map<String, Long> profileRequests = new HashMap<>();
    private AlertDialog aliasDialog;
    private boolean aliasLoading;

    private final BroadcastReceiver discoveryStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                scanInProgress = true;
                scanHandler.removeCallbacks(scanTimeout);
                scanHandler.postDelayed(scanTimeout, 15_000L);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                finishScan(false);
            }
        }
    };

    private final BroadcastReceiver scanModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(intent.getAction())) {
                updateDiscoverableMenuItem();
            }
        }
    };


    @SuppressLint("ResourceAsColor")
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        mainActivity = (MainActivity) getActivity();
        if (mainActivity != null) {
            StaticObject.connectionAttemptRegistry.updateHandlers(mainActivity.mHandler);
        }
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        viewGeneration++;
        View root = binding.getRoot();


        // ========== 新增：适配 Android 15 边到边模式 ==========
        setupEdgeToEdge(root);






        Toolbar toolbar = binding.toolbar;
        toolbar.setTitle(getString(R.string.bluetooth_assistant));

        toolbar.inflateMenu(R.menu.home_menu);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                String url = "";
                if (itemId == R.id.home_plugins) {
                    finishScan(true);
                    startActivity(new Intent(requireContext(), com.zzf.bluetoothsmp.plugin.PluginCenterActivity.class));
                    return true;
                } else if (itemId == R.id.home_ble) {
                    finishScan(true);
                    startActivity(new Intent(requireContext(), com.zzf.bluetoothsmp.ble.BleActivity.class));
                    return true;
                } else if (itemId == R.id.home_discoverable) {
                    requestDiscoverable();
                    return true;
                } else if (itemId == R.id.support_developer) {
                    return mainActivity != null && mainActivity.onOptionsItemSelected(item);
                } else if (itemId == R.id.ys) {
                    url = "https://fei1025.github.io/privacy-policie/bluetto/";
                } else if (itemId == R.id.me) {
                    // 使用说明,待定
                    url = "https://fei1025.github.io/privacy-policie/bluetto/";
                } else if (itemId == R.id.bt_menu_language) {
                    Locale prefAppLocale = LanguageUtils.getCurrentAppLocale();
                    String language = prefAppLocale.getLanguage();
                    if("zh".equals(language)){
                        mainActivity.toSetLanguage(1);
                        item.setIcon(R.drawable.ic_en);

                    }else if("en".equals(language)){
                        mainActivity.toSetLanguage(2);
                        item.setIcon(R.drawable.ic_zh);

                    }
                    return true;
                }
                if (url.isEmpty()) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }
        });
        Locale prefAppLocale = LanguageUtils.getCurrentAppLocale();
        String language = prefAppLocale.getLanguage();
        Menu menu = toolbar.getMenu();
        discoverableMenuItem = menu.findItem(R.id.home_discoverable);
        updateDiscoverableMenuItem();
        MenuItem item = menu.findItem(R.id.bt_menu_language);
        if("zh".equals(language)){
            item.setIcon(R.drawable.ic_en);
        }else if("en".equals(language)){
            item.setIcon(R.drawable.ic_zh);
        }
        mRecyclerView = binding.cardList;
        mRecyclerView.setHasFixedSize(true);
        adapter = new FruitAdapter(fruitList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(adapter);
        //设置下拉刷新
        swipeRefresh = binding.swipeRefresh;
        swipeRefresh.setTouchSlop(50);
        swipeRefresh.setColorSchemeColors(com.google.android.material.R.color.design_default_color_primary);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                if (scanInProgress) {
                    return;
                }
                if (((new Date().getTime() - uploadTime.getTime()) / 1000) < 10) {
                    ToastUtil.toastWord(getString(R.string.update));
                    swipeRefresh.setRefreshing(false);
                    return;
                }
                fruitList.clear();
                adapter.notifyDataSetChanged();
                startScan();
                uploadTime = new Date();
            }
        });
        adapter.setOnItemClickListener(new FruitAdapter.onItemDeleteListener() {
            @Override
            public void OnItemClick(Fruit fruit) {
                if (fruit == null || fruit.getBluetoothDevice() == null) {
                    return;
                }
                String address = BluetoothAddressUtils.normalize(fruit.getAddress());
                BluetoothConnectionState state = StaticObject.connectionRegistry.get(address);
                BluetoothTelemetry.logDeviceSelected(state);
                if (state == BluetoothConnectionState.CONNECTING
                        || state == BluetoothConnectionState.PAIRING
                        || state == BluetoothConnectionState.RECONNECTING) {
                    BluetoothObject connecting = StaticObject.connectionAttemptRegistry.get(address);
                    if (connecting != null) {
                        connecting.cancelConnect();
                        refreshConnectionStates();
                    }
                    return;
                }
                if (state == BluetoothConnectionState.CONNECTED) {
                    openConnectedSession(fruit);
                    return;
                }
                bluetoothObject = new BluetoothObject();
                BluetoothDevice bluetoothDevice = fruit.getBluetoothDevice();
                if (!BluetoothPermissionUtils.hasConnectPermission(requireContext())) {
                    pendingConnectFruit = fruit;
                    requestPermissions(BluetoothPermissionUtils.connectPermissions(),
                            REQUEST_CONNECT_PERMISSION);
                    return;
                }
                bluetoothObject.setBluetoothDevice(bluetoothDevice);
                try {
                    bluetoothObject.connect(mainActivity.getApplicationContext(), mainActivity.mHandler);
                } catch (Exception e) {
                    ToastUtil.toastWord(mainActivity, getString(R.string.connect_fails));
                    e.printStackTrace();
                }
            }
        });
        adapter.setOnFavoriteClickListener(fruit -> {
            String address = BluetoothAddressUtils.normalize(fruit.getAddress());
            if (address == null || profileRequests.containsKey(address)) return;
            boolean favorite = !fruit.isFavorite();
            final int generation = viewGeneration;
            final long request = ++profileWriteSequence;
            profileRequests.put(address, request);
            BluetoothDeviceProfileStore.setFavoriteAsync(address, favorite, System.currentTimeMillis(),
                    new BluetoothDeviceProfileStore.Callback<BluetoothDeviceProfileEntity>() {
                        @Override public void onSuccess(BluetoothDeviceProfileEntity saved) {
                            if (!currentProfileRequest(generation, address, request)) return;
                            profileRequests.remove(address);
                            updateDisplayedProfile(saved);
                        }
                        @Override public void onError(Throwable error) {
                            if (!currentProfileRequest(generation, address, request)) return;
                            profileRequests.remove(address);
                            ToastUtil.toastWord(requireContext(), getString(R.string.device_profile_save_failed));
                        }
                    });
        });
        adapter.setOnLongClickListener(fruit -> {
            showAliasDialog(fruit);
            return true;
        });

        mainActivity.setOnActivityDataChangedListener(new MainActivity.OnActivityDataChangedListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public synchronized void addFruitData(Fruit f) {
                if (f == null || adapter == null) {
                    return;
                }
                String address = BluetoothAddressUtils.normalize(f.getAddress());
                if (address == null) {
                    return;
                }
                f.setAddress(address);
                f.setConnectionState(StaticObject.connectionRegistry.get(address));
                applyProfile(f);
                int existingIndex = findFruitIndex(address);
                if (existingIndex < 0) {
                    fruitList.add(f);
                } else {
                    fruitList.set(existingIndex, f);
                }
                sortFruitList();
                adapter.notifyDataSetChanged();
            }
        });
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!scanModeReceiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
            ContextCompat.registerReceiver(requireContext(), scanModeReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            scanModeReceiverRegistered = true;
        }
        IntentFilter discoveryFilter = new IntentFilter();
        discoveryFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        discoveryFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (!discoveryStateReceiverRegistered) {
            ContextCompat.registerReceiver(requireContext(), discoveryStateReceiver,
                    discoveryFilter, ContextCompat.RECEIVER_EXPORTED);
            discoveryStateReceiverRegistered = true;
        }
        scanHandler.removeCallbacks(connectionStateRefresh);
        scanHandler.post(connectionStateRefresh);
        updateDiscoverableMenuItem();
        loadSavedProfiles();
        if (!scanInProgress) {
            startScan();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 权限弹窗或系统“允许被发现”页面返回后，刷新首页自己的 Toolbar 菜单。
        View root = getView();
        if (root != null) {
            root.post(this::updateDiscoverableMenuItem);
        }
        if (!scanInProgress && mainActivity != null
                && mainActivity.getmBluetooth() != null) {
            startScan();
        }
    }

    @Override
    public void onStop() {
        scanHandler.removeCallbacks(scanTimeout);
        scanHandler.removeCallbacks(connectionStateRefresh);
        scanInProgress = false;
        if (discoveryStateReceiverRegistered) {
            requireContext().unregisterReceiver(discoveryStateReceiver);
            discoveryStateReceiverRegistered = false;
        }
        if (scanModeReceiverRegistered) {
            requireContext().unregisterReceiver(scanModeReceiver);
            scanModeReceiverRegistered = false;
        }
        super.onStop();
    }

    private void requestDiscoverable() {
        BluetoothAdapter adapter = resolveBluetoothAdapter();
        if (adapter == null) {
            ToastUtil.toastWord(requireContext(), getString(R.string.BluetoothNotFound));
            updateDiscoverableMenuItem();
            return;
        }
        if (!BluetoothPermissionUtils.hasRequiredPermissions(requireContext())) {
            mainActivity.requestBluetoothPermissions();
            return;
        }
        if (!adapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                DISCOVERABLE_DURATION_SECONDS);
        startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                requestDiscoverable();
            } else {
                updateDiscoverableMenuItem();
            }
            return;
        }
        if (requestCode == REQUEST_DISCOVERABLE) {
            updateDiscoverableMenuItem();
            if (resultCode == Activity.RESULT_CANCELED) {
                ToastUtil.toastWord(requireContext(), getString(R.string.discoverable_request_denied));
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void updateDiscoverableMenuItem() {
        if (discoverableMenuItem == null || mainActivity == null) {
            return;
        }
        BluetoothAdapter adapter = resolveBluetoothAdapter();
        if (adapter == null) {
            setDiscoverableMenuUnavailable();
            return;
        }

        if (!BluetoothPermissionUtils.hasRequiredPermissions(requireContext())) {
            setDiscoverableMenuPermissionRequired();
            return;
        }

        final boolean adapterEnabled;
        final boolean discoverable;
        try {
            adapterEnabled = adapter.isEnabled();
            discoverable = adapterEnabled && adapter.getScanMode()
                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        } catch (RuntimeException error) {
            // The permission can be revoked between the check above and the Bluetooth IPC.
            // Some vendor Bluetooth services also surface binder failures as RuntimeException.
            Log.w(TAG, "Unable to read Bluetooth discoverable state", error);
            setDiscoverableMenuUnavailable();
            return;
        }

        if (!adapterEnabled) {
            setDiscoverableMenuUnavailable();
            return;
        }
        discoverableMenuItem.setEnabled(true);
        if (discoverable) {
            discoverableMenuItem.setIcon(R.drawable.ic_visibility);
            discoverableMenuItem.setTitle(R.string.renew_discoverable);
        } else {
            discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
            discoverableMenuItem.setTitle(R.string.open_discoverable);
        }
    }

    private void setDiscoverableMenuUnavailable() {
        if (discoverableMenuItem == null) {
            return;
        }
        discoverableMenuItem.setEnabled(false);
        discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
        discoverableMenuItem.setTitle(R.string.discoverable_status_unavailable);
    }

    private void setDiscoverableMenuPermissionRequired() {
        if (discoverableMenuItem == null) {
            return;
        }
        discoverableMenuItem.setEnabled(true);
        discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
        discoverableMenuItem.setTitle(R.string.bluetooth_permission_required_title);
    }

    private BluetoothAdapter resolveBluetoothAdapter() {
        BluetoothAdapter adapter = mainActivity == null ? null : mainActivity.getmBluetooth();
        if (adapter != null) {
            return adapter;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return null;
        }
        return bluetoothManager.getAdapter();
    }


    @SuppressLint("MissingPermission")
    private void startScan() {
        BluetoothAdapter adapter = resolveBluetoothAdapter();
        if (mainActivity == null || mainActivity.getmBluetooth() == null || adapter == null) {
            finishScan(false);
            if (isAdded()) {
                ToastUtil.toastWord(requireContext(), getString(R.string.BluetoothNotFound));
            }
            return;
        }
        if (!BluetoothPermissionUtils.hasDiscoveryPermissions(requireContext())) {
            mainActivity.requestBluetoothPermissions();
            return;
        }
        if (!adapter.isEnabled()) {
            finishScan(false);
            ToastUtil.toastWord(requireContext(), getString(R.string.initBluetooth));
            return;
        }
        scanInProgress = true;
        swipeRefresh.setRefreshing(true);
        mainActivity.initBluetooth();
        scanHandler.removeCallbacks(scanTimeout);
        scanHandler.postDelayed(scanTimeout, 15_000L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CONNECT_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            com.zzf.bluetoothsmp.BluetoothTelemetry.logPermissionResult("connect", granted);
            Fruit fruit = pendingConnectFruit;
            pendingConnectFruit = null;
            if (granted && fruit != null && fruit.getBluetoothDevice() != null) {
                bluetoothObject = new BluetoothObject();
                bluetoothObject.setBluetoothDevice(fruit.getBluetoothDevice());
                bluetoothObject.connect(mainActivity.getApplicationContext(), mainActivity.mHandler);
            }
        }
    }

    private void finishScan(boolean timedOut) {
        scanHandler.removeCallbacks(scanTimeout);
        boolean wasScanning = scanInProgress;
        scanInProgress = false;
        if (swipeRefresh != null) {
            swipeRefresh.setRefreshing(false);
        }
        if (wasScanning && fruitList.isEmpty() && isAdded()) {
            ToastUtil.toastWord(requireContext(), getString(
                    timedOut ? R.string.scan_timeout : R.string.scan_no_results));
        }
    }

    private int findFruitIndex(String address) {
        for (int i = 0; i < fruitList.size(); i++) {
            if (address.equals(BluetoothAddressUtils.normalize(fruitList.get(i).getAddress()))) {
                return i;
            }
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private void loadSavedProfiles() {
        if (mainActivity == null || mainActivity.getmBluetooth() == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        final int generation = viewGeneration;
        final int request = ++profileLoadRequest;
        mergeSavedProfiles(BluetoothDeviceProfileStore.findAllCached());
        BluetoothDeviceProfileStore.loadAsync(new BluetoothDeviceProfileStore.Callback<List<BluetoothDeviceProfileEntity>>() {
            @Override public void onSuccess(List<BluetoothDeviceProfileEntity> profiles) {
                if (binding == null || !isAdded() || generation != viewGeneration || request != profileLoadRequest) return;
                mergeSavedProfiles(profiles);
            }
            @Override public void onError(Throwable error) {
                if (binding == null || !isAdded() || generation != viewGeneration || request != profileLoadRequest) return;
                ToastUtil.toastWord(requireContext(), getString(R.string.device_profile_load_failed));
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void mergeSavedProfiles(List<BluetoothDeviceProfileEntity> profiles) {
        if (binding == null || mainActivity == null || mainActivity.getmBluetooth() == null
                || !BluetoothPermissionUtils.hasConnectPermission(requireContext())) return;
        for (BluetoothDeviceProfileEntity profile : profiles) {
            try {
                BluetoothDeviceProfileEntity current = BluetoothDeviceProfileStore.findCached(profile.getAddress());
                if (current != null) profile = current;
                int existing = findFruitIndex(profile.getAddress());
                if (existing >= 0) {
                    Fruit discovered = fruitList.get(existing);
                    discovered.setAlias(profile.getAlias());
                    discovered.setFavorite(profile.isFavorite());
                    discovered.setLastConnectedAt(profile.getLastConnectedAt());
                    if ((discovered.getName() == null || discovered.getAddress().equals(discovered.getName()))
                            && profile.getDeviceName() != null) discovered.setName(profile.getDeviceName());
                    continue;
                }
                BluetoothDevice device = mainActivity.getmBluetooth()
                        .getRemoteDevice(profile.getAddress());
                Fruit fruit = new Fruit(requireContext());
                fruit.setAddress(profile.getAddress());
                fruit.setName(device.getName() == null ? profile.getDeviceName() : device.getName());
                fruit.setState(device.getBondState());
                fruit.setBluetoothType(device.getType());
                fruit.setBluetoothDevice(device);
                fruit.setAlias(profile.getAlias());
                fruit.setFavorite(profile.isFavorite());
                fruit.setLastConnectedAt(profile.getLastConnectedAt());
                fruit.setConnectionState(StaticObject.connectionRegistry.get(profile.getAddress()));
                fruitList.add(fruit);
            } catch (IllegalArgumentException | SecurityException ignored) {
                // Keep stale profile data for a later discovery pass.
            }
        }
        sortFruitList();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void applyProfile(Fruit fruit) {
        BluetoothDeviceProfileEntity profile = BluetoothDeviceProfileStore.findCached(fruit.getAddress());
        if (profile == null) {
            return;
        }
        fruit.setAlias(profile.getAlias());
        fruit.setFavorite(profile.isFavorite());
        fruit.setLastConnectedAt(profile.getLastConnectedAt());
    }

    private void showAliasDialog(Fruit fruit) {
        if (!isAdded() || binding == null || fruit == null || aliasLoading
                || (aliasDialog != null && aliasDialog.isShowing())) {
            return;
        }
        final String address = BluetoothAddressUtils.normalize(fruit.getAddress());
        if (address == null || profileRequests.containsKey(address)) return;
        if (!BluetoothDeviceProfileStore.isCacheLoaded()) {
            aliasLoading = true;
            final int generation = viewGeneration;
            BluetoothDeviceProfileStore.loadAsync(new BluetoothDeviceProfileStore.Callback<List<BluetoothDeviceProfileEntity>>() {
                @Override public void onSuccess(List<BluetoothDeviceProfileEntity> profiles) {
                    if (binding == null || generation != viewGeneration || !isAdded()) return;
                    aliasLoading = false;
                    int index = findFruitIndex(address);
                    if (index >= 0) showAliasDialog(fruitList.get(index));
                }
                @Override public void onError(Throwable error) {
                    if (binding == null || generation != viewGeneration || !isAdded()) return;
                    aliasLoading = false;
                    ToastUtil.toastWord(requireContext(), getString(R.string.device_profile_load_failed));
                }
            });
            return;
        }
        BluetoothDeviceProfileEntity cached = BluetoothDeviceProfileStore.findCached(address);
        EditText input = new EditText(requireContext());
        input.setHint(R.string.device_alias);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(cached == null ? fruit.getAlias() : cached.getAlias());
        input.setSelection(input.length());
        SwitchMaterial autoReconnect = new SwitchMaterial(requireContext());
        autoReconnect.setText(R.string.device_auto_reconnect);
        autoReconnect.setChecked(StaticObject.reconnectManager
                .isDeviceReconnectEnabled(address));
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);
        content.addView(input);
        content.addView(autoReconnect);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.device_alias)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null).create();
        aliasDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (aliasDialog == dialog) aliasDialog = null;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (profileRequests.containsKey(address)) return;
            final String alias = input.getText() == null ? "" : input.getText().toString();
            final boolean reconnect = autoReconnect.isChecked();
            final int generation = viewGeneration;
            final long request = ++profileWriteSequence;
            profileRequests.put(address, request);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            input.setEnabled(false);
            autoReconnect.setEnabled(false);
            BluetoothDeviceProfileStore.updateAliasAsync(address, alias, System.currentTimeMillis(),
                    new BluetoothDeviceProfileStore.Callback<BluetoothDeviceProfileEntity>() {
                        @Override public void onSuccess(BluetoothDeviceProfileEntity saved) {
                            StaticObject.reconnectManager.setDeviceReconnectEnabled(address, reconnect);
                            if (!currentProfileRequest(generation, address, request)) return;
                            profileRequests.remove(address);
                            updateDisplayedProfile(saved);
                            dialog.dismiss();
                        }
                        @Override public void onError(Throwable error) {
                            if (!currentProfileRequest(generation, address, request)) return;
                            profileRequests.remove(address);
                            dialog.setCancelable(true);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            input.setEnabled(true);
                            autoReconnect.setEnabled(true);
                            ToastUtil.toastWord(requireContext(), getString(R.string.device_profile_save_failed));
                        }
                    });
        });
    }

    private boolean currentProfileRequest(int generation, String address, long request) {
        Long current = profileRequests.get(address);
        return binding != null && isAdded() && generation == viewGeneration
                && current != null && current == request;
    }

    private void updateDisplayedProfile(BluetoothDeviceProfileEntity saved) {
        int index = findFruitIndex(saved.getAddress());
        if (index >= 0) applyProfile(fruitList.get(index));
        sortFruitList();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void openConnectedSession(Fruit fruit) {
        BluetoothDrive drive = new BluetoothDrive();
        drive.setDriveName(fruit.getDisplayName());
        drive.setDriveAdd(fruit.getAddress());
        BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(fruit.getAddress());
        if (connection != null && connection.isPluginOwned()) {
            android.widget.Toast.makeText(requireContext(), R.string.plugin_device_busy, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        drive.setUuid(connection == null || connection.getSendUuid() == null
                ? BluetoothObject.SPP_UUID : connection.getSendUuid());
        Intent intent = new Intent(requireContext(), Liantian_new.class);
        intent.putExtra("bluetoothName", fruit.getDisplayName());
        intent.putExtra("bluetoothAdd", fruit.getAddress());
        intent.putExtra("bluetoothUUid", drive.getUuid());
        intent.putExtra("BluetoothDrive", drive);
        startActivity(intent);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshConnectionStates() {
        if (adapter == null) {
            return;
        }
        boolean changed = false;
        for (Fruit fruit : fruitList) {
            String address = BluetoothAddressUtils.normalize(fruit.getAddress());
            BluetoothConnectionState state = StaticObject.connectionRegistry.get(address);
            if (fruit.getConnectionState() != state) {
                fruit.setConnectionState(state);
                changed = true;
            }
        }
        if (changed) {
            adapter.notifyDataSetChanged();
        }
    }

    private void sortFruitList() {
        Collections.sort(fruitList, (left, right) -> {
            if (left.isFavorite() != right.isFavorite()) {
                return left.isFavorite() ? -1 : 1;
            }

            boolean leftPaired = left.getState() != null
                    && left.getState() == BluetoothDevice.BOND_BONDED;
            boolean rightPaired = right.getState() != null
                    && right.getState() == BluetoothDevice.BOND_BONDED;
            if (leftPaired != rightPaired) {
                return leftPaired ? -1 : 1;
            }

            int recentCompare = Long.compare(right.getLastConnectedAt(), left.getLastConnectedAt());
            if (recentCompare != 0) {
                return recentCompare;
            }

            int rssiCompare = Integer.compare(parseRssi(right), parseRssi(left));
            if (rssiCompare != 0) {
                return rssiCompare;
            }
            String leftName = left.getName() == null ? "" : left.getName().trim();
            String rightName = right.getName() == null ? "" : right.getName().trim();
            int nameCompare = leftName.compareToIgnoreCase(rightName);
            if (nameCompare != 0) {
                return nameCompare;
            }
            String leftAddress = left.getAddress() == null ? "" : left.getAddress();
            String rightAddress = right.getAddress() == null ? "" : right.getAddress();
            return leftAddress.compareToIgnoreCase(rightAddress);
        });
    }

    private int parseRssi(Fruit fruit) {
        if (fruit.getRssi() == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(fruit.getRssi().trim());
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        profileLoadRequest++;
        profileRequests.clear();
        aliasLoading = false;
        if (aliasDialog != null) {
            aliasDialog.dismiss();
            aliasDialog = null;
        }
        scanHandler.removeCallbacks(scanTimeout);
        scanHandler.removeCallbacks(connectionStateRefresh);
        if (mainActivity != null) {
            mainActivity.setOnActivityDataChangedListener(null);
        }
        super.onDestroyView();
        discoverableMenuItem = null;
        binding = null;
        adapter = null;
        mRecyclerView = null;
        swipeRefresh = null;
    }
}
