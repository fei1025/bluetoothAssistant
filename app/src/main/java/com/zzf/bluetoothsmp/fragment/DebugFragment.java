package com.zzf.bluetoothsmp.fragment;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.BluetoothServiceConnect;
import com.zzf.bluetoothsmp.customAdapter.DebugLogAdapter;
import com.zzf.bluetoothsmp.databinding.FragmentDebugBinding;
import com.zzf.bluetoothsmp.entity.LogItem;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.event.Event;
import com.zzf.bluetoothsmp.event.EventListener;
import com.zzf.bluetoothsmp.utils.HexUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class DebugFragment extends BaseFragment {

    private static final String ARG_EMBEDDED = "arg_embedded";

    private FragmentDebugBinding binding;
    private DebugLogAdapter adapter;
    private String debugUUID;

    // Connection state
    private boolean isConnected = false;
    private String connectedDeviceAddress;
    private String connectedDeviceName;
    private long connectionStartTime = 0;
    private Handler timerHandler;
    private Runnable timerRunnable;

    // Throughput stats
    private AtomicLong totalBytesSent = new AtomicLong(0);
    private AtomicLong totalBytesReceived = new AtomicLong(0);
    private AtomicLong totalMessages = new AtomicLong(0);

    // Send test
    private volatile boolean isSending = false;
    private Thread sendThread;

    private EventListener sendListener;
    private EventListener receiveListener;
    private EventListener notConnectListener;

    public static DebugFragment newInstance(boolean embedded) {
        DebugFragment fragment = new DebugFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_EMBEDDED, embedded);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        debugUUID = UUID.randomUUID().toString();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDebugBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        boolean embedded = isEmbedded();
        if (!embedded) {
            // 适配 Android 15 边缘到边模式
            setupEdgeToEdge(view);
        } else if (getActivity() != null) {
            requireActivity().getWindow().setStatusBarColor(ContextCompat.getColor(requireContext(), R.color.purple_700));
            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                    requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(false);
        }

        setupToolbar();
        setupRecyclerView();
        setupConnectionMonitor();
        setupSendTest();
        setupDiagnostics();
        registerEventListeners();
        updateConnectionStatus();
    }

    private boolean isEmbedded() {
        Bundle args = getArguments();
        return args != null && args.getBoolean(ARG_EMBEDDED, false);
    }

    private void setupToolbar() {
        binding.toolbar.setTitle(R.string.debug);
        if (isEmbedded()) {
            binding.toolbar.setVisibility(View.GONE);
        }
    }

    private void setupRecyclerView() {
        adapter = new DebugLogAdapter();
        binding.logRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.logRecyclerView.setAdapter(adapter);

        // Toggle Hex/ASCII view
        binding.btnToggleView.setOnClickListener(v -> {
            boolean newShowHex = !adapter.isShowHex();
            adapter.setShowHex(newShowHex);
            binding.btnToggleView.setText(newShowHex ? R.string.hex_view : R.string.ascii_view);
        });

        // Clear log
        binding.btnClearLog.setOnClickListener(v -> {
            adapter.clearLogs();
            resetStats();
        });

        // Export log
        binding.btnExportLog.setOnClickListener(v -> exportLogs());
    }

    private void setupConnectionMonitor() {
        timerHandler = new Handler(Looper.getMainLooper());
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    updateDuration();
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    private void setupSendTest() {
        binding.btnSendOnce.setOnClickListener(v -> sendOnce());
        binding.btnContinuousSend.setOnClickListener(v -> startContinuousSend());
        binding.btnStopSend.setOnClickListener(v -> stopSend());
    }

    private void setupDiagnostics() {
        binding.btnRunDiagnostics.setOnClickListener(v -> runDiagnostics());
    }

    private void registerEventListeners() {
        // Listen for SEND events
        sendListener = new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (!isAdded()) return;
                Msg msg = (Msg) event.getEventData()[0];
                if (isConnected && msg.getBluetoothAdd().equals(connectedDeviceAddress)) {
                    addLogItem(msg, true);
                    totalBytesSent.addAndGet(msg.getPayloadOrUtf8().length);
                    totalMessages.incrementAndGet();
                    updateStats();
                }
            }
        };

        // Listen for RECEIVE events
        receiveListener = new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (!isAdded()) return;
                Msg msg = (Msg) event.getEventData()[0];
                if (isConnected && msg.getBluetoothAdd().equals(connectedDeviceAddress)) {
                    addLogItem(msg, false);
                    totalBytesReceived.addAndGet(msg.getPayloadOrUtf8().length);
                    totalMessages.incrementAndGet();
                    updateStats();
                }
            }
        };

        // Listen for disconnect events
        notConnectListener = new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    handleDisconnect();
                });
            }
        };

        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, sendListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, receiveListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, notConnectListener, debugUUID);
    }

    private void addLogItem(Msg msg, boolean isSent) {
        requireActivity().runOnUiThread(() -> {
            String content = msg.getContent();
            if (content == null) return;

            // Remove trailing \r\n for cleaner display
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            }

            byte[] rawBytes = msg.getPayloadOrUtf8();
            LogItem logItem = new LogItem(content, rawBytes, isSent ? LogItem.TYPE_SENT : LogItem.TYPE_RECEIVED, msg.getBluetoothAdd());
            logItem.setHexContent(HexUtils.bytesToHex(rawBytes));
            logItem.setAsciiContent(HexUtils.bytesToAscii(rawBytes));

            adapter.addLog(logItem);
        });
    }

    private void updateConnectionStatus() {
        Map<String, BluetoothServiceConnect> socketMap = StaticObject.bluetoothSocketMap;
        if (socketMap != null && !socketMap.isEmpty()) {
            Set<String> addresses = socketMap.keySet();
            if (!addresses.isEmpty()) {
                connectedDeviceAddress = addresses.iterator().next();
                BluetoothServiceConnect conn = socketMap.get(connectedDeviceAddress);
                if (conn != null) {
                    connectedDeviceName = getRemoteDeviceName(conn);
                    if (connectedDeviceName == null || connectedDeviceName.isEmpty()) {
                        connectedDeviceName = connectedDeviceAddress;
                    }
                    handleConnect();
                }
            }
        } else {
            handleDisconnect();
        }
    }

    private String getRemoteDeviceName(BluetoothServiceConnect conn) {
        if (conn.bluetoothSocket == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            if (conn.bluetoothSocket.getRemoteDevice() == null) {
                return null;
            }
            return conn.bluetoothSocket.getRemoteDevice().getName();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private void handleConnect() {
        isConnected = true;
        connectionStartTime = System.currentTimeMillis();

        requireActivity().runOnUiThread(() -> {
            binding.connectionState.setText(R.string.connected);
            binding.connectionState.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_700));
            binding.connectionDuration.setVisibility(View.VISIBLE);
            binding.deviceInfoLayout.setVisibility(View.VISIBLE);
            binding.deviceName.setText(getString(R.string.device_name) + ": " + connectedDeviceName);
            binding.deviceAddress.setText(connectedDeviceAddress);
            binding.throughputLayout.setVisibility(View.VISIBLE);

            timerHandler.post(timerRunnable);
        });
    }

    private void handleDisconnect() {
        isConnected = false;
        stopSend();

        requireActivity().runOnUiThread(() -> {
            binding.connectionState.setText(R.string.disconnected);
            binding.connectionState.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
            binding.connectionDuration.setVisibility(View.GONE);
            binding.deviceInfoLayout.setVisibility(View.GONE);
            binding.throughputLayout.setVisibility(View.GONE);
            binding.diagnosticsResult.setVisibility(View.GONE);

            timerHandler.removeCallbacks(timerRunnable);
            adapter.clearLogs();
            resetStats();
        });
    }

    private void updateDuration() {
        long elapsed = System.currentTimeMillis() - connectionStartTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;

        String duration = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        binding.connectionDuration.setText(duration);
    }

    private void updateStats() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            binding.bytesSent.setText(String.format(Locale.getDefault(), "Sent: %d B", totalBytesSent.get()));
            binding.bytesReceived.setText(String.format(Locale.getDefault(), "Rcvd: %d B", totalBytesReceived.get()));
            binding.totalMessages.setText(String.format(Locale.getDefault(), "Msgs: %d", totalMessages.get()));
        });
    }

    private void resetStats() {
        totalBytesSent.set(0);
        totalBytesReceived.set(0);
        totalMessages.set(0);
        updateStats();
    }

    private void sendOnce() {
        if (!isConnected) {
            Toast.makeText(getContext(), R.string.no_connection, Toast.LENGTH_SHORT).show();
            return;
        }

        String hexInput = binding.hexInput.getText().toString().trim();
        if (hexInput.isEmpty()) {
            Toast.makeText(getContext(), R.string.invalid_hex, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!HexUtils.isValidHex(hexInput)) {
            Toast.makeText(getContext(), R.string.invalid_hex, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean appendCrlf = binding.checkboxAppendCrlf.isChecked();
        if (appendCrlf) {
            hexInput = hexInput.replaceAll("\\s", "") + "0D0A";
        }

        byte[] bytes = HexUtils.hexStringToBytes(hexInput);
        sendMessage(bytes);
        Toast.makeText(getContext(), R.string.send_success, Toast.LENGTH_SHORT).show();
    }

    private void startContinuousSend() {
        if (!isConnected) {
            Toast.makeText(getContext(), R.string.no_connection, Toast.LENGTH_SHORT).show();
            return;
        }

        final String hexInput = binding.hexInput.getText().toString().trim();
        if (hexInput.isEmpty() || !HexUtils.isValidHex(hexInput)) {
            Toast.makeText(getContext(), R.string.invalid_hex, Toast.LENGTH_SHORT).show();
            return;
        }

        final int interval;
        int parsedInterval = 100;
        try {
            int input = Integer.parseInt(binding.intervalInput.getText().toString());
            parsedInterval = Math.max(input, 10);
        } catch (NumberFormatException ignored) {
        }
        interval = parsedInterval;

        final boolean appendCrlf = binding.checkboxAppendCrlf.isChecked();

        isSending = true;
        binding.btnContinuousSend.setEnabled(false);
        binding.btnStopSend.setEnabled(true);

        sendThread = new Thread(() -> {
            while (isSending && isConnected) {
                String hex = hexInput.replaceAll("\\s", "");
                if (appendCrlf) {
                    hex += "0D0A";
                }
                byte[] bytes = HexUtils.hexStringToBytes(hex);
                sendMessage(bytes);

                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        sendThread.start();
    }

    private void stopSend() {
        isSending = false;
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        requireActivity().runOnUiThread(() -> {
            binding.btnContinuousSend.setEnabled(true);
            binding.btnStopSend.setEnabled(false);
        });
    }

    private void sendMessage(byte[] payload) {
        Msg msg = new Msg(payload, Msg.TYPE_SENT, connectedDeviceAddress);
        try {
            StaticObject.mTaskQueue.put(msg);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void runDiagnostics() {
        if (!isConnected) {
            Toast.makeText(getContext(), R.string.no_connection, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.diagnosticsResult.setVisibility(View.VISIBLE);
        binding.diagnosticsResult.setText(R.string.testing_uuid);

        new Thread(() -> {
            try {
                Thread.sleep(500);
                requireActivity().runOnUiThread(() -> {
                    binding.diagnosticsResult.setText(R.string.uuid_available);
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void exportLogs() {
        if (adapter.getItemCount() == 0) {
            Toast.makeText(getContext(), "No logs to export", Toast.LENGTH_SHORT).show();
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String filename = "bluetooth_debug_" + sdf.format(new Date()) + ".txt";

        StringBuilder sb = new StringBuilder();
        sb.append("Bluetooth Debug Log\n");
        sb.append("Exported: ").append(sdf.format(new Date())).append("\n");
        sb.append("Device: ").append(connectedDeviceName != null ? connectedDeviceName : "N/A").append("\n");
        sb.append("Address: ").append(connectedDeviceAddress != null ? connectedDeviceAddress : "N/A").append("\n");
        sb.append("---\n\n");

        for (LogItem item : adapter.getLogs()) {
            String dir = item.isSent() ? "SEND" : "RECV";
            sb.append("[").append(item.getTimestamp()).append("] ").append(dir).append("\n");
            sb.append("HEX: ").append(item.getHexContent()).append("\n");
            sb.append("ASCII: ").append(item.getContent()).append("\n\n");
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = requireContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

                if (uri != null) {
                    try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                        os.write(sb.toString().getBytes());
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    requireContext().getContentResolver().update(uri, values, null, null);
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadsDir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(sb.toString().getBytes());
                }
            }
            Toast.makeText(getContext(), R.string.log_exported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), R.string.export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timerHandler.removeCallbacks(timerRunnable);
        stopSend();

        if (debugUUID != null) {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(debugUUID);
        }

        binding = null;
    }
}
