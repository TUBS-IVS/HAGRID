package hagrid.utils.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe CSV appender that records per-carrier routing status in real time.
 * Writes one row per event: START, HEARTBEAT, DONE, ERROR with timestamp and details.
 */
public class CarrierRoutingStatusLogger {

    private final Path csvPath;
    private final ReentrantLock lock = new ReentrantLock();
    private final DateTimeFormatter tsFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    public CarrierRoutingStatusLogger(Path csvPath) {
        this.csvPath = csvPath;
        initIfNeeded();
    }

    private void initIfNeeded() {
        lock.lock();
        try {
            Files.createDirectories(csvPath.getParent());
            if (!Files.exists(csvPath)) {
                String header = String.join(",",
                        "timestamp","event","carrierType","carrierId","provider","services",
                        "sizeClass","threadingType","elapsedSeconds","thread","message")
                        + System.lineSeparator();
                Files.writeString(csvPath, header, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            // Swallow here; runtime appends will try again
        } finally {
            lock.unlock();
        }
    }

    public void appendStart(String carrierType, String carrierId, String provider,
                             int services, String sizeClass, String threadingType, String threadName) {
        String ts = tsFmt.format(Instant.now());
        String[] cols = new String[] { ts, "START", carrierType, carrierId, provider,
                Integer.toString(services), sizeClass, threadingType, "0", threadName, "" };
        writeRow(cols);
    }

    public void appendHeartbeat(String carrierType, String carrierId, String provider,
                                int services, String sizeClass, String threadingType,
                                double elapsedSeconds, String threadName) {
        String ts = tsFmt.format(Instant.now());
        String[] cols = new String[] { ts, "HEARTBEAT", carrierType, carrierId, provider,
                Integer.toString(services), sizeClass, threadingType,
                String.format(Locale.ROOT, "%.1f", elapsedSeconds), threadName, "" };
        writeRow(cols);
    }

    public void appendCriticalHeartbeat(String carrierType, String carrierId, String provider,
                                        int services, String sizeClass, String threadingType,
                                        double elapsedSeconds, String threadName, String message) {
        String ts = tsFmt.format(Instant.now());
        String[] cols = new String[] { ts, "CRITICAL", carrierType, carrierId, provider,
                Integer.toString(services), sizeClass, threadingType,
                String.format(Locale.ROOT, "%.1f", elapsedSeconds), threadName,
                message == null ? "" : message };
        writeRow(cols);
    }

    public void appendDone(String carrierType, String carrierId, String provider,
                           int services, String sizeClass, String threadingType,
                           double elapsedSeconds, String threadName) {
        String ts = tsFmt.format(Instant.now());
        String[] cols = new String[] { ts, "DONE", carrierType, carrierId, provider,
                Integer.toString(services), sizeClass, threadingType,
                String.format(Locale.ROOT, "%.3f", elapsedSeconds), threadName, "" };
        writeRow(cols);
    }

    public void appendError(String carrierType, String carrierId, String provider,
                            int services, String sizeClass, String threadingType,
                            double elapsedSeconds, String threadName, String message) {
        String ts = tsFmt.format(Instant.now());
        String[] cols = new String[] { ts, "ERROR", carrierType, carrierId, provider,
                Integer.toString(services), sizeClass, threadingType,
                String.format(Locale.ROOT, "%.3f", elapsedSeconds), threadName, message == null ? "" : message };
        writeRow(cols);
    }

    private void writeRow(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cols[i] == null ? "" : cols[i]));
        }
        sb.append(System.lineSeparator());
        lock.lock();
        try {
            Files.createDirectories(csvPath.getParent());
            Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Best-effort logging; ignore to not interrupt routing
        } finally {
            lock.unlock();
        }
    }

    private static String escape(String v) {
        String needsQuote = ",\n\r\"";
        boolean quote = v.chars().anyMatch(c -> needsQuote.indexOf(c) >= 0);
        String s = v.replace("\"", "\"\"");
        return quote ? ("\"" + s + "\"") : s;
    }
}
