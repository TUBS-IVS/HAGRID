package hagrid.core.util;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Prepares all output bytes before replacement and restores earlier files on an operational failure.
 * Individual replacements are atomic; multiple files are not a crash-atomic filesystem transaction.
 */
public final class OutputFileSet {
    @FunctionalInterface public interface Writer { void write(Path temporary) throws IOException; }
    public record Artifact(Path target, Writer writer) {
        public Artifact {
            target = Objects.requireNonNull(target).toAbsolutePath().normalize();
            Objects.requireNonNull(writer);
        }
    }
    @FunctionalInterface interface Mover { void move(Path from, Path to) throws IOException; }
    private OutputFileSet() {}

    public static void publish(List<Artifact> artifacts) throws IOException {
        publish(artifacts, OutputFileSet::replace);
    }

    static synchronized void publish(List<Artifact> artifacts, Mover mover) throws IOException {
        var inputs = List.copyOf(artifacts);
        var targets = new HashSet<Path>();
        for (var input : inputs) {
            if (!targets.add(input.target())) throw new IllegalArgumentException("Duplicate output target " + input.target());
            state(input.target()); // Reject foreign directories/symlinks before any serialization.
        }
        var work = new ArrayList<Prepared>();
        Throwable failure = null;
        try {
            for (var input : inputs) {
                var prepared = new Prepared(input.target(), state(input.target()));
                work.add(prepared);
                Files.createDirectories(input.target().getParent());
                prepared.temporary = Files.createTempFile(input.target().getParent(), ".hagrid-output-", "." + input.target().getFileName());
                input.writer().write(prepared.temporary);
                prepared.newDigest = state(prepared.temporary);
                if (prepared.newDigest == null) throw new IOException("Writer produced no output: " + input.target());
                if (prepared.oldDigest != null) {
                    prepared.backup = Files.createTempFile(input.target().getParent(), ".hagrid-backup-", ".pending");
                    Files.copy(input.target(), prepared.backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    if (!prepared.oldDigest.equals(state(prepared.backup)))
                        throw new IOException("Output changed while preparing backup: " + input.target());
                }
            }
            // This helper serializes its own publishers; a changed external file must not be overwritten knowingly.
            for (var prepared : work)
                if (!Objects.equals(prepared.oldDigest, state(prepared.target)))
                    throw new IOException("Output changed during preparation: " + prepared.target);
            for (var prepared : work) {
                prepared.attempted = true;
                mover.move(prepared.temporary, prepared.target);
            }
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            for (var prepared : work.reversed()) {
                if (!prepared.attempted) continue;
                prepared.keepBackup = prepared.backup != null;
                try {
                    String current = state(prepared.target);
                    if (Objects.equals(current, prepared.oldDigest)) { prepared.keepBackup = false; continue; }
                    if (!Objects.equals(current, prepared.newDigest))
                        throw new IOException("Output changed after replacement; preserve recovery backup: " + prepared.target);
                    if (prepared.backup == null) Files.delete(prepared.target);
                    else mover.move(prepared.backup, prepared.target);
                    prepared.keepBackup = false;
                } catch (IOException | RuntimeException | Error recovery) {
                    prepared.keepBackup = true;
                    error.addSuppressed(new IOException("Recovery failed; backup retained at " + prepared.backup, recovery));
                }
            }
            throw error;
        } finally {
            Throwable cleanup = null;
            for (var prepared : work) {
                for (Path own : new Path[]{prepared.temporary, prepared.keepBackup ? null : prepared.backup}) {
                    if (own == null) continue;
                    try { Files.deleteIfExists(own); }
                    catch (IOException | RuntimeException | Error error) {
                        if (cleanup == null) cleanup = error; else cleanup.addSuppressed(error);
                    }
                }
            }
            // Cleanup after a successful publication cannot turn its committed files into a reported failed attempt.
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup);
                else org.apache.logging.log4j.LogManager.getLogger(OutputFileSet.class).warn("Published outputs; own temporary cleanup failed", cleanup);
            }
        }
    }

    private static void replace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String state(Path path) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return null; }
        if (!attributes.isRegularFile())
            throw new IOException("Output target must be a regular file or absent: " + path);
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(Files.newInputStream(path), hash)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static final class Prepared {
        final Path target;
        final String oldDigest;
        Path temporary, backup;
        String newDigest;
        boolean attempted, keepBackup;
        Prepared(Path target, String oldDigest) { this.target = target; this.oldDigest = oldDigest; }
    }
}