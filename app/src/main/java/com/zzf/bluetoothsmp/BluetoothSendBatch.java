package com.zzf.bluetoothsmp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cancellation follows a batch through both the message bus and socket send queues. */
public final class BluetoothSendBatch {
    private volatile boolean canceled;
    private final Set<Runnable> cancellations = new HashSet<>();

    public boolean isCanceled() {
        return canceled;
    }

    public void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (canceled) {
                return;
            }
            canceled = true;
            pending = new ArrayList<>(cancellations);
            cancellations.clear();
        }
        // Never hold the batch lock while entering a send queue or notifying observers.
        for (Runnable cancellation : pending) {
            cancellation.run();
        }
    }

    synchronized boolean register(Runnable cancellation) {
        if (canceled) {
            return false;
        }
        cancellations.add(cancellation);
        return true;
    }

    synchronized void unregister(Runnable cancellation) {
        cancellations.remove(cancellation);
    }
}
