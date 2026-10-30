import com.android.apksig.ApkVerifier;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Read verified binary certificates, never parse apksigner's changing display format. */
public final class VerifyManagedApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected APK and public certificate pin file");
        String expected = Files.readString(Path.of(args[1])).trim();
        if (!expected.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid signer pin");
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException("APK signature verification failed: " + result.getErrors());
        Set<X509Certificate> certificates = new HashSet<>(result.getSignerCertificates());
        for (var signer : result.getV3SchemeSigners()) certificates.add(signer.getCertificate());
        for (var signer : result.getV31SchemeSigners()) certificates.add(signer.getCertificate());
        Set<String> fingerprints = new HashSet<>();
        for (X509Certificate certificate : certificates) {
            fingerprints.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        }
        if (!fingerprints.equals(Set.of(expected))) {
            throw new IllegalStateException("APK signer mismatch: expected " + expected + ", found " + fingerprints);
        }
        System.out.println(expected);
    }
}
