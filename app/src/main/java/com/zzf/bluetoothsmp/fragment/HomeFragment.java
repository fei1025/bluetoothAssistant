package com.zzf.bluetoothsmp.fragment;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.example.bluetoothsmp.R;
import com.example.bluetoothsmp.databinding.FragmentHomeBinding;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.Fruit;
import com.zzf.bluetoothsmp.Liao_tian;
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.customAdapter.FruitAdapter;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.myLayout.MySwipeRefreshLayout;
import com.zzf.bluetoothsmp.utils.LanguageUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class HomeFragment extends Fragment {

    private final String TAG = "HomeFragment";
    private FragmentHomeBinding binding;
    private RecyclerView mRecyclerView;
    private FruitAdapter adapter;
    private MySwipeRefreshLayout swipeRefresh;
    private final List<Fruit> fruitList = new ArrayList<>();
    private MainActivity mainActivity;
    private BluetoothObject bluetoothObject;
    private Date uploadTime = new Date();


    @SuppressLint("ResourceAsColor")
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        mainActivity = (MainActivity) getActivity();
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        Toolbar toolbar = binding.toolbar;
        toolbar.setTitle(getString(R.string.bluetooth_assistant));

        toolbar.inflateMenu(R.menu.home_menu);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                String url = "";
                switch (itemId) {
                    case R.id.ys:
                        url = "https://zhangzhenfei.cn/archives/lan-ya-ce-shi-zhu-shou---yin-si-zheng-ce";
                        break;
                    case R.id.me:
                        url = "https://zhangzhenfei.cn/archives/spp-lan-ya-zhu-shou---shi-yong-shuo-ming";
                        break;
                    case R.id.bt_menu_language:
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

    public void onStop() {
        super.onStop();
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
        binding = null;
    }
}