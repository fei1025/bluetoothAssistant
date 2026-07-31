package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.utils.HexUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses the deliberately small, local macro format used by Debug. */
public final class MacroParser {
    private MacroParser() {
    }

    public static List<MacroStep> parse(String script) {
        if (script == null) {
            throw new IllegalArgumentException("script must not be null");
        }
        List<MacroStep> steps = new ArrayList<>();
        String[] lines = script.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            String command = space < 0 ? line : line.substring(0, space).toUpperCase(Locale.ROOT);
            String argument = space < 0 ? "" : line.substring(space + 1);
            switch (command) {
                case "TEXT":
                    if (argument.isEmpty()) {
                        throw invalidLine(index, "TEXT requires content");
                    }
                    steps.add(MacroStep.text(argument));
                    break;
                case "HEX":
                    if (!HexUtils.isValidHex(argument)) {
                        throw invalidLine(index, "HEX requires valid bytes");
                    }
                    steps.add(MacroStep.hex(HexUtils.hexStringToBytes(argument)));
                    break;
                case "DELAY":
                    try {
                        long delay = Long.parseLong(argument);
                        if (delay < 0 || delay > 60000L) {
                            throw new NumberFormatException();
                        }
                        steps.add(MacroStep.delay(delay));
                    } catch (NumberFormatException error) {
                        throw invalidLine(index, "DELAY must be 0..60000 ms");
                    }
                    break;
                default:
                    throw invalidLine(index, "unknown command: " + command);
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("macro has no steps");
        }
        return steps;
    }

    private static IllegalArgumentException invalidLine(int index, String message) {
        return new IllegalArgumentException("line " + (index + 1) + ": " + message);
    }
}
