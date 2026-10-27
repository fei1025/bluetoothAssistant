package com.zzf.bluetoothsmp.plugin;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;

public final class PluginSignatures {
    private PluginSignatures() { }
    public static String sha256(byte[] data) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    private static java.math.BigInteger number(String hex) { return new java.math.BigInteger(hex, 16); }
    public static boolean verify(byte[] message, byte[] key, byte[] signature) {
        try {
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(key));
            // Android 6 providers do not consistently implement named-curve parameter equality.
            // Compare P-256 mathematical parameters, never provider-specific curve object identity.
            ECParameterSpec actual = publicKey.getParams();
            if (!(actual.getCurve().getField() instanceof ECFieldFp)
                    || !((ECFieldFp) actual.getCurve().getField()).getP().equals(number("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"))
                    || !actual.getCurve().getA().equals(number("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC"))
                    || !actual.getCurve().getB().equals(number("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"))
                    || !actual.getGenerator().getAffineX().equals(number("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"))
                    || !actual.getGenerator().getAffineY().equals(number("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"))
                    || !actual.getOrder().equals(number("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"))
                    || actual.getCofactor() != 1) return false;
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey); verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException error) { return false; }
    }
}
