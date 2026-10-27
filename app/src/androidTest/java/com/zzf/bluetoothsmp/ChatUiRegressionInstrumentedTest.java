package com.zzf.bluetoothsmp;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager2.widget.ViewPager2;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.zzf.bluetoothsmp.customAdapter.MsgAdapter;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.KeyboardEntity;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.liaoTian.ChatTranscriptController;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.HistoryRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class ChatUiRegressionInstrumentedTest {
    private static final String ADDRESS = "02:00:00:00:CE:01";
    private static final String OTHER_ADDRESS = "02:00:00:00:CE:02";
    private static final String UUID = "chat-ui-regression";
    private Instrumentation instrumentation;
    private Instrumentation.ActivityMonitor discoveryPrompt;
    private MainActivity main;

    @Before
    public void before() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grant(Manifest.permission.BLUETOOTH_CONNECT);
            grant(Manifest.permission.BLUETOOTH_SCAN);
            grant(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            grant(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        instrumentation.getTargetContext().getSharedPreferences("keyboard_layouts", 0)
                .edit().remove(ADDRESS).commit();
        discoveryPrompt = instrumentation.addMonitor(new IntentFilter(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        main = (MainActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        awaitMain(() -> updateResumedMain());
    }

    @After
    public void after() throws Exception {
        StaticObject.bluetoothSocketMap.remove(ADDRESS);
        StaticObject.bluetoothSocketMap.remove(OTHER_ADDRESS);
        if (main != null) instrumentation.runOnMainSync(main::finishAndRemoveTask);
        instrumentation.waitForIdleSync();
        instrumentation.removeMonitor(discoveryPrompt);
        HistoryRepository.awaitWrites(5000L);
        HistoryRepository.deleteConversation(ADDRESS, UUID);
        LitePal.deleteAll(KeyboardEntity.class, "driveadd = ?", ADDRESS);
        instrumentation.getTargetContext().getSharedPreferences("keyboard_layouts", 0)
                .edit().remove(ADDRESS).commit();
    }

    @Test
    public void browsingHistoryAndPressingBackKeepsActiveTransport() {
        AtomicInteger closed = new AtomicInteger();
        BluetoothServiceConnect existing = new BluetoothServiceConnect() {
            @Override public void close() { closed.incrementAndGet(); }
            @Override public void closeForLifecycle() { closed.incrementAndGet(); }
        };
        StaticObject.bluetoothSocketMap.put(ADDRESS, existing);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(Liao_tian.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(() -> resumedMain().startActivity(new Intent(resumedMain(), Liao_tian.class)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra("bluetoothAdd", ADDRESS).putExtra("bluetoothUUid", UUID)
                    .putExtra("infoType", "0")));
            Liao_tian history = (Liao_tian) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(history);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertEquals(View.GONE, history.findViewById(R.id.history_input_bar).getVisibility());
                history.getOnBackPressedDispatcher().onBackPressed();
            });
            awaitMain(() -> history.isDestroyed());
            assertEquals(0, closed.get());
            assertSame(existing, StaticObject.bluetoothSocketMap.get(ADDRESS));
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void anonymousBinaryMessageRendersWithoutWritingDeviceData() {
        int before = LitePal.where("driveadd = ? AND uuid = ?", ADDRESS, UUID).count(BluetoothDrive.class);
        instrumentation.runOnMainSync(() -> {
            Msg message = message(ADDRESS, "session", Msg.TYPE_RECEIVED);
            message.setBluetoothName(null);
            MsgAdapter adapter = new MsgAdapter(Collections.singletonList(message));
            RecyclerView parent = new RecyclerView(main);
            parent.setLayoutManager(new LinearLayoutManager(main));
            MsgAdapter.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(holder, 0);
            TextView raw = holder.itemView.findViewById(R.id.message_raw);
            assertTrue(raw.getText().toString().contains("00 FF 80"));
            TextView metadata = holder.itemView.findViewById(R.id.message_metadata);
            assertTrue(metadata.getText().toString().contains(main.getString(R.string.chat_received)));
        });
        assertEquals(before, LitePal.where("driveadd = ? AND uuid = ?", ADDRESS, UUID).count(BluetoothDrive.class));
    }

    @Test
    public void simultaneousTranscriptsRejectCrossDeviceAndOldSessionEventsAndDeduplicateStatus() {
        final ChatTranscriptController[] controllers = new ChatTranscriptController[2];
        final RecyclerView[] views = new RecyclerView[2];
        final AtomicInteger disconnects = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            View rootA = main.getLayoutInflater().inflate(R.layout.fragment_liantian_new, null, false);
            View rootB = main.getLayoutInflater().inflate(R.layout.fragment_liantian_new, null, false);
            controllers[0] = new ChatTranscriptController(rootA, ADDRESS, UUID, "first", disconnects::incrementAndGet);
            controllers[1] = new ChatTranscriptController(rootB, OTHER_ADDRESS, UUID, "second", disconnects::incrementAndGet);
            views[0] = rootA.findViewById(R.id.msg_recycler_view);
            views[1] = rootB.findViewById(R.id.msg_recycler_view);
        });
        try {
            StaticObject.bluetoothEvent.receiveMsg(message(ADDRESS, "first", Msg.TYPE_RECEIVED));
            StaticObject.bluetoothEvent.receiveMsg(message(ADDRESS, "old", Msg.TYPE_RECEIVED));
            StaticObject.bluetoothEvent.receiveMsg(message(OTHER_ADDRESS, "second", Msg.TYPE_RECEIVED));
            awaitMain(() -> views[0].getAdapter().getItemCount() == 1 && views[1].getAdapter().getItemCount() == 1);
            Msg sent = message(ADDRESS, "first", Msg.TYPE_SENT);
            sent.updateSendStatus(BluetoothSendStatus.QUEUED, null);
            StaticObject.bluetoothEvent.sendStatus(sent);
            sent.updateSendStatus(BluetoothSendStatus.SENDING, null);
            StaticObject.bluetoothEvent.sendStatus(sent);
            sent.updateSendStatus(BluetoothSendStatus.FAILED, "test failure");
            StaticObject.bluetoothEvent.sendStatus(sent);
            awaitMain(() -> views[0].getAdapter().getItemCount() == 2);
            Msg oldDisconnect = message(ADDRESS, "old", Msg.TYPE_RECEIVED);
            oldDisconnect.setStateType(1);
            StaticObject.bluetoothEvent.notConnect(oldDisconnect);
            instrumentation.waitForIdleSync();
            assertEquals(0, disconnects.get());
            instrumentation.runOnMainSync(() -> {
                assertEquals(2, views[0].getAdapter().getItemCount());
                assertEquals(1, views[1].getAdapter().getItemCount());
            });
        } finally {
            instrumentation.runOnMainSync(() -> {
                controllers[0].close();
                controllers[1].close();
            });
        }
    }

    @Test
    public void keyboardCancelReleasesOnceAndAccessibleClickSendsOnePressReleasePair() {
        KeyboardEntity key = new KeyboardEntity();
        key.setDriveAdd(ADDRESS);
        key.setButtonId(1);
        key.setButtonName("Regression key");
        key.setActionDown("PRESS");
        key.setActionUp("RELEASE");
        assertTrue(key.save());
        List<String> submissions = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        BluetoothServiceConnect session = new BluetoothServiceConnect() {
            @Override public String getSendUuid() { return UUID; }
            @Override public byte[] encodeTextPayload(String text) { return text.getBytes(StandardCharsets.UTF_8); }
            @Override public boolean bindOutboundMessage(Msg message) {
                message.setBluetoothAdd(ADDRESS);
                message.setSendUuid(UUID);
                message.setSessionId(getSessionId());
                message.setPersistHistory(false);
                if (seen.add(message.getMessageId())) submissions.add(message.getContent());
                return true;
            }
        };
        StaticObject.bluetoothSocketMap.put(ADDRESS, session);
        BluetoothDrive drive = new BluetoothDrive();
        drive.setDriveAdd(ADDRESS);
        drive.setDriveName("Keyboard regression");
        drive.setUuid(UUID);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(Liantian_new.class.getName(), null, false);
        Liantian_new chat = null;
        try {
            instrumentation.runOnMainSync(() -> resumedMain().startActivity(new Intent(resumedMain(), Liantian_new.class)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra("BluetoothDrive", drive)));
            chat = (Liantian_new) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(chat);
            Liantian_new current = chat;
            instrumentation.runOnMainSync(() -> ((ViewPager2) current.findViewById(R.id.view_pager)).setCurrentItem(1, false));
            awaitMain(() -> {
                Button button = current.findViewById(R.id.button1);
                return button != null && button.isEnabled() && "Regression key".contentEquals(button.getText())
                        && button.getHeight() >= Math.round(48 * current.getResources().getDisplayMetrics().density);
            });
            instrumentation.runOnMainSync(() -> {
                Button button = current.findViewById(R.id.button1);
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 4, 4, 0);
                MotionEvent cancel = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_CANCEL, 4, 4, 0);
                try {
                    button.dispatchTouchEvent(down);
                    button.dispatchTouchEvent(cancel);
                    button.dispatchTouchEvent(cancel);
                    button.performClick();
                    assertTrue(button.getHeight() >= Math.round(48 * current.getResources().getDisplayMetrics().density));
                } finally {
                    down.recycle();
                    cancel.recycle();
                }
            });
            assertEquals(java.util.Arrays.asList("PRESS", "RELEASE", "PRESS", "RELEASE"), submissions);
        } finally {
            if (chat != null) {
                Liantian_new finishing = chat;
                instrumentation.runOnMainSync(finishing::finish);
            }
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void keyboardDragAndAddPersistAcrossRecreationWithoutSendingCommands() {
        KeyboardEntity key = new KeyboardEntity();
        key.setDriveAdd(ADDRESS);
        key.setButtonId(1);
        key.setButtonName("Layout key");
        key.setActionDown("MUST_NOT_SEND");
        key.setActionUp("MUST_NOT_SEND_UP");
        assertTrue(key.save());
        AtomicInteger submissions = new AtomicInteger();
        BluetoothServiceConnect session = new BluetoothServiceConnect() {
            @Override public String getSendUuid() { return UUID; }
            @Override public byte[] encodeTextPayload(String text) { return text.getBytes(StandardCharsets.UTF_8); }
            @Override public boolean bindOutboundMessage(Msg message) {
                submissions.incrementAndGet();
                return false;
            }
        };
        StaticObject.bluetoothSocketMap.put(ADDRESS, session);
        BluetoothDrive drive = new BluetoothDrive();
        drive.setDriveAdd(ADDRESS);
        drive.setDriveName("Layout regression");
        drive.setUuid(UUID);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(Liantian_new.class.getName(), null, false);
        final Liantian_new[] chat = new Liantian_new[1];
        try {
            instrumentation.runOnMainSync(() -> resumedMain().startActivity(new Intent(resumedMain(), Liantian_new.class)
                    .setAction(Intent.ACTION_VIEW).putExtra("BluetoothDrive", drive)));
            chat[0] = (Liantian_new) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(chat[0]);
            instrumentation.runOnMainSync(() -> ((ViewPager2) chat[0].findViewById(R.id.view_pager)).setCurrentItem(1, false));
            awaitMain(() -> {
                Button button = chat[0].findViewById(R.id.button1);
                return button != null && button.getHeight() > 0 && "Layout key".contentEquals(button.getText());
            });
            float[] original = new float[2];
            instrumentation.runOnMainSync(() -> {
                chat[0].findViewById(R.id.toggleButton).performClick();
                Button button = chat[0].findViewById(R.id.button1);
                original[0] = button.getX();
                original[1] = button.getY();
                drag(button, MotionEvent.ACTION_CANCEL);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                Button button = chat[0].findViewById(R.id.button1);
                assertEquals(original[0], button.getX(), 1);
                assertEquals(original[1], button.getY(), 1);
                drag(button, MotionEvent.ACTION_UP);
            });
            awaitMain(() -> chat[0].findViewById(R.id.keyboard_add).isEnabled());
            KeyboardLayoutState moved = KeyboardLayoutState.restore(instrumentation.getTargetContext()
                    .getSharedPreferences("keyboard_layouts", 0).getString(ADDRESS, null), Collections.emptySet());
            assertTrue(moved.buttons().get(1).xFraction > 0);
            assertTrue(moved.buttons().get(1).yDp > 0);
            int previousCount = moved.buttons().size();
            instrumentation.runOnMainSync(() -> chat[0].findViewById(R.id.keyboard_add).performClick());
            awaitMain(() -> ((android.view.ViewGroup) chat[0].findViewById(R.id.keyboard_canvas)).getChildCount() == previousCount + 1);
            instrumentation.waitForIdleSync();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.editText_button))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText("Added key"));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            awaitMain(() -> {
                android.view.ViewGroup canvas = chat[0].findViewById(R.id.keyboard_canvas);
                return "Added key".contentEquals(((Button) canvas.getChildAt(canvas.getChildCount() - 1)).getText());
            });
            Liantian_new previous = chat[0];
            instrumentation.runOnMainSync(previous::recreate);
            awaitMain(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof Liantian_new && activity != previous) {
                        chat[0] = (Liantian_new) activity;
                        ((ViewPager2) chat[0].findViewById(R.id.view_pager)).setCurrentItem(1, false);
                        return true;
                    }
                }
                return false;
            });
            awaitMain(() -> {
                android.view.ViewGroup canvas = chat[0].findViewById(R.id.keyboard_canvas);
                return canvas != null && canvas.getChildCount() == previousCount + 1
                        && "Added key".contentEquals(((Button) canvas.getChildAt(canvas.getChildCount() - 1)).getText());
            });
            KeyboardLayoutState restored = KeyboardLayoutState.restore(instrumentation.getTargetContext()
                    .getSharedPreferences("keyboard_layouts", 0).getString(ADDRESS, null), Collections.emptySet());
            assertEquals(moved.buttons().get(1).xFraction, restored.buttons().get(1).xFraction, 0.001f);
            assertEquals(moved.buttons().get(1).yDp, restored.buttons().get(1).yDp, 0.001f);
            assertEquals(0, submissions.get());
        } finally {
            if (chat[0] != null) instrumentation.runOnMainSync(chat[0]::finish);
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void legacyKeyboardRowsRemainIntactWhenLayoutIsMissingButtons() {
        KeyboardEntity legacy23 = legacyKey(0x7f08006f, "Old first key", "  DOWN\r\n", "UP\n");
        KeyboardEntity legacy28 = legacyKey(0x7f090087, "Old third key", "THIRD", "THIRD_UP");
        KeyboardEntity duplicate = legacyKey(0x7f090082, "", "", "");
        KeyboardEntity unknown = legacyKey(0x7f070001, "Keep unknown", "RAW_DOWN", "RAW_UP");
        List<KeyboardEntity> originals = java.util.Arrays.asList(legacy23, legacy28, duplicate, unknown);
        int initialCount = LitePal.where("driveadd = ?", ADDRESS).count(KeyboardEntity.class);
        assertTrue(instrumentation.getTargetContext().getSharedPreferences("keyboard_layouts", 0)
                .edit().putString(ADDRESS, "v1;1,0.65,210;").commit());
        BluetoothDrive drive = new BluetoothDrive();
        drive.setDriveAdd(ADDRESS);
        drive.setDriveName("Legacy buttons");
        drive.setUuid(UUID);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(Liantian_new.class.getName(), null, false);
        Liantian_new chat = null;
        try {
            instrumentation.runOnMainSync(() -> resumedMain().startActivity(new Intent(resumedMain(), Liantian_new.class)
                    .setAction(Intent.ACTION_VIEW).putExtra("BluetoothDrive", drive)));
            chat = (Liantian_new) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(chat);
            Liantian_new current = chat;
            instrumentation.runOnMainSync(() -> ((ViewPager2) current.findViewById(R.id.view_pager)).setCurrentItem(1, false));
            awaitMain(() -> {
                android.view.ViewGroup canvas = current.findViewById(R.id.keyboard_canvas);
                Button first = canvas == null ? null : canvas.findViewWithTag(1);
                Button third = canvas == null ? null : canvas.findViewWithTag(3);
                return first != null && third != null && "Old first key".contentEquals(first.getText())
                        && "Old third key".contentEquals(third.getText());
            });
            instrumentation.runOnMainSync(() -> {
                current.findViewById(R.id.toggleButton).performClick();
                Button third = current.findViewById(R.id.keyboard_canvas).findViewWithTag(3);
                third.performClick();
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.editText_down))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.withText("THIRD")));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.editText_up))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.withText("THIRD_UP")));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            // Dialog dismissal restores the async keyboard load state; do not click a disabled add control.
            awaitMain(() -> current.findViewById(R.id.keyboard_add).isEnabled());
            instrumentation.runOnMainSync(() -> current.findViewById(R.id.keyboard_add).performClick());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertEquals(initialCount, LitePal.where("driveadd = ?", ADDRESS).count(KeyboardEntity.class));
            for (KeyboardEntity original : originals) {
                KeyboardEntity stored = LitePal.find(KeyboardEntity.class, original.getKeyboardRecordId());
                assertNotNull(stored);
                assertEquals(original.getButtonId(), stored.getButtonId());
                assertEquals(original.getDriveAdd(), stored.getDriveAdd());
                assertEquals(original.getButtonName(), stored.getButtonName());
                assertEquals(original.getActionDown(), stored.getActionDown());
                assertEquals(original.getActionUp(), stored.getActionUp());
            }
            KeyboardLayoutState saved = KeyboardLayoutState.restore(instrumentation.getTargetContext()
                    .getSharedPreferences("keyboard_layouts", 0).getString(ADDRESS, null), Collections.emptySet());
            assertTrue(saved.buttons().containsKey(3));
            assertEquals(0.65f, saved.buttons().get(1).xFraction, 0.001f);
            assertEquals(210, saved.buttons().get(1).yDp, 0.001f);
        } finally {
            if (chat != null) instrumentation.runOnMainSync(chat::finish);
            instrumentation.removeMonitor(monitor);
        }
    }

    private KeyboardEntity legacyKey(int id, String name, String down, String up) {
        KeyboardEntity key = new KeyboardEntity();
        key.setDriveAdd(ADDRESS);
        key.setButtonId(id);
        key.setButtonName(name);
        key.setActionDown(down);
        key.setActionUp(up);
        assertTrue(key.save());
        return key;
    }

    private void drag(Button button, int terminalAction) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 8, 8, 0);
        MotionEvent move = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, 88, 88, 0);
        MotionEvent terminal = MotionEvent.obtain(now, now + 40, terminalAction, 88, 88, 0);
        try {
            button.dispatchTouchEvent(down);
            button.dispatchTouchEvent(move);
            button.dispatchTouchEvent(terminal);
        } finally {
            down.recycle();
            move.recycle();
            terminal.recycle();
        }
    }

    private Msg message(String address, String session, int type) {
        Msg msg = new Msg(new byte[]{0, (byte) 255, (byte) 128}, type, address);
        msg.setSendUuid(UUID);
        msg.setSessionId(session);
        msg.setPersistHistory(false);
        return msg;
    }

    private void awaitMain(Check check) {
        AtomicBoolean ready = new AtomicBoolean();
        long end = SystemClock.uptimeMillis() + 5000L;
        do {
            instrumentation.runOnMainSync(() -> ready.set(check.ready()));
            if (ready.get()) return;
            SystemClock.sleep(20L);
        } while (SystemClock.uptimeMillis() < end);
        assertTrue("UI state did not settle", ready.get());
    }

    private interface Check { boolean ready(); }

    private boolean updateResumedMain() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof MainActivity) {
                main = (MainActivity) activity;
                return true;
            }
        }
        return false;
    }

    private MainActivity resumedMain() {
        assertTrue("No live MainActivity to launch from", updateResumedMain());
        return main;
    }

    private void grant(String permission) throws Exception {
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "pm grant " + instrumentation.getTargetContext().getPackageName() + " " + permission);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (input.read() != -1) { }
        }
    }
}
