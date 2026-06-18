package hagrid.utils.general;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StudyArea")
class StudyAreaTest {

    @Test
    @DisplayName("HANNOVER maps to the empty input subfolder (preserves legacy layout)")
    void hannoverFolderIsEmpty() {
        assertThat(StudyArea.HANNOVER.folder()).isEmpty();
    }

    @Test
    @DisplayName("LAUSITZ_HOYERSWERDA maps to the 'lausitz' input subfolder")
    void lausitzFolder() {
        assertThat(StudyArea.LAUSITZ_HOYERSWERDA.folder()).isEqualTo("lausitz");
    }

    @Test
    @DisplayName("valueOf is case-sensitive enum lookup")
    void valueOfRoundTrips() {
        assertThat(StudyArea.valueOf("HANNOVER")).isSameAs(StudyArea.HANNOVER);
        assertThat(StudyArea.valueOf("LAUSITZ_HOYERSWERDA")).isSameAs(StudyArea.LAUSITZ_HOYERSWERDA);
    }
}
