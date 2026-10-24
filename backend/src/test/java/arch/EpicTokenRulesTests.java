package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.FhircastAccess;
import com.example.backend.auth.epic.controller.EpicFhircastController;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules in {@link ArchitectureTest} that keep Epic's tokens out of the browser (ADR 0012, D29;
 * ADR 0016), each shown reporting a planted controller and passing the one named exception — the
 * service's own controllers comply, so the rules alone would never be seen to fail.
 */
class EpicTokenRulesTests {

    @Test
    void anotherControllerReachingTheReleasedAccessTokenIsReported() {
        assertThat(violations(
                ArchitectureTest.the_released_access_token_reaches_one_rest_controller,
                ReleasesTheAccessToken.class))
                .contains("ReleasesTheAccessToken");
    }

    @Test
    void theFhircastReleaseIsTheOneControllerThatMayReachTheReleasedAccessToken() {
        assertThat(violations(
                ArchitectureTest.the_released_access_token_reaches_one_rest_controller,
                EpicFhircastController.class, EpicFhircastController.FhircastRelease.class))
                .isEmpty();
    }

    @Test
    void aControllerReachingTheKeptTokensIsReported() {
        assertThat(violations(ArchitectureTest.epic_tokens_never_reach_a_rest_controller,
                RendersTheKeptTokens.class))
                .contains("RendersTheKeptTokens");
    }

    /** The named exception reaches the access token alone, never the kept set. */
    @Test
    void theFhircastReleaseReachesNoneOfTheKeptTokens() {
        assertThat(violations(ArchitectureTest.epic_tokens_never_reach_a_rest_controller,
                EpicFhircastController.class))
                .isEmpty();
    }

    private static String violations(ArchRule rule, Class<?>... planted) {
        EvaluationResult result = rule.evaluate(new ClassFileImporter().importClasses(planted));
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    // ---- planted classes ----------------------------------------------------------------------

    @RestController
    static class ReleasesTheAccessToken {
        public Optional<FhircastAccess> release() {
            return Optional.empty();
        }
    }

    @RestController
    static class RendersTheKeptTokens {
        public Optional<EpicTokenSet> tokens() {
            return Optional.empty();
        }
    }
}
