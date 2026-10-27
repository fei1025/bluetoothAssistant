package com.zzf.bluetoothsmp.liaoTian;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.ToggleButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.fragment.app.Fragment;

import com.zzf.bluetoothsmp.BluetoothTelemetry;
import com.zzf.bluetoothsmp.KeyboardSlotResolver;
import com.zzf.bluetoothsmp.KeyboardLayoutState;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.KeyboardEntity;
import com.zzf.bluetoothsmp.utils.StringUtils;
import android.widget.Toast;

import org.litepal.LitePal;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KeyboardFragment extends Fragment {
    private BluetoothDrive drive;
    private ToggleButton toggle;
    private Liantian_new liantian_new;
    private Map<Integer, KeyboardEntity> deviceKeys = Collections.emptyMap();
    private Map<Integer, KeyboardEntity> defaultKeys = Collections.emptyMap();
    private final Map<Integer, String> pendingReleases = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService database = Executors.newSingleThreadExecutor();
    private View currentRoot;
    private int viewGeneration;
    private KeyboardCanvas canvas;
    private Button addButton;
    private SharedPreferences layoutPreferences;
    private KeyboardLayoutState layoutState;
    private boolean savingLayout;
    private int editingTouchSlot;
    private AlertDialog editorDialog;

    public KeyboardFragment() {
    }

    @Override
    public void onResume() {
        super.onResume();
        BluetoothTelemetry.logScreenView("keyboard", getClass().getSimpleName());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        liantian_new = (Liantian_new) requireActivity();
        drive = liantian_new.getDrive();
        View root = inflater.inflate(R.layout.frament_keyboard_moder, container, false);
        currentRoot = root;
        viewGeneration++;
        if (drive == null || drive.getDriveAdd() == null) return root;
        initData(root);
        return root;
    }

    private KeyboardEntity effectiveKey(int slot) {
        return KeyboardSlotResolver.effectiveForSlot(slot, deviceKeys, defaultKeys);
    }

    private Map<Integer, KeyboardEntity> loadKeys(String address) {
        List<KeyboardEntity> rows = LitePal.where("driveAdd = ?", address)
                .find(KeyboardEntity.class);
        // Resolve legacy IDs in memory. Opening this page must not rewrite old
        // rows, duplicate records or payloads; writes require an explicit edit.
        return KeyboardSlotResolver.selectForAddress(rows, address);
    }

    private void showDialog(Button button, int slot) {
        if (editorDialog != null && editorDialog.isShowing()) return;
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_layout, null);
        EditText nameInput = dialogView.findViewById(R.id.editText_button);
        EditText downInput = dialogView.findViewById(R.id.editText_down);
        EditText upInput = dialogView.findViewById(R.id.editText_up);
        nameInput.setHint(getString(R.string.PleaseEnterButtonName));
        downInput.setHint(getString(R.string.PleaseEnterSenMsg));
        upInput.setHint(getString(R.string.UpEnterSenMsg));
        KeyboardEntity effective = effectiveKey(slot);
        if (effective != null) {
            nameInput.setText(effective.getButtonName());
            downInput.setText(effective.getActionDown());
            upInput.setText(effective.getActionUp());
        }

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton(getString(R.string.confirm), null)
                .setNegativeButton(getString(R.string.cancel),
                        (ignored, which) -> ignored.dismiss())
                .create();
        editorDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (editorDialog == dialog) editorDialog = null;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            KeyboardEntity own = deviceKeys.get(slot);
            if (own == null) {
                // The inputs contain any default fallback. Save a new device row
                // rather than moving or overwriting that shared default entity.
                own = new KeyboardEntity();
                own.setDriveAdd(drive.getDriveAdd());
            }
            final int previousId = own.getButtonId();
            final String previousName = own.getButtonName();
            final String previousDown = own.getActionDown();
            final String previousUp = own.getActionUp();
            own.setButtonId(slot);
            own.setButtonName(nameInput.getText().toString());
            own.setActionDown(downInput.getText().toString());
            own.setActionUp(upInput.getText().toString());
            final KeyboardEntity savedRow = own;
            final int generation = viewGeneration;
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            database.execute(() -> {
                boolean success;
                try { success = savedRow.save(); }
                catch (RuntimeException error) { success = false; }
                final boolean stored = success;
                main.post(() -> {
                    if (generation != viewGeneration || currentRoot == null) return;
                    dialog.setCancelable(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (!stored) {
                        savedRow.setButtonId(previousId);
                        savedRow.setButtonName(previousName);
                        savedRow.setActionDown(previousDown);
                        savedRow.setActionUp(previousUp);
                        Toast.makeText(requireContext(), R.string.keyboard_save_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    deviceKeys.put(slot, savedRow);
                    applyLabel(button, slot, savedRow);
                    dialog.dismiss();
                });
            });
        });
    }

    private void applyLabel(Button button, int slot, KeyboardEntity key) {
        String name = key == null ? null : key.getButtonName();
        button.setText(StringUtils.isNotEmpty(name) ? name : getString(R.string.keyboard_button, slot));
    }

    private void initData(View root) {
        deviceKeys = new HashMap<>();
        defaultKeys = Collections.emptyMap();
        savingLayout = false;
        editingTouchSlot = 0;
        canvas = root.findViewById(R.id.keyboard_canvas);
        addButton = root.findViewById(R.id.keyboard_add);
        layoutPreferences = requireContext().getApplicationContext()
                .getSharedPreferences("keyboard_layouts", Context.MODE_PRIVATE);
        toggle = root.findViewById(R.id.toggleButton);
        toggle.setEnabled(false);
        addButton.setEnabled(false);
        toggle.setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.purple_700)));
        toggle.setTextColor(getResources().getColor(R.color.white));
        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            releaseAll();
            toggle.setTextColor(getResources().getColor(checked ? R.color.orange : R.color.white));
            ((TextView) root.findViewById(R.id.keyboard_hint)).setText(
                    checked ? R.string.keyboard_edit_hint : R.string.keyboard_use_hint);
        });
        addButton.setOnClickListener(view -> {
            if (savingLayout || editingTouchSlot != 0 || editorDialog != null) return;
            KeyboardLayoutState next = layoutState.copy();
            Set<Integer> configured = new TreeSet<>(deviceKeys.keySet());
            configured.addAll(defaultKeys.keySet());
            int slot = next.add(configured);
            if (slot == 0) {
                Toast.makeText(requireContext(), getString(R.string.keyboard_limit,
                        KeyboardLayoutState.MAX_BUTTONS), Toast.LENGTH_SHORT).show();
                return;
            }
            releaseAll();
            saveLayout(next, () -> {
                renderButtons();
                toggle.setChecked(true);
                Button added = canvas.findViewWithTag(slot);
                canvas.post(() -> {
                    if (currentRoot != root) return;
                    ((ScrollView) root.findViewById(R.id.keyboard_scroll)).smoothScrollTo(0, added.getTop());
                    showDialog(added, slot);
                });
            });
        });
        final int generation = viewGeneration;
        final String address = drive.getDriveAdd();
        final SharedPreferences preferences = layoutPreferences;
        database.execute(() -> {
            try {
                Map<Integer, KeyboardEntity> own = loadKeys(address);
                Map<Integer, KeyboardEntity> defaults = KeyboardSlotResolver.DEFAULT_ADDRESS.equals(address)
                        ? Collections.emptyMap() : loadKeys(KeyboardSlotResolver.DEFAULT_ADDRESS);
                Set<Integer> slots = new TreeSet<>(own.keySet());
                slots.addAll(defaults.keySet());
                KeyboardLayoutState loaded = KeyboardLayoutState.restore(preferences.getString(address, null), slots);
                main.post(() -> {
                    if (generation != viewGeneration || currentRoot == null) return;
                    deviceKeys = new HashMap<>(own);
                    defaultKeys = defaults;
                    layoutState = loaded;
                    toggle.setEnabled(true);
                    addButton.setEnabled(true);
                    renderButtons();
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (generation == viewGeneration && currentRoot != null) {
                        Toast.makeText(requireContext(), R.string.history_load_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void saveLayout(KeyboardLayoutState next, Runnable onSaved) {
        savingLayout = true;
        toggle.setEnabled(false);
        addButton.setEnabled(false);
        final int generation = viewGeneration;
        final String address = drive.getDriveAdd();
        final SharedPreferences preferences = layoutPreferences;
        final String encoded = next.encode();
        database.execute(() -> {
            boolean stored;
            try { stored = preferences.edit().putString(address, encoded).commit(); }
            catch (RuntimeException error) { stored = false; }
            final boolean success = stored;
            main.post(() -> {
                if (generation != viewGeneration || currentRoot == null) return;
                savingLayout = false;
                toggle.setEnabled(true);
                addButton.setEnabled(true);
                if (success) {
                    layoutState = next;
                    canvas.setState(layoutState);
                    onSaved.run();
                } else {
                    canvas.setState(layoutState);
                    Toast.makeText(requireContext(), R.string.keyboard_save_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void renderButtons() {
        canvas.removeAllViews();
        canvas.setState(layoutState);
        for (int slot : layoutState.buttons().keySet()) {
            AppCompatButton button = new AppCompatButton(requireContext());
            button.setId(slot == 1 ? R.id.button1 : View.generateViewId());
            button.setTag(slot);
            button.setAllCaps(false);
            applyLabel(button, slot, effectiveKey(slot));
            bindButton(button, slot);
            canvas.addView(button);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindButton(Button button, int slot) {
        final boolean[] touchClick = {false};
        button.setOnClickListener(view -> {
            if (touchClick[0] || savingLayout || editingTouchSlot != 0) return;
            if (toggle.isChecked()) showDialog(button, slot);
            else {
                press(slot);
                release(slot);
            }
        });
        button.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, originalX, originalY;
            private boolean editing, dragging;
            private int pointerId = -1;
            private final int slop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();

            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (savingLayout || editorDialog != null || editingTouchSlot != 0) return true;
                    pointerId = event.getPointerId(0);
                    editing = toggle.isChecked();
                    dragging = false;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    originalX = view.getX();
                    originalY = view.getY();
                    view.setPressed(true);
                    if (editing) {
                        editingTouchSlot = slot;
                        toggle.setEnabled(false);
                        addButton.setEnabled(false);
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    else press(slot);
                } else if (pointerId != -1 && action == MotionEvent.ACTION_MOVE && editing) {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (dx * dx + dy * dy > slop * slop) dragging = true;
                    if (dragging) canvas.drag(view, originalX + dx, originalY + dy);
                } else if (pointerId != -1 && (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointerId))) {
                    pointerId = -1;
                    view.setPressed(false);
                    if (editing) {
                        editingTouchSlot = 0;
                        toggle.setEnabled(true);
                        addButton.setEnabled(true);
                    }
                    if (!editing) release(slot);
                    if (editing && dragging && action == MotionEvent.ACTION_UP) {
                        KeyboardLayoutState next = layoutState.copy();
                        next.move(slot, canvas.capture(view));
                        saveLayout(next, () -> { });
                    } else if (editing && dragging) {
                        canvas.setState(layoutState);
                    } else if (action == MotionEvent.ACTION_UP) {
                        touchClick[0] = !editing;
                        try { view.performClick(); }
                        finally { touchClick[0] = false; }
                    }
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }
        });
    }

    private void releaseAll() {
        for (Integer slot : new java.util.ArrayList<>(pendingReleases.keySet())) release(slot);
    }

    private void cancelTouches() {
        if (canvas == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try {
            for (int i = 0; i < canvas.getChildCount(); i++) canvas.getChildAt(i).dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
        canvas.setState(layoutState);
    }

    private void press(int slot) {
        if (pendingReleases.containsKey(slot)) return;
        KeyboardEntity key = effectiveKey(slot);
        if (key == null) return;
        String down = key.getActionDown();
        if (StringUtils.isNotEmpty(down) && !liantian_new.sendText(down)) return;
        pendingReleases.put(slot, key.getActionUp());
    }

    private void release(int slot) {
        if (!pendingReleases.containsKey(slot)) return;
        String up = pendingReleases.remove(slot);
        if (StringUtils.isNotEmpty(up)) liantian_new.sendText(up);
    }

    @Override
    public void onPause() {
        cancelTouches();
        releaseAll();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        cancelTouches();
        viewGeneration++;
        if (editorDialog != null) {
            editorDialog.dismiss();
            editorDialog = null;
        }
        releaseAll();
        currentRoot = null;
        canvas = null;
        addButton = null;
        toggle = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        database.shutdown();
        super.onDestroy();
    }
}
