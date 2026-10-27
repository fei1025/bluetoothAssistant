package com.zzf.bluetoothsmp.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.customAdapter.InfoAdapter;
import com.zzf.bluetoothsmp.databinding.FragmentDashboardBinding;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.utils.HistoryRepository;
import com.zzf.bluetoothsmp.utils.HistoryRetention;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardFragment extends BaseFragment {
    private static final String ARG_EMBEDDED = "arg_embedded";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService database = Executors.newSingleThreadExecutor();
    private final String listenerId = UUID.randomUUID().toString();
    private FragmentDashboardBinding binding;
    private InfoAdapter adapter;
    private int viewGeneration;
    private boolean active;
    private boolean loading;
    private boolean refreshAgain;
    private boolean refreshScheduled;

    @Override
    protected String getAnalyticsScreenName() { return "history"; }

    public static DashboardFragment newInstance(boolean embedded) {
        DashboardFragment fragment = new DashboardFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_EMBEDDED, embedded);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        viewGeneration++;
        loading = false;
        refreshAgain = false;
        Toolbar toolbar = binding.toolbar;
        toolbar.setTitle(R.string.history);
        toolbar.getMenu().add(R.string.history_retention).setOnMenuItemClickListener(item -> {
            showRetention();
            return true;
        });
        if (getArguments() != null && getArguments().getBoolean(ARG_EMBEDDED, false)) {
            toolbar.setVisibility(View.GONE);
        }
        setupEdgeToEdge(binding.getRoot());
        adapter = new InfoAdapter(new ArrayList<>());
        binding.cardList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.cardList.setAdapter(adapter);
        adapter.setOnClickDelete((position, drive) -> deleteConversation(drive));
        return binding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        active = true;
        loading = false;
        adapter.clearPendingDeletes();
        refreshHistory();
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.HISTORY_CHANGED,
                ignored -> scheduleRefresh(), listenerId);
    }

    private void scheduleRefresh() {
        main.post(() -> {
            if (!active || binding == null || refreshScheduled) return;
            refreshScheduled = true;
            main.postDelayed(() -> {
                refreshScheduled = false;
                if (active && binding != null) refreshHistory();
            }, 750L);
        });
    }

    private void refreshHistory() {
        if (!active || binding == null) return;
        if (loading) {
            refreshAgain = true;
            return;
        }
        loading = true;
        final int generation = viewGeneration;
        database.execute(() -> {
            try {
                List<BluetoothDrive> rows = HistoryRepository.loadConversations();
                main.post(() -> {
                    if (generation != viewGeneration || binding == null) return;
                    loading = false;
                    adapter.submitList(rows);
                    if (refreshAgain) {
                        refreshAgain = false;
                        refreshHistory();
                    }
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (generation != viewGeneration || binding == null) return;
                    loading = false;
                    if (active) Toast.makeText(requireContext(), R.string.history_load_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void deleteConversation(BluetoothDrive drive) {
        final int generation = viewGeneration;
        adapter.setDeleting(drive, true);
        database.execute(() -> {
            boolean removed;
            try {
                removed = HistoryRepository.deleteConversation(drive.getDriveAdd(), drive.getUuid());
            } catch (RuntimeException error) {
                removed = false;
            }
            final boolean success = removed;
            main.post(() -> {
                if (generation != viewGeneration || binding == null) return;
                adapter.setDeleting(drive, false);
                if (!success) Toast.makeText(requireContext(), R.string.history_delete_failed, Toast.LENGTH_SHORT).show();
                refreshHistory();
            });
        });
    }

    private void showRetention() {
        final android.content.Context context = requireContext().getApplicationContext();
        int[] days = {0, 7, 30, 90};
        String[] options = {getString(R.string.history_retention_unlimited),
                getString(R.string.history_retention_days, 7),
                getString(R.string.history_retention_days, 30),
                getString(R.string.history_retention_days, 90)};
        int currentDays = HistoryRetention.getDays(context);
        int selected = 0;
        for (int i = 0; i < days.length; i++) if (days[i] == currentDays) selected = i;
        final int[] choice = {selected};
        new AlertDialog.Builder(requireContext()).setTitle(R.string.history_retention)
                .setSingleChoiceItems(options, selected, (dialog, which) -> choice[0] = which)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int selectedDays = days[choice[0]];
                    if (selectedDays == 0) {
                        applyRetention(context, selectedDays);
                    } else {
                        new AlertDialog.Builder(requireContext()).setTitle(R.string.history_retention)
                                .setMessage(R.string.history_retention_confirm)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.confirm,
                                        (ignored, button) -> applyRetention(context, selectedDays)).show();
                    }
                }).show();
    }

    private void applyRetention(android.content.Context context, int days) {
        final int generation = viewGeneration;
        database.execute(() -> {
            boolean saved = HistoryRetention.setDaysAndPrune(context, days);
            main.post(() -> {
                if (generation != viewGeneration || binding == null) return;
                if (!saved) Toast.makeText(requireContext(), R.string.history_retention_failed, Toast.LENGTH_SHORT).show();
                refreshHistory();
            });
        });
    }

    @Override
    public void onStop() {
        active = false;
        viewGeneration++;
        loading = false;
        refreshScheduled = false;
        main.removeCallbacksAndMessages(null);
        StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerId);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        binding.cardList.setAdapter(null);
        binding = null;
        adapter = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        database.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
