package com.zzf.bluetoothsmp;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.zzf.bluetoothsmp.entity.CommandMacroEntity;
import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One accepted SQL operation plus one coalesced refresh, retained across view recreation. */
public final class DebugMacroState extends ViewModel {
    interface Storage {
        List<CommandMacroEntity> load(String address);
        void save(String address, String name, String script, int repeat);
        void delete(CommandMacroEntity macro);
    }

    public static final class Result {
        public final String address;
        public final long generation;
        public final List<CommandMacroEntity> macros;
        private final int feedback;
        Result(String address, long generation, List<CommandMacroEntity> macros, int feedback) {
            this.address = address; this.generation = generation;
            this.macros = Collections.unmodifiableList(new java.util.ArrayList<>(macros));
            this.feedback = feedback;
        }
    }

    private final Storage storage;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "debug-macro-storage"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Result> results = new MutableLiveData<>();
    // Main-thread state: worker completion only mutates this state via main.post.
    private boolean busy, refreshPending, closed;
    private String selectedAddress;
    private long generation, consumedFeedback = -1;
    private String pendingFeedbackAddress;
    private int pendingFeedback;

    public DebugMacroState() {
        this(new Storage() {
            @Override public List<CommandMacroEntity> load(String address) {
                return CommandMacroStore.findForAddress(address);
            }
            @Override public void save(String address, String name, String script, int repeat) {
                CommandMacroStore.saveForAddress(address, name, script, repeat);
            }
            @Override public void delete(CommandMacroEntity macro) {
                if (macro.delete() <= 0) throw new IllegalStateException("Macro was not deleted");
            }
        });
    }

    DebugMacroState(Storage storage) { this.storage = storage; }
    public LiveData<Result> results() { return results; }
    public boolean isBusy() { return busy; }

    public int consumeFeedback(long request) {
        Result result = results.getValue();
        if (result == null || result.generation != request || result.feedback == 0
                || consumedFeedback == request) return 0;
        consumedFeedback = request;
        if (Objects.equals(result.address, pendingFeedbackAddress)) {
            pendingFeedbackAddress = null;
            pendingFeedback = 0;
        }
        return result.feedback;
    }

    public void load(String address) {
        if (closed) return;
        selectedAddress = BluetoothAddressUtils.normalize(address);
        // A destroyed view has no selected device. Keep its accepted mutation outcome
        // until a view for the same device returns; switching devices discards it.
        if (selectedAddress != null && !Objects.equals(selectedAddress, pendingFeedbackAddress)) {
            pendingFeedbackAddress = null;
            pendingFeedback = 0;
        }
        generation++;
        results.setValue(new Result(selectedAddress, generation, Collections.emptyList(), 0));
        if (busy) { refreshPending = true; return; }
        refreshPending = false;
        if (selectedAddress != null) submit(selectedAddress, generation, null, 0);
    }

    public boolean save(String address, String name, String script, int repeat) {
        String target = BluetoothAddressUtils.normalize(address);
        if (closed || busy || target == null) return false;
        selectedAddress = target;
        submit(target, ++generation, () -> storage.save(target, name, script, repeat), R.string.macro_saved);
        return true;
    }

    public boolean delete(String address, CommandMacroEntity macro) {
        String target = BluetoothAddressUtils.normalize(address);
        if (closed || busy || target == null || macro == null
                || !Objects.equals(target, BluetoothAddressUtils.normalize(macro.getBluetoothAddress()))) return false;
        selectedAddress = target;
        submit(target, ++generation, () -> storage.delete(macro), R.string.macro_deleted);
        return true;
    }

    private void submit(String address, long request, Runnable mutation, int feedback) {
        busy = true;
        io.execute(() -> {
            List<CommandMacroEntity> loaded = Collections.emptyList();
            boolean succeeded = false;
            try {
                if (mutation != null) mutation.run();
                loaded = storage.load(address);
                succeeded = true;
            } catch (RuntimeException storageFailure) {
                // Do not log private macro text or addresses.
            }
            final List<CommandMacroEntity> completed = loaded;
            final int resource = succeeded ? feedback : R.string.macro_storage_failed;
            main.post(() -> {
                busy = false;
                if (closed) return;
                if (mutation != null && (selectedAddress == null || Objects.equals(address, selectedAddress))) {
                    pendingFeedbackAddress = address;
                    pendingFeedback = resource;
                }
                if (request == generation && Objects.equals(address, selectedAddress)) {
                    int delivery = resource != 0 ? resource
                            : Objects.equals(address, pendingFeedbackAddress) ? pendingFeedback : 0;
                    results.setValue(new Result(address, request, completed, delivery));
                }
                if (refreshPending) {
                    refreshPending = false;
                    if (selectedAddress != null) submit(selectedAddress, generation, null, 0);
                }
            });
        });
    }

    @Override protected void onCleared() {
        closed = true;
        // Accepted transactions finish; their callbacks cannot access a destroyed view.
        io.shutdown();
    }
}
