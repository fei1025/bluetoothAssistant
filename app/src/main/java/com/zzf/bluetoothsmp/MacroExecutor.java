package com.zzf.bluetoothsmp;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MacroExecutor implements AutoCloseable {
    public interface Sender {
        void send(MacroStep step) throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bluetooth-macro");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> running;

    public synchronized void execute(List<MacroStep> steps, int repeatCount, Sender sender) {
        if (steps == null || steps.isEmpty() || sender == null) {
            throw new IllegalArgumentException("macro steps and sender are required");
        }
        int repeats = Math.max(1, Math.min(repeatCount, 100));
        cancel();
        running = executor.submit(() -> {
            try {
                for (int repeat = 0; repeat < repeats; repeat++) {
                    for (MacroStep step : steps) {
                        if (step.getType() == MacroStep.Type.DELAY) {
                            Thread.sleep(step.getDelayMillis());
                        } else {
                            sender.send(step);
                        }
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The connection layer records write failures; cancellation is local.
            }
        });
    }

    public synchronized void cancel() {
        if (running != null) {
            running.cancel(true);
            running = null;
        }
    }

    @Override
    public synchronized void close() {
        cancel();
        executor.shutdownNow();
    }
}
