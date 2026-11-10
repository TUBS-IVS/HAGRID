package hagrid.core.simulation.scoring;

/** Monetary components; known turns and uncertain reversals affect utility separately. Durations are seconds, distances meters. */
public record CarrierCostBreakdown(double fixed, double distance, double travelTime, double activity,
        double overtime, double timeWindowPenalty, double totalDistanceMeters, double totalTravelTimeSeconds, int uTurnCount, int uncertainReversalCount) {
    public CarrierCostBreakdown(double fixed,double distance,double travelTime,double activity,double overtime,double timeWindowPenalty,double totalDistanceMeters,double totalTravelTimeSeconds,int uTurnCount) {this(fixed,distance,travelTime,activity,overtime,timeWindowPenalty,totalDistanceMeters,totalTravelTimeSeconds,uTurnCount,0);}
    public int chargedReversalCount(){return Math.addExact(uTurnCount,uncertainReversalCount);}
    public CarrierCostBreakdown {
        for (double value : new double[]{fixed,distance,travelTime,activity,overtime,timeWindowPenalty,totalDistanceMeters,totalTravelTimeSeconds})
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid carrier cost component " + value);
        if (uTurnCount < 0 || uncertainReversalCount < 0 || !Double.isFinite(fixed+distance+travelTime+activity+overtime+timeWindowPenalty))
            throw new IllegalArgumentException("Invalid carrier cost total/count");
    }
    public double totalCosts() { return fixed + distance + travelTime + activity + overtime + timeWindowPenalty; }
}
