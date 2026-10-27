import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/** JDK-only helper. Private keys are PKCS#8 and never written into plugin packages. */
public class PluginCrypto {
    public static void main(String[] args) throws Exception {
        switch(args[0]) {
            case "keygen": {
                KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair=generator.generateKeyPair();
                Path privatePath=Paths.get(args[1]);
                Files.write(privatePath,pair.getPrivate().getEncoded(),StandardOpenOption.CREATE_NEW);
                privatePath.toFile().setReadable(false,false); privatePath.toFile().setWritable(false,false);
                privatePath.toFile().setReadable(true,true); privatePath.toFile().setWritable(true,true);
                Files.write(Paths.get(args[1]+".pub"), Base64.getEncoder().encode(pair.getPublic().getEncoded()),StandardOpenOption.CREATE_NEW);
                break;
            }
            case "sign": {
                PrivateKey key=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Paths.get(args[1]))));
                Signature signer=Signature.getInstance("SHA256withECDSA"); signer.initSign(key); signer.update(System.in.readAllBytes());
                System.out.print(Base64.getEncoder().encodeToString(signer.sign())); break;
            }
            case "verify": {
                PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(args[1])));
                Signature verifier=Signature.getInstance("SHA256withECDSA"); verifier.initVerify(key); verifier.update(System.in.readAllBytes());
                if(!verifier.verify(Base64.getDecoder().decode(args[2]))) System.exit(1);
                break;
            }
            default: throw new IllegalArgumentException("Unknown crypto operation");
        }
    }
}
