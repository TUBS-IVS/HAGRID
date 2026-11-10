package hagrid.lausitz.freight;

import hagrid.core.util.CodeFingerprint;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class JarFingerprintAdapterTest {
    @Test void theLegacyTestOverrideIsLocalAndCanBeReset() {
        JarFingerprint.setOverrideForTests("forced");
        try {
            assertThat(JarFingerprint.ofRunningCode()).isEqualTo(Optional.of("forced"));
            assertThat(CodeFingerprint.ofRunningCode()).isEmpty();
        } finally {
            JarFingerprint.setOverrideForTests(null);
        }
        assertThat(JarFingerprint.ofRunningCode()).isEqualTo(CodeFingerprint.ofRunningCode()).isEmpty();
    }
}
