package hagrid.core.routing.sections;
/** Explicit reproducible benchmark iteration and ruin policy. */
public record SectionSearchPolicy(int exactIterations,double radialShare,double randomShare,boolean fastRegret) {
 public SectionSearchPolicy(int exactIterations,double radialShare,double randomShare) {
  this(exactIterations,radialShare,randomShare,false);
 }
 public SectionSearchPolicy {
  if(exactIterations<=0 || !Double.isFinite(radialShare)||radialShare<=0||radialShare>1 || !Double.isFinite(randomShare)||randomShare<=0||randomShare>1)
   throw new IllegalArgumentException("Invalid section search policy");
 }
}
