package hagrid.integrated.shareduse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ParcelAttributes} must REFUSE an incomplete parcel-person rather than let a caller
 * default it. Each default it replaces produced a plausible-but-wrong number with no crash:
 * load -> "1 parcel" (dwell collapses), channel -> DOOR (channel split skews), window -> never
 * expires (M5 silently off for that request).
 */
@DisplayName("ParcelAttributes — strict parcel-person snapshots")
class ParcelAttributesTest {

    private static Population population() {
        return PopulationUtils.createPopulation(ConfigUtils.createConfig());
    }

    /** A fully-attributed parcel-person, exactly as ParcelAgentGenerator writes it. */
    private static Person parcelPerson(Population pop, String id, int load, String channel) {
        Person p = pop.getFactory().createPerson(Id.createPersonId(id));
        p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, load);
        p.getAttributes().putAttribute(SharedUse.DWELL_ATTRIBUTE, SharedUse.segmentDwellSeconds(load));
        p.getAttributes().putAttribute(SharedUse.CHANNEL_ATTRIBUTE, channel);
        p.getAttributes().putAttribute(SharedUse.WINDOW_END_ATTRIBUTE, SharedUse.B2C_WINDOW_END_S);
        pop.addPerson(p);
        return p;
    }

    @Test
    @DisplayName("reads a well-formed population and ignores passengers")
    void readsWellFormedPopulation() {
        Population pop = population();
        parcelPerson(pop, "parcel_dhl_1_B2C", 3, "DOOR");
        parcelPerson(pop, "parcel_dhl_2_B2B", 25, "LOCKER");
        pop.addPerson(pop.getFactory().createPerson(Id.createPersonId("p42")));  // plain passenger

        assertThat(ParcelAttributes.loads(pop))
                .containsOnlyKeys(Id.createPersonId("parcel_dhl_1_B2C"),
                                  Id.createPersonId("parcel_dhl_2_B2B"))
                .containsEntry(Id.createPersonId("parcel_dhl_1_B2C"), 3)
                .containsEntry(Id.createPersonId("parcel_dhl_2_B2B"), 25);
        assertThat(ParcelAttributes.dwells(pop))
                .containsEntry(Id.createPersonId("parcel_dhl_1_B2C"), SharedUse.segmentDwellSeconds(3));
        assertThat(ParcelAttributes.channels(pop))
                .containsEntry(Id.createPersonId("parcel_dhl_2_B2B"), "LOCKER");
        assertThat(ParcelAttributes.windowEnds(pop))
                .containsEntry(Id.createPersonId("parcel_dhl_1_B2C"), SharedUse.B2C_WINDOW_END_S);
    }

    @Test
    @DisplayName("empty population (noParcels reference run) is valid, not an error")
    void emptyPopulationIsFine() {
        Population pop = population();
        pop.addPerson(pop.getFactory().createPerson(Id.createPersonId("p1")));

        assertThatCode(() -> ParcelAttributes.loads(pop)).doesNotThrowAnyException();
        assertThat(ParcelAttributes.loads(pop)).isEmpty();
    }

    @Test
    @DisplayName("missing load attribute aborts instead of defaulting to 1 parcel")
    void missingLoadThrows() {
        Population pop = population();
        Person p = parcelPerson(pop, "parcel_dhl_1_B2C", 3, "DOOR");
        p.getAttributes().removeAttribute(SharedUse.LOAD_ATTRIBUTE);

        assertThatThrownBy(() -> ParcelAttributes.loads(pop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parcel load")
                .hasMessageContaining(SharedUse.LOAD_ATTRIBUTE)
                .hasMessageContaining("parcel_dhl_1_B2C (absent)")
                .hasMessageContaining("PrepareLausitzDrtInputs");
    }

    @Test
    @DisplayName("non-numeric load (e.g. attribute written as text) aborts too")
    void nonNumericLoadThrows() {
        Population pop = population();
        Person p = parcelPerson(pop, "parcel_dhl_1_B2C", 3, "DOOR");
        p.getAttributes().putAttribute(SharedUse.LOAD_ATTRIBUTE, "3");   // String, not Number

        assertThatThrownBy(() -> ParcelAttributes.loads(pop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parcel_dhl_1_B2C ('3')");
    }

    @Test
    @DisplayName("unknown channel value is rejected, not silently bucketed as DOOR")
    void unknownChannelThrows() {
        Population pop = population();
        parcelPerson(pop, "parcel_dhl_1_B2C", 3, "Packstation");   // not a Channel enum name

        assertThatThrownBy(() -> ParcelAttributes.channels(pop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery channel")
                .hasMessageContaining("parcel_dhl_1_B2C ('Packstation')");
    }

    @Test
    @DisplayName("missing delivery window aborts instead of meaning 'never expires'")
    void missingWindowThrows() {
        Population pop = population();
        Person p = parcelPerson(pop, "parcel_dhl_1_B2C", 3, "DOOR");
        p.getAttributes().removeAttribute(SharedUse.WINDOW_END_ATTRIBUTE);

        assertThatThrownBy(() -> ParcelAttributes.windowEnds(pop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery window end");
    }

    @Test
    @DisplayName("all offenders are reported together, so one restart fixes the whole population")
    void reportsAllOffendersAtOnce() {
        Population pop = population();
        for (int i = 0; i < 3; i++) {
            Person p = parcelPerson(pop, "parcel_dhl_" + i + "_B2C", 2, "DOOR");
            p.getAttributes().removeAttribute(SharedUse.LOAD_ATTRIBUTE);
        }

        assertThatThrownBy(() -> ParcelAttributes.loads(pop))
                .hasMessageContaining("3 parcel-person(s)")
                .hasMessageContaining("parcel_dhl_0_B2C")
                .hasMessageContaining("parcel_dhl_1_B2C")
                .hasMessageContaining("parcel_dhl_2_B2C");
    }

    @Test
    @DisplayName("offender list is truncated so a broken 10k population does not dump 10k ids")
    void truncatesLongOffenderList() {
        Population pop = population();
        for (int i = 0; i < 25; i++) {
            Person p = parcelPerson(pop, "parcel_dhl_" + i + "_B2C", 2, "DOOR");
            p.getAttributes().removeAttribute(SharedUse.LOAD_ATTRIBUTE);
        }

        assertThatThrownBy(() -> ParcelAttributes.loads(pop))
                .hasMessageContaining("25 parcel-person(s)")
                .hasMessageContaining("(15 more)");
    }
}
