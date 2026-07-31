package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.CommandMacroEntity;
import com.zzf.bluetoothsmp.utils.HexUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small, dependency-free transfer format for macros. It never includes message history. */
public final class MacroTransferCodec {
    private static final String HEADER = "SPP_MACRO_EXPORT_V1";
    private static final int MAX_MACROS = 100;
    private static final int MAX_TEXT_BYTES = 256 * 1024;

    private MacroTransferCodec() {
    }

    public static String exportMacros(List<CommandMacroEntity> macros) {
        if (macros == null || macros.isEmpty()) {
            throw new IllegalArgumentException("macros must not be empty");
        }
        if (macros.size() > MAX_MACROS) {
            throw new IllegalArgumentException("too many macros");
        }
        StringBuilder output = new StringBuilder(HEADER).append('\n');
        for (CommandMacroEntity macro : macros) {
            if (macro == null) {
                throw new IllegalArgumentException("macro must not be null");
            }
            String name = requireName(macro.getName());
            String script = requireScript(macro.getScript());
            int repeat = requireRepeat(macro.getRepeatCount());
            output.append(utf8Hex(name)).append('\t')
                    .append(repeat).append('\t')
                    .append(utf8Hex(script)).append('\n');
        }
        if (output.length() > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("export is too large");
        }
        return output.toString();
    }

    public static List<CommandMacroEntity> importMacros(String content) {
        if (content == null || content.length() == 0
                || content.length() > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid export content");
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            throw new IllegalArgumentException("unsupported macro export format");
        }
        List<CommandMacroEntity> result = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (result.size() >= MAX_MACROS) {
                throw invalidLine(index, "too many macros");
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 || !HexUtils.isValidHex(fields[0])
                    || !HexUtils.isValidHex(fields[2])) {
                throw invalidLine(index, "expected name, repeat and script");
            }
            String name = utf8String(fields[0]);
            String script = utf8String(fields[2]);
            int repeat;
            try {
                repeat = Integer.parseInt(fields[1]);
            } catch (NumberFormatException error) {
                throw invalidLine(index, "repeat must be a number");
            }
            try {
                requireName(name);
                requireScript(script);
                requireRepeat(repeat);
                MacroParser.parse(script);
            } catch (IllegalArgumentException error) {
                throw invalidLine(index, error.getMessage());
            }
            CommandMacroEntity macro = new CommandMacroEntity();
            macro.setName(name);
            macro.setScript(script);
            macro.setRepeatCount(repeat);
            result.add(macro);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("macro export has no entries");
        }
        return result;
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        return name.trim();
    }

    private static String requireScript(String script) {
        if (script == null || script.trim().isEmpty()) {
            throw new IllegalArgumentException("script must not be empty");
        }
        return script;
    }

    private static int requireRepeat(int repeat) {
        if (repeat < 1 || repeat > 100) {
            throw new IllegalArgumentException("repeat must be 1..100");
        }
        return repeat;
    }

    private static String utf8Hex(String value) {
        return HexUtils.bytesToHexString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String utf8String(String hex) {
        byte[] bytes = HexUtils.hexStringToBytes(hex);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalidLine(int index, String message) {
        return new IllegalArgumentException("line " + (index + 1) + ": " + message);
    }
}
