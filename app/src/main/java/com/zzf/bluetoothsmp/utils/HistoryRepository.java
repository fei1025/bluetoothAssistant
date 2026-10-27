package com.zzf.bluetoothsmp.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import com.zzf.bluetoothsmp.BluetoothRequestIds;
import com.zzf.bluetoothsmp.BluetoothSendStatus;
import com.zzf.bluetoothsmp.BluetoothTextEncoding;
import com.zzf.bluetoothsmp.MyApplication;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.entity.Msg;
import org.litepal.LitePal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded background history persistence. Disk failures retain the in-flight batch. */
public final class HistoryRepository {
    private static final String TAG = "BluetoothHistory";
    private static final int MAX_RECORDS = 256, MAX_STATUS = 4096;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    // Schema migration fixtures also serialize temporary LitePal.use() with DB_LOCK.
    private static final Object PENDING_LOCK = new Object(), DB_LOCK = new Object();
    private static final LinkedHashMap<String, Snapshot> pending = new LinkedHashMap<>();
    private static final LinkedHashMap<String, StatusSnapshot> pendingStatus = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Track> trackedSends = new LinkedHashMap<>();
    private static final Map<String, StatusSnapshot> earlyStatus = new ConcurrentHashMap<>();
    private static final Map<String, Long> deletionWatermarks = new ConcurrentHashMap<>();
    private static final List<PruneBarrier> pruneBarriers = new CopyOnWriteArrayList<>();
    private static final AtomicLong writeFailures = new AtomicLong();
    private static long pendingBytes, lastPruneElapsed;
    private static boolean workerStarted, maintenanceRequested;
    private static Batch inFlight;
    private static SQLiteDatabase indexedDatabase, retainedDatabase;
    private static volatile Context retentionContext;
    private static volatile int retentionDays = -1;
    // Instrumentation fixtures inject a transient failure immediately before COMMIT.
    private static volatile Runnable transactionFaultForTests;
    private HistoryRepository() { }
    public static long getWriteFailureCount() { return writeFailures.get(); }
    public static int getPendingCount() {
        synchronized (PENDING_LOCK) { return pending.size() + pendingStatus.size() + (inFlight == null ? 0 : inFlight.records.size() + inFlight.statuses.size()); }
    }
    public static long getPendingBytes() {
        synchronized (PENDING_LOCK) { return pendingBytes + (inFlight == null ? 0L : inFlight.bytes); }
    }
    private static boolean isMainThread() { return Looper.myLooper() == Looper.getMainLooper(); }
    private static String key(String address, String uuid) { return address + "\n" + emptyIfNull(uuid); }
    private static boolean deleted(String address, String uuid, long requestId) {
        Long watermark = deletionWatermarks.get(key(address, uuid));
        return watermark != null && requestId <= watermark;
    }
    private static long cutoff() {
        int days = retentionDays;
        if (days < 0) {
            Context context = retentionContext == null ? MyApplication.getContext() : retentionContext;
            days = context == null ? 0 : HistoryRetention.getDays(context);
            retentionDays = days;
        }
        return days == 0 ? Long.MIN_VALUE : System.currentTimeMillis() - days * 86400000L;
    }
    private static boolean excluded(String address, String uuid, long requestId, long time) {
        if (deleted(address, uuid, requestId) || time < cutoff()) return true;
        for (PruneBarrier barrier : pruneBarriers) if (requestId <= barrier.watermark && time < barrier.cutoff) return true;
        return false;
    }

    /** The dispatcher reserves result capacity before SEND, never on the UI thread. */
    public static boolean reserveSend(Msg message) {
        if (!message.isPersistHistory()) return true;
        if (excluded(message.getBluetoothAdd(), message.getSendUuid(), message.getRequestId(), message.getSenTime().getTime())) {
            message.setPersistHistory(false); // History deletion does not cancel physical sends.
            return true;
        }
        synchronized (PENDING_LOCK) {
            startWorker();
            while (!trackedSends.containsKey(message.getMessageId()) && trackedSends.size() >= MAX_STATUS) {
                if (!message.hasTransportAdmission() && (message.isSendCanceled() || !message.isForegroundGenerationCurrent()
                        || !currentSendSession(message))) return false;
                if (isMainThread()) { reportFailure("Send history reservations require an IO producer", null); return false; }
                try { PENDING_LOCK.wait(100L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
            if (!trackedSends.containsKey(message.getMessageId())) trackedSends.put(message.getMessageId(), new Track(message));
            return true;
        }
    }
    private static boolean currentSendSession(Msg message) {
        if (message.getSessionId() == null) return true; // Standalone history fixtures/direct records.
        String address = BluetoothAddressUtils.normalize(message.getBluetoothAdd());
        com.zzf.bluetoothsmp.BluetoothServiceConnect session = address == null ? null : StaticObject.bluetoothSocketMap.get(address);
        return session != null && session.isSessionOpen() && session.getSessionId().equals(message.getSessionId());
    }
    public static void abandonSend(Msg message) {
        synchronized (PENDING_LOCK) {
            if (!message.isHistoryRecordRequested()) {
                trackedSends.remove(message.getMessageId()); pendingStatus.remove(message.getMessageId());
                earlyStatus.remove(message.getMessageId());
                PENDING_LOCK.notifyAll();
            }
        }
    }
    public static void record(Msg message) {
        if (message == null || !message.isPersistHistory()) return;
        boolean send = message.getType() == Msg.TYPE_SENT;
        if (send && !message.hasTransportAdmission() && message.getSendStatus() != BluetoothSendStatus.SENT
                && (message.isSendCanceled() || !message.isForegroundGenerationCurrent())) {
            synchronized (PENDING_LOCK) { if (!trackedSends.containsKey(message.getMessageId())) return; }
        }
        if (excluded(message.getBluetoothAdd(), message.getSendUuid(), message.getRequestId(), message.getSenTime().getTime())) return;
        message.markHistoryRecordRequested();
        if (send && !reserveSend(message)) return;
        Snapshot snapshot = new Snapshot(message);
        if (snapshot.bytes > MAX_BYTES) { reportFailure("History payload exceeds budget", null); abandonOversized(snapshot.id); return; }
        synchronized (PENDING_LOCK) {
            startWorker();
            for (;;) {
                if (excluded(snapshot.address, snapshot.uuid, snapshot.requestId, snapshot.time)) return;
                Snapshot old = pending.get(snapshot.id);
                if (old != null && !HistoryStatusPolicy.shouldReplace(old.status, snapshot.status)) return;
                long projected = pendingBytes - (old == null ? 0L : old.bytes) + snapshot.bytes;
                if ((old != null || pending.size() < MAX_RECORDS) && projected <= MAX_BYTES) break;
                if (isMainThread()) { reportFailure("History records require an IO producer", null); return; }
                try { PENDING_LOCK.wait(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); reportFailure("History admission interrupted", interrupted); return; }
            }
            Snapshot old = pending.put(snapshot.id, snapshot);
            pendingBytes += snapshot.bytes - (old == null ? 0L : old.bytes);
            PENDING_LOCK.notifyAll();
        }
    }
    private static void abandonOversized(String id) {
        synchronized (PENDING_LOCK) { trackedSends.remove(id); pendingStatus.remove(id); PENDING_LOCK.notifyAll(); }
    }
    /** Terminal updates only mutate a reserved, tiny entry and never wait for disk or copy bytes. */
    public static void updateStatus(Msg message) {
        if (message == null || !message.isPersistHistory()) return;
        StatusSnapshot snapshot = new StatusSnapshot(message);
        if (!HistoryStatusPolicy.isTerminal(snapshot.status) || excluded(snapshot.address, snapshot.uuid, snapshot.requestId, snapshot.time)) return;
        synchronized (PENDING_LOCK) {
            startWorker();
            Track track = trackedSends.get(snapshot.id);
            if (track == null) {
                // Central rejection/cancellation never entered SEND and has no future base row.
                if (!message.hasTransportAdmission() && !message.isHistoryRecordRequested()) return;
                if (trackedSends.size() >= MAX_STATUS) { reportFailure("Unreserved send status exceeds result budget", null); return; }
                track = new Track(message); trackedSends.put(snapshot.id, track);
            }
            StatusSnapshot old = pendingStatus.get(snapshot.id);
            if (old == null || HistoryStatusPolicy.shouldReplace(old.status, snapshot.status)) pendingStatus.put(snapshot.id, snapshot);
            PENDING_LOCK.notifyAll();
        }
    }
    private static void startWorker() {
        if (workerStarted) return;
        workerStarted = true;
        Thread thread = new Thread(HistoryRepository::writeLoop, "bluetooth-history-writer");
        thread.setDaemon(true); thread.start();
    }
    private static void reportFailure(String message, Throwable error) { writeFailures.incrementAndGet(); Log.e(TAG, message, error); }
    private static void writeLoop() {
        int failures = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Batch batch;
                synchronized (PENDING_LOCK) {
                    while (inFlight == null && pending.isEmpty() && pendingStatus.isEmpty() && !maintenanceRequested) PENDING_LOCK.wait();
                    if (inFlight == null) {
                        inFlight = new Batch(new ArrayList<>(pending.values()), new ArrayList<>(pendingStatus.values()), pendingBytes);
                        pending.clear(); pendingStatus.clear(); pendingBytes = 0L; maintenanceRequested = false;
                        PENDING_LOCK.notifyAll();
                    }
                    batch = inFlight;
                }
                try {
                    writeBatch(batch);
                    synchronized (PENDING_LOCK) { if (inFlight == batch) inFlight = null; PENDING_LOCK.notifyAll(); }
                    failures = 0;
                    StaticObject.bluetoothEvent.historyChanged();
                } catch (RuntimeException failure) {
                    reportFailure("History transaction failed; batch retained for retry", failure);
                    failures = Math.min(failures + 1, 5);
                    Thread.sleep(Math.min(2000L, 100L << (failures - 1)));
                }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { synchronized (PENDING_LOCK) { workerStarted = false; PENDING_LOCK.notifyAll(); } }
    }
    private static void writeBatch(Batch batch) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = database();
            Commit changes = new Commit();
            db.beginTransaction();
            try {
                Map<String, Snapshot> summaries = new LinkedHashMap<>();
                for (Snapshot record : batch.records) {
                    if (excluded(record.address, record.uuid, record.requestId, record.time)) { changes.finished.add(record.id); changes.consumeEarly.add(record.id); continue; }
                    saveRecord(db, record, changes);
                    Snapshot old = summaries.get(key(record.address, record.uuid));
                    if (old == null || old.time <= record.time) summaries.put(key(record.address, record.uuid), record);
                }
                for (StatusSnapshot status : batch.statuses) saveStatus(db, status, changes);
                for (Snapshot summary : summaries.values()) saveSummary(db, summary);
                Runnable fault = transactionFaultForTests;
                if (fault != null) fault.run();
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            // Memory cache mutations happen only after COMMIT, so retry keeps early outcomes.
            for (String id : changes.consumeEarly) earlyStatus.remove(id);
            synchronized (PENDING_LOCK) {
                for (Map.Entry<String, StatusSnapshot> entry : changes.earlyAdds.entrySet()) {
                    if (!trackedSends.containsKey(entry.getKey())) continue;
                    StatusSnapshot old = earlyStatus.get(entry.getKey());
                    if (old == null || HistoryStatusPolicy.shouldReplace(old.status, entry.getValue().status)) earlyStatus.put(entry.getKey(), entry.getValue());
                }
                for (String id : changes.baseIds) { Track track = trackedSends.get(id); if (track != null) track.baseCommitted = true; }
                for (String id : changes.finished) { trackedSends.remove(id); pendingStatus.remove(id); }
                PENDING_LOCK.notifyAll();
            }
        }
    }
    public static boolean awaitWrites(long timeoutMillis) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + timeoutMillis;
        synchronized (PENDING_LOCK) {
            if (inFlight != null || !pending.isEmpty() || !pendingStatus.isEmpty() || maintenanceRequested) startWorker();
            while (inFlight != null || !pending.isEmpty() || !pendingStatus.isEmpty() || maintenanceRequested) {
                long remaining = end - SystemClock.elapsedRealtime(); if (remaining <= 0) return false;
                PENDING_LOCK.wait(remaining);
            }
            return true;
        }
    }
    private static SQLiteDatabase database() {
        SQLiteDatabase db = LitePal.getDatabase();
        if (indexedDatabase != db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_conversation ON messagemapper(sendadd, senduuid, id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON messagemapper(sendtime)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_history_message ON messagemapper(messageid) WHERE messageid IS NOT NULL AND messageid <> ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_address ON bluetoothdrive(driveadd, uuid)");
            indexedDatabase = db;
        }
        long cutoff = cutoff(), now = SystemClock.elapsedRealtime();
        if (cutoff != Long.MIN_VALUE && (retainedDatabase != db || now - lastPruneElapsed >= 60000L)) {
            prune(db, cutoff); retainedDatabase = db; lastPruneElapsed = now;
            addPruneBarrier(cutoff);
            removeExcludedPending();
        }
        return db;
    }
    private static void saveRecord(SQLiteDatabase db, Snapshot record, Commit changes) {
        ContentValues values = new ContentValues();
        values.put("messageid", record.id); values.put("requestid", record.requestId); values.put("sessionid", record.sessionId);
        values.put("payloadbase64", Base64.encodeToString(record.payload, Base64.NO_WRAP));
        values.put("textencoding", record.encoding); values.put("sendstatus", record.status); values.put("senderror", record.error);
        values.put("sendadd", record.address); values.put("senduuid", record.uuid); values.put("sendname", record.name);
        values.put("receivename", record.localName); values.put("receiveadd", record.localAddress);
        values.put("message", record.content); values.put("sendtime", record.time);
        if (record.type == Msg.TYPE_RECEIVED) values.put("receivetime", record.time);
        values.put("savedate", System.currentTimeMillis()); values.put("type", record.type);
        String result = record.status;
        if (db.insertWithOnConflict("messagemapper", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
            try (Cursor cursor = db.query("messagemapper", new String[]{"sendstatus"}, "messageid = ?", new String[]{record.id}, null, null, null)) {
                if (!cursor.moveToFirst()) throw new IllegalStateException("History insert failed without a matching row");
                if (!HistoryStatusPolicy.shouldReplace(cursor.getString(0), record.status)) {
                    result = cursor.getString(0); values.remove("sendstatus"); values.remove("senderror");
                }
            }
            db.update("messagemapper", values, "messageid = ?", new String[]{record.id});
        }
        changes.baseIds.add(record.id);
        if (HistoryStatusPolicy.isTerminal(result)) changes.finished.add(record.id);
        StatusSnapshot early = earlyStatus.get(record.id);
        if (early != null) { saveStatus(db, early, changes); changes.consumeEarly.add(record.id); }
    }
    private static void saveStatus(SQLiteDatabase db, StatusSnapshot status, Commit changes) {
        if (excluded(status.address, status.uuid, status.requestId, status.time)) { changes.finished.add(status.id); changes.consumeEarly.add(status.id); return; }
        try (Cursor cursor = db.query("messagemapper", new String[]{"sendstatus"}, "messageid = ?", new String[]{status.id}, null, null, null)) {
            if (!cursor.moveToFirst()) { changes.earlyAdds.put(status.id, status); return; }
            if (!HistoryStatusPolicy.shouldReplace(cursor.getString(0), status.status)) {
                if (HistoryStatusPolicy.isTerminal(cursor.getString(0))) changes.finished.add(status.id);
                return;
            }
        }
        ContentValues values = new ContentValues(); values.put("sendstatus", status.status); values.put("senderror", status.error); values.put("requestid", status.requestId);
        db.update("messagemapper", values, "messageid = ?", new String[]{status.id});
        changes.finished.add(status.id);
    }
    private static void saveSummary(SQLiteDatabase db, Snapshot record) {
        long id = 0, time = 0;
        try (Cursor cursor = db.query("bluetoothdrive", new String[]{"id", "sendate"}, "driveadd = ? AND COALESCE(uuid, '') = ?",
                new String[]{record.address, emptyIfNull(record.uuid)}, null, null, "id DESC", "1")) {
            if (cursor.moveToFirst()) { id = cursor.getLong(0); time = cursor.getLong(1); }
        }
        if (time > record.time) return;
        ContentValues values = new ContentValues(); values.put("driveadd", record.address); values.put("uuid", record.uuid);
        values.put("drivename", record.name); values.put("lastreceivemsg", record.content); values.put("sendate", record.time);
        if (id == 0) db.insertOrThrow("bluetoothdrive", null, values); else db.update("bluetoothdrive", values, "id = ?", new String[]{Long.toString(id)});
    }
    public static List<MessageMapper> loadMessages(String mac, String uuid, long beforeId, int limit) { return loadMessages(mac, uuid, beforeId, limit, null, null, null, null); }
    public static List<MessageMapper> loadMessages(String mac, String uuid, long beforeId, int limit, String keyword, Long fromMillis, Long toMillis, Integer type) {
        synchronized (DB_LOCK) {
            database(); List<String> args = new ArrayList<>(); StringBuilder where = new StringBuilder("sendadd = ? AND "); args.add(mac);
            if (uuid == null || uuid.isEmpty()) where.append("COALESCE(senduuid, '') = ''"); else { where.append("senduuid = ?"); args.add(uuid); }
            where.append(" AND id < ?"); args.add(Long.toString(beforeId));
            if (keyword != null && !keyword.trim().isEmpty()) { where.append(" AND instr(message, ?) > 0"); args.add(keyword.trim()); }
            if (fromMillis != null) { where.append(" AND sendtime >= ?"); args.add(fromMillis.toString()); }
            if (toMillis != null) { where.append(" AND sendtime < ?"); args.add(toMillis.toString()); }
            if (type != null) { where.append(" AND type = ?"); args.add(type.toString()); }
            args.add(0, where.toString()); return LitePal.where(args.toArray(new String[0])).order("id DESC").limit(Math.max(1, Math.min(200, limit))).find(MessageMapper.class);
        }
    }
    public static List<BluetoothDrive> loadConversations() { synchronized (DB_LOCK) { database(); return LitePal.order("senDate DESC, id DESC").find(BluetoothDrive.class); } }
    public static boolean deleteConversation(String mac, String uuid) {
        long watermark = BluetoothRequestIds.current();
        try {
            synchronized (DB_LOCK) {
                SQLiteDatabase db = database(); db.beginTransaction();
                try {
                    db.delete("messagemapper", "sendadd = ? AND COALESCE(senduuid, '') = ?", new String[]{mac, emptyIfNull(uuid)});
                    db.delete("bluetoothdrive", "driveadd = ? AND COALESCE(uuid, '') = ?", new String[]{mac, emptyIfNull(uuid)});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                Long oldWatermark = deletionWatermarks.get(key(mac, uuid));
                deletionWatermarks.put(key(mac, uuid), oldWatermark == null ? watermark : Math.max(oldWatermark, watermark));
                removeExcludedPending();
            }
            StaticObject.bluetoothEvent.historyChanged(); return true;
        } catch (RuntimeException failure) { reportFailure("Conversation deletion failed", failure); return false; }
    }
    private static void prune(SQLiteDatabase db, long cutoff) {
        db.beginTransaction();
        try {
            db.delete("messagemapper", "sendtime < ?", new String[]{Long.toString(cutoff)});
            db.delete("bluetoothdrive", "sendate < ?", new String[]{Long.toString(cutoff)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public static boolean pruneBefore(long cutoffMillis) {
        try { synchronized (DB_LOCK) { prune(database(), cutoffMillis); addPruneBarrier(cutoffMillis); removeExcludedPending(); } StaticObject.bluetoothEvent.historyChanged(); return true; }
        catch (RuntimeException failure) { reportFailure("History retention failed", failure); return false; }
    }
    public static boolean configureRetention(Context context, int days) {
        if (days < 0 || days > 36500) return false;
        try {
            synchronized (DB_LOCK) {
                SQLiteDatabase db = database();
                if (days > 0) {
                    long cutoff = System.currentTimeMillis() - days * 86400000L;
                    prune(db, cutoff); addPruneBarrier(cutoff);
                }
                retentionContext = context.getApplicationContext(); retentionDays = days;
                HistoryRetention.persistDays(context, days);
                retainedDatabase = db; lastPruneElapsed = SystemClock.elapsedRealtime();
                removeExcludedPending();
            }
            StaticObject.bluetoothEvent.historyChanged(); return true;
        } catch (RuntimeException failure) { reportFailure("History retention failed", failure); return false; }
    }
    public static void initializeRetention(Context context) {
        retentionContext = context.getApplicationContext(); retentionDays = HistoryRetention.getDays(context);
        synchronized (PENDING_LOCK) { maintenanceRequested = true; startWorker(); PENDING_LOCK.notifyAll(); }
    }
    private static void addPruneBarrier(long cutoff) {
        long watermark = BluetoothRequestIds.current();
        // A later, stricter cleanup subsumes old barriers. Disabling retention
        // still cannot resurrect snapshots that an earlier cleanup already removed.
        for (PruneBarrier old : new ArrayList<>(pruneBarriers)) {
            if (watermark >= old.watermark && cutoff >= old.cutoff) pruneBarriers.remove(old);
        }
        pruneBarriers.add(new PruneBarrier(watermark, cutoff));
    }
    // Called with DB_LOCK, never while waiting for a producer. Only tiny bookkeeping is removed here.
    private static void removeExcludedPending() {
        synchronized (PENDING_LOCK) {
            java.util.Iterator<Snapshot> records = pending.values().iterator();
            while (records.hasNext()) { Snapshot record = records.next(); if (excluded(record.address, record.uuid, record.requestId, record.time)) { pendingBytes -= record.bytes; records.remove(); } }
            java.util.Iterator<Track> tracks = trackedSends.values().iterator();
            while (tracks.hasNext()) { Track track = tracks.next(); if (excluded(track.address, track.uuid, track.requestId, track.time)) tracks.remove(); }
            java.util.Iterator<StatusSnapshot> statuses = pendingStatus.values().iterator();
            while (statuses.hasNext()) { StatusSnapshot status = statuses.next(); if (excluded(status.address, status.uuid, status.requestId, status.time)) statuses.remove(); }
            PENDING_LOCK.notifyAll();
        }
        java.util.Iterator<StatusSnapshot> statuses = earlyStatus.values().iterator();
        while (statuses.hasNext()) { StatusSnapshot status = statuses.next(); if (excluded(status.address, status.uuid, status.requestId, status.time)) statuses.remove(); }
    }
    public static Msg toMessage(MessageMapper row) {
        String id = row.getMessageId(); if (id == null || id.isEmpty()) id = "legacy:" + row.getId();
        Msg message = Msg.restored(row.getPayload(), row.getMessage(), row.getType(), row.getSendAdd(), id, row.getRequestId());
        message.setSessionId(row.getSessionId()); message.setBluetoothName(row.getSendName()); message.setSendUuid(row.getSendUuid());
        message.setSenTime(row.getSendTime());
        message.setTextEncoding(null);
        try { message.setTextEncoding(BluetoothTextEncoding.valueOf(row.getTextEncoding())); } catch (RuntimeException ignored) { }
        try { message.updateSendStatus(BluetoothSendStatus.valueOf(row.getSendStatus()), row.getSendError()); } catch (RuntimeException ignored) { }
        return message;
    }
    private static String emptyIfNull(String value) { return value == null ? "" : value; }
    private static final class Batch {
        final List<Snapshot> records; final List<StatusSnapshot> statuses; final long bytes;
        Batch(List<Snapshot> records, List<StatusSnapshot> statuses, long bytes) { this.records = records; this.statuses = statuses; this.bytes = bytes; }
    }
    private static final class PruneBarrier {
        final long watermark, cutoff;
        PruneBarrier(long watermark, long cutoff) { this.watermark = watermark; this.cutoff = cutoff; }
    }
    private static final class Commit {
        final Set<String> baseIds = new HashSet<>(), finished = new HashSet<>(), consumeEarly = new HashSet<>();
        final Map<String, StatusSnapshot> earlyAdds = new LinkedHashMap<>();
    }
    private static final class Track {
        final String address, uuid; final long requestId, time; boolean baseCommitted;
        Track(Msg message) { address = message.getBluetoothAdd(); uuid = message.getSendUuid(); requestId = message.getRequestId(); time = message.getSenTime().getTime(); }
    }
    private static final class Snapshot {
        final String id, sessionId, address, uuid, name, localName, localAddress, content, encoding, status, error;
        final long requestId, time, bytes; final int type; final byte[] payload;
        Snapshot(Msg message) {
            synchronized (message) {
                id = message.getMessageId(); sessionId = message.getSessionId(); requestId = message.getRequestId(); address = message.getBluetoothAdd(); uuid = message.getSendUuid();
                name = message.getBluetoothName() == null || message.getBluetoothName().trim().isEmpty() ? address : message.getBluetoothName();
                content = message.getContent(); payload = message.getPayloadOrUtf8();
                encoding = message.getTextEncoding() == null ? "UTF_8" : message.getTextEncoding().name();
                status = message.getSendStatus() == null ? null : message.getSendStatus().name(); error = message.getSendError();
                type = message.getType(); time = message.getSenTime() == null ? System.currentTimeMillis() : message.getSenTime().getTime();
            }
            localName = StaticObject.myBluetoothName; localAddress = StaticObject.myBluetoothAdd;
            bytes = payload.length + (content == null ? 0L : content.length() * 2L) + 256L;
        }
    }
    private static final class StatusSnapshot {
        final String id, status, error, address, uuid; final long requestId, time;
        StatusSnapshot(Msg message) {
            synchronized (message) {
                id = message.getMessageId(); status = message.getSendStatus() == null ? null : message.getSendStatus().name(); error = message.getSendError();
                address = message.getBluetoothAdd(); uuid = message.getSendUuid(); requestId = message.getRequestId(); time = message.getSenTime().getTime();
            }
        }
    }
}
