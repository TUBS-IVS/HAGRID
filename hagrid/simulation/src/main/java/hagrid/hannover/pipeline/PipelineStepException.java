package hagrid.hannover.pipeline;

import java.util.Objects;

/** A required pipeline stage failed; callers must not publish a successful run. */
public final class PipelineStepException extends RuntimeException {
    private final String stage;

    public PipelineStepException(String stage, Throwable cause) {
        super("HAGRID pipeline stage '" + Objects.requireNonNull(stage) + "' failed", Objects.requireNonNull(cause));
        this.stage = stage;
    }

    public String stage() { return stage; }
}
