package hagrid.lausitz.freight;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers for the jsprit result cache. Files are streamed, never read whole. */
final class Sha256 {

    private Sha256() {
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    static String ofText(String text) {
        return hex(newDigest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static String ofFile(Path file) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }
}
