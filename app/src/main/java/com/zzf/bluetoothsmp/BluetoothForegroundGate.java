package com.zzf.bluetoothsmp;

/** App-wide foreground ownership. Publication and invalidation share this lock. */
public final class BluetoothForegroundGate {
    // Kept independent of Android so messages can capture their epoch without
    // initializing the Bluetooth runtime (and remain usable in JVM tests).
    public static final BluetoothForegroundGate APP = new BluetoothForegroundGate();
    private int startedActivities;
    private boolean active;
    private long generation;

    public synchronized void activityStarted(boolean screenAvailable) {
        startedActivities++;
        if (screenAvailable) {
            activate();
        }
    }

    public synchronized void activityStopped(boolean changingConfigurations) {
        startedActivities = Math.max(0, startedActivities - 1);
        // A replacement Activity owns the same foreground interval during rotation
        // or an in-app language change, even when the count briefly reaches zero.
        if (startedActivities == 0 && !changingConfigurations) {
            deactivate();
        }
    }

    public synchronized void screenUnavailable() {
        deactivate();
    }

    public synchronized void screenAvailable() {
        if (startedActivities > 0) {
            activate();
        }
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized long currentGeneration() {
        return active ? generation : -1L;
    }

    public synchronized boolean isCurrent(long token) {
        return active && token == generation;
    }

    /** The action must only publish initialized resources, never block on socket I/O. */
    public synchronized boolean runIfCurrent(long token, Runnable action) {
        if (!isCurrent(token)) {
            return false;
        }
        action.run();
        return true;
    }

    private void activate() {
        if (!active) {
            active = true;
            generation++;
        }
    }

    private void deactivate() {
        if (active) {
            active = false;
            generation++;
        }
    }
}
