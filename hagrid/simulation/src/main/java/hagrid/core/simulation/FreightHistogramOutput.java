package hagrid.core.simulation;

import com.google.inject.Inject;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.freight.carriers.usecases.analysis.LegHistogram;

/** Writes existing native freight/population histograms at the requested cadence, always including the final run. */
final class FreightHistogramOutput implements IterationEndsListener {
    private final LegHistogram freightOnly, withoutFreight;
    private final int interval;
    @Inject private OutputDirectoryHierarchy output;

    FreightHistogramOutput(LegHistogram freightOnly, LegHistogram withoutFreight, int interval) {
        if (interval < 1) throw new IllegalArgumentException("Positive histogram interval required");
        this.freightOnly = freightOnly;
        this.withoutFreight = withoutFreight;
        this.interval = interval;
    }

    @Override public void notifyIterationEnds(IterationEndsEvent event) {
        try {
            if (event.isLastIteration() || event.getIteration() % interval == 0) {
                String prefix = output.getIterationPath(event.getIteration()) + "/" + event.getIteration();
                freightOnly.writeGraphic(prefix + ".legHistogram_freight.png");
                withoutFreight.writeGraphic(prefix + ".legHistogram_withoutFreight.png");
            }
        } finally {
            // Retain per-iteration statistics, including when an intermediate output is skipped.
            freightOnly.reset(event.getIteration());
            withoutFreight.reset(event.getIteration());
        }
    }
}