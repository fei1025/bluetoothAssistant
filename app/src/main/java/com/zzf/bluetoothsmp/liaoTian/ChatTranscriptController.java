package com.zzf.bluetoothsmp.liaoTian;

import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zzf.bluetoothsmp.utils.HistoryRepository;
import com.zzf.bluetoothsmp.utils.HistoryStatusPolicy;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.customAdapter.MsgAdapter;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded transcript behavior for the live screen and the read-only history screen. */
public final class ChatTranscriptController implements AutoCloseable {
    private static final int PAGE_SIZE = 150;
    private static final int MAX_ROWS = 450;
    private static final int MAX_DISPLAY_BYTES = 2 * 1024 * 1024;

    private final RecyclerView recycler;
    private final LinearLayoutManager layout;
    private final Button olderButton;
    private final Button pauseButton;
    private final Button newerButton;
    private final String address;
    private final String uuid;
    private final ChatSessionIdentity session;
    private final Runnable onDisconnected;
    private final List<Msg> messages = new ArrayList<>();
    private final Map<String, Long> historyIds = new HashMap<>();
    private final Set<String> pendingIds = new HashSet<>();
    private final LinkedHashMap<String, Msg> deferredMessages = new LinkedHashMap<>();
    private long deferredBytes;
    private final Object incomingLock = new Object();
    private final LinkedHashMap<String, Msg> incoming = new LinkedHashMap<>();
    private long incomingBytes;
    private int omittedDisplayMessages;
    private boolean drainScheduled;
    private final MsgAdapter adapter = new MsgAdapter(messages);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService database = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final String listenerId = UUID.randomUUID().toString();
    private volatile boolean closed;
    private boolean loading;
    private boolean paused;
    private boolean hasOlder = true;
    private boolean browsingOlder;
    private long oldestId = Long.MAX_VALUE;
    private int pendingMessages;
    private String keyword;
    private Long fromMillis;
    private Long toMillis;
    private Integer direction;

    public ChatTranscriptController(View root, String address, String uuid,
                                    String sessionId, Runnable onDisconnected) {
        this.address = address;
        this.uuid = uuid;
        this.session = new ChatSessionIdentity(address, uuid, sessionId);
        this.onDisconnected = onDisconnected;
        recycler = root.findViewById(R.id.msg_recycler_view);
        layout = new LinearLayoutManager(root.getContext());
        recycler.setLayoutManager(layout);
        recycler.setAdapter(adapter);
        olderButton = root.findViewById(R.id.chat_load_older);
        pauseButton = root.findViewById(R.id.chat_pause);
        newerButton = root.findViewById(R.id.chat_new_messages);
        olderButton.setOnClickListener(view -> loadPage(false));
        pauseButton.setVisibility(sessionId == null ? View.GONE : View.VISIBLE);
        pauseButton.setOnClickListener(view -> {
            paused = !paused;
            pauseButton.setText(paused ? R.string.chat_resume : R.string.chat_pause);
            if (!paused) {
                loadPage(true);
            }
        });
        newerButton.setOnClickListener(view -> {
            paused = false;
            pauseButton.setText(R.string.chat_pause);
            loadPage(true);
        });
        if (sessionId != null) {
            registerEvents();
        }
        loadPage(true);
    }

    private void registerEvents() {
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE,
                event -> postMessage((Msg) event.getEventData()[0]), listenerId);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND_STATUS,
                event -> postMessage((Msg) event.getEventData()[0]), listenerId);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, event -> {
            Msg message = (Msg) event.getEventData()[0];
            if (session.acceptsDisconnect(message.getBluetoothAdd(), message.getSessionId())) {
                main.post(() -> {
                    if (!closed && onDisconnected != null) onDisconnected.run();
                });
            }
        }, listenerId);
    }

    private void postMessage(Msg message) {
        if (!session.accepts(message.getBluetoothAdd(), message.getSendUuid(), message.getSessionId())) {
            return;
        }
        synchronized (incomingLock) {
            if (closed) return;
            Msg previous = incoming.put(message.getMessageId(), message);
            incomingBytes += displayBytes(message) - (previous == null ? 0L : displayBytes(previous));
            while (!incoming.isEmpty() && (incoming.size() > MAX_ROWS || incomingBytes > MAX_DISPLAY_BYTES)) {
                String first = incoming.keySet().iterator().next();
                incomingBytes -= displayBytes(incoming.remove(first));
                omittedDisplayMessages++;
            }
            if (!drainScheduled) {
                drainScheduled = true;
                main.postDelayed(this::drainIncoming, 100L);
            }
        }
    }

    private void drainIncoming() {
        List<Msg> batch;
        int omitted;
        synchronized (incomingLock) {
            if (closed) return;
            batch = new ArrayList<>(incoming.values());
            incoming.clear();
            incomingBytes = 0;
            drainScheduled = false;
            omitted = omittedDisplayMessages;
            omittedDisplayMessages = 0;
        }
        boolean atBottom = !browsingOlder && (messages.isEmpty()
                || layout.findLastVisibleItemPosition() >= messages.size() - 3);
        boolean changed = false;
        for (Msg message : batch) {
            int existing = findMessage(message.getMessageId());
            if (existing >= 0) {
                messages.set(existing, message);
                changed = true;
            } else if (paused || !atBottom) {
                if (pendingIds.size() < MAX_ROWS && pendingIds.add(message.getMessageId())) pendingMessages++;
                deferMessage(message);
            } else {
                messages.add(message);
                changed = true;
            }
        }
        if (omitted > 0) pendingMessages = Math.min(MAX_ROWS, pendingMessages + omitted);
        showPendingCount();
        if (changed) {
            trimWindow(false);
            updateCursor(oldestId);
            adapter.notifyDataSetChanged();
            if (!paused && atBottom) recycler.scrollToPosition(messages.size() - 1);
        }
    }

    private int findMessage(String id) {
        if (id == null) return -1;
        for (int i = 0; i < messages.size(); i++) {
            if (id.equals(messages.get(i).getMessageId())) return i;
        }
        return -1;
    }

    private void deferMessage(Msg message) {
        Msg previous = deferredMessages.put(message.getMessageId(), message);
        deferredBytes += displayBytes(message) - (previous == null ? 0L : displayBytes(previous));
        while (!deferredMessages.isEmpty() && (deferredMessages.size() > MAX_ROWS
                || deferredBytes > MAX_DISPLAY_BYTES)) {
            String first = deferredMessages.keySet().iterator().next();
            deferredBytes -= displayBytes(deferredMessages.remove(first));
        }
    }

    private void showPendingCount() {
        newerButton.setVisibility(pendingMessages > 0 || browsingOlder ? View.VISIBLE : View.GONE);
        if (pendingMessages > 0) {
            newerButton.setText(recycler.getContext().getString(R.string.chat_new_messages, pendingMessages));
        } else {
            newerButton.setText(R.string.chat_latest);
        }
    }

    private void loadPage(boolean latest) {
        if (closed || address == null || (loading && !latest)) return;
        if (!latest && !hasOlder) {
            Toast.makeText(recycler.getContext(), R.string.chat_no_older, Toast.LENGTH_SHORT).show();
            return;
        }
        loading = true;
        olderButton.setText(R.string.chat_load_older);
        olderButton.setEnabled(false);
        final int generation = loadGeneration.incrementAndGet();
        final long beforeId = latest ? Long.MAX_VALUE : oldestId;
        final String selectedKeyword = keyword;
        final Long selectedFrom = fromMillis;
        final Long selectedTo = toMillis;
        final Integer selectedDirection = direction;
        database.execute(() -> {
            try {
                List<MessageMapper> rows = HistoryRepository.loadMessages(address, uuid, beforeId,
                        PAGE_SIZE, selectedKeyword, selectedFrom, selectedTo, selectedDirection);
                List<Msg> page = new ArrayList<>();
                Map<String, Long> pageIds = new HashMap<>();
                long earliest = beforeId;
                for (MessageMapper row : rows) {
                    earliest = Math.min(earliest, row.getId());
                    Msg restored = HistoryRepository.toMessage(row);
                    page.add(restored);
                    pageIds.put(restored.getMessageId(), row.getId());
                }
                Collections.reverse(page);
                final long cursor = earliest;
                main.post(() -> {
                    if (closed || generation != loadGeneration.get()) return;
                    loading = false;
                    browsingOlder = !latest;
                    hasOlder = rows.size() == PAGE_SIZE;
                    oldestId = cursor;
                    int first = layout.findFirstVisibleItemPosition();
                    View firstView = first < 0 ? null : layout.findViewByPosition(first);
                    int offset = firstView == null ? 0 : firstView.getTop() - recycler.getPaddingTop();
                    List<Msg> previous = new ArrayList<>(messages);
                    if (latest) {
                        previous.addAll(deferredMessages.values());
                        messages.clear();
                        historyIds.clear();
                    }
                    historyIds.putAll(pageIds);
                    int inserted = 0;
                    for (Msg message : page) {
                        if (findMessage(message.getMessageId()) < 0) {
                            messages.add(inserted++, message);
                        }
                    }
                    if (latest) {
                        // Preserve in-flight status rows not yet written by the history worker.
                        for (Msg message : previous) {
                            int existing = findMessage(message.getMessageId());
                            if (existing >= 0 && session.accepts(message.getBluetoothAdd(),
                                    message.getSendUuid(), message.getSessionId())
                                    && HistoryStatusPolicy.shouldReplace(status(messages.get(existing)), status(message))) {
                                messages.set(existing, message);
                            } else if (existing < 0 && session.accepts(message.getBluetoothAdd(),
                                    message.getSendUuid(), message.getSessionId())
                                    && message.getSenTime().getTime() >= newestPersistedTime(page)) {
                                messages.add(message);
                            }
                        }
                        Collections.sort(messages, (a, b) -> {
                            int time = Long.compare(a.getSenTime().getTime(), b.getSenTime().getTime());
                            return time != 0 ? time : Long.compare(a.getRequestId(), b.getRequestId());
                        });
                        pendingMessages = 0;
                        pendingIds.clear();
                        deferredMessages.clear();
                        deferredBytes = 0;
                    }
                    int beforeTrim = messages.size();
                    trimWindow(!latest);
                    if (latest && messages.size() < beforeTrim) hasOlder = true;
                    updateCursor(cursor);
                    adapter.notifyDataSetChanged();
                    olderButton.setEnabled(hasOlder);
                    olderButton.setText(hasOlder ? R.string.chat_load_older : R.string.chat_no_older);
                    showPendingCount();
                    if (latest && !messages.isEmpty()) {
                        recycler.scrollToPosition(messages.size() - 1);
                    } else if (first >= 0) {
                        layout.scrollToPositionWithOffset(Math.min(first + inserted,
                                Math.max(0, messages.size() - 1)), offset);
                    }
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (closed || generation != loadGeneration.get()) return;
                    loading = false;
                    olderButton.setEnabled(true);
                    Toast.makeText(recycler.getContext(), R.string.history_load_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private long newestPersistedTime(List<Msg> page) {
        return page.isEmpty() ? 0L : page.get(page.size() - 1).getSenTime().getTime();
    }

    private String status(Msg message) {
        return message.getSendStatus() == null ? null : message.getSendStatus().name();
    }

    private void trimWindow(boolean keepOlder) {
        long estimatedBytes = 0;
        for (Msg message : messages) {
            estimatedBytes += displayBytes(message);
        }
        while (messages.size() > 1 && (messages.size() > MAX_ROWS || estimatedBytes > MAX_DISPLAY_BYTES)) {
            Msg removed = messages.remove(keepOlder ? messages.size() - 1 : 0);
            historyIds.remove(removed.getMessageId());
            estimatedBytes -= displayBytes(removed);
        }
    }

    private long displayBytes(Msg message) {
        return message.getPayloadLength() + (message.getContent() == null ? 0L : message.getContent().length() * 2L);
    }

    private void updateCursor(long fallback) {
        long earliest = Long.MAX_VALUE;
        for (Msg message : messages) {
            Long rowId = historyIds.get(message.getMessageId());
            if (rowId != null) earliest = Math.min(earliest, rowId);
        }
        oldestId = earliest == Long.MAX_VALUE ? fallback : earliest;
    }

    public void setHistoryFilter(String keyword, Long fromMillis, Long toMillis, Integer direction) {
        this.keyword = keyword;
        this.fromMillis = fromMillis;
        this.toMillis = toMillis;
        this.direction = direction;
        oldestId = Long.MAX_VALUE;
        hasOlder = true;
        loadPage(true);
    }

    public void saveState(Bundle state) {
        state.putBoolean("chat_terminal_paused", paused);
    }

    public void restoreState(Bundle state) {
        if (state != null && pauseButton.getVisibility() == View.VISIBLE) {
            paused = state.getBoolean("chat_terminal_paused", false);
            pauseButton.setText(paused ? R.string.chat_resume : R.string.chat_pause);
        }
    }

    @Override
    public void close() {
        closed = true;
        loadGeneration.incrementAndGet();
        StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerId);
        main.removeCallbacksAndMessages(null);
        database.shutdownNow();
        recycler.setAdapter(null);
        messages.clear();
        historyIds.clear();
        pendingIds.clear();
        deferredMessages.clear();
        deferredBytes = 0;
        synchronized (incomingLock) {
            incoming.clear();
            incomingBytes = 0;
        }
    }
}
