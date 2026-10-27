package com.zzf.bluetoothsmp.plugin;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class PluginArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private KeyPair author, reviewer;
    private String reviewKey;
    @Before public void keys() throws Exception { author = key(); reviewer = key(); reviewKey = b64(reviewer.getPublic().getEncoded()); }
    private KeyPair key() throws Exception { KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair(); }
    private static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] utf(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String sign(byte[] value, KeyPair key) throws Exception { Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(key.getPrivate()); signer.update(value); return b64(signer.sign()); }
    private Map<String, byte[]> resources(boolean reviewed) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>(); files.put("index.html", utf("<!doctype html><h1>Example</h1>"));
        JSONObject manifest = new JSONObject().put("id", "example.device").put("name", "Example").put("author", "Test")
                .put("version", 1).put("sdk", 1).put("entry", "index.html").put("authorKey", b64(author.getPublic().getEncoded()))
                .put("capabilities", new JSONArray(Arrays.asList("ble.scan", "ble.connect", "ble.gatt")))
                .put("files", new JSONObject().put("index.html", PluginSignatures.sha256(files.get("index.html"))));
        byte[] metadata = utf(manifest.toString());
        JSONObject signature = new JSONObject().put("author", sign(metadata, author));
        if (reviewed) signature.put("reviewKey", reviewKey).put("review", sign(metadata, reviewer));
        files.put("plugin.json", metadata); files.put("signature.json", utf(signature.toString())); return files;
    }
    private File archive(Map<String, byte[]> files) throws Exception {
        File file = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return file;
    }
    private PluginArchive verify(File file, boolean dev) throws Exception { return new PluginArchive(file, temporary.newFolder(), Collections.singleton(reviewKey), dev, value -> Base64.getDecoder().decode(value)); }
    private void reject(String code, Map<String, byte[]> files, boolean dev) throws Exception {
        try { verify(archive(files), dev); fail("Accepted invalid package"); }
        catch (PluginException error) { assertEquals(code, error.code); }
    }
    @Test public void verifiesBothSignaturesAndExtractsOnlyDeclaredResources() throws Exception {
        PluginArchive verified = verify(archive(resources(true)), false);
        assertTrue(verified.reviewed); assertEquals("example.device", verified.manifest.id);
        assertEquals(PluginSignatures.sha256(author.getPublic().getEncoded()), verified.authorFingerprint);
    }
    @Test public void repackingCannotChangeContentIdentityOrEvadeRevocation() throws Exception {
        Map<String, byte[]> files = resources(true);
        PluginArchive first = verify(archive(files), false);
        File repacked = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(repacked))) {
            zip.setComment("Different ZIP metadata");
            zip.setLevel(0);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        PluginArchive second = verify(repacked, false);
        assertNotEquals(first.archiveDigest, second.archiveDigest);
        assertEquals(first.digest, second.digest);
        assertEquals(PluginSignatures.sha256(files.get("plugin.json")), first.digest);
    }
    @Test public void releaseRequiresReviewAndDeveloperModeStillRequiresAuthor() throws Exception {
        reject("REVIEW_REQUIRED", resources(false), false);
        assertFalse(verify(archive(resources(false)), true).reviewed);
        Map<String, byte[]> resources = resources(false);
        resources.put("signature.json", utf(new JSONObject().put("author", sign(utf("wrong"), author)).toString()));
        reject("AUTHOR_SIGNATURE_INVALID", resources, true);
    }
    @Test public void signingPackageAsOwnReviewerDoesNotMakeItOfficial() throws Exception {
        Map<String, byte[]> files = resources(true);
        files.put("signature.json", utf(new JSONObject().put("author", sign(files.get("plugin.json"), author))
                .put("reviewKey", b64(author.getPublic().getEncoded())).put("review", sign(files.get("plugin.json"), author)).toString()));
        reject("REVIEW_REQUIRED", files, false);
    }
    @Test public void signatureCannotBeReusedForModifiedManifest() throws Exception {
        Map<String, byte[]> files = resources(true);
        files.put("plugin.json", utf(new JSONObject(new String(files.get("plugin.json"), StandardCharsets.UTF_8)).put("version", 2).toString()));
        reject("AUTHOR_SIGNATURE_INVALID", files, false);
    }
    @Test public void rejectsTamperedAndUndeclaredResources() throws Exception {
        Map<String, byte[]> files = resources(true); files.put("index.html", utf("tampered")); reject("RESOURCE_HASH_MISMATCH", files, false);
        files = resources(true); files.put("extra.js", utf("alert(1)")); reject("RESOURCE_HASH_MISMATCH", files, false);
    }
    @Test public void rejectsPathTraversalAndCaseCollisions() throws Exception {
        Map<String, byte[]> files = resources(true); files.put("../escape.js", utf("bad")); reject("INVALID_ARCHIVE", files, false);
        files = resources(true); files.put("INDEX.html", utf("bad")); reject("INVALID_ARCHIVE", files, false);
        assertFalse(PluginManifest.safePath("/absolute.js")); assertFalse(PluginManifest.safePath("a//b.js"));
        assertFalse(PluginManifest.safePath("a\\b.js")); assertFalse(PluginManifest.safePath("a/%2e%2e/b.js"));
    }
    @Test public void refusesNativeCodeEvenInDeveloperBuilds() throws Exception {
        Map<String, byte[]> files = resources(true); files.put("payload.dex", new byte[1]); reject("UNSUPPORTED_RESOURCE", files, true);
    }
    @Test public void rejectsUnixSymlinkAttributesIgnoredByZipFile() throws Exception {
        File file = archive(resources(true)); byte[] bytes = Files.readAllBytes(file.toPath());
        for (int i = 0; i < bytes.length - 46; i++) {
            if (bytes[i] == 0x50 && bytes[i+1] == 0x4b && bytes[i+2] == 1 && bytes[i+3] == 2) {
                bytes[i+5] = 3; bytes[i+40] = (byte) 0xff; bytes[i+41] = (byte) 0xa1; break;
            }
        }
        Files.write(file.toPath(), bytes);
        try { verify(file, true); fail(); } catch (PluginException error) { assertEquals("INVALID_ARCHIVE", error.code); }
    }
    @Test public void refusesUnsupportedSdkAndCapabilities() throws Exception {
        JSONObject manifest = new JSONObject(new String(resources(true).get("plugin.json"), StandardCharsets.UTF_8));
        manifest.put("sdk", 2);
        try { new PluginManifest(utf(manifest.toString())); fail(); } catch (PluginException error) { assertEquals("SDK_UNSUPPORTED", error.code); }
        manifest.put("sdk", 1).put("capabilities", new JSONArray(Arrays.asList("network.request")));
        try { new PluginManifest(utf(manifest.toString())); fail(); } catch (PluginException error) { assertEquals("INVALID_MANIFEST", error.code); }
    }
    @Test public void refusesOversizedCompressedPackageBeforeReadingZip() throws Exception {
        File file = temporary.newFile(); try (RandomAccessFile data = new RandomAccessFile(file, "rw")) { data.setLength(PluginArchive.MAX_ARCHIVE + 1L); }
        try { verify(file, false); fail(); } catch (PluginException error) { assertEquals("PACKAGE_TOO_LARGE", error.code); }
    }
    @Test public void refusesExpandedZipBomb() throws Exception {
        Map<String, byte[]> files = resources(true); files.put("huge.txt", new byte[PluginArchive.MAX_EXPANDED + 1]);
        reject("PACKAGE_TOO_LARGE", files, false);
    }
}
