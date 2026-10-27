package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.CommandMacroEntity;

import org.litepal.LitePal;

import java.util.Collections;
import java.util.List;

public final class CommandMacroStore {
    public static void importForAddress(String address, List<CommandMacroEntity> imported) {
        String target = com.zzf.bluetoothsmp.utils.BluetoothAddressUtils.normalize(address);
        if (target == null) throw new IllegalArgumentException("Invalid import target");
        // Validate the whole bounded transfer before opening a write transaction.
        MacroTransferCodec.exportMacros(imported);
        for (CommandMacroEntity item : imported) MacroParser.parse(item.getScript());
        android.database.sqlite.SQLiteDatabase database = LitePal.getDatabase();
        database.beginTransaction();
        try {
            for (CommandMacroEntity item : imported) {
                String name = item.getName().trim();
                CommandMacroEntity existing = findByName(target, name);
                if (existing == null) { existing = new CommandMacroEntity(); existing.setBluetoothAddress(target); }
                existing.setName(name); existing.setScript(item.getScript());
                existing.setRepeatCount(item.getRepeatCount());
                long now = System.currentTimeMillis();
                if (existing.getCreatedAt() <= 0) existing.setCreatedAt(now);
                existing.setUpdatedAt(now);
                if (!existing.save()) throw new IllegalStateException("Could not persist imported macro");
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }
    private CommandMacroStore() {
    }

    public static List<CommandMacroEntity> findForAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return LitePal.where("bluetoothAddress = ?", address.trim().toUpperCase(
                java.util.Locale.ROOT)).order("updatedAt desc, name asc")
                .find(CommandMacroEntity.class);
    }

    public static CommandMacroEntity findByName(String address, String name) {
        if (address == null || name == null) {
            return null;
        }
        return LitePal.where("bluetoothAddress = ? and name = ?",
                address.trim().toUpperCase(java.util.Locale.ROOT), name.trim())
                .findFirst(CommandMacroEntity.class);
    }

    public static void save(CommandMacroEntity macro) {
        if (macro == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (macro.getCreatedAt() <= 0) {
            macro.setCreatedAt(now);
        }
        macro.setUpdatedAt(now);
        if (!macro.save()) throw new IllegalStateException("Could not persist macro");
    }

    /** Run on a storage worker. Serialize the lookup and upsert with concurrent imports. */
    public static void saveForAddress(String address, String name, String script, int repeat) {
        String target = com.zzf.bluetoothsmp.utils.BluetoothAddressUtils.normalize(address);
        if (target == null || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid macro target or name");
        }
        android.database.sqlite.SQLiteDatabase database = LitePal.getDatabase();
        database.beginTransaction();
        try {
            CommandMacroEntity macro = findByName(target, name);
            if (macro == null) {
                macro = new CommandMacroEntity();
                macro.setBluetoothAddress(target);
            }
            macro.setName(name.trim());
            macro.setScript(script);
            macro.setRepeatCount(repeat);
            save(macro);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }
}
