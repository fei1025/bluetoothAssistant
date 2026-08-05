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
import com.zzf.bluetoothsmp.BluetoothTelemetry;
import com.zzf.bluetoothsmp.CommandMacroStore;
import com.zzf.bluetoothsmp.MacroExecutor;
import com.zzf.bluetoothsmp.MacroParser;
import com.zzf.bluetoothsmp.MacroStep;
import com.zzf.bluetoothsmp.MacroTransferCodec;
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

    private static final String ARG_EMBEDDED = "arg_embedded";
    private static final int REQUEST_EXPORT_MACROS = 0x52;
    private static final int REQUEST_IMPORT_MACROS = 0x53;
    private static final int REQUEST_EXPORT_LOGS = 0x54;

    private FragmentDebugBinding binding;
    private DebugLogAdapter adapter;
    private String debugUUID;

    // Connection state
    private volatile boolean isConnected = false;
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
    private String lastDiagnosticsReport;
    private BluetoothServiceConnect progressConnection;
    private boolean segmentedSendActive;
    private MacroExecutor macroExecutor;
    private final List<CommandMacroEntity> macros = new ArrayList<>();
    private ArrayAdapter<String> macroAdapter;
    private boolean loadingMacroSelection;
    private String pendingMacroExport;
    private String pendingLogExport;
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
        boolean changed = !address.equals(connectedDeviceAddress);
        connectedDeviceAddress = address;
        connectedDeviceName = getRemoteDeviceName(connection);
        if (connectedDeviceName == null || connectedDeviceName.isEmpty()) {
            connectedDeviceName = address;
        }
        if (changed) {
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
    }

    private void showMacroHelp() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.macro_help_title)
                .setMessage(R.string.macro_help_message)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void loadMacros() {
        if (macroAdapter == null) {
            return;
        }
        String address = normalizedDiagnosticAddress();
        macros.clear();
        if (address != null) {
            macros.addAll(CommandMacroStore.findForAddress(address));
        }
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
        CommandMacroEntity macro = CommandMacroStore.findByName(address, name);
        if (macro == null) {
            macro = new CommandMacroEntity();
            macro.setBluetoothAddress(address);
        }
        macro.setName(name);
        macro.setScript(script);
        macro.setRepeatCount(repeat);
        CommandMacroStore.save(macro);
        loadMacros();
        Toast.makeText(getContext(), R.string.macro_saved, Toast.LENGTH_SHORT).show();
    }

    private void deleteMacro() {
        CommandMacroEntity macro = selectedMacro();
        if (macro == null) {
            return;
        }
        macro.delete();
        loadMacros();
        Toast.makeText(getContext(), R.string.macro_deleted, Toast.LENGTH_SHORT).show();
    }

    private void exportMacros() {
        String address = normalizedDiagnosticAddress();
        if (address == null) {
            Toast.makeText(getContext(), R.string.macro_transfer_no_device,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pendingMacroExport = MacroTransferCodec.exportMacros(
                    CommandMacroStore.findForAddress(address));
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "spp_macros.txt");
            startActivityForResult(intent, REQUEST_EXPORT_MACROS);
        } catch (IllegalArgumentException error) {
            pendingMacroExport = null;
            Toast.makeText(getContext(), R.string.macro_transfer_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void importMacros() {
        if (normalizedDiagnosticAddress() == null) {
            Toast.makeText(getContext(), R.string.macro_transfer_no_device,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain");
        startActivityForResult(intent, REQUEST_IMPORT_MACROS);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != android.app.Activity.RESULT_OK || data == null
                || data.getData() == null) {
            if (requestCode == REQUEST_EXPORT_MACROS) {
                pendingMacroExport = null;
            } else if (requestCode == REQUEST_EXPORT_LOGS) {
                pendingLogExport = null;
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_MACROS) {
            writeMacroExport(data.getData());
        } else if (requestCode == REQUEST_IMPORT_MACROS) {
            readMacroImport(data.getData());
        } else if (requestCode == REQUEST_EXPORT_LOGS) {
            writeLogExport(data.getData());
        }
    }

    private void writeMacroExport(Uri uri) {
        String content = pendingMacroExport;
        pendingMacroExport = null;
        if (content == null) {
            return;
        }
        try (OutputStream output = requireContext().getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("unable to open export destination");
            }
            output.write(content.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(getContext(), R.string.macro_exported, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(getContext(), R.string.macro_transfer_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void readMacroImport(Uri uri) {
        try (InputStream input = requireContext().getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IllegalStateException("unable to open macro source");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 256 * 1024) {
                    throw new IllegalArgumentException("macro export is too large");
                }
            }
            List<CommandMacroEntity> imported = MacroTransferCodec.importMacros(
                    new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            String address = normalizedDiagnosticAddress();
            if (address == null) {
                throw new IllegalArgumentException("no device");
            }
            for (CommandMacroEntity importedMacro : imported) {
                CommandMacroEntity macro = CommandMacroStore.findByName(
                        address, importedMacro.getName());
                if (macro == null) {
                    macro = new CommandMacroEntity();
                    macro.setBluetoothAddress(address);
                }
                macro.setName(importedMacro.getName());
                macro.setScript(importedMacro.getScript());
                macro.setRepeatCount(importedMacro.getRepeatCount());
                CommandMacroStore.save(macro);
            }
            loadMacros();
            Toast.makeText(getContext(), getString(R.string.macro_imported, imported.size()),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(getContext(), R.string.macro_transfer_failed,
                    Toast.LENGTH_SHORT).show();
        }
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
            getMacroExecutor().execute(steps, macro.getRepeatCount(),
                    step -> enqueueMacroStep(address, step));
            Toast.makeText(getContext(), R.string.macro_started, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(getContext(), R.string.macro_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void enqueueMacroStep(String address, MacroStep step) throws InterruptedException {
        if (step.getType() == MacroStep.Type.TEXT) {
            BluetoothServiceConnect connection = StaticObject.bluetoothSocketMap.get(address);
            if (connection == null) {
                throw new IllegalStateException("device is no longer connected");
            }
            Msg msg = new Msg(connection.encodeTextPayload(step.getText()), Msg.TYPE_SENT, address);
            msg.setContent(step.getText());
            msg.setPersistHistory(false);
            StaticObject.mTaskQueue.put(msg);
        } else if (step.getType() == MacroStep.Type.HEX) {
            enqueuePayload(step.getBytes(), address, 0L, false);
        }
    }

    private String normalizedDiagnosticAddress() {
        return BluetoothAddressUtils.normalize(resolveDiagnosticAddress());
    }

    private void registerEventListeners() {
        // Listen for SEND events
        sendListener = new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (!isAdded()) return;
                Msg msg = (Msg) event.getEventData()[0];
                if (isConnected && msg.getBluetoothAdd() != null
                        && msg.getBluetoothAdd().equals(connectedDeviceAddress)) {
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
                if (isConnected && msg.getBluetoothAdd() != null
                        && msg.getBluetoothAdd().equals(connectedDeviceAddress)) {
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
                postToView(() -> {
                    refreshDeviceSelector();
                });
            }
        };

        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, sendListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, receiveListener, debugUUID);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, notConnectListener, debugUUID);
    }

    private void addLogItem(Msg msg, boolean isSent) {
        postToView(() -> {
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

    private void postToView(Runnable action) {
        if (action == null || binding == null) {
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (!isAdded() || binding == null) {
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
            binding.deviceName.setText(getString(R.string.device_name) + ": " + connectedDeviceName);
            binding.deviceAddress.setText(connectedDeviceAddress);
                    binding.throughputLayout.setVisibility(View.VISIBLE);

            timerHandler.post(timerRunnable);
            loadFrameConfig();
            loadMacros();
        });
    }

    private void handleDisconnect() {
        isConnected = false;
        connectedDeviceAddress = null;
        connectedDeviceName = null;
        if (macroExecutor != null) {
            macroExecutor.cancel();
        }
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
        if (!isAdded() || binding == null) return;
        postToView(() -> {
            BluetoothServiceConnect connection = connectedDeviceAddress == null ? null
                    : StaticObject.bluetoothSocketMap.get(connectedDeviceAddress);
            long sent = connection == null ? totalBytesSent.get() : connection.getBytesSentCount();
            long received = connection == null ? totalBytesReceived.get() : connection.getBytesReceivedCount();
            long frames = connection == null ? totalMessages.get() : connection.getReceivedFrameCount();
            long errors = connection == null ? 0L : connection.getErrorCount();
            long connectedAt = connection == null ? connectionStartTime : connection.getConnectedAtMillis();
            long elapsedMillis = Math.max(1L, System.currentTimeMillis() - connectedAt);
            double rate = (sent + received) * 1000.0d / elapsedMillis;
            binding.bytesSent.setText(String.format(Locale.getDefault(), "Sent: %d B", sent));
            binding.bytesReceived.setText(String.format(Locale.getDefault(), "Rcvd: %d B", received));
            binding.totalMessages.setText(String.format(Locale.getDefault(), "Msgs: %d", totalMessages.get()));
            binding.framesReceived.setText(String.format(Locale.getDefault(), "Frames: %d", frames));
            binding.connectionErrors.setText(String.format(Locale.getDefault(), "Errors: %d", errors));
            binding.throughputRate.setText(String.format(Locale.getDefault(), "Rate: %.1f B/s", rate));
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
        String payloadHex = hexInput.replaceAll("\\s", "");
        if (appendCrlf) {
            payloadHex += "0D0A";
        }
        final byte[] payload = HexUtils.hexStringToBytes(payloadHex);
        final String address = connectedDeviceAddress;
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

        isSending = true;
        binding.btnContinuousSend.setEnabled(false);
        binding.btnStopSend.setEnabled(true);

        sendThread = new Thread(() -> {
            while (isSending && isConnected) {
                enqueuePayload(payload, address, segmentIntervalMillis);

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
        if (progressConnection != null) {
            progressConnection.cancelSegmentedSends();
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
                    handleSegmentedStatus(status);
                }

                @Override
                public void onProgress(long requestId, int sentBytes, int totalBytes) {
                    handleSegmentedProgress(sentBytes, totalBytes);
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

    private void sendMessage(byte[] payload) {
        long intervalMillis = 0L;
        if (payload != null && payload.length > 1024) {
            try {
                intervalMillis = parseNonNegative(binding.intervalInput.getText());
            } catch (IllegalArgumentException ignored) {
                // Keep the large payload continuous when the interval field is invalid.
            }
        }
        enqueuePayload(payload, connectedDeviceAddress, intervalMillis);
    }

    private void enqueuePayload(byte[] payload, String address, long intervalMillis) {
        enqueuePayload(payload, address, intervalMillis, true);
    }

    private void enqueuePayload(byte[] payload, String address, long intervalMillis,
                                boolean persistHistory) {
        Msg msg = new Msg(payload, Msg.TYPE_SENT, address);
        msg.setPersistHistory(persistHistory);
        if (payload != null && payload.length > 1024) {
            msg.setSegmentSize(1024);
            msg.setSegmentIntervalMillis(Math.max(0L, intervalMillis));
        }
        try {
            StaticObject.mTaskQueue.put(msg);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
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

        pendingLogExport = sb.toString();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(intent, REQUEST_EXPORT_LOGS);
    }

    private void writeLogExport(Uri uri) {
        String content = pendingLogExport;
        pendingLogExport = null;
        if (content == null) {
            return;
        }
        try (OutputStream output = requireContext().getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("unable to open export destination");
            }
            output.write(content.getBytes(StandardCharsets.UTF_8));
            BluetoothTelemetry.logUserAction("log_exported");
            Toast.makeText(getContext(), R.string.log_exported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), R.string.export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timerHandler.removeCallbacks(timerRunnable);
        if (macroExecutor != null) {
            macroExecutor.close();
            macroExecutor = null;
        }
        stopSend();

        if (debugUUID != null) {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(debugUUID);
        }
        attachSendProgressListener(null);
        pendingMacroExport = null;
        pendingLogExport = null;

        binding = null;
    }

    private MacroExecutor getMacroExecutor() {
        if (macroExecutor == null) {
            macroExecutor = new MacroExecutor();
        }
        return macroExecutor;
    }
}
