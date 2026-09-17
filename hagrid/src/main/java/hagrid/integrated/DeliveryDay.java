package hagrid.integrated;

/**
 * The delivery day shared by ALL Lausitz arms — Baseline, 1c Shared-Use and 1d Modular.
 *
 * <p><b>Why this class exists.</b> The three arms used to carry three different delivery windows:
 * the baseline 08:00–20:00 ({@code LmdCarrierBuilder}), 1c B2B 07:30–17:00 / B2C 07:30–20:00
 * ({@code SharedUse}) and 1d 07:30–21:00 ({@code Modular}). Since the integrated scenarios are
 * measured <em>against</em> the baseline, a narrower baseline window is not a neutral difference:
 * less time to deliver means a systematically lower delivery rate, so part of the integration
 * "benefit" would have been an artefact of the window. Unified to one constant on 2026-07-30
 * (user decision) so the arms cannot drift apart again — that drift is exactly what a per-scenario
 * constant invites.
 *
 * <p><b>Scope: Lausitz only.</b> The Hannover pipeline keeps its own window
 * ({@code HagridConfig.Routing.deliveryWindowStartHour/EndHour} = 8/20). It must NOT be aligned to
 * this one: the Hannover capacity-sensitivity sweep is a separate study whose runs would all become
 * incomparable.
 *
 * <p><b>Limitation to carry into the methods chapter.</b> 21:00 applies to B2B parcels too, and a
 * business recipient is not present at 21:00. That is a deliberate trade: comparability across arms
 * beats per-type realism, and 1d already had this property by design (its day window makes no
 * B2B/B2C distinction). See METHODS-LOG §1.2.
 *
 * <p>The window bounds the <em>service start</em>; a vehicle still has to complete the stop and
 * return, so the last delivery lands somewhat before {@link #END_S}.
 */
public final class DeliveryDay {

    /** 07:30 — parcels arrive at the depot overnight, so the delivery day starts early. */
    public static final double START_S = 7.5 * 3600.0;

    /** 21:00 — unified across all three arms (user decision 2026-07-30). */
    public static final double END_S = 21 * 3600.0;

    private DeliveryDay() {
    }
}
