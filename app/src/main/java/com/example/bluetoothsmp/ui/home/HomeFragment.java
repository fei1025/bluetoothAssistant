package com.example.bluetoothsmp.ui.home;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.bluetoothsmp.R;
import com.example.bluetoothsmp.databinding.FragmentHomeBinding;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.Fruit;
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.customAdapter.FruitAdapter;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class HomeFragment extends Fragment {

    private final String TAG = "HomeFragment";
    private FragmentHomeBinding binding;
    private RecyclerView mRecyclerView;
    private FruitAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private final List<Fruit> fruitList = new ArrayList<>();
    private MainActivity mainActivity;
    private BluetoothObject bluetoothObject;


    @SuppressLint("ResourceAsColor")
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        mainActivity = (MainActivity) getActivity();
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        Toolbar toolbar = binding.toolbar;
        toolbar.setTitle(R.string.bluetooth_assistant);
        mRecyclerView = binding.cardList;
        mRecyclerView.setHasFixedSize(true);
        adapter = new FruitAdapter(fruitList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(adapter);
        // mRecyclerView.setVisibility(View.GONE);
        //设置下拉刷新
        swipeRefresh = binding.swipeRefresh;
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


    private void refreshFruits() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                mainActivity.runOnUiThread(new Runnable() {
                    @SuppressLint("NotifyDataSetChanged")
                    @Override
                    public void run() {
                        fruitList.clear();
                        adapter = new FruitAdapter(fruitList);
                        mRecyclerView.setAdapter(adapter);
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        swipeRefresh.setRefreshing(false);
                        //adapter.notifyDataSetChanged();
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