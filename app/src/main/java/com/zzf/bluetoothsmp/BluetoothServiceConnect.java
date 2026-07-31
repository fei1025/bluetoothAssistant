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

public class BluetoothServiceConnect {
    private static final String TAG = "BluetoothSession";

    public interface SendProgressListener {
        void onStatus(BluetoothSendStatus status, long requestId);

        void onProgress(long requestId, int sentBytes, int totalBytes);
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
        if (socket == null) {
            return false;
        }

        duplicateSession = false;

        bluetoothSocket = socket;
        this.sendUuid = sendUuid;
        String address = null;

        try {
            BluetoothDevice remoteDevice = socket.getRemoteDevice();
            address = remoteDevice.getAddress();
            listenerUuid = UUID.randomUUID().toString();
            bluetoothAdd = address;
            bluetoothName = remoteDevice.getName();
            frameConfig = BluetoothProtocolConfigStore.get(context, address);
            textEncoding = BluetoothTextEncodingStore.get(context, address);
            bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
            bufferedInputStream = new BufferedInputStream(socket.getInputStream());
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
                closeQuietly(bufferedOutputStream);
                closeQuietly(bufferedInputStream);
                closeSocketQuietly(socket);
                closed.set(true);
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
            StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, event -> {
                Msg msg = (Msg) event.getEventData()[0];
                if (!bluetoothAdd.equals(msg.getBluetoothAdd()) || closed.get()) {
                    return;
                }
                byte[] payload = payloadForSend(msg);
                if (msg.getSegmentSize() > 0) {
                    sendQueue.enqueueSegmented(payload, msg.getSegmentSize(),
                            Math.max(0L, msg.getSegmentIntervalMillis()));
                } else {
                    sendQueue.enqueue(payload);
                }
            }, listenerUuid);

            StaticObject.connectionRegistry.set(address, BluetoothConnectionState.CONNECTED);
            connectedAtMillis = System.currentTimeMillis();

            startFrameTimeoutMonitor();
            receiveThread = new Thread(this::receiveLoop, "bt-receive-" + address);
            receiveThread.start();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to initialize session "
                    + (address == null ? "unknown" : address), e);
            closeInternal();
            return false;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1024];
        StreamFrameDecoder decoder = new StreamFrameDecoder(frameConfig == null
                ? BluetoothFrameConfig.defaultConfig() : frameConfig);
        frameDecoder = decoder;
        try {
            int read;
            while (!closed.get() && (read = bufferedInputStream.read(buffer)) != -1) {
                bytesReceivedCount.addAndGet(read);
                for (byte[] frame : decoder.append(buffer, read)) {
                    publishReceivedFrame(frame);
                }
                for (byte[] frame : decoder.pollTimeout(System.currentTimeMillis())) {
                    publishReceivedFrame(frame);
                }
                reportDroppedFrames(decoder);
            }
            if (!closed.get()) {
                StaticObject.connectionRegistry.setError(bluetoothAdd,
                        BluetoothConnectionErrorCode.CONNECTION_CLOSED);
                notifyDisconnect();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                errorCount.incrementAndGet();
                StaticObject.connectionRegistry.setError(bluetoothAdd,
                        BluetoothConnectionErrorCode.classifyRead(e));
                Log.e(TAG, "Receive failed for " + bluetoothAdd, e);
                notifyDisconnect();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeInternal();
        }
    }

    private void notifyDisconnect() {
        if (!disconnectNotified.compareAndSet(false, true) || bluetoothAdd == null) {
            return;
        }
        BluetoothTelemetry.logDisconnect();
        StaticObject.reconnectManager.onUnexpectedDisconnect(bluetoothAdd);
        Msg msg = new Msg(bluetoothAdd);
        msg.setStateType(1);
        StaticObject.mTaskQueue.offer(msg);
    }

    public boolean isConnected() {
        return !closed.get() && bluetoothSocket != null && bluetoothSocket.isConnected();
    }

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
        if (bluetoothAdd != null) {
            StaticObject.reconnectManager.disable(bluetoothAdd);
        }
        closeInternal();
    }

    /**
     * Closes the transport for a process or Bluetooth lifecycle transition while
     * preserving the user's per-device automatic reconnect preference.
     */
    public void closeForLifecycle() {
        closeInternal();
    }

    public void closeAfterUnexpectedDisconnect() {
        notifyDisconnect();
        closeInternal();
    }

    private void closeInternal() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        BluetoothTelemetry.logSessionSummary("spp", bytesSentCount.get(),
                bytesReceivedCount.get(), successfulSendCount.get(), receivedFrameCount.get());
        if (listenerUuid != null) {
            StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerUuid);
        }
        if (sendQueue != null) {
            sendQueue.close();
        }
        if (frameTimeoutExecutor != null) {
            frameTimeoutExecutor.shutdownNow();
            frameTimeoutExecutor = null;
        }
        if (bluetoothAdd != null) {
            boolean removed = StaticObject.bluetoothSocketMap.remove(bluetoothAdd, this);
            if (removed && StaticObject.connectionRegistry.get(bluetoothAdd)
                    != BluetoothConnectionState.RECONNECTING) {
                StaticObject.connectionRegistry.set(bluetoothAdd, BluetoothConnectionState.DISCONNECTED);
            }
        }
        closeQuietly(bufferedOutputStream);
        closeQuietly(bufferedInputStream);
        closeSocketQuietly(bluetoothSocket);
        if (receiveThread != null && receiveThread != Thread.currentThread()) {
            receiveThread.interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocketQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void writePayload(byte[] payload) throws IOException {
        if (closed.get() || bufferedOutputStream == null) {
            throw new IOException("Bluetooth session is closed");
        }
        bufferedOutputStream.write(payload);
        bufferedOutputStream.flush();
        bytesSentCount.addAndGet(payload == null ? 0 : payload.length);
    }

    private void publishReceivedFrame(byte[] frame) throws InterruptedException {
        receivedFrameCount.incrementAndGet();
        Msg msg = new Msg(frame, Msg.TYPE_RECEIVED, bluetoothAdd);
        msg.setContent(BluetoothEncodingUtils.decode(frame, textEncoding));
        msg.setSendUuid(sendUuid);
        msg.setBluetoothName(bluetoothName);
        StaticObject.mTaskQueue.put(msg);
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
                for (byte[] frame : frameDecoder.pollTimeout(System.currentTimeMillis())) {
                    publishReceivedFrame(frame);
                }
                reportDroppedFrames(frameDecoder);
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
        StaticObject.connectionRegistry.setError(bluetoothAdd,
                BluetoothConnectionErrorCode.FRAME_TOO_LARGE);
        errorCount.incrementAndGet();
        Log.w(TAG, "Dropped oversized frame for " + bluetoothAdd + ", total=" + dropped);
    }

    private void onSendStatus(long requestId, BluetoothSendStatus status, int pendingCount,
                              Throwable error) {
        lastSendStatus = status;
        SendProgressListener listener = sendProgressListener;
        if (listener != null) {
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
            StaticObject.connectionRegistry.setError(bluetoothAdd,
                    BluetoothConnectionErrorCode.classifyWrite(error));
            Log.e(TAG, "Failed to send to " + bluetoothAdd + ", request=" + requestId, error);
            notifyDisconnect();
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
}
