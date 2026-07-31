package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.CommandMacroEntity;

import org.litepal.LitePal;

import java.util.Collections;
import java.util.List;

public final class CommandMacroStore {
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
        macro.save();
    }
}
