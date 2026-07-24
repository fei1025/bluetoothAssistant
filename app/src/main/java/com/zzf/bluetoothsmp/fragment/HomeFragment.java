package com.zzf.bluetoothsmp.fragment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.databinding.FragmentHomeBinding;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.Fruit;
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.customAdapter.FruitAdapter;
import com.zzf.bluetoothsmp.myLayout.MySwipeRefreshLayout;
import com.zzf.bluetoothsmp.utils.LanguageUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class HomeFragment extends BaseFragment {

    private final String TAG = "HomeFragment";
    private static final int REQUEST_ENABLE_BLUETOOTH = 0x33;
    private static final int REQUEST_DISCOVERABLE = 0x32;
    private static final int DISCOVERABLE_DURATION_SECONDS = 300;

    private FragmentHomeBinding binding;
    private RecyclerView mRecyclerView;
    private FruitAdapter adapter;
    private MySwipeRefreshLayout swipeRefresh;
    private final List<Fruit> fruitList = new ArrayList<>();
    private MainActivity mainActivity;
    private BluetoothObject bluetoothObject;
    private Date uploadTime = new Date();
    private MenuItem discoverableMenuItem;
    private boolean scanModeReceiverRegistered;

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
        binding = FragmentHomeBinding.inflate(inflater, container, false);
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
                if (itemId == R.id.home_discoverable) {
                    requestDiscoverable();
                    return true;
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
                        mainActivity.toSetLanguage(0);
                        item.setIcon(R.drawable.ic_zh);

                    }
                    return true;
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
                if (((new Date().getTime() - uploadTime.getTime()) / 1000) < 10) {
                    ToastUtil.toastWord(getString(R.string.update));
                    swipeRefresh.setRefreshing(false);
                    return;
                }
                fruitList.clear();
                adapter = new FruitAdapter(fruitList);
                mRecyclerView.setAdapter(adapter);
                refreshFruits();
                uploadTime = new Date();
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
                    bluetoothObject.connect(mainActivity, mainActivity.mHandler);
                } catch (Exception e) {
                    ToastUtil.toastWord(mainActivity, getString(R.string.connect_fails));
                    e.printStackTrace();
                }
            }
        });

        mainActivity.setOnActivityDataChangedListener(new MainActivity.OnActivityDataChangedListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public synchronized void addFruitData(Fruit f) {
                if (!fruitList.contains(f)) {
                    fruitList.add(f);
                } else {
                    for (int i = 0; i < fruitList.size(); i++) {
                        Fruit fruit = fruitList.get(i);
                        if (fruit.equals(f)) {
                            fruitList.set(i, f);
                            break;
                        }
                    }

                }
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
        updateDiscoverableMenuItem();
    }

    @Override
    public void onStop() {
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
        if (discoverable) {
            discoverableMenuItem.setIcon(R.drawable.ic_visibility);
            discoverableMenuItem.setTitle(R.string.renew_discoverable);
        } else {
            discoverableMenuItem.setIcon(R.drawable.ic_visibility_off);
            discoverableMenuItem.setTitle(R.string.open_discoverable);
        }
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


    private void refreshFruits() {
        new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mainActivity.runOnUiThread(new Runnable() {
                    @SuppressLint({"NotifyDataSetChanged", "MissingPermission"})
                    @Override
                    public void run() {
                        //fruitList.clear();
                        mainActivity.initBluetooth();
                        adapter = new FruitAdapter(fruitList);
                        mRecyclerView.setAdapter(adapter);
                        swipeRefresh.setRefreshing(false);
                    }
                });
            }
        }).start();
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        discoverableMenuItem = null;
        binding = null;
    }
}
