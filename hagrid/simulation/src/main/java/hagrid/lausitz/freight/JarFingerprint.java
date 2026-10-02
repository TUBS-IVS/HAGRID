package hagrid.lausitz.freight;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fingerprint of the code that runs the LMD preprocessing (spec 2026-10-01 section 5.3): SHA-256
 * over every entry of the running JAR - name and content, sorted by name - except directories.
 * {@code META-INF/MANIFEST.MF} and every {@code pom.properties} are included: the manifest can
 * change what runs ({@code Multi-Release: true} switches on the {@code META-INF/versions/}
 * classes, {@code Class-Path} extends the classpath), and neither file carries a build time in
 * this build. Zip timestamps never enter. A rebuild of the same sources therefore keeps the
 * fingerprint, any code, resource or manifest change anywhere replaces it.
 *
 * <p>Outside a JAR (tests, IDE) there is no fingerprint and the cache stays off. So it is when the
 * JAR was changed after this JVM started ({@link #keepIfUnchangedSinceJvmStart}).
 */
final class JarFingerprint {

    private static final Logger LOG = LogManager.getLogger(JarFingerprint.class);

    /** {@code null} = not computed yet. Computed once per JVM (about 5 s for the shaded JAR). */
    private static volatile Optional<String> cached;
    private static volatile String overrideForTests;

    private JarFingerprint() {
    }

    static Optional<String> ofRunningCode() {
        String override = overrideForTests;
        if (override != null) {
            return Optional.of(override);
        }
        Optional<String> c = cached;
        if (c == null) {
            synchronized (JarFingerprint.class) {
                c = cached;
                if (c == null) {
                    c = compute();
                    cached = c;
                }
            }
        }
        return c;
    }

    /** Test hook (spec section 9.2): forces a fingerprint; {@code null} restores the real one. */
    static void setOverrideForTests(String fingerprintOrNull) {
        overrideForTests = fingerprintOrNull;
    }

    static Optional<String> of(Path codeSource) throws IOException {
        if (!Files.isRegularFile(codeSource)
                || !codeSource.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return Optional.empty();
        }
        long t0 = System.nanoTime();
        String fingerprint = ofJar(codeSource);
        LOG.info("jsprit cache: code fingerprint {} of {} in {} ms", fingerprint.substring(0, 16),
                codeSource.getFileName(), (System.nanoTime() - t0) / 1_000_000);
        return Optional.of(fingerprint);
    }

    static String ofJar(Path jar) throws IOException {
        MessageDigest outer = Sha256.newDigest();
        byte[] buf = new byte[1 << 16];
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry e : entries) {
                // name, separator, digest of the content: unambiguous even if bytes move between entries
                MessageDigest inner = Sha256.newDigest();
                try (InputStream in = zip.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        inner.update(buf, 0, n);
                    }
                }
                outer.update(e.getName().getBytes(StandardCharsets.UTF_8));
                outer.update((byte) 0);
                outer.update(inner.digest());
            }
        }
        return Sha256.hex(outer.digest());
    }

    private static Optional<String> compute() {
        try {
            Path location = Path.of(LausitzFreightPreprocessor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Optional<String> fingerprint = of(location);
            if (fingerprint.isEmpty()) {
                return fingerprint;
            }
            // read AFTER hashing: a JAR replaced before or while it was hashed shows up here
            return keepIfUnchangedSinceJvmStart(location, fingerprint, lastChangedMillis(location),
                    ManagementFactory.getRuntimeMXBean().getStartTime());
        } catch (IOException | URISyntaxException | RuntimeException e) {
            LOG.warn("jsprit cache: cannot fingerprint the running code ({}) - cache off for this run", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Review 2026-10-02 I1: the JAR at the path may no longer be the code this JVM runs. On Linux a
     * rebuild replaces the file (new inode) under a running JVM, which keeps executing the old
     * classes; hashing the new file would store the old code's results under the new code's key. A
     * JAR changed after this JVM started therefore gives no fingerprint, and the cache is off.
     * Changed at or before the start is fine; there is no tolerance in the trusting direction.
     *
     * @param jarChangedMillis {@link #lastChangedMillis} of the JAR, read after hashing it
     * @param jvmStartMillis   {@code RuntimeMXBean.getStartTime()}
     */
    static Optional<String> keepIfUnchangedSinceJvmStart(Path jar, Optional<String> fingerprint,
                                                         long jarChangedMillis, long jvmStartMillis) {
        if (fingerprint.isPresent() && jarChangedMillis > jvmStartMillis) {
            LOG.warn("jsprit cache: {} was modified after this JVM started - code fingerprint unavailable,"
                    + " cache off for this run", jar);
            return Optional.empty();
        }
        return fingerprint;
    }

    /**
     * Last modification of the JAR; on a POSIX file system the later of modification and status
     * change ({@code ctime}). A copy that preserves the old modification time ({@code cp -p},
     * {@code rsync -t}) still gets a fresh {@code ctime}, which user tools cannot set. Windows has
     * no {@code ctime}; there the modification time alone counts.
     */
    static long lastChangedMillis(Path jar) throws IOException {
        long changed = Files.getLastModifiedTime(jar).toMillis();
        try {
            if (Files.getAttribute(jar, "unix:ctime") instanceof FileTime ctime) {
                changed = Math.max(changed, ctime.toMillis());
            }
        } catch (UnsupportedOperationException | IllegalArgumentException notPosix) {
            // no "unix" attribute view (Windows): the modification time alone
        }
        return changed;
    }
}
