package com.example.backend.auth.epic.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.epic.controller.EpicFhircastController.FhircastRelease;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The FHIRcast release's answer as a value (ADR 0016): its text never names the access token or
 * the topic. What it answers, and to whom, is {@code EpicTokensIntegrationTests}'.
 */
class EpicFhircastControllerTests {

    @Test
    void theReleaseNamesNeitherTheAccessTokenNorTheTopic() {
        FhircastRelease release = new FhircastRelease("access-1",
                Instant.parse("2026-10-24T10:00:00Z"), List.of("launch", "openid"),
                "http://127.0.0.1:8103/hub", "topic-1");

        assertThat(release.toString()).isEqualTo("FhircastRelease[accessToken=<redacted>,"
                + " expiresAt=2026-10-24T10:00:00Z, scope=[launch, openid],"
                + " hubUrl=http://127.0.0.1:8103/hub, hubTopic=<redacted>]");
    }
}
