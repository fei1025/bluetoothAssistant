package com.zzf.bluetoothsmp.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.BluetoothServiceConnect;
import com.zzf.bluetoothsmp.BluetoothConnectionErrorCode;
import com.zzf.bluetoothsmp.BluetoothConnectionLogEntry;
import com.zzf.bluetoothsmp.BluetoothConnectionState;
import com.zzf.bluetoothsmp.BluetoothFrameConfig;
import com.zzf.bluetoothsmp.BluetoothFrameMode;
import com.zzf.bluetoothsmp.BluetoothProtocolConfigStore;
import com.zzf.bluetoothsmp.BluetoothTextEncoding;
import com.zzf.bluetoothsmp.BluetoothTextEncodingStore;
import com.zzf.bluetoothsmp.BluetoothSendStatus;
import com.zzf.bluetoothsmp.BluetoothSendBatch;
import com.zzf.bluetoothsmp.BluetoothTelemetry;
import com.zzf.bluetoothsmp.MacroExecutor;
import com.zzf.bluetoothsmp.MacroParser;
import com.zzf.bluetoothsmp.MacroStep;
import com.zzf.bluetoothsmp.MacroTransferCodec;
import com.zzf.bluetoothsmp.DebugTransferState;
import com.zzf.bluetoothsmp.DebugLogPipeline;
import com.zzf.bluetoothsmp.DebugMacroState;
import com.zzf.bluetoothsmp.utils.HistoryRepository;
import com.zzf.bluetoothsmp.customAdapter.DebugLogAdapter;
import com.zzf.bluetoothsmp.databinding.FragmentDebugBinding;
import com.zzf.bluetoothsmp.entity.CommandMacroEntity;
import com.zzf.bluetoothsmp.entity.LogItem;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.event.Event;
import com.zzf.bluetoothsmp.event.EventListener;
import com.zzf.bluetoothsmp.utils.HexUtils;
import com.zzf.bluetoothsmp.utils.CrcUtils;
import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class DebugFragment extends BaseFragment {

    @Override
    protected String getAnalyticsScreenName() {
        return isEmbedded() ? "chat_debug" : "debug";
    }

    private static final String ARG_EMBEDDED = "arg_embedded";

    private FragmentDebugBinding binding;
    private DebugLogAdapter adapter;
    private String debugUUID;

    // Connection state
    private volatile boolean isConnected = false;
    private String connectedDeviceAddress;
    private volatile String connectedSessionId;
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
    private BluetoothSendBatch continuousSendBatch;

    private EventListener sendListener;
    private EventListener sendStatusListener;
    private EventListener receiveListener;
    private EventListener notConnectListener;
    private String lastDiagnosticsReport;
    private BluetoothServiceConnect progressConnection;
    private boolean segmentedSendActive;
    private MacroExecutor macroExecutor;
    private BluetoothSendBatch macroSendBatch;
    private long statsSentBase, statsReceivedBase, statsFramesBase, statsSendCountBase, statsErrorsBase;
    private long statsStartedAt = System.currentTimeMillis();
    private final List<CommandMacroEntity> macros = new ArrayList<>();
    private ArrayAdapter<String> macroAdapter;
    private boolean loadingMacroSelection;
    private DebugMacroState macroState;
    private DebugTransferState transferState;
    private ActivityResultLauncher<Intent> macroExportLauncher;
    private ActivityResultLauncher<Intent> macroImportLauncher;
    private ActivityResultLauncher<Intent> logExportLauncher;
    private volatile DebugLogPipeline logPipeline;
    private final Handler logUi = new Handler(Looper.getMainLooper());
    private boolean displayPaused;
    private long lastLogRevision = -1;
    private volatile long viewEpoch;
    private final Runnable flushLogs = new Runnable() {
        @Override public void run() {
            if (binding == null || logPipeline == null) return;
            long revision = logPipeline.revision();
            if (!displayPaused && revision != lastLogRevision) {
                adapter.replaceLogs(logPipeline.snapshot()); lastLogRevision = revision;
            }
            updateStats();
            logUi.postDelayed(this, 100);
        }
    };
    private ArrayAdapter<String> deviceSelectorAdapter;
    private final List<String> debugDeviceAddresses = new ArrayList<>();
    private boolean loadingDeviceSelection;

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
        transferState = new ViewModelProvider(this).get(DebugTransferState.class);
        macroState = new ViewModelProvider(this).get(DebugMacroState.class);
        macroExportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> documentResult(result, "macro"));
        macroImportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> documentResult(result, "import"));
        logExportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> documentResult(result, "log"));
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
        viewEpoch++;
        logPipeline = new DebugLogPipeline(getString(R.string.debug_preview_truncated));
        lastLogRevision = -1;
        macroExecutor = new MacroExecutor();

        boolean embedded = isEmbedded();
        if (!embedded) {
            // 适配 Android 15 边缘到边模式
            setupEdgeToEdge(view);
        } else if (getActivity() != null) {
            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                    requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(false);
        }

        setupToolbar();
        setupRecyclerView();
        setupDeviceSelector();
        setupConnectionMonitor();
        setupSendTest();
        setupMacros();
        setupDiagnostics();
        registerEventListeners();
        updateConnectionStatus();
        loadFrameConfig();
        observeTransfers();
        transferState.resumePending(requireContext());
        logUi.post(flushLogs);
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
        binding.btnPauseLogs.setText(displayPaused ? R.string.debug_resume_logs : R.string.debug_pause_logs);

        // Toggle Hex/ASCII view
        binding.btnToggleView.setOnClickListener(v -> {
            boolean newShowHex = !adapter.isShowHex();
            adapter.setShowHex(newShowHex);
            binding.btnToggleView.setText(newShowHex ? R.string.hex_view : R.string.ascii_view);
        });

        // Clear log
        binding.btnClearLog.setOnClickListener(v -> {
            logPipeline.clear();
            adapter.clearLogs();
            resetStats();
        });

        // Export log
        binding.btnExportLog.setOnClickListener(v -> exportLogs());
        binding.btnPauseLogs.setOnClickListener(v -> {
            displayPaused = !displayPaused;
            binding.btnPauseLogs.setText(displayPaused ? R.string.debug_resume_logs : R.string.debug_pause_logs);
            if (!displayPaused) lastLogRevision = -1;
        });
    }

    private void setupConnectionMonitor() {
        timerHandler = new Handler(Looper.getMainLooper());
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    refreshDeviceSelector();
                    updateDuration();
                    updateStats();
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    private void setupDeviceSelector() {
        deviceSelectorAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceSelectorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.debugDeviceSelector.setAdapter(deviceSelectorAdapter);
        binding.debugDeviceSelector.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                               int position, long id) {
                        if (!loadingDeviceSelection && position >= 0
                                && position < debugDeviceAddresses.size()) {
                            selectDebugDevice(debugDeviceAddresses.get(position));
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
    }

    private void refreshDeviceSelector() {
        if (deviceSelectorAdapter == null || binding == null) {
            return;
        }
        List<String> addresses = new ArrayList<>(StaticObject.bluetoothSocketMap.keySet());
        Collections.sort(addresses);
        String selectedAddress = connectedDeviceAddress;
        debugDeviceAddresses.clear();
        deviceSelectorAdapter.clear();
        for (String address : addresses) {
            BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(address);
            if (connection == null) {
                continue;
            }
            String name = getRemoteDeviceName(connection);
            String display = name == null || name.trim().isEmpty()
                    ? address : name + " (" + address + ")";
            debugDeviceAddresses.add(address);
            deviceSelectorAdapter.add(display);
        }
        loadingDeviceSelection = true;
        deviceSelectorAdapter.notifyDataSetChanged();
        if (debugDeviceAddresses.isEmpty()) {
            loadingDeviceSelection = false;
            if (isConnected) {
                handleDisconnect();
            }
            return;
        }
        if (selectedAddress == null || !debugDeviceAddresses.contains(selectedAddress)) {
            selectedAddress = debugDeviceAddresses.get(0);
        }
        int selectedIndex = debugDeviceAddresses.indexOf(selectedAddress);
        binding.debugDeviceSelector.setSelection(Math.max(0, selectedIndex));
        loadingDeviceSelection = false;
        selectDebugDevice(selectedAddress);
    }

    private void selectDebugDevice(String address) {
        BluetoothServiceConnect connection = address == null
                ? null : StaticObject.bluetoothSocketMap.get(address);
        if (connection == null) {
            return;
        }
        boolean changed = !address.equals(connectedDeviceAddress)
                || !connection.getSessionId().equals(connectedSessionId);
        if (changed) stopSend();
        connectedDeviceAddress = address;
        connectedSessionId = connection.getSessionId();
        connectedDeviceName = getRemoteDeviceName(connection);
        if (connectedDeviceName == null || connectedDeviceName.isEmpty()) {
            connectedDeviceName = address;
        }
        if (changed) {
            logPipeline.clear();
            adapter.clearLogs();
            resetStats();
        }
        if (changed || !isConnected) {
            handleConnect();
        }
    }

    private void setupSendTest() {
        binding.btnSendOnce.setOnClickListener(v -> sendOnce());
        binding.btnContinuousSend.setOnClickListener(v -> startContinuousSend());
        binding.btnStopSend.setOnClickListener(v -> stopSend());
        binding.btnAppendCrc.setOnClickListener(v -> appendChecksum());
        binding.btnSaveFrameConfig.setOnClickListener(v -> saveFrameConfig());
    }

    private void loadFrameConfig() {
        String address = resolveDiagnosticAddress();
        if (address == null || binding == null) {
            return;
        }
        BluetoothFrameConfig config = BluetoothProtocolConfigStore.get(requireContext(), address);
        String mode = config.getMode().name();
        for (int i = 0; i < binding.frameModeSpinner.getCount(); i++) {
            if (mode.equals(binding.frameModeSpinner.getItemAtPosition(i).toString())) {
                binding.frameModeSpinner.setSelection(i);
                break;
            }
        }
        binding.frameFixedLengthInput.setText(String.valueOf(config.getFixedLength()));
        binding.frameTimeoutInput.setText(String.valueOf(config.getTimeoutMillis()));
        binding.frameDelimiterInput.setText(HexUtils.bytesToHex(config.getDelimiter()));
        BluetoothTextEncoding encoding = BluetoothTextEncodingStore.get(requireContext(), address);
        for (int i = 0; i < binding.textEncodingSpinner.getCount(); i++) {
            if (encoding.name().equals(binding.textEncodingSpinner.getItemAtPosition(i).toString())) {
                binding.textEncodingSpinner.setSelection(i);
                break;
            }
        }
    }

    private void saveFrameConfig() {
        String address = resolveDiagnosticAddress();
        if (address == null) {
            Toast.makeText(getContext(), R.string.frame_config_no_device, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            BluetoothFrameMode mode = BluetoothFrameMode.valueOf(
                    binding.frameModeSpinner.getSelectedItem().toString());
            int fixedLength = parsePositive(binding.frameFixedLengthInput.getText());
            long timeoutMillis = parseNonNegative(binding.frameTimeoutInput.getText());
            byte[] delimiter;
            switch (mode) {
                case CRLF:
                    delimiter = new byte[]{'\r', '\n'};
                    break;
                case LF:
                    delimiter = new byte[]{'\n'};
                    break;
                case CR:
                    delimiter = new byte[]{'\r'};
                    break;
                case CUSTOM:
                    String delimiterText = binding.frameDelimiterInput.getText() == null
                            ? "" : binding.frameDelimiterInput.getText().toString().trim();
                    if (!HexUtils.isValidHex(delimiterText)) {
                        throw new IllegalArgumentException("invalid delimiter");
                    }
                    delimiter = HexUtils.hexStringToBytes(delimiterText);
                    break;
                case RAW:
                case FIXED_LENGTH:
                case TIMEOUT:
                default:
                    delimiter = new byte[0];
                    break;
            }
            if (mode == BluetoothFrameMode.TIMEOUT && timeoutMillis <= 0) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            BluetoothFrameConfig config = new BluetoothFrameConfig(mode,
                    BluetoothFrameConfig.DEFAULT_MAX_FRAME_BYTES, fixedLength,
                    delimiter, timeoutMillis);
            BluetoothProtocolConfigStore.save(requireContext(), address, config);
            BluetoothTextEncodingStore.save(requireContext(), address,
                    BluetoothTextEncoding.valueOf(binding.textEncodingSpinner
                            .getSelectedItem().toString()));
            Toast.makeText(getContext(), R.string.frame_config_saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(getContext(), R.string.frame_config_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private int parsePositive(android.text.Editable editable) {
        int value = Integer.parseInt(editable == null ? "" : editable.toString().trim());
        if (value <= 0) {
            throw new IllegalArgumentException("value must be positive");
        }
        return value;
    }

    private long parseNonNegative(android.text.Editable editable) {
        long value = Long.parseLong(editable == null ? "" : editable.toString().trim());
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
        return value;
    }

    private void appendChecksum() {
        String input = binding.hexInput.getText() == null
                ? "" : binding.hexInput.getText().toString().trim();
        if (!HexUtils.isValidHex(input)) {
            Toast.makeText(getContext(), R.string.crc_invalid_input, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            CrcUtils.Algorithm algorithm = CrcUtils.Algorithm.valueOf(
                    binding.crcAlgorithmSpinner.getSelectedItem().toString());
            String result = CrcUtils.appendChecksumHex(input, algorithm,
                    binding.crcLittleEndian.isChecked());
            binding.hexInput.setText(result);
            binding.hexInput.setSelection(result.length());
        } catch (IllegalArgumentException error) {
            Toast.makeText(getContext(), R.string.crc_invalid_input, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupDiagnostics() {
        binding.btnRunDiagnostics.setOnClickListener(v -> runDiagnostics());
        binding.btnCopyDiagnostics.setOnClickListener(v -> copyDiagnostics());
    }

    private void setupMacros() {
        macroAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new ArrayList<>());
        macroAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.macroSelector.setAdapter(macroAdapter);
        binding.macroSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (!loadingMacroSelection && position >= 0 && position < macros.size()) {
                    loadMacro(macros.get(position));
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        binding.btnSaveMacro.setOnClickListener(v -> saveMacro());
        binding.btnRunMacro.setOnClickListener(v -> runMacro());
        binding.btnDeleteMacro.setOnClickListener(v -> deleteMacro());
        binding.btnExportMacros.setOnClickListener(v -> exportMacros());
        binding.btnImportMacros.setOnClickListener(v -> importMacros());
        binding.btnMacroHelp.setOnClickListener(v -> showMacroHelp());
        macroState.results().observe(getViewLifecycleOwner(), result -> {
            if (result == null || !java.util.Objects.equals(result.address, normalizedDiagnosticAddress())) return;
            renderMacros(result.macros);
            int feedback = macroState.consumeFeedback(result.generation);
            if (feedback != 0) Toast.makeText(requireContext(), feedback, Toast.LENGTH_SHORT).show();
        });
    }

    private void showMacroHelp() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.macro_help_title)
                .setMessage(R.string.macro_help_message)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void loadMacros() {
        if (macroAdapter == null || binding == null) return;
        macroState.load(normalizedDiagnosticAddress());
    }

    private void renderMacros(List<CommandMacroEntity> loaded) {
        if (binding == null || macroAdapter == null) return;
        macros.clear();
        macros.addAll(loaded);
        loadingMacroSelection = true;
        macroAdapter.clear();
        for (CommandMacroEntity macro : macros) {
            macroAdapter.add(macro.getName());
        }
        macroAdapter.notifyDataSetChanged();
        loadingMacroSelection = false;
        if (!macros.isEmpty()) {
            loadMacro(macros.get(0));
        } else {
            binding.macroNameInput.setText("");
            binding.macroScriptInput.setText("");
            binding.macroRepeatInput.setText("1");
        }
    }

    private boolean rejectBusyMacroStorage() {
        if (!macroState.isBusy()) return false;
        Toast.makeText(requireContext(), R.string.macro_storage_busy, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void loadMacro(CommandMacroEntity macro) {
        if (macro == null || binding == null) {
            return;
        }
        binding.macroNameInput.setText(macro.getName());
        binding.macroScriptInput.setText(macro.getScript());
        binding.macroRepeatInput.setText(String.valueOf(macro.getRepeatCount()));
    }

    private CommandMacroEntity selectedMacro() {
        int position = binding.macroSelector.getSelectedItemPosition();
        return position >= 0 && position < macros.size() ? macros.get(position) : null;
    }

    private void saveMacro() {
        if (rejectBusyMacroStorage()) return;
        String address = normalizedDiagnosticAddress();
        if (address == null) {
            Toast.makeText(getContext(), R.string.macro_no_device, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = binding.macroNameInput.getText() == null ? ""
                : binding.macroNameInput.getText().toString().trim();
        String script = binding.macroScriptInput.getText() == null ? ""
                : binding.macroScriptInput.getText().toString();
        if (name.isEmpty()) {
            Toast.makeText(getContext(), R.string.macro_name_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (script.trim().isEmpty()) {
            Toast.makeText(getContext(), R.string.macro_script_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int repeat;
        try {
            repeat = parsePositive(binding.macroRepeatInput.getText());
        } catch (IllegalArgumentException error) {
            Toast.makeText(getContext(), R.string.macro_repeat_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (repeat > 100) {
            Toast.makeText(getContext(), R.string.macro_repeat_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MacroParser.parse(script);
        } catch (IllegalArgumentException error) {
            Toast.makeText(getContext(), R.string.macro_script_invalid,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!macroState.save(address, name, script, repeat)) rejectBusyMacroStorage();
    }

    private void deleteMacro() {
        if (rejectBusyMacroStorage()) return;
        CommandMacroEntity macro = selectedMacro();
        if (macro == null) {
            return;
        }
        String address = normalizedDiagnosticAddress();
        if (!java.util.Objects.equals(address, macro.getBluetoothAddress())) return;
        if (!macroState.delete(address, macro)) rejectBusyMacroStorage();
    }

    private void exportMacros() {
        String address = normalizedDiagnosticAddress();
        if (address == null) {
            Toast.makeText(getContext(), R.string.macro_transfer_no_device, Toast.LENGTH_SHORT).show(); return;
        }
        if (!transferState.prepareMacros(requireContext(), address)) {
            Toast.makeText(getContext(), R.string.debug_file_busy, Toast.LENGTH_SHORT).show();
        }
    }

    private void importMacros() {
        String address = normalizedDiagnosticAddress();
        if (address == null) {
            Toast.makeText(getContext(), R.string.macro_transfer_no_device, Toast.LENGTH_SHORT).show(); return;
        }
        if (!transferState.beginImport(address)) {
            Toast.makeText(getContext(), R.string.debug_file_busy, Toast.LENGTH_SHORT).show(); return;
        }
        try {
            macroImportLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
        } catch (RuntimeException unavailablePicker) {
            transferState.cancel("import");
            Toast.makeText(requireContext(), R.string.macro_transfer_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void observeTransfers() {
        transferState.readyExports().observe(getViewLifecycleOwner(), slot -> {
            if (slot == null || !transferState.markPickerLaunched(slot)) return;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, transferState.filename(slot))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                if ("macro".equals(slot)) macroExportLauncher.launch(intent);
                else logExportLauncher.launch(intent);
            } catch (RuntimeException unavailablePicker) {
                transferState.cancel(slot);
                Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show();
            }
        });
        transferState.feedback().observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;
            int resource = result.getInt("resource"), count = result.getInt("count");
            transferState.consumeFeedback();
            Toast.makeText(requireContext(), resource == R.string.macro_imported
                    ? getString(resource, count) : getString(resource), Toast.LENGTH_SHORT).show();
            if (resource == R.string.macro_imported) loadMacros();
            if (resource == R.string.log_exported) BluetoothTelemetry.logUserAction("log_exported");
        });
    }

    private void documentResult(ActivityResult result, String slot) {
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            transferState.cancel(slot); return;
        }
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if (flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                requireContext().getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (flags == Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (flags == Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
                requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
        catch (SecurityException | IllegalArgumentException nonPersistentProvider) { /* Immediate IO still uses the activity grant. */ }
        if ("import".equals(slot)) transferState.importMacros(requireContext(), uri);
        else transferState.writeExport(requireContext(), uri, slot);
    }

    private void runMacro() {
        String address = normalizedDiagnosticAddress();
        if (address == null) {
            Toast.makeText(getContext(), R.string.macro_no_device, Toast.LENGTH_SHORT).show();
            return;
        }
        CommandMacroEntity macro = selectedMacro();
        if (macro == null) {
            Toast.makeText(getContext(), R.string.macro_no_selection,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            List<MacroStep> steps = MacroParser.parse(macro.getScript());
            final long generation = StaticObject.foregroundGate.currentGeneration();
            final String session = connectedSessionId;
            if (macroSendBatch != null) macroSendBatch.cancel();
            final BluetoothSendBatch batch = new BluetoothSendBatch();
            macroSendBatch = batch;
            getMacroExecutor().execute(steps, macro.getRepeatCount(),
                    step -> enqueueMacroStep(address, step, generation, session, batch));
            Toast.makeText(getContext(), R.string.macro_started, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(getContext(), R.string.macro_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void enqueueMacroStep(String address, MacroStep step, long generation, String session,
                                  BluetoothSendBatch batch) throws InterruptedException {
        if (!StaticObject.foregroundGate.isCurrent(generation)) {
            throw new InterruptedException("Macro foreground interval ended");
        }
        if (step.getType() == MacroStep.Type.TEXT) {
            BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(address);
            if (connection == null || !connection.getSessionId().equals(session) || batch.isCanceled()) {
                throw new IllegalStateException("device is no longer connected");
            }
            Msg msg = new Msg(connection.encodeTextPayload(step.getText()), Msg.TYPE_SENT, address);
            msg.setForegroundGeneration(generation);
            msg.setSessionId(session);
            msg.setSendBatch(batch);
            msg.setContent(step.getText());
            msg.setPersistHistory(false);
            if (!StaticObject.submitMessage(msg)) {
                postToView(() -> Toast.makeText(requireContext(), R.string.send_queue_full, Toast.LENGTH_SHORT).show());
                throw new InterruptedException("Macro admission failed");
            }
            if (!awaitSend(msg, session, generation)) throw new InterruptedException("Macro send failed");
        } else if (step.getType() == MacroStep.Type.HEX) {
            Msg msg = enqueuePayload(step.getBytes(), address, 0L, false, batch, generation, session);
            if (msg == null || !awaitSend(msg, session, generation)) throw new InterruptedException("Macro send failed");
        }
    }

    private String normalizedDiagnosticAddress() {
        return BluetoothAddressUtils.normalize(resolveDiagnosticAddress());
    }

    private boolean matchesSelectedSession(Msg message) {
        return message != null && isConnected && connectedSessionId != null
                && connectedSessionId.equals(message.getSessionId())
                && java.util.Objects.equals(connectedDeviceAddress, message.getBluetoothAdd())
                && message.isForegroundGenerationCurrent();
    }

    private void registerEventListeners() {
        final long listenerEpoch = viewEpoch;
        sendListener = event -> acceptLog((Msg) event.getEventData()[0], true, listenerEpoch);
        sendStatusListener = event -> acceptLog((Msg) event.getEventData()[0], true, listenerEpoch);
        receiveListener = event -> acceptLog((Msg) event.getEventData()[0], false, listenerEpoch);
        notConnectListener = event -> postToView(this::refreshDeviceSelector);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, sendListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND_STATUS, sendStatusListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, receiveListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, notConnectListener, debugUUID);
    }

    private void acceptLog(Msg message, boolean sent, long listenerEpoch) {
        DebugLogPipeline pipeline = logPipeline;
        if (listenerEpoch == viewEpoch && pipeline != null && matchesSelectedSession(message)) {
            pipeline.offer(message, sent);
        }
    }

    private void postToView(Runnable action) {
        if (action == null || binding == null) {
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        final long epoch = viewEpoch;
        activity.runOnUiThread(() -> {
            if (!isAdded() || binding == null || epoch != viewEpoch) {
                return;
            }
            action.run();
        });
    }

    private void updateConnectionStatus() {
        refreshDeviceSelector();
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
        BluetoothServiceConnect connection = connectedDeviceAddress == null ? null
                : StaticObject.bluetoothSocketMap.get(connectedDeviceAddress);
        if (connection != null && connection.getConnectedAtMillis() > 0) {
            connectionStartTime = connection.getConnectedAtMillis();
        } else if (connectionStartTime <= 0) {
            connectionStartTime = System.currentTimeMillis();
        }
        attachSendProgressListener(connection);

        postToView(() -> {
            binding.connectionState.setText(R.string.connected);
            binding.connectionState.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_700));
            binding.connectionDuration.setVisibility(View.VISIBLE);
            binding.deviceInfoLayout.setVisibility(View.VISIBLE);
            binding.deviceName.setText(getString(R.string.debug_device_format, connectedDeviceName));
            binding.deviceAddress.setText(connectedDeviceAddress);
                    binding.throughputLayout.setVisibility(View.VISIBLE);

            timerHandler.post(timerRunnable);
            loadFrameConfig();
            loadMacros();
        });
    }

    private void handleDisconnect() {
        isConnected = false;
        connectedSessionId = null;
        connectedDeviceAddress = null;
        connectedDeviceName = null;
        if (macroExecutor != null) {
            macroExecutor.cancel();
        }
        if (macroSendBatch != null) macroSendBatch.cancel();
        stopSend();
        attachSendProgressListener(null);

        postToView(() -> {
            binding.connectionState.setText(R.string.disconnected);
            binding.connectionState.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
            binding.connectionDuration.setVisibility(View.GONE);
            binding.deviceInfoLayout.setVisibility(View.GONE);
            binding.throughputLayout.setVisibility(View.GONE);
            binding.diagnosticsResult.setVisibility(View.GONE);
            binding.sendProgress.setVisibility(View.GONE);
            binding.sendProgressText.setVisibility(View.GONE);

            if (timerHandler != null) timerHandler.removeCallbacks(timerRunnable);
            adapter.clearLogs();
            if (logPipeline != null) logPipeline.clear();
            resetStats();
            loadMacros();
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
        if (!isAdded() || binding == null) return;
        postToView(() -> {
            BluetoothServiceConnect connection = connectedDeviceAddress == null ? null
                    : StaticObject.bluetoothSocketMap.get(connectedDeviceAddress);
            if (connection == null || !connection.getSessionId().equals(connectedSessionId)) return;
            // Transport increments these only on the actual terminal SENT/received frame.
            long sent = Math.max(0, connection.getBytesSentCount() - statsSentBase);
            long received = Math.max(0, connection.getBytesReceivedCount() - statsReceivedBase);
            long frames = Math.max(0, connection.getReceivedFrameCount() - statsFramesBase);
            long errors = Math.max(0, connection.getErrorCount() - statsErrorsBase);
            long messages = frames + Math.max(0, connection.getSuccessfulSendCount() - statsSendCountBase);
            long elapsedMillis = Math.max(1L, System.currentTimeMillis() - statsStartedAt);
            double rate = (sent + received) * 1000.0d / elapsedMillis;
            binding.bytesSent.setText(getString(R.string.debug_sent_format, sent + " B"));
            binding.bytesReceived.setText(getString(R.string.debug_received_format, received + " B"));
            binding.totalMessages.setText(getString(R.string.debug_messages_format, messages));
            binding.framesReceived.setText(getString(R.string.debug_frames_format, frames));
            binding.connectionErrors.setText(getString(R.string.debug_errors_format, errors));
            binding.throughputRate.setText(getString(R.string.debug_rate_format, String.format(Locale.getDefault(), "%.1f B", rate)));
        });
    }

    private void resetStats() {
        totalBytesSent.set(0);
        totalBytesReceived.set(0);
        totalMessages.set(0);
        BluetoothServiceConnect connection = connectedDeviceAddress == null ? null : StaticObject.bluetoothSocketMap.get(connectedDeviceAddress);
        statsSentBase = connection == null ? 0 : connection.getBytesSentCount();
        statsReceivedBase = connection == null ? 0 : connection.getBytesReceivedCount();
        statsFramesBase = connection == null ? 0 : connection.getReceivedFrameCount();
        statsSendCountBase = connection == null ? 0 : connection.getSuccessfulSendCount();
        statsErrorsBase = connection == null ? 0 : connection.getErrorCount();
        statsStartedAt = System.currentTimeMillis();
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
        if (sendMessage(bytes)) Toast.makeText(getContext(), R.string.send_queued, Toast.LENGTH_SHORT).show();
    }

    private void startContinuousSend() {
        if (isSending) {
            return;
        }
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
        String payloadHex = hexInput.replaceAll("\\s", "");
        if (appendCrlf) {
            payloadHex += "0D0A";
        }
        final byte[] payload = HexUtils.hexStringToBytes(payloadHex);
        final String address = connectedDeviceAddress;
        final String session = connectedSessionId;
        final long segmentIntervalMillis;
        if (payload.length > 1024) {
            long parsedSegmentInterval = 0L;
            try {
                parsedSegmentInterval = parseNonNegative(binding.intervalInput.getText());
            } catch (IllegalArgumentException ignored) {
                // Keep the large payload continuous when the interval field is invalid.
            }
            segmentIntervalMillis = parsedSegmentInterval;
        } else {
            segmentIntervalMillis = 0L;
        }

        final long generation = StaticObject.foregroundGate.currentGeneration();
        final BluetoothSendBatch batch = new BluetoothSendBatch();
        continuousSendBatch = batch;
        isSending = true;
        binding.btnContinuousSend.setEnabled(false);
        binding.btnStopSend.setEnabled(true);

        sendThread = new Thread(() -> {
            // A captured batch prevents an old producer from restarting when the
            // user quickly presses Stop and then starts another continuous send.
            try {
                while (!batch.isCanceled() && isConnected && java.util.Objects.equals(session, connectedSessionId)) {
                    Msg message = enqueuePayload(payload, address, segmentIntervalMillis, true, batch, generation, session);
                    if (message == null) break;
                    if (!awaitSend(message, session, generation)) {
                        postToView(() -> Toast.makeText(requireContext(),
                                message.getSendError() != null && message.getSendError().contains("budget")
                                        ? R.string.send_queue_full : R.string.send_progress_failed,
                                Toast.LENGTH_SHORT).show());
                        break;
                    }
                    Thread.sleep(interval);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                batch.cancel();
                postToView(() -> { if (continuousSendBatch == batch) stopSend(); });
            }
        });
        sendThread.start();
    }

    @Override
    public void onStop() {
        stopSend();
        if (macroExecutor != null) {
            macroExecutor.cancel();
        }
        if (macroSendBatch != null) macroSendBatch.cancel();
        super.onStop();
    }

    private void stopSend() {
        isSending = false;
        BluetoothSendBatch batch = continuousSendBatch;
        continuousSendBatch = null;
        if (batch != null) {
            batch.cancel();
        }
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        if (binding == null || getActivity() == null) {
            return;
        }
        postToView(() -> {
            if (binding == null) {
                return;
            }
            binding.btnContinuousSend.setEnabled(true);
            binding.btnStopSend.setEnabled(false);
        });
    }

    private void attachSendProgressListener(BluetoothServiceConnect connection) {
        if (progressConnection == connection) {
            return;
        }
        if (progressConnection != null) {
            progressConnection.setSendProgressListener(null);
        }
        progressConnection = connection;
        segmentedSendActive = false;
        if (connection != null) {
            connection.setSendProgressListener(new BluetoothServiceConnect.SendProgressListener() {
                @Override
                public void onStatus(BluetoothSendStatus status, long requestId) {
                    postToView(() -> {
                        if (progressConnection == connection && connection.getSessionId().equals(connectedSessionId)) handleSegmentedStatus(status);
                    });
                }

                @Override
                public void onProgress(long requestId, int sentBytes, int totalBytes) {
                    postToView(() -> {
                        if (progressConnection == connection && connection.getSessionId().equals(connectedSessionId)) handleSegmentedProgress(sentBytes, totalBytes);
                    });
                }
            });
        }
    }

    private void handleSegmentedProgress(int sentBytes, int totalBytes) {
        segmentedSendActive = true;
        if (!isAdded() || binding == null) {
            return;
        }
        int progress = totalBytes <= 0 ? 0 : (int) ((sentBytes * 100L) / totalBytes);
        postToView(() -> {
            if (binding == null) {
                return;
            }
            binding.sendProgress.setVisibility(View.VISIBLE);
            binding.sendProgressText.setVisibility(View.VISIBLE);
            binding.sendProgress.setProgress(Math.min(100, progress));
            binding.sendProgressText.setText(getString(R.string.send_progress_format,
                    sentBytes, totalBytes));
        });
    }

    private void handleSegmentedStatus(BluetoothSendStatus status) {
        if (!segmentedSendActive || !isAdded() || binding == null) {
            return;
        }
        if (status != BluetoothSendStatus.SENT
                && status != BluetoothSendStatus.FAILED
                && status != BluetoothSendStatus.CANCELED) {
            return;
        }
        postToView(() -> {
            if (binding == null) {
                return;
            }
            binding.sendProgressText.setText(status == BluetoothSendStatus.SENT
                    ? R.string.send_progress_complete
                    : status == BluetoothSendStatus.CANCELED
                    ? R.string.send_progress_canceled : R.string.send_progress_failed);
        });
        segmentedSendActive = false;
    }

    private boolean sendMessage(byte[] payload) {
        long intervalMillis = 0L;
        if (payload != null && payload.length > 1024) {
            try { intervalMillis = parseNonNegative(binding.intervalInput.getText()); }
            catch (IllegalArgumentException ignored) { }
        }
        return enqueuePayload(payload, connectedDeviceAddress, intervalMillis, true, null,
                StaticObject.foregroundGate.currentGeneration(), connectedSessionId) != null;
    }

    private Msg enqueuePayload(byte[] payload, String address, long intervalMillis,
                               boolean persistHistory, BluetoothSendBatch batch, long generation,
                               String expectedSession) {
        if (address == null || expectedSession == null) return null;
        BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(address);
        if (!StaticObject.foregroundGate.isCurrent(generation) || (batch != null && batch.isCanceled())
                || connection == null || !connection.getSessionId().equals(expectedSession)) return null;
        if (!connection.canAcceptSend(payload == null ? 0 : payload.length)) {
            postToView(() -> Toast.makeText(requireContext(), R.string.send_queue_full, Toast.LENGTH_SHORT).show());
            return null;
        }
        Msg message = new Msg(payload, Msg.TYPE_SENT, address);
        message.setSessionId(expectedSession);
        message.setForegroundGeneration(generation); message.setPersistHistory(persistHistory); message.setSendBatch(batch);
        if (payload != null && payload.length > 1024) {
            message.setSegmentSize(1024); message.setSegmentIntervalMillis(Math.max(0L, intervalMillis));
        }
        if (!StaticObject.submitMessage(message)) {
            postToView(() -> Toast.makeText(requireContext(), isConnected ? R.string.send_queue_full : R.string.no_connection,
                    Toast.LENGTH_SHORT).show());
            return null;
        }
        return message;
    }

    private boolean awaitSend(Msg message, String expectedSession, long generation) throws InterruptedException {
        while (message.getSendStatus() == null || !message.getSendStatus().isTerminal()) {
            if (!StaticObject.foregroundGate.isCurrent(generation)
                    || !java.util.Objects.equals(expectedSession, connectedSessionId)) throw new InterruptedException();
            Thread.sleep(20);
        }
        return message.getSendStatus() == BluetoothSendStatus.SENT;
    }

    private void runDiagnostics() {
        String address = resolveDiagnosticAddress();
        BluetoothServiceConnect connection = address == null
                ? null : StaticObject.bluetoothSocketMap.get(address);
        BluetoothConnectionState state = address == null
                ? BluetoothConnectionState.IDLE : StaticObject.connectionRegistry.get(address);
        BluetoothConnectionErrorCode error = address == null
                ? BluetoothConnectionErrorCode.NONE : StaticObject.connectionRegistry.getError(address);
        String deviceName = connection == null ? connectedDeviceName : connection.getBluetoothName();
        String uuid = connection == null ? "N/A" : connection.getSendUuid();
        int reconnectAttempt = StaticObject.reconnectManager.getPendingAttempt(address);
        String duration = connection == null ? "N/A" : formatDuration(connection.getConnectedAtMillis());
        String sendStatus = connection == null || connection.getLastSendStatus() == null
                ? "N/A" : connection.getLastSendStatus().name();
        String sendCounts = connection == null ? "N/A"
                : connection.getQueuedSendCount() + "/"
                + connection.getSuccessfulSendCount() + "/"
                + connection.getFailedSendCount() + "/"
                + connection.getCanceledSendCount();
        String sendError = connection == null ? "N/A" : valueOrUnknown(connection.getLastSendError());
        String frameMode = connection == null || connection.getFrameConfig() == null
                ? "N/A" : connection.getFrameConfig().getMode().name();
        String textEncoding = connection == null || connection.getTextEncoding() == null
                ? "N/A" : connection.getTextEncoding().name();
        String connectionLogs = formatConnectionLogs(address);
        lastDiagnosticsReport = buildDiagnosticsReport(deviceName, address, uuid, state, error,
                reconnectAttempt, duration, sendStatus, sendCounts, sendError,
                frameMode, textEncoding, connectionLogs);
        long pendingBytes = StaticObject.mTaskQueue.getPendingBytes() + (connection == null ? 0 : connection.getPendingSendBytes())
                + (logPipeline == null ? 0 : logPipeline.pendingBytes());
        int pendingCount = StaticObject.mTaskQueue.size() + (connection == null ? 0 : connection.getPendingSendCount())
                + (logPipeline == null ? 0 : logPipeline.pendingCount());
        lastDiagnosticsReport += "\n" + getString(R.string.debug_queue_format, pendingCount,
                pendingBytes + " B", HistoryRepository.getPendingCount(), StaticObject.getRejectedMessageCount()
                        + (logPipeline == null ? 0 : logPipeline.rejectedCount()), HistoryRepository.getWriteFailureCount());
        binding.diagnosticsResult.setVisibility(View.VISIBLE);
        binding.diagnosticsResult.setText(lastDiagnosticsReport);
    }

    private String resolveDiagnosticAddress() {
        if (connectedDeviceAddress != null) {
            return connectedDeviceAddress;
        }
        for (String address : StaticObject.connectionRegistry.getKnownAddresses()) {
            if (StaticObject.connectionRegistry.get(address) != BluetoothConnectionState.IDLE) {
                return address;
            }
        }
        return null;
    }

    private String buildDiagnosticsReport(String deviceName, String address, String uuid,
                                          BluetoothConnectionState state,
                                          BluetoothConnectionErrorCode error,
                                          int reconnectAttempt, String duration,
                                          String sendStatus, String sendCounts, String sendError,
                                          String frameMode, String textEncoding,
                                          String connectionLogs) {
        String reconnect = StaticObject.reconnectManager.isGlobalEnabled()
                ? getString(R.string.enabled) : getString(R.string.disabled);
        String attempt = reconnectAttempt == 0 ? getString(R.string.none)
                : String.valueOf(reconnectAttempt);
        return getString(R.string.diagnostics_report_header) + "\n"
                + getString(R.string.device_name) + ": " + valueOrUnknown(deviceName) + "\n"
                + getString(R.string.device_address) + ": "
                + valueOrUnknown(BluetoothAddressUtils.mask(address)) + "\n"
                + getString(R.string.diagnostics_uuid) + ": " + valueOrUnknown(uuid) + "\n"
                + getString(R.string.diagnostics_state) + ": " + state.name() + "\n"
                + getString(R.string.diagnostics_error) + ": " + error.name() + "\n"
                + getString(R.string.diagnostics_reconnect) + ": " + reconnect
                + " (" + getString(R.string.diagnostics_attempt) + ": " + attempt + ")\n"
                + getString(R.string.diagnostics_send_status) + ": " + sendStatus + "\n"
                + getString(R.string.diagnostics_send_counts) + ": " + sendCounts + "\n"
                + getString(R.string.diagnostics_send_error) + ": " + sendError + "\n"
                + getString(R.string.diagnostics_frame_mode) + ": " + frameMode + "\n"
                + getString(R.string.diagnostics_text_encoding) + ": " + textEncoding + "\n"
                + getString(R.string.diagnostics_logs) + ":\n" + connectionLogs + "\n"
                + getString(R.string.connection_duration) + ": " + duration;
    }

    private String formatConnectionLogs(String address) {
        if (address == null) {
            return "N/A";
        }
        List<BluetoothConnectionLogEntry> entries = StaticObject.connectionRegistry.getLogs(address);
        if (entries.isEmpty()) {
            return "N/A";
        }
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        StringBuilder result = new StringBuilder();
        int first = Math.max(0, entries.size() - 8);
        for (int i = first; i < entries.size(); i++) {
            BluetoothConnectionLogEntry entry = entries.get(i);
            result.append(format.format(new Date(entry.getTimestampMillis())))
                    .append(" ")
                    .append(entry.getFromState())
                    .append(" -> ")
                    .append(entry.getToState())
                    .append(" [")
                    .append(entry.getThreadName())
                    .append("]");
            if (entry.getErrorCode() != BluetoothConnectionErrorCode.NONE) {
                result.append(" ").append(entry.getErrorCode());
            }
            if (entry.getSummary() != null && !entry.getSummary().isEmpty()) {
                result.append(" ").append(entry.getSummary());
            }
            if (i < entries.size() - 1) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? getString(R.string.unknown) : value;
    }

    private String formatDuration(long connectedAt) {
        if (connectedAt <= 0) {
            return "N/A";
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - connectedAt) / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    private void copyDiagnostics() {
        if (lastDiagnosticsReport == null) {
            runDiagnostics();
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && lastDiagnosticsReport != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Bluetooth diagnostics", lastDiagnosticsReport));
            BluetoothTelemetry.logUserAction("diagnostic_report_copied");
            Toast.makeText(getContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void exportLogs() {
        List<LogItem> logs = logPipeline == null ? Collections.emptyList() : logPipeline.snapshot();
        if (logs.isEmpty()) {
            Toast.makeText(getContext(), R.string.debug_no_logs, Toast.LENGTH_SHORT).show(); return;
        }
        String filename = "bluetooth_debug_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        if (!transferState.prepareLogs(requireContext(), filename, logs, connectedDeviceName, connectedDeviceAddress)) {
            Toast.makeText(getContext(), R.string.debug_file_busy, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (timerHandler != null) timerHandler.removeCallbacks(timerRunnable);
        if (macroExecutor != null) {
            macroExecutor.close();
            macroExecutor = null;
        }
        if (macroSendBatch != null) { macroSendBatch.cancel(); macroSendBatch = null; }
        stopSend();

        if (debugUUID != null) {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(debugUUID);
        }
        attachSendProgressListener(null);
        viewEpoch++;
        macroState.load(null);
        isConnected = false;
        connectedSessionId = null;
        logUi.removeCallbacks(flushLogs);
        if (logPipeline != null) { logPipeline.close(); logPipeline = null; }

        binding = null;
    }

    private MacroExecutor getMacroExecutor() {
        if (macroExecutor == null) {
            macroExecutor = new MacroExecutor();
        }
        return macroExecutor;
    }
}
