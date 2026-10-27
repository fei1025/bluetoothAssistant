import java.io.InputStream;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verifies every AAB payload entry with the exact registered upload certificate. */
public final class VerifyBundle {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: VerifyBundle.java bundle.aab certificateSha256");
        String expected = args[1].replace(":", "").toLowerCase();
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid certificate SHA-256");
        int verified = 0;
        boolean manifestFound = false;
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            byte[] buffer = new byte[65536];
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream stream = jar.getInputStream(entry)) {
                    while (stream.read(buffer) != -1) { /* Trigger JAR signature verification. */ }
                }
                if (entry.getName().startsWith("META-INF/")) continue;
                if (entry.getName().equals("base/manifest/AndroidManifest.xml")) manifestFound = true;
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) {
                    throw new SecurityException("Unsigned or unexpectedly signed AAB payload: " + entry.getName());
                }
                X509Certificate certificate = (X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0);
                certificate.checkValidity();
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                if (!fingerprint.equals(expected)) throw new SecurityException("AAB upload certificate mismatch");
                verified++;
            }
        }
        if (!manifestFound || verified == 0) throw new SecurityException("Not a signed Android App Bundle");
        System.out.println("Verified " + verified + " signed AAB payload entries; upload certificate " + expected);
    }
}
