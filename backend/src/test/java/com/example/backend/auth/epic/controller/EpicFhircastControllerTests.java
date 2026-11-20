package com.example.backend.auth.epic.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.FhircastAccess;
import com.example.backend.auth.domain.FhircastHub;
import com.example.backend.auth.epic.controller.EpicFhircastController.FhircastRelease;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The FHIRcast release's answer as a value (ADR 0016): what it answers of a {@link FhircastAccess},
 * and its text, which never names the access token or the topic. What it answers, and to whom,
 * over HTTP is {@code EpicTokensIntegrationTests}'.
 */
class EpicFhircastControllerTests {

    private static final FhircastHub HUB =
            FhircastHub.of("http://127.0.0.1:8103/hub", "topic-1").orElseThrow();

    @Test
    void aReleaseOfTheTokenAnswersItsExpiryItsScopeSortedTheHubAndTheSoftwareId() {
        FhircastAccess access = new FhircastAccess("access-1",
                Instant.parse("2026-10-24T10:00:00Z"), Set.of("openid", "launch", "fhirUser",
                        "fhircast/patient-open.read", "fhircast/patient-close.read", "profile",
                        "email", "address"), HUB);

        FhircastRelease release = FhircastRelease.of(access, "epic-client-id");

        assertThat(Arrays.asList(release.accessToken(), release.expiresAt(), release.scope(),
                release.hubUrl(), release.hubTopic(), release.softwareId())).containsExactly(
                        "access-1", Instant.parse("2026-10-24T10:00:00Z"),
                        List.of("address", "email", "fhirUser", "fhircast/patient-close.read",
                                "fhircast/patient-open.read", "launch", "openid", "profile"),
                        "http://127.0.0.1:8103/hub", "topic-1", "epic-client-id");
    }

    @Test
    void aReleaseOfTheHubAloneAnswersNoMemberOfTheToken() {
        FhircastAccess access = new FhircastAccess(null, null, Set.of(), HUB);

        FhircastRelease release = FhircastRelease.of(access, "epic-client-id");

        assertThat(Arrays.asList(release.accessToken(), release.expiresAt(), release.scope(),
                release.hubUrl(), release.hubTopic(), release.softwareId())).containsExactly(
                        null, null, null, "http://127.0.0.1:8103/hub", "topic-1",
                        "epic-client-id");
    }

    @Test
    void theReleaseNamesNeitherTheAccessTokenNorTheTopic() {
        FhircastRelease release = new FhircastRelease("access-1",
                Instant.parse("2026-10-24T10:00:00Z"), List.of("launch", "openid"),
                "http://127.0.0.1:8103/hub", "topic-1", "epic-client-id");

        assertThat(release.toString()).isEqualTo("FhircastRelease[accessToken=<redacted>,"
                + " expiresAt=2026-10-24T10:00:00Z, scope=[launch, openid],"
                + " hubUrl=http://127.0.0.1:8103/hub, hubTopic=<redacted>,"
                + " softwareId=epic-client-id]");
    }

    @Test
    void aReleaseOfTheHubAloneNamesTheAccessTokensAbsence() {
        FhircastRelease release = new FhircastRelease(null, null, null,
                "http://127.0.0.1:8103/hub", "topic-1", "epic-client-id");

        assertThat(release.toString()).isEqualTo("FhircastRelease[accessToken=<none>,"
                + " expiresAt=null, scope=null, hubUrl=http://127.0.0.1:8103/hub,"
                + " hubTopic=<redacted>, softwareId=epic-client-id]");
    }
}
