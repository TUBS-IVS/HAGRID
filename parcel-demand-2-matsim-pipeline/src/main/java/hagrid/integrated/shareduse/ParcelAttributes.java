package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * STRICT snapshots of the parcel-person attributes written by {@link ParcelAgentGenerator}.
 *
 * <p><b>Why strict.</b> Every consumer of these attributes used to tolerate a miss with a
 * quiet stand-in, and each stand-in produced a plausible-but-wrong number instead of a crash:
 * <ul>
 *   <li>{@link SharedUseStopDurationProvider} fell back to "1 parcel", collapsing depot pickup
 *       from up to {@value SharedUse#MAX_PICKUP_DURATION_S} s to 30 s and door dwell from up to
 *       900 s to 120 s — Shared-Use would simply look cheaper than it is;</li>
 *   <li>{@link SharedUseKpiHandler} counted a segment as 0 parcels and bucketed an unattributed
 *       channel as DOOR, silently deflating {@code parcels_submitted} and skewing the channel
 *       split;</li>
 *   <li>{@link ParcelOnlyRetryQueue} treated a missing delivery window as "never expires",
 *       disabling the M5 deadline for that request.</li>
 * </ul>
 * All of those are unreachable in a correctly generated population — {@code ParcelAgentGenerator}
 * always writes load, dwell, channel and window-end together. A miss therefore means the
 * population is not what this module assumes (stale plans file, a hand-edited population, a
 * changed attribute name), and the honest response is to refuse the run rather than to simulate
 * a different scenario than the one requested.
 *
 * <p>Validation happens ONCE at module-install / handler-construction time, so the failure is a
 * cheap startup abort rather than an exception hours into a mobsim. All offenders are collected
 * and reported together — fixing them one restart at a time would be needlessly slow.
 */
public final class ParcelAttributes {

    /** How many offending person ids to name before truncating the message. */
    private static final int MAX_REPORTED = 10;

    private ParcelAttributes() {}

    /** Parcel count per parcel-person ({@link SharedUse#LOAD_ATTRIBUTE}). */
    public static Map<Id<Person>, Integer> loads(Population population) {
        return snapshot(population, SharedUse.LOAD_ATTRIBUTE, "parcel load",
                v -> v instanceof Number n ? n.intValue() : null);
    }

    /** Door-delivery dwell per parcel-person ({@link SharedUse#DWELL_ATTRIBUTE}). */
    public static Map<Id<Person>, Double> dwells(Population population) {
        return snapshot(population, SharedUse.DWELL_ATTRIBUTE, "segment dwell",
                v -> v instanceof Number n ? n.doubleValue() : null);
    }

    /** Absolute delivery deadline per parcel-person ({@link SharedUse#WINDOW_END_ATTRIBUTE}). */
    public static Map<Id<Person>, Double> windowEnds(Population population) {
        return snapshot(population, SharedUse.WINDOW_END_ATTRIBUTE, "delivery window end",
                v -> v instanceof Number n ? n.doubleValue() : null);
    }

    /**
     * Delivery channel per parcel-person ({@link SharedUse#CHANNEL_ATTRIBUTE}). Only the two
     * {@link DeliveryChannelResolver.Channel} names are accepted, so a typo cannot silently
     * land in the DOOR bucket the way {@code !"LOCKER".equals(...)} used to let it.
     */
    public static Map<Id<Person>, String> channels(Population population) {
        return snapshot(population, SharedUse.CHANNEL_ATTRIBUTE, "delivery channel", v -> {
            if (v == null) {
                return null;
            }
            String s = v.toString();
            for (DeliveryChannelResolver.Channel c : DeliveryChannelResolver.Channel.values()) {
                if (c.name().equals(s)) {
                    return s;
                }
            }
            return null;   // present but not a known channel -> reported as invalid
        });
    }

    /**
     * Reads {@code attribute} off every parcel-person, converting via {@code parser}.
     *
     * @throws IllegalStateException if any parcel-person is missing the attribute or carries an
     *                               unusable value ({@code parser} returned {@code null})
     */
    private static <T> Map<Id<Person>, T> snapshot(Population population, String attribute,
                                                   String label, Function<Object, T> parser) {
        Map<Id<Person>, T> out = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        for (Person p : population.getPersons().values()) {
            if (!SharedUse.isParcelPerson(p.getId().toString())) {
                continue;
            }
            Object raw = p.getAttributes().getAttribute(attribute);
            T value = parser.apply(raw);
            if (value == null) {
                invalid.add(p.getId() + " (" + (raw == null ? "absent" : "'" + raw + "'") + ")");
            } else {
                out.put(p.getId(), value);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException(describe(attribute, label, invalid));
        }
        return out;
    }

    private static String describe(String attribute, String label, List<String> invalid) {
        StringBuilder sb = new StringBuilder()
                .append(invalid.size()).append(" parcel-person(s) have no usable ").append(label)
                .append(" attribute '").append(attribute)
                .append("'. Shared-Use cannot dwell/price/deadline them correctly, and defaulting")
                .append(" would silently simulate a different scenario. Re-generate the population")
                .append(" via PrepareLausitzDrtInputs. Offenders: ");
        sb.append(String.join(", ", invalid.subList(0, Math.min(MAX_REPORTED, invalid.size()))));
        if (invalid.size() > MAX_REPORTED) {
            sb.append(", ... (").append(invalid.size() - MAX_REPORTED).append(" more)");
        }
        return sb.toString();
    }
}
