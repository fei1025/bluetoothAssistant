package com.example.bluetoothsmp.ui.dashboard;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.example.bluetoothsmp.databinding.FragmentDashboardBinding;
import com.zzf.bluetoothsmp.customAdapter.InfoAdapter;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.utils.ImageUtils;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class DashboardFragment extends Fragment {

    private static final String TAG = "DashboardFragment";
    private FragmentDashboardBinding binding;
    RecyclerView msgRecyclerView;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        DashboardViewModel dashboardViewModel =
                new ViewModelProvider(this).get(DashboardViewModel.class);

        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        Toolbar toolbar =binding.toolbar;
        toolbar.setTitle("历史记录");
        //final TextView textView = binding.textDashboard;
        // dashboardViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        // Cursor bySQL = LitePal.findBySQL("");
        //BluetoothDrive messageList = LitePal.where("driveAdd = ? ",msg.getBluetoothAdd()).findFirst(BluetoothDrive.class);
        List<BluetoothDrive> all = LitePal.findAll(BluetoothDrive.class);
        Log.d(TAG, "onCreateView: " + all.size());
        if (all == null || all.size() == 0) {
            all = new ArrayList<>();
        }
        initmsg(all);
        msgRecyclerView = binding.cardList;
        InfoAdapter adapter = new InfoAdapter(all);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        msgRecyclerView.setLayoutManager(layoutManager);
        msgRecyclerView.setAdapter(adapter);
        return root;
    }


    public void initmsg(List<BluetoothDrive> all) {
        for (int i = 0; i < 10; i++) {
            BluetoothDrive demo = new BluetoothDrive();
            demo.setLastReceiveMsg("这是数据" + i);
            demo.setSystemImg(ImageUtils.defaultAvatar("A"));
            demo.setDriveName("demo" + i);
            demo.setSenDate(new Date());
            all.add(demo);
        }

    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}