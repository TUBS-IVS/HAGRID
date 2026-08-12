package hagrid.demand;

import hagrid.demand.CarrierServiceMerger.MergeSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the capacity-aware split of merged service groups. A merged stop must never exceed
 * what a single van can carry (jsprit may not split a service, and the fleet is INFINITE but of
 * identical vans), so a group whose pooled demand exceeds the per-run vehicle capacity is broken into
 * {@code ceil(total / segCap)} sub-stops of at most {@code segCap} parcels each. The split is a
 * strict no-op when the pooled demand already fits one van — which keeps high-capacity runs (segCap
 * >= total) byte-identical to the previous single-merged-service behaviour.
 */
@DisplayName("CarrierServiceMerger — capacity-aware split of merged groups")
class CarrierServiceMergerSplitTest {

    @Test
    @DisplayName("returns a single segment when the pooled demand already fits one van (no-op)")
    void noSplitWhenWithinCapacity() {
        List<MergeSegment> segs = CarrierServiceMerger.splitMergeGroup(25, 30, 1200.0, 10, 15);

        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).capacity()).isEqualTo(25);
        assertThat(segs.get(0).duration()).isCloseTo(1200.0, within(1e-9));
        assertThat(segs.get(0).b2b()).isEqualTo(10);
        assertThat(segs.get(0).b2c()).isEqualTo(15);
    }

    @Test
    @DisplayName("splits an oversized group into ceil(total/segCap) segments, each <= segCap")
    void splitsOversizedGroup() {
        List<MergeSegment> segs = CarrierServiceMerger.splitMergeGroup(179, 30, 1790.0, 179, 0);

        assertThat(segs).hasSize(6); // ceil(179/30)
        assertThat(segs).allSatisfy(s -> assertThat(s.capacity()).isBetween(1, 30));
        assertThat(segs.stream().mapToInt(MergeSegment::capacity).sum()).isEqualTo(179);
        // five full vans + a 29-parcel remainder
        assertThat(segs.stream().filter(s -> s.capacity() == 30).count()).isEqualTo(5);
        assertThat(segs.get(5).capacity()).isEqualTo(29);
    }

    @Test
    @DisplayName("distributes duration proportionally to each segment's capacity")
    void durationIsProportional() {
        List<MergeSegment> segs = CarrierServiceMerger.splitMergeGroup(100, 40, 1000.0, 0, 100);

        assertThat(segs).hasSize(3); // 40, 40, 20
        assertThat(segs.get(0).duration()).isCloseTo(400.0, within(1e-6));
        assertThat(segs.get(1).duration()).isCloseTo(400.0, within(1e-6));
        assertThat(segs.get(2).duration()).isCloseTo(200.0, within(1e-6));
        assertThat(segs.stream().mapToDouble(MergeSegment::duration).sum()).isCloseTo(1000.0, within(1e-6));
    }

    @Test
    @DisplayName("preserves the b2b/b2c totals exactly across the split (each segment sums to its capacity)")
    void preservesB2bB2cTotals() {
        // 100 parcels: 70 B2B + 30 B2C, capacity 40 -> segments [40, 40, 20]
        List<MergeSegment> segs = CarrierServiceMerger.splitMergeGroup(100, 40, 500.0, 70, 30);

        assertThat(segs.stream().mapToInt(MergeSegment::b2b).sum()).isEqualTo(70);
        assertThat(segs.stream().mapToInt(MergeSegment::b2c).sum()).isEqualTo(30);
        assertThat(segs).allSatisfy(s -> assertThat(s.b2b() + s.b2c()).isEqualTo(s.capacity()));
    }

    @Test
    @DisplayName("an exact multiple splits into equal full segments")
    void exactMultiple() {
        List<MergeSegment> segs = CarrierServiceMerger.splitMergeGroup(90, 30, 900.0, 0, 90);

        assertThat(segs).hasSize(3);
        assertThat(segs).allSatisfy(s -> assertThat(s.capacity()).isEqualTo(30));
    }
}
