package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.DrtRequestInsertionRetryParams;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pending semantics for PARCELS ONLY:
 * <ul>
 *   <li>passenger rejections stay native-immediate (return {@code false}) — identical
 *       KPI semantics to DRT_BASELINE;</li>
 *   <li>parcels retry via the native queue until the global {@code maxRequestAge};</li>
 *   <li>BUT a parcel stops being retried once wall-clock passes its OWN delivery
 *       window (M5: B2B 17:00 / B2C 20:00), even when the global ceiling would still
 *       accept it — so post-window "deliveries" cannot occur and δ is a clean signal.</li>
 * </ul>
 */
@DisplayName("ParcelOnlyRetryQueue")
class ParcelOnlyRetryQueueTest {

    private static final double RETRY_INTERVAL = 300;
    private static final double MAX_REQUEST_AGE = 86400.0;
    private static final double B2B_WINDOW_END = 17 * 3600.0;   // 61200 s
    private static final Id<Person> B2B_PARCEL = Id.createPersonId("parcel_dhl_1_B2B");

    private static DrtRequestInsertionRetryParams params() {
        DrtRequestInsertionRetryParams p = new DrtRequestInsertionRetryParams();
        p.setRetryInterval((int) RETRY_INTERVAL);
        p.setMaxRequestAge(MAX_REQUEST_AGE);
        return p;
    }

    private static ParcelOnlyRetryQueue queue() {
        return new ParcelOnlyRetryQueue(params(), Map.of(B2B_PARCEL, B2B_WINDOW_END));
    }

    private static DrtRequest request(Id<Person> personId, double submissionTime) {
        return DrtRequest.newBuilder()
                .id(Id.create("r_" + personId, Request.class))
                .passengerIds(List.of(personId))
                .mode("drt")
                .submissionTime(submissionTime)
                .build();
    }

    @Test
    @DisplayName("passenger rejection is native-immediate (not enqueued)")
    void paxIsNotRetried() {
        assertFalse(queue().tryAddFailedRequest(request(Id.createPersonId("p42"), 100.0), 100.0));
    }

    @Test
    @DisplayName("parcel within its window is enqueued and re-emerges after the retry interval")
    void parcelIsEnqueuedAndReemerges() {
        ParcelOnlyRetryQueue queue = queue();
        // well before the 17:00 window
        double now = 8 * 3600.0;    // 28800 s
        DrtRequest parcel = request(B2B_PARCEL, now);
        assertTrue(queue.tryAddFailedRequest(parcel, now));

        double retryTime = now + RETRY_INTERVAL;
        List<DrtRequest> due = queue.getRequestsToRetryNow(retryTime);
        assertEquals(1, due.size());
        assertEquals(List.of(B2B_PARCEL), due.get(0).getPassengerIds());
    }

    @Test
    @DisplayName("parcel past its OWN window is dropped even though the global maxRequestAge would still accept it")
    void parcelPastItsWindowIsDropped() {
        // submitted at 07:30, wall-clock just past the 17:00 B2B window
        DrtRequest parcel = request(B2B_PARCEL, 7.5 * 3600.0);
        double now = B2B_WINDOW_END + 100.0;    // 61300 s > 61200 s window

        // sanity: the global ceiling alone would NOT reject here
        // (submission 27000 + 86400 = 113400 is far beyond now+interval = 61600)
        assertTrue(7.5 * 3600.0 + MAX_REQUEST_AGE >= now + RETRY_INTERVAL);

        assertFalse(queue().tryAddFailedRequest(parcel, now));
    }

    @Test
    @DisplayName("parcel exactly within its window (now == windowEnd) is still enqueued")
    void parcelAtWindowEndIsKept() {
        DrtRequest parcel = request(B2B_PARCEL, 7.5 * 3600.0);
        assertTrue(queue().tryAddFailedRequest(parcel, B2B_WINDOW_END));
    }

    @Test
    @DisplayName("RETRIEVAL path drops a parcel whose window passed AFTER it was enqueued (never re-attempted past its deadline)")
    void retrievalPathDropsParcelWhoseWindowPassedAfterEnqueue() {
        // Enqueued just BEFORE its window (16:58 < 17:00), so tryAddFailedRequest accepts it.
        ParcelOnlyRetryQueue queue = queue();
        double tEnqueue = B2B_WINDOW_END - 120.0;    // 61080 s = 16:58
        assertTrue(queue.tryAddFailedRequest(request(B2B_PARCEL, tEnqueue), tEnqueue),
                "a parcel enqueued before its window must be accepted");

        // Retrieved AFTER the window (17:05) AND after the retry interval (tEnqueue + 300 = 61380),
        // so the native, window-unaware queue WOULD hand it back here.
        double tAfter = B2B_WINDOW_END + 300.0;      // 61500 s = 17:05
        assertTrue(queue.hasRequestsToRetryNow(tAfter),
                "sanity: without the window guard the native queue is retry-interval-eligible here");
        // The window guard must filter it out: a past-window parcel is never re-attempted,
        // so it can never be scheduled/delivered past its deadline (M5).
        assertTrue(queue.getRequestsToRetryNow(tAfter).isEmpty(),
                "a parcel past its own delivery window must NOT be retrieved for re-attempt");
    }

    @Test
    @DisplayName("RETRIEVAL path positive control: a parcel still within its window IS returned")
    void retrievalPathReturnsParcelStillWithinWindow() {
        ParcelOnlyRetryQueue queue = queue();
        double tEnqueue = 8 * 3600.0;                // 28800 s = 08:00, well before the 17:00 window
        assertTrue(queue.tryAddFailedRequest(request(B2B_PARCEL, tEnqueue), tEnqueue));

        double tWithin = tEnqueue + RETRY_INTERVAL;  // 29100 s = 08:05, within the window & past the interval
        List<DrtRequest> due = queue.getRequestsToRetryNow(tWithin);
        assertEquals(1, due.size(), "a parcel within its window must still be retried");
        assertEquals(List.of(B2B_PARCEL), due.get(0).getPassengerIds());
    }
}
