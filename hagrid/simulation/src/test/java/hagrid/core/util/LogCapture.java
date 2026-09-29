package hagrid.core.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only log4j2 capture for one class's logger. Use in try-with-resources:
 * <pre>
 * try (LogCapture log = LogCapture.of(Foo.class)) {
 *     Foo.doSomething();
 *     assertThat(log.warnings()).anyMatch(m -&gt; m.contains("fallback"));
 * }
 * </pre>
 * Exists so that a warning which replaces a silent fallback is asserted rather than
 * assumed: a WARN nobody checks can be deleted by the next refactor without a red test.
 */
public final class LogCapture implements AutoCloseable {

    /** One captured event: level plus the fully formatted message. */
    public record Entry(Level level, String message) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final AbstractAppender appender;

    private LogCapture(Class<?> cls) {
        this.logger = (Logger) LogManager.getLogger(cls);
        this.appender = new AbstractAppender("capture-" + UUID.randomUUID(), null, null, true,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                entries.add(new Entry(event.getLevel(), event.getMessage().getFormattedMessage()));
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> cls) {
        return new LogCapture(cls);
    }

    /** Messages logged at WARN or more severe. */
    public List<String> warnings() {
        return entries.stream().filter(e -> e.level().isMoreSpecificThan(Level.WARN))
                .map(Entry::message).toList();
    }

    /** Messages logged at INFO. */
    public List<String> infos() {
        return entries.stream().filter(e -> e.level() == Level.INFO).map(Entry::message).toList();
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        appender.stop();
    }
}
