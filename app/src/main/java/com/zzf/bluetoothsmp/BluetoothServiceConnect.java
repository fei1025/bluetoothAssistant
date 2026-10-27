package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BluetoothServiceConnect {
    private static final String TAG = "BluetoothSession";
    private final String sessionId = UUID.randomUUID().toString();
    private final Object framePublicationLock = new Object();
    private final ConcurrentMap<Long, Msg> pendingMessages = new ConcurrentHashMap<>();
    private BluetoothEncodingUtils.StreamingTextDecoder streamingTextDecoder;

    interface SessionOwner {
        boolean isActive();
    }

    public interface SendProgressListener {
        void onStatus(BluetoothSendStatus status, long requestId);

        void onProgress(long requestId, int sentBytes, int totalBytes);
    }

    public interface PluginListener {
        void onData(byte[] bytes);
        void onSend(long requestId, BluetoothSendStatus status, Throwable error);
        void onClosed();
    }
    private volatile PluginListener pluginListener;
    private boolean pluginOwned;

    /** RAW transport-only session. Configure before reserving/starting; never changes device preferences. */
    public void configurePlugin(PluginListener listener) {
        if (bluetoothSocket != null) throw new IllegalStateException("Session already started");
        pluginOwned = true; pluginListener = listener;
    }
    public long sendPlugin(byte[] payload) {
        if (!pluginOwned || sendQueue == null || !isSessionOpen()) throw new IllegalStateException("Session closed");
        long id = BluetoothRequestIds.next();
        sendQueue.enqueue(id, payload, null);
        return id;
    }
    public void sendPlugin(long requestId, byte[] payload) {
        if (!pluginOwned || sendQueue == null || !isSessionOpen()) throw new IllegalStateException("Session closed");
        sendQueue.enqueue(requestId, payload, null);
    }
    public BluetoothSocket bluetoothSocket;
    private BufferedOutputStream bufferedOutputStream;
    private BufferedInputStream bufferedInputStream;
    private String bluetoothName;
    private String bluetoothAdd;
    private String listenerUuid;
    private String sendUuid;
    private BluetoothFrameConfig frameConfig;
    private BluetoothTextEncoding textEncoding = BluetoothTextEncoding.UTF_8;
    private StreamFrameDecoder frameDecoder;
    private Thread receiveThread;
    private ScheduledExecutorService frameTimeoutExecutor;
    private long connectedAtMillis;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);
    private BluetoothSendQueue sendQueue;
    private volatile SendProgressListener sendProgressListener;
    private volatile BluetoothSendStatus lastSendStatus;
    private volatile String lastSendError;
    private volatile boolean duplicateSession;
    private long foregroundGeneration;
    private volatile String closeReason = "unknown";
    private final AtomicLong queuedSendCount = new AtomicLong();
    private final AtomicLong successfulSendCount = new AtomicLong();
    private final AtomicLong failedSendCount = new AtomicLong();
    private final AtomicLong canceledSendCount = new AtomicLong();
    private final AtomicInteger reportedDroppedFrames = new AtomicInteger();
    private final AtomicLong bytesSentCount = new AtomicLong();
    private final AtomicLong bytesReceivedCount = new AtomicLong();
    private final AtomicLong receivedFrameCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    @SuppressLint("MissingPermission")
    public boolean start(Context context, BluetoothSocket socket, String sendUuid) {
        return start(context, socket, sendUuid, StaticObject.foregroundGate.currentGeneration());
    }

    @SuppressLint("MissingPermission")
    public boolean start(Context context, BluetoothSocket socket, String sendUuid, long generation) {
        return start(context, socket, sendUuid, generation, () -> true);
    }

    @SuppressLint("MissingPermission")
    boolean start(Context context, BluetoothSocket socket, String sendUuid, long generation,
                  SessionOwner ownerStillActive) {
        if (socket == null) {
            return false;
        }

        duplicateSession = false;
        foregroundGeneration = generation;

        bluetoothSocket = socket;
        this.sendUuid = sendUuid;
        String address = null;

        try {
            BluetoothDevice remoteDevice = socket.getRemoteDevice();
            address = remoteDevice.getAddress();
            listenerUuid = UUID.randomUUID().toString();
            bluetoothAdd = address;
            bluetoothName = remoteDevice.getName();
            frameConfig = pluginOwned ? new BluetoothFrameConfig(BluetoothFrameMode.RAW, 65536, 1, new byte[0], 0)
                    : BluetoothProtocolConfigStore.get(context, address);
            textEncoding = pluginOwned ? BluetoothTextEncoding.UTF_8 : BluetoothTextEncodingStore.get(context, address);
            bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
            bufferedInputStream = new BufferedInputStream(socket.getInputStream());
            final String sessionAddress = address;
            final boolean[] started = {false};
            // Invalidation and map publication are atomic. Socket setup/close happen
            // outside the gate so background teardown never waits on blocked I/O here.
            StaticObject.foregroundGate.runIfCurrent(generation,
                    () -> started[0] = ownerStillActive.isActive() && activateSession(sessionAddress));
            if (!started[0]) {
                closeInternal();
            }
            return started[0];
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to initialize session "
                    + (address == null ? "unknown" : address), e);
            closeInternal();
            return false;
        }
    }

    private boolean activateSession(String address) {
        if (closed.get()) {
            return false;
        }
        if (!BluetoothSessionOwners.acquire("classic", address, this)) {
            duplicateSession = true;
            return false;
        }
        while (true) {
            BluetoothServiceConnect existing = StaticObject.bluetoothSocketMap.putIfAbsent(address, this);
            if (existing == null) {
                break;
            }
            if (existing.closed.get()
                    && StaticObject.bluetoothSocketMap.remove(address, existing)) {
                // The previous session is already closing but has not finished its
                // cleanup yet. Retry ownership after removing its stale slot.
                continue;
            }
            duplicateSession = true;
            return false;
        }
        sendQueue = new BluetoothSendQueue(this::writePayload,
                new BluetoothSendQueue.Listener() {
                    @Override
                    public void onStatus(long requestId, BluetoothSendStatus status,
                                          int pendingCount, Throwable error) {
                        onSendStatus(requestId, status, pendingCount, error);
                    }

                    @Override
                    public void onProgress(long requestId, int sentBytes, int totalBytes) {
                        onSendProgress(requestId, sentBytes, totalBytes);
                    }
                });
        if (!pluginOwned) StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, event -> {
            Msg msg = (Msg) event.getEventData()[0];
            if (!StaticObject.foregroundGate.isCurrent(foregroundGeneration)
                    || !bluetoothAdd.equals(msg.getBluetoothAdd()) || closed.get()
                    || msg.isSendCanceled() || !msg.isForegroundGenerationCurrent()
                    || msg.getForegroundGeneration() != foregroundGeneration
                    || !sessionId.equals(msg.getSessionId())) {
                return;
            }
            if (!msg.claimTransportAdmission()) return;
            pendingMessages.put(msg.getRequestId(), msg);
            try {
                byte[] payload = payloadForSend(msg);
                if (msg.getSegmentSize() > 0) {
                    sendQueue.enqueueSegmented(msg.getRequestId(), payload, msg.getSegmentSize(),
                            Math.max(0L, msg.getSegmentIntervalMillis()), msg.getSendBatch());
                } else {
                    sendQueue.enqueue(msg.getRequestId(), payload, msg.getSendBatch());
                }
            } catch (IllegalArgumentException invalidPayload) {
                pendingMessages.remove(msg.getRequestId());
                StaticObject.publishSendStatus(msg, BluetoothSendStatus.FAILED, invalidPayload);
            }
        }, listenerUuid);

        StaticObject.connectionRegistry.set(address, BluetoothConnectionState.CONNECTED);
        connectedAtMillis = System.currentTimeMillis();

        frameDecoder = new StreamFrameDecoder(frameConfig == null
                ? BluetoothFrameConfig.defaultConfig() : frameConfig);
        if (frameConfig != null && frameConfig.getMode() == BluetoothFrameMode.RAW) {
            streamingTextDecoder = new BluetoothEncodingUtils.StreamingTextDecoder(textEncoding);
        }
        startFrameTimeoutMonitor();
        receiveThread = new Thread(this::receiveLoop, "bt-receive-" + address);
        receiveThread.start();
        return true;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1024];
        StreamFrameDecoder decoder = frameDecoder;
        try {
            int read;
            while (!closed.get() && (read = bufferedInputStream.read(buffer)) != -1) {
                bytesReceivedCount.addAndGet(read);
                // Both the reader and timeout worker publish under this lock: a
                // timeout frame must not be overtaken by a later socket read.
                synchronized (framePublicationLock) {
                    for (byte[] frame : decoder.append(buffer, read)) publishReceivedFrame(frame);
                    reportDroppedFrames(decoder);
                }
            }
            if (!closed.get()) {
                setCurrentSessionError(BluetoothConnectionErrorCode.CONNECTION_CLOSED);
                notifyDisconnect("remote_closed");
            }
        } catch (IOException e) {
            if (!closed.get()) {
                errorCount.incrementAndGet();
                setCurrentSessionError(BluetoothConnectionErrorCode.classifyRead(e));
                Log.e(TAG, "Receive failed for " + bluetoothAdd, e);
                notifyDisconnect("read_failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeInternal();
        }
    }

    private void notifyDisconnect(String reason) {
        if (bluetoothAdd == null || !StaticObject.foregroundGate.isCurrent(foregroundGeneration) || closed.get()
                || StaticObject.bluetoothSocketMap.get(bluetoothAdd) != this
                || !disconnectNotified.compareAndSet(false, true)) {
            return;
        }
        closeReason = normalizeCloseReason(reason);
        if (pluginOwned) return;
        BluetoothTelemetry.logDisconnect(closeReason,
                BluetoothTelemetryPolicy.elapsedMillis(
                        connectedAtMillis, System.currentTimeMillis()));
        if (!pluginOwned) StaticObject.reconnectManager.onUnexpectedDisconnect(bluetoothAdd, foregroundGeneration, sessionId);
        StaticObject.foregroundGate.runIfCurrent(foregroundGeneration, () -> {
            if (StaticObject.bluetoothSocketMap.get(bluetoothAdd) == this && !closed.get()) {
                Msg msg = new Msg(bluetoothAdd);
                msg.setSessionId(sessionId);
                msg.setSendUuid(sendUuid);
                msg.setBluetoothName(bluetoothName);
                msg.setForegroundGeneration(foregroundGeneration);
                msg.setStateType(1);
                StaticObject.submitMessage(msg);
            }
        });
    }

    private void setCurrentSessionError(BluetoothConnectionErrorCode error) {
        StaticObject.foregroundGate.runIfCurrent(foregroundGeneration, () -> {
            if (!closed.get() && StaticObject.bluetoothSocketMap.get(bluetoothAdd) == this) {
                StaticObject.connectionRegistry.setError(bluetoothAdd, error);
            }
        });
    }

    public boolean isPluginOwned() { return pluginOwned; }

    public boolean isConnected() {
        return !closed.get() && bluetoothSocket != null && bluetoothSocket.isConnected();
    }

    public boolean isSessionOpen() { return !closed.get(); }

    /** Returns true when this wrapper rejected a socket because another session owns the MAC. */
    public boolean isDuplicateSession() {
        return duplicateSession;
    }

    public String getBluetoothName() {
        return bluetoothName;
    }

    public String getSendUuid() {
        return sendUuid;
    }

    public String getSessionId() { return sessionId; }

    /** Attach actual transport metadata, rejecting work captured for an old session. */
    public boolean bindOutboundMessage(Msg message) {
        if (message == null || closed.get() || !message.isForegroundGenerationCurrent()
                || message.getForegroundGeneration() != foregroundGeneration
                || !StaticObject.foregroundGate.isCurrent(foregroundGeneration)
                || (message.getSessionId() != null && !sessionId.equals(message.getSessionId()))) return false;
        message.setBluetoothAdd(bluetoothAdd);
        message.setBluetoothName(bluetoothName);
        message.setSendUuid(sendUuid);
        message.setSessionId(sessionId);
        message.setTextEncoding(textEncoding);
        if (!message.hasPayload()) message.setPayload(encodeTextPayload(message.getContent()));
        return true;
    }

    public long getConnectedAtMillis() {
        return connectedAtMillis;
    }

    public BluetoothFrameConfig getFrameConfig() {
        return frameConfig;
    }

    public BluetoothTextEncoding getTextEncoding() {
        return textEncoding;
    }

    /** Encodes a text command using this device session's saved protocol settings. */
    public byte[] encodeTextPayload(String text) {
        return BluetoothTextFrameEncoder.encode(text, frameConfig, textEncoding);
    }

    public void setSendProgressListener(SendProgressListener listener) {
        sendProgressListener = listener;
    }

    public int cancelSegmentedSends() {
        return sendQueue == null ? 0 : sendQueue.cancelSegmented();
    }

    public int getPendingSendCount() { return sendQueue == null ? 0 : sendQueue.getPendingCount(); }
    public long getPendingSendBytes() { return sendQueue == null ? 0L : sendQueue.getPendingBytes(); }
    public boolean canAcceptSend(int payloadBytes) {
        return !closed.get() && sendQueue != null && sendQueue.canAccept(payloadBytes);
    }

    public BluetoothSendStatus getLastSendStatus() {
        return lastSendStatus;
    }

    public String getLastSendError() {
        return lastSendError;
    }

    public long getQueuedSendCount() {
        return queuedSendCount.get();
    }

    public long getSuccessfulSendCount() {
        return successfulSendCount.get();
    }

    public long getFailedSendCount() {
        return failedSendCount.get();
    }

    public long getCanceledSendCount() {
        return canceledSendCount.get();
    }

    public long getBytesSentCount() {
        return bytesSentCount.get();
    }

    public long getBytesReceivedCount() {
        return bytesReceivedCount.get();
    }

    public long getReceivedFrameCount() {
        return receivedFrameCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public void close() {
        if (!pluginOwned && bluetoothAdd != null) {
            StaticObject.reconnectManager.disable(bluetoothAdd);
        }
        closeReason = "user_requested";
        closeInternal();
    }

    /**
     * Closes the transport for a process or Bluetooth lifecycle transition while
     * preserving the user's per-device automatic reconnect preference.
     */
    public void closeForLifecycle() {
        closeReason = "lifecycle";
        closeInternal();
    }

    public void closeAfterUnexpectedDisconnect() {
        notifyDisconnect("acl_disconnected");
        closeInternal();
    }

    private void closeInternal() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (connectedAtMillis > 0L) {
            BluetoothTelemetry.logSessionSummary("spp",
                    BluetoothTelemetryPolicy.elapsedMillis(
                            connectedAtMillis, System.currentTimeMillis()),
                    bytesSentCount.get(), bytesReceivedCount.get(),
                    successfulSendCount.get(), receivedFrameCount.get(),
                    failedSendCount.get(), errorCount.get(), closeReason);
        }
        if (listenerUuid != null) {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerUuid);
        }
        if (sendQueue != null) {
            sendQueue.close();
        }
        BluetoothTransportCloser.close(bluetoothSocket, bufferedOutputStream, bufferedInputStream);
        if (frameTimeoutExecutor != null) {
            frameTimeoutExecutor.shutdownNow();
            frameTimeoutExecutor = null;
        }
        if (bluetoothAdd != null) {
            BluetoothSessionOwners.release("classic", bluetoothAdd, this);
            synchronized (StaticObject.foregroundGate) {
                boolean removed = StaticObject.bluetoothSocketMap.remove(bluetoothAdd, this);
                if (removed && StaticObject.connectionRegistry.get(bluetoothAdd)
                        != BluetoothConnectionState.RECONNECTING) {
                    StaticObject.connectionRegistry.set(bluetoothAdd, BluetoothConnectionState.DISCONNECTED);
                }
            }
        }
        PluginListener observer = pluginListener;
        if (observer != null) observer.onClosed();
        if (receiveThread != null && receiveThread != Thread.currentThread()) {
            receiveThread.interrupt();
        }
    }

    private void writePayload(byte[] payload) throws IOException {
        if (!StaticObject.foregroundGate.isCurrent(foregroundGeneration)
                || closed.get() || bufferedOutputStream == null) {
            throw new IOException("Bluetooth session is closed");
        }
        bufferedOutputStream.write(payload);
        bufferedOutputStream.flush();
        bytesSentCount.addAndGet(payload == null ? 0 : payload.length);
    }

    private void publishReceivedFrame(byte[] frame) throws InterruptedException {
        receivedFrameCount.incrementAndGet();
        if (pluginOwned) {
            PluginListener observer = pluginListener;
            if (observer != null) observer.onData(frame.clone());
            return;
        }
        Msg msg = new Msg(frame, Msg.TYPE_RECEIVED, bluetoothAdd);
        msg.setSessionId(sessionId);
        msg.setTextEncoding(textEncoding);
        msg.setForegroundGeneration(foregroundGeneration);
        msg.setContent(streamingTextDecoder == null ? BluetoothEncodingUtils.decode(frame, textEncoding)
                : streamingTextDecoder.decode(frame));
        msg.setSendUuid(sendUuid);
        msg.setBluetoothName(bluetoothName);
        StaticObject.submitReceivedMessage(msg);
    }

    private byte[] payloadForSend(Msg msg) {
        byte[] rawPayload = msg.getPayload();
        if (rawPayload != null) {
            return rawPayload;
        }
        return BluetoothTextFrameEncoder.encode(msg.getContent(), frameConfig, textEncoding);
    }

    private void startFrameTimeoutMonitor() {
        long timeoutMillis = frameConfig == null ? 0L : frameConfig.getTimeoutMillis();
        if (timeoutMillis <= 0) {
            return;
        }
        long periodMillis = Math.max(20L, Math.min(100L, timeoutMillis / 4L));
        frameTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bt-frame-timeout-" + bluetoothAdd);
            thread.setDaemon(true);
            return thread;
        });
        frameTimeoutExecutor.scheduleWithFixedDelay(() -> {
            if (closed.get() || frameDecoder == null) {
                return;
            }
            try {
                synchronized (framePublicationLock) {
                    if (closed.get()) return;
                    for (byte[] frame : frameDecoder.pollTimeout(StreamFrameDecoder.monotonicMillis())) {
                        publishReceivedFrame(frame);
                    }
                    reportDroppedFrames(frameDecoder);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void reportDroppedFrames(StreamFrameDecoder decoder) {
        int dropped = decoder.getDroppedFrameCount();
        int reported = reportedDroppedFrames.get();
        if (dropped <= reported || !reportedDroppedFrames.compareAndSet(reported, dropped)) {
            return;
        }
        setCurrentSessionError(BluetoothConnectionErrorCode.FRAME_TOO_LARGE);
        errorCount.incrementAndGet();
        Log.w(TAG, "Dropped oversized frame for " + bluetoothAdd + ", total=" + dropped);
    }

    private void onSendStatus(long requestId, BluetoothSendStatus status, int pendingCount,
                              Throwable error) {
        lastSendStatus = status;
        PluginListener observer = pluginListener;
        if (observer != null) observer.onSend(requestId, status, error);
        Msg message = pendingMessages.get(requestId);
        if (message != null) {
            StaticObject.publishSendStatus(message, status, error);
            if (status.isTerminal()) pendingMessages.remove(requestId, message);
        }
        SendProgressListener listener = sendProgressListener;
        if (listener != null && (message == null || message.getSendStatus() == status)) {
            try {
                listener.onStatus(status, requestId);
            } catch (RuntimeException ignored) {
                // UI observers must not break the connection writer.
            }
        }
        if (status == BluetoothSendStatus.QUEUED) {
            queuedSendCount.incrementAndGet();
        } else if (status == BluetoothSendStatus.SENT) {
            successfulSendCount.incrementAndGet();
        } else if (status == BluetoothSendStatus.FAILED) {
            failedSendCount.incrementAndGet();
            errorCount.incrementAndGet();
            lastSendError = error == null ? null : error.getMessage();
            if (error instanceof BluetoothQueueFullException) {
                Log.w(TAG, "Send admission rejected for " + bluetoothAdd);
                return;
            }
            setCurrentSessionError(BluetoothConnectionErrorCode.classifyWrite(error));
            Log.e(TAG, "Failed to send to " + bluetoothAdd + ", request=" + requestId, error);
            notifyDisconnect("write_failed");
            closeInternal();
        } else if (status == BluetoothSendStatus.CANCELED) {
            canceledSendCount.incrementAndGet();
        }
    }

    private void onSendProgress(long requestId, int sentBytes, int totalBytes) {
        SendProgressListener listener = sendProgressListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(requestId, sentBytes, totalBytes);
        } catch (RuntimeException ignored) {
            // UI observers must not break the connection writer.
        }
    }

    private static String normalizeCloseReason(String reason) {
        return reason == null || reason.trim().isEmpty() ? "unknown" : reason;
    }
}
