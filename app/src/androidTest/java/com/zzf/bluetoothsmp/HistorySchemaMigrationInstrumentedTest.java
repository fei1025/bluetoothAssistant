package com.zzf.bluetoothsmp;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.utils.HistoryRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;
import org.litepal.LitePalDB;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Runs the actual LitePal legacy upgrade on an isolated database. */
@RunWith(AndroidJUnit4.class)
public class HistorySchemaMigrationInstrumentedTest {
    @Test public void versionFourUpgradePreservesLegacyIdsTextDatesAndNullFields() throws Exception {
        verifyUpgrade(4);
    }
    @Test public void versionFiveUpgradeAddsLosslessByteStorageWithoutDeletingText() throws Exception {
        verifyUpgrade(5);
    }
    private void verifyUpgrade(int fromVersion) throws Exception {
        assertTrue(HistoryRepository.awaitWrites(10000));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        File file = context.getDatabasePath(name + ".db");
        file.getParentFile().mkdirs();
        try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            old.execSQL("CREATE TABLE messagemapper (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "receiveadd TEXT, receivetime INTEGER, receivename TEXT, senduuid TEXT, "
                    + "sendtime INTEGER, sendadd TEXT, sendname TEXT, type INTEGER, savedate INTEGER, message TEXT)");
            old.execSQL("INSERT INTO messagemapper(id,senduuid,sendtime,sendadd,type,message) "
                    + "VALUES (42,'legacy-service',123456789,'02:00:00:00:EE:FF',0,'preserved text')");
            old.execSQL("CREATE TABLE table_schema (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, type INTEGER)");
            old.execSQL("INSERT INTO table_schema(name,type) VALUES('messagemapper',0)");
            old.setVersion(fromVersion);
        }
        // Database switching is limited to this test and serialized against background history IO.
        Field lockField = HistoryRepository.class.getDeclaredField("DB_LOCK");
        lockField.setAccessible(true);
        synchronized (lockField.get(null)) {
            try {
                LitePalDB configuration = new LitePalDB(name, 6);
                configuration.addClassName(MessageMapper.class.getName());
                LitePal.use(configuration);
                SQLiteDatabase upgraded = LitePal.getDatabase();
                assertEquals(6, upgraded.getVersion());
                List<MessageMapper> rows = LitePal.findAll(MessageMapper.class);
                assertEquals(1, rows.size()); assertEquals(42, rows.get(0).getId());
                assertEquals("preserved text", rows.get(0).getMessage());
                assertEquals(123456789L, rows.get(0).getSendTime().getTime());
                assertNull(rows.get(0).getPayload()); assertNull(rows.get(0).getSendStatus());
                try (Cursor fields = upgraded.rawQuery("PRAGMA table_info(messagemapper)", null)) {
                    java.util.Set<String> columns = new java.util.HashSet<>();
                    while (fields.moveToNext()) columns.add(fields.getString(1));
                    assertTrue(columns.contains("payloadbase64")); assertTrue(columns.contains("messageid"));
                    assertTrue(columns.contains("sendstatus")); assertTrue(columns.contains("textencoding"));
                }
            } finally {
                LitePal.useDefault();
                LitePal.deleteDatabase(name);
            }
        }
    }
}
