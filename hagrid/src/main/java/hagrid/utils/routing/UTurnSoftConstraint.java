package hagrid.utils.routing;

import com.graphhopper.jsprit.core.problem.constraint.SoftActivityConstraint;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.activity.End;
import com.graphhopper.jsprit.core.problem.solution.route.activity.Start;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

/**
 * Soft constraint that penalizes U-turns during JSprit insertion evaluation.
 * <p>
 * A <b>U-turn</b> is detected when two consecutive activities are on
 * <i>reverse links</i> — i.e. link L1 goes from node A→B and link L2
 * goes from node B→A. In reality such a maneuver is costly for delivery
 * vehicles (turn-around time, difficult with vans in narrow streets) but
 * the standard MATSim shortest-path router treats it as free.
 * </p>
 * <p>
 * This is a <b>soft</b> constraint: it adds a configurable penalty to the
 * insertion cost, making U-turn-heavy placements more expensive. JSprit
 * will still allow them when no better alternative exists (e.g. dead-end
 * streets), but will prefer non-U-turn positions when the cost difference
 * is small.
 * </p>
 *
 * <h2>Detection Logic</h2>
 * When evaluating the insertion of {@code newAct} between {@code prevAct}
 * and {@code nextAct}, the constraint checks three activity pairs:
 * <ol>
 *   <li>{@code prevAct → newAct}: was there a U-turn introduced?  (+penalty)</li>
 *   <li>{@code newAct → nextAct}: was there a U-turn introduced?  (+penalty)</li>
 *   <li>{@code prevAct → nextAct}: was an existing U-turn <i>removed</i> by the insertion?  (−penalty)</li>
 * </ol>
 * The net result is the <b>marginal</b> U-turn cost change from this insertion.
 *
 * @author HAGRID Team
 */
public class UTurnSoftConstraint implements SoftActivityConstraint {

    private static final Logger LOGGER = LogManager.getLogger(UTurnSoftConstraint.class);

    private final Network network;
    private final double penaltyCost;

    /**
     * Creates a U-turn soft constraint.
     *
     * @param network     the MATSim network (for resolving link topology)
     * @param penaltyCost the score penalty added per detected U-turn (in JSprit cost units)
     */
    public UTurnSoftConstraint(Network network, double penaltyCost) {
        this.network = network;
        this.penaltyCost = penaltyCost;
        LOGGER.info("U-turn soft constraint active: penalty = {} cost units", penaltyCost);
    }

    @Override
    public double getCosts(JobInsertionContext iFacts,
                           TourActivity prevAct,
                           TourActivity newAct,
                           TourActivity nextAct,
                           double prevActDepTime) {

        double cost = 0.0;

        // Penalty for introducing a U-turn: prevAct → newAct
        if (isUTurn(prevAct, newAct)) {
            cost += penaltyCost;
        }

        // Penalty for introducing a U-turn: newAct → nextAct
        if (isUTurn(newAct, nextAct)) {
            cost += penaltyCost;
        }

        // Credit for removing an existing U-turn: prevAct → nextAct
        // (this segment is replaced by prevAct → newAct → nextAct)
        if (isUTurn(prevAct, nextAct)) {
            cost -= penaltyCost;
        }

        return cost;
    }

    /**
     * Checks whether traveling from {@code from} to {@code to} constitutes a U-turn.
     * <p>
     * A U-turn is defined as: the two activities are on <i>reverse links</i>,
     * meaning link1 goes A→B and link2 goes B→A. The vehicle would finish
     * link1 at node B, then immediately traverse link2 back to node A — a
     * 180° reversal.
     * </p>
     *
     * @param from the origin activity
     * @param to   the destination activity
     * @return {@code true} if a reverse-link U-turn is detected
     */
    boolean isUTurn(TourActivity from, TourActivity to) {
        // Skip Start/End depot activities — they have no real link placement
        if (from == null || to == null) return false;
        if (from instanceof Start || from instanceof End) return false;
        if (to instanceof Start || to instanceof End) return false;
        if (from.getLocation() == null || to.getLocation() == null) return false;

        String fromId = from.getLocation().getId();
        String toId = to.getLocation().getId();
        if (fromId == null || toId == null || fromId.equals(toId)) return false;

        Link fromLink = network.getLinks().get(Id.createLinkId(fromId));
        Link toLink = network.getLinks().get(Id.createLinkId(toId));
        if (fromLink == null || toLink == null) return false;

        // Reverse-link check: L1 (A→B), L2 (B→A)
        return fromLink.getToNode().getId().equals(toLink.getFromNode().getId())
            && fromLink.getFromNode().getId().equals(toLink.getToNode().getId());
    }
}
