package com.zzf.bluetoothsmp.plugin;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Validates the complete archive before any installed version is replaced. No extraction is trusted. */
public final class PluginArchive {
    public static final int MAX_ARCHIVE = 10 * 1024 * 1024;
    public static final int MAX_EXPANDED = 32 * 1024 * 1024;
    public static final int MAX_FILES = 512;
    public interface Decoder { byte[] decode(String text); }
    public final PluginManifest manifest;
    /** Content identity is signed-manifest SHA-256; ZIP repacking cannot evade a revocation. */
    public final String digest, archiveDigest, authorFingerprint;
    public final boolean reviewed;

    public PluginArchive(File archive, File destination, Set<String> reviewKeys, boolean allowDeveloper,
                         Decoder decoder) throws PluginException {
        try {
            if (archive.length() <= 0 || archive.length() > MAX_ARCHIVE) fail("PACKAGE_TOO_LARGE");
            inspectCentralDirectory(archive);
            MessageDigest archiveHash = MessageDigest.getInstance("SHA-256");
            try (InputStream source = new FileInputStream(archive)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = source.read(buffer)) != -1) archiveHash.update(buffer, 0, read);
            }
            archiveDigest = PluginSignatures.hex(archiveHash.digest());
            Map<String, String> hashes = new TreeMap<>();
            byte[] metadata = null, signatures = null;
            long expanded = 0; int count = 0;
            if (!destination.mkdirs() && !destination.isDirectory()) fail("INSTALL_FAILED");
            try (ZipFile zip = new ZipFile(archive)) {
                Set<String> seen = new HashSet<>(); Set<String> folded = new HashSet<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement(); String name = entry.getName();
                    if (++count > MAX_FILES || entry.isDirectory() || !PluginManifest.safePath(name)
                            || !seen.add(name) || !folded.add(name.toLowerCase(Locale.ROOT))) fail("INVALID_ARCHIVE");
                    if (!name.matches(".*\\.(html|js|css|json|svg|png|jpg|jpeg|webp|gif|ico|woff2?|ttf|txt)$")) fail("UNSUPPORTED_RESOURCE");
                    if (entry.getSize() < 0 || entry.getSize() > MAX_EXPANDED) fail("PACKAGE_TOO_LARGE");
                    File file = new File(destination, name);
                    if (!file.getCanonicalPath().startsWith(destination.getCanonicalPath() + File.separator)) fail("INVALID_ARCHIVE");
                    if (!file.getParentFile().mkdirs() && !file.getParentFile().isDirectory()) fail("INSTALL_FAILED");
                    MessageDigest hash = MessageDigest.getInstance("SHA-256"); CRC32 crc = new CRC32(); long size = 0;
                    ByteArrayOutputStream special = name.equals("plugin.json") || name.equals("signature.json") ? new ByteArrayOutputStream() : null;
                    try (InputStream source = zip.getInputStream(entry); OutputStream target = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = source.read(buffer)) != -1) {
                            expanded += read; size += read;
                            if (expanded > MAX_EXPANDED || (special != null && size > 128 * 1024)) fail("PACKAGE_TOO_LARGE");
                            hash.update(buffer, 0, read); crc.update(buffer, 0, read); target.write(buffer, 0, read);
                            if (special != null) special.write(buffer, 0, read);
                        }
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) fail("INVALID_ARCHIVE");
                    if (name.equals("plugin.json")) metadata = special.toByteArray();
                    else if (name.equals("signature.json")) signatures = special.toByteArray();
                    else hashes.put(name, PluginSignatures.hex(hash.digest()));
                }
            }
            if (metadata == null || signatures == null) fail("INVALID_ARCHIVE");
            manifest = new PluginManifest(metadata);
            digest = PluginSignatures.sha256(metadata);
            if (!manifest.files.equals(hashes)) fail("RESOURCE_HASH_MISMATCH");
            JSONObject signature = new JSONObject(new String(signatures, StandardCharsets.UTF_8));
            byte[] authorKey = decoder.decode(manifest.authorKey);
            if (!PluginSignatures.verify(metadata, authorKey, decoder.decode(signature.getString("author")))) fail("AUTHOR_SIGNATURE_INVALID");
            authorFingerprint = PluginSignatures.sha256(authorKey);
            String reviewKey = signature.optString("reviewKey", "");
            reviewed = reviewKeys.contains(reviewKey) && PluginSignatures.verify(metadata, decoder.decode(reviewKey),
                    decoder.decode(signature.optString("review", "")));
            if (!reviewed && !allowDeveloper) fail("REVIEW_REQUIRED");
        } catch (PluginException error) { throw error; }
        catch (Exception error) { throw new PluginException("INVALID_ARCHIVE", "Plugin package validation failed"); }
    }

    /** ZIP external Unix attributes are otherwise ignored by java.util.zip. Reject ZIP64/encryption/symlinks. */
    private static void inspectCentralDirectory(File archive) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long end = -1;
            for (long offset = file.length() - 22; offset >= Math.max(0, file.length() - 65557); offset--) {
                file.seek(offset);
                if (u32(file) == 0x06054b50L) {
                    file.seek(offset + 20); int comment = u16(file);
                    if (offset + 22 + comment == file.length()) { end = offset; break; }
                }
            }
            if (end < 0) fail("INVALID_ARCHIVE");
            file.seek(end + 4);
            if (u16(file) != 0 || u16(file) != 0) fail("INVALID_ARCHIVE");
            int diskCount = u16(file), count = u16(file);
            long size = u32(file), start = u32(file);
            if (count != diskCount || count < 2 || count > MAX_FILES || start + size != end) fail("INVALID_ARCHIVE");
            file.seek(start);
            for (int i = 0; i < count; i++) {
                long at = file.getFilePointer();
                if (u32(file) != 0x02014b50L) fail("INVALID_ARCHIVE");
                int madeBy = u16(file); u16(file); int flags = u16(file); int method = u16(file);
                if ((flags & 1) != 0 || (method != 0 && method != 8)) fail("INVALID_ARCHIVE");
                file.seek(at + 28); int name = u16(file), extra = u16(file), comment = u16(file);
                if (u16(file) != 0) fail("INVALID_ARCHIVE");
                u16(file); long attributes = u32(file), local = u32(file);
                int mode = (int) (attributes >>> 16) & 0170000;
                if ((madeBy >>> 8) == 3 && mode != 0 && mode != 0100000) fail("INVALID_ARCHIVE");
                if (local >= start || name == 0 || at + 46 + name + extra + comment > end) fail("INVALID_ARCHIVE");
                file.seek(at + 46 + name + extra + comment);
            }
            if (file.getFilePointer() != end) fail("INVALID_ARCHIVE");
        }
    }
    private static int u16(RandomAccessFile file) throws IOException { return file.readUnsignedByte() | file.readUnsignedByte() << 8; }
    private static long u32(RandomAccessFile file) throws IOException { return (u16(file) | (long) u16(file) << 16) & 0xffffffffL; }
    private static void fail(String code) throws PluginException { throw new PluginException(code, "Plugin package rejected: " + code); }
}
