package hagrid.demand;

import hagrid.utils.demand.Delivery.DeliveryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the delivery mode that a demand feature's {@code stop_type} attribute selects.
 */
@DisplayName("DeliveryGenerator.deliveryModeOf")
class DeliveryModeOfTest {

    @Test
    @DisplayName("lockers, shared boxes and pickup shops are out-of-home points")
    void outOfHomePoints() {
        assertThat(DeliveryGenerator.deliveryModeOf("locker")).isEqualTo(DeliveryMode.PARCEL_LOCKER_EXISTING);
        assertThat(DeliveryGenerator.deliveryModeOf("shared_locker")).isEqualTo(DeliveryMode.PARCEL_LOCKER_EXISTING);
        assertThat(DeliveryGenerator.deliveryModeOf("shop")).isEqualTo(DeliveryMode.PARCEL_LOCKER_EXISTING);
        assertThat(DeliveryGenerator.deliveryModeOf("counter")).isEqualTo(DeliveryMode.PARCEL_LOCKER_EXISTING);
    }

    @Test
    @DisplayName("home stops and older demand files without stop_type stay home deliveries")
    void homeDeliveries() {
        assertThat(DeliveryGenerator.deliveryModeOf("home")).isEqualTo(DeliveryMode.HOME);
        assertThat(DeliveryGenerator.deliveryModeOf(null)).isEqualTo(DeliveryMode.HOME);
    }
}
