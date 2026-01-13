package com.zzf.bluetoothsmp.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.zzf.bluetoothsmp.databinding.FragmentDashboardBinding;
import com.zzf.bluetoothsmp.customAdapter.InfoAdapter;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.MessageMapper;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zzf.bluetoothsmp.R;

public class DashboardFragment extends Fragment {

    private static final String TAG = "DashboardFragment";
    private FragmentDashboardBinding binding;
    RecyclerView msgRecyclerView;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        Toolbar toolbar = binding.toolbar;
        toolbar.setTitle(R.string.history);

        List<BluetoothDrive> all = LitePal.findAll(BluetoothDrive.class);
        if (all.size() == 0) {
            all = new ArrayList<>();
        }
        msgRecyclerView = binding.cardList;
        InfoAdapter adapter = new InfoAdapter(all);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        msgRecyclerView.setLayoutManager(layoutManager);
        msgRecyclerView.setAdapter(adapter);
        adapter.setOnClickDelete(new InfoAdapter.OnClickDelete() {
            @Override
            public void deleteItem(int adapterPosition, BluetoothDrive remove) {
                List<MessageMapper> messageList = LitePal.where(" sendAdd =? and sendUuid = ?", remove.getDriveAdd(), remove.getUuid()).order("sendTime ").find(MessageMapper.class);
                if (messageList != null && messageList.size() != 0) {
                    for (int i = 0; i < messageList.size(); i++) {
                        MessageMapper messageMapper = messageList.get(i);
                        messageMapper.delete();
                    }
                }
                remove.delete();
            }
        });

        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}