package hagrid.core.routing.sections;
/** Known turns and unresolved reversals remain separate even when both incur the model tariff. */
public record SectionLeg(double meters, double seconds, int uTurns, int uncertainReversals) {
 public SectionLeg(double meters,double seconds,int uTurns){this(meters,seconds,uTurns,0);}
 public SectionLeg {if(uTurns<0||uncertainReversals<0)throw new IllegalArgumentException("Negative reversal count");}
 public int chargedTurns(){return Math.addExact(uTurns,uncertainReversals);}
}
