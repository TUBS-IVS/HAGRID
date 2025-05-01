package hagrid.utils.general;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@Builder
public class HAGRIDSummary {
    private int totalDeliveries;
    private int totalB2BDeliveries;
    private int totalParcels;
    private int totalB2BParcels;
    private double b2bDeliveryRatio;
    private double b2bParcelRatio;
    private double averageWeight;
    private double averageB2BWeight;
    private int totalLockerDeliveries;
    private int totalLockerParcels;
    private double lockerDeliveryRatio;
    private double lockerParcelRatio;
    private int correctionFactor;  
}
