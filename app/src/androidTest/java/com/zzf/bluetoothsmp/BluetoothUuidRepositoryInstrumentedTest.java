package com.zzf.bluetoothsmp;

import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.utils.BluetoothUuidRepository;
import com.zzf.bluetoothsmp.utils.HistoryRepository;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;
import org.litepal.LitePalDB;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BluetoothUuidRepositoryInstrumentedTest {
    private static final String SERVER = "00001102-0000-1000-8000-00805F9B34FB";
    private static final String CLIENT = "00001103-0000-1000-8000-00805F9B34FB";

    @Test(timeout = 15000) public void asyncCallbacksUseMainAndUpdatesPreserveOtherUuid() throws Exception {
        fixture(() -> {
            Result defaults = load(); assertSuccess(defaults);
            assertEquals(BluetoothObject.SPP_UUID, defaults.value.getServerUuid());
            assertEquals(BluetoothObject.SPP_UUID, defaults.value.getClientUuid());
            Result server = update(SERVER, true); assertSuccess(server);
            Result client = update(CLIENT, false); assertSuccess(client);
            assertEquals(SERVER, client.value.getServerUuid()); assertEquals(CLIENT, client.value.getClientUuid());
            SystemInfoMapper row = LitePal.findFirst(SystemInfoMapper.class);
            assertEquals(SERVER, row.getServiceSpp()); assertEquals(CLIENT, row.getClientSpp());
        });
    }
    @Test(timeout = 15000) public void failedTransactionRollsBackAndCannotPublishCache() throws Exception {
        fixture(() -> {
            assertSuccess(update(SERVER, true));
            faultField().set(null, (Runnable) () -> { throw new IllegalStateException("Fixture commit failure"); });
            Result failed = update(CLIENT, true);
            assertNotNull(failed.error); assertTrue(failed.onMain);
            faultField().set(null, null);
            assertEquals(SERVER, BluetoothUuidRepository.loadForTransport().getServerUuid());
            Result persisted = load(); assertSuccess(persisted); assertEquals(SERVER, persisted.value.getServerUuid());
        });
    }
    @Test(timeout = 15000) public void invalidUuidDoesNotWriteOrChangeEffectiveConfig() throws Exception {
        fixture(() -> {
            assertSuccess(update(SERVER, true));
            Result failed = update("invalid", true); assertNotNull(failed.error);
            assertEquals(SERVER, BluetoothUuidRepository.loadForTransport().getServerUuid());
            assertEquals(SERVER, LitePal.findFirst(SystemInfoMapper.class).getServiceSpp());
        });
    }
    @Test(timeout = 15000) public void fullSettingsQueueRejectsImmediatelyOnMainWithoutUnboundedAdmission() throws Exception {
        fixture(() -> {
            Field field = BluetoothUuidRepository.class.getDeclaredField("IO"); field.setAccessible(true);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(null);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            List<Result> accepted = new ArrayList<>();
            try {
                executor.execute(() -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 32; i++) { Result result = new Result(); accepted.add(result); BluetoothUuidRepository.load(result); }
                assertEquals(32, executor.getQueue().size());
                Result rejected = load(); assertNotNull(rejected.error); assertTrue(rejected.onMain);
                assertEquals(32, executor.getQueue().size());
            } finally { release.countDown(); }
            for (Result result : accepted) { result.await(); assertSuccess(result); }
        });
    }
    private interface Fixture { void run() throws Exception; }
    private static void fixture(Fixture fixture) throws Exception {
        assertTrue(HistoryRepository.awaitWrites(5000));
        Field lock = HistoryRepository.class.getDeclaredField("DB_LOCK"); lock.setAccessible(true);
        String name = "uuid_fixture_" + UUID.randomUUID().toString().replace("-", "");
        synchronized (lock.get(null)) {
            try {
                LitePalDB configuration = new LitePalDB(name, 1);
                configuration.addClassName(SystemInfoMapper.class.getName());
                LitePal.use(configuration);
                assertSuccess(load());
                fixture.run();
            } finally {
                faultField().set(null, null);
                LitePal.useDefault();
                assertSuccess(load()); // Restore the transport cache as well as the database.
                LitePal.deleteDatabase(name);
            }
        }
    }
    private static Result load() throws Exception {
        Result result = new Result(); BluetoothUuidRepository.load(result); result.await(); return result;
    }
    private static Result update(String uuid, boolean server) throws Exception {
        Result result = new Result();
        if (server) BluetoothUuidRepository.updateServer(uuid, result); else BluetoothUuidRepository.updateClient(uuid, result);
        result.await(); return result;
    }
    private static void assertSuccess(Result result) { assertNull(result.error); assertNotNull(result.value); assertTrue(result.onMain); }
    private static Field faultField() throws Exception {
        Field field = BluetoothUuidRepository.class.getDeclaredField("transactionFaultForTests"); field.setAccessible(true); return field;
    }
    private static final class Result implements BluetoothUuidRepository.Callback<BluetoothUuidRepository.Config> {
        final CountDownLatch done = new CountDownLatch(1);
        BluetoothUuidRepository.Config value; Throwable error; boolean onMain;
        public void onSuccess(BluetoothUuidRepository.Config value) { this.value = value; finish(); }
        public void onError(Throwable error) { this.error = error; finish(); }
        private void finish() { onMain = Looper.myLooper() == Looper.getMainLooper(); done.countDown(); }
        void await() throws Exception { assertTrue("UUID callback did not complete", done.await(5, TimeUnit.SECONDS)); }
    }
}
