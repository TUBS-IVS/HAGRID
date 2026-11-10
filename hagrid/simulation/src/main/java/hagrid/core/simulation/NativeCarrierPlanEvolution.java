package hagrid.core.simulation;

import hagrid.core.config.ResolvedHagridConfig;
import hagrid.core.routing.*;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.*;

import java.util.*;

/** Native strategy draws are batched so one completed preceding profile serves the pass. */
final class NativeCarrierPlanEvolution {
    enum Choice { BEST, MUTATE }
    private static final int MEMORY = 5;
    private static final BestPlanSelector<CarrierPlan,Carrier> BEST = new BestPlanSelector<>();

    static CarrierStrategyManager manager(ValidatedCarrierReplanning lifecycle,int stopInnovation) {
        var manager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
        var choices = new LinkedHashMap<Carrier,Choice>();
        // MATSim calls init on every strategy, then runs one weighted choice per carrier,
        // then calls finish on every strategy. The always-enabled BEST brackets the entire pass.
        var best = new Draw(choices,Choice.BEST) {
            private int iteration;
            @Override public void init(ReplanningContext replanning) {
                iteration = replanning.getIteration();
                choices.clear();
                lifecycle.startReplanning(iteration);
            }
            @Override public void finish() {
                try { lifecycle.applyChoices(iteration,choices); }
                finally { choices.clear(); }
            }
        };
        var mutation = new Draw(choices,Choice.MUTATE);
        manager.setMaxPlansPerAgent(MEMORY);
        manager.setPlanSelectorForRemoval(person -> worstAlternative((Carrier) person));
        manager.addStrategy(best,null,1.0);
        manager.addStrategy(mutation,null,0.5);
        manager.addChangeRequest(stopInnovation,mutation,null,0);
        return manager;
    }

    private static class Draw implements GenericPlanStrategy<CarrierPlan,Carrier> {
        private final Map<Carrier,Choice> choices;
        private final Choice choice;
        Draw(Map<Carrier,Choice> choices,Choice choice) { this.choices=choices; this.choice=choice; }
        public void init(ReplanningContext replanning) {}
        public void finish() {}
        public void run(HasPlansAndId<CarrierPlan,Carrier> person) {
            var carrier = (Carrier)person;
            if (carrier.getServices().isEmpty() && carrier.getShipments().isEmpty()) return;
            if (choice == Choice.MUTATE || BEST.selectPlan(carrier) != carrier.getSelectedPlan())
                choices.put(carrier,choice);
        }
    }

    static void apply(Carrier carrier,Choice choice,RoutingContext context) {
        var request = new RoutingRequest(carrier,context,CarrierModeResolver.resolve(carrier).orElseThrow());
        var costs = new ModalTransportCosts(request);
        var previous = carrier.getSelectedPlan();
        if (choice == Choice.MUTATE) {
            var candidate = copy(previous);
            new CarrierTimeAllocationMutator.Factory().build().handlePlan(candidate);
            var cost = routeCost(carrier,candidate,costs,context.config());
            if (cost.isEmpty()) return;
            carrier.addPlan(candidate);
            select(carrier,candidate,cost.getAsDouble());
            while (carrier.getPlans().size() > MEMORY) carrier.removePlan(worstAlternative(carrier));
        } else {
            CarrierPlan best;
            while ((best = BEST.selectPlan(carrier)) != previous) {
                // Refresh the historical roads at their own departure before allowing QSim to use them.
                var candidate = copy(best);
                var cost = routeCost(carrier,candidate,costs,context.config());
                if (cost.isPresent()) {
                    candidate.setScore(best.getScore());
                    best.getAttributes().getAsMap().forEach(candidate.getAttributes()::putAttribute);
                    // BestPlan breaks score ties by memory order. Keep that order when refreshing immutable tours.
                    int index = carrier.getPlans().indexOf(best);
                    candidate.setCarrier(carrier);
                    carrier.getPlans().set(index,candidate);
                    select(carrier,candidate,cost.getAsDouble());
                    return;
                }
                carrier.removePlan(best);
            }
        }
    }

    private static CarrierPlan copy(CarrierPlan source) {
        var tours = new ArrayList<ScheduledTour>();
        for (var scheduled : source.getScheduledTours())
            tours.add(ScheduledTour.newInstance(scheduled.getTour().duplicate(),scheduled.getVehicle(),scheduled.getDeparture()));
        return new CarrierPlan(tours);
    }

    private static void select(Carrier carrier,CarrierPlan plan,double cost) {
        carrier.setSelectedPlan(plan);
        if (Boolean.TRUE.equals(carrier.getAttributes().getAttribute("hadFirstReplanning")))
            carrier.getAttributes().putAttribute("validatedRoutingCost",cost);
    }

    private static OptionalDouble routeCost(Carrier carrier,CarrierPlan candidate,ModalTransportCosts costs,ResolvedHagridConfig config) {
        try {
            CarrierRoadRouter.routePlan(candidate,costs);
            var replay = PlanValidator.validateNativeExecution(carrier,candidate,costs,config);
            return replay.valid() ? OptionalDouble.of(replay.finalCost()) : OptionalDouble.empty();
        } catch (IllegalArgumentException infeasible) {
            // Includes disconnected native roads and a shifted departure outside the captured horizon.
            return OptionalDouble.empty();
        }
    }

    private static CarrierPlan worstAlternative(Carrier carrier) {
        return carrier.getPlans().stream().filter(plan -> plan != carrier.getSelectedPlan())
                .min(Comparator.comparingDouble(plan -> plan.getScore() == null ? Double.NEGATIVE_INFINITY : plan.getScore()))
                .orElseThrow();
    }
}
