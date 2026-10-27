package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothTransportCloserTest {
    @Test
    public void socketCloseReleasesBlockedWriterBeforeClosingBufferedStream() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        BufferedOutputStream buffered = new BufferedOutputStream(transport);
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            buffered.write(payload);
            buffered.flush();
        }, null);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicBoolean inputClosed = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            queue.close();
            BluetoothTransportCloser.close(transport, buffered, () -> inputClosed.set(true));
            closeFinished.countDown();
        });
        closer.setDaemon(true);
        try {
            queue.enqueue(new byte[32]);
            assertTrue(transport.writeEntered.await(2, TimeUnit.SECONDS));
            closer.start();
            assertTrue("Closing must not wait for a blocked buffered writer",
                    closeFinished.await(2, TimeUnit.SECONDS));
            closer.join(2000);
            assertTrue(transport.closed.get());
            assertTrue(inputClosed.get());
            assertFalse(closer.isAlive());
        } finally {
            transport.close();
            queue.close();
            closer.join(2000);
        }
    }

    private static final class BlockingTransport extends OutputStream {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releasedByClose = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeEntered.countDown();
            while (true) {
                try {
                    releasedByClose.await();
                    break;
                } catch (InterruptedException ignored) {
                    // Socket I/O is not guaranteed to respond to Future.cancel(true).
                }
            }
            throw new IOException("socket closed");
        }

        @Override
        public void close() {
            closed.set(true);
            releasedByClose.countDown();
        }
    }
}
