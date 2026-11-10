package hagrid.core.simulation.scoring;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.freight.carriers.Carrier;

/** The globally bound ledger owns events; this adapter only finalizes and reads its result. */
public final class CarrierScoringFunction implements ScoringFunction {
    private final Carrier carrier; private final CarrierExecutionLedger ledger;
    private final ResolvedHagridConfig config; private final Network network;
    private final hagrid.core.routing.SectionBindingCompanion.BoundContext binding;
    private CarrierCostCalculator.Evaluation finished;
    public CarrierScoringFunction(Carrier carrier, CarrierExecutionLedger ledger, ResolvedHagridConfig config, Network network) {
        this(carrier,ledger,config,network,null);
    }
    public CarrierScoringFunction(Carrier carrier, CarrierExecutionLedger ledger, ResolvedHagridConfig config, Network network, hagrid.core.routing.SectionBindingCompanion.BoundContext binding) {
        this.carrier=carrier; this.ledger=ledger; this.config=config; this.network=network; this.binding=binding;
    }
    @Override public void finish() {
        if (finished!=null) return;
        var result=CarrierCostCalculator.calculateBound(carrier,ledger.complete(carrier.getId()),config,network,binding);
        CarrierCostAttributeWriter.write(carrier,result.costs());
        carrier.getAttributes().putAttribute("costDefinitionVersion","hagrid-carrier-costs-v2-execution");
        carrier.getAttributes().putAttribute("costExecutionComplete",true);
        finished=result;
    }
    @Override public double getScore() { return finished==null?0:finished.score(); }
    @Override public void handleActivity(Activity activity) {}
    @Override public void handleLeg(Leg leg) {}
    @Override public void handleEvent(Event event) {}
    @Override public void agentStuck(double time) {}
    @Override public void addMoney(double amount) {}
    @Override public void addScore(double amount) {}
}
