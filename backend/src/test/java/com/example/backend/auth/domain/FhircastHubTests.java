package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

/**
 * The FHIRcast hub an Epic token response named (ADR 0016): kept only on loopback, with a topic
 * within its grammar, and stored as a session attribute is.
 */
class FhircastHubTests {

    private static final String HUB = "http://127.0.0.1:8103/fhircast/STU3";

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:8103/fhircast/STU3",
        "https://127.0.0.1:443/hub",
        "http://127.0.0.1/hub",
        "HTTP://127.0.0.1:1/",
        "HTTPS://127.0.0.1:65535/hub?x=1"})
    void aLoopbackHttpHubIsKept(String url) {
        assertThat(FhircastHub.of(url, "topic").map(FhircastHub::url)).contains(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.2:8103/hub",
        "http://localhost:8103/hub",
        "http://[::1]:8103/hub",
        "http://10.0.0.1/hub",
        "http://127.0.0.1.example.org/hub",
        "http://user@127.0.0.1/hub",
        "http://user:pass@127.0.0.1/hub",
        "ws://127.0.0.1:8103/hub",
        "ftp://127.0.0.1/hub",
        "127.0.0.1:8103/hub",
        "/fhircast/STU3",
        "http://127.0.0.1:8103/has space",
        ""})
    void anyOtherHubIsDropped(String url) {
        assertThat(FhircastHub.of(url, "topic")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "Topic-1.2_3~4", "0123456789"})
    void aTopicWithinTheGrammarIsKept(String topic) {
        assertThat(FhircastHub.of(HUB, topic).map(FhircastHub::topic)).contains(topic);
    }

    @Test
    void aTopicOf128CharactersIsKept() {
        assertThat(FhircastHub.of(HUB, "t".repeat(128))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "a/b", "a%20b", "a+b", "ü", "a\nb"})
    void aTopicOutsideTheGrammarIsDropped(String topic) {
        assertThat(FhircastHub.of(HUB, topic)).isEmpty();
    }

    @Test
    void aTopicOf129CharactersIsDropped() {
        assertThat(FhircastHub.of(HUB, "t".repeat(129))).isEmpty();
    }

    @Test
    void membersThatAreNotStringsAreDropped() {
        assertThat(List.of(FhircastHub.of(7, "topic"), FhircastHub.of(HUB, Map.of()),
                FhircastHub.of(null, "topic"), FhircastHub.of(HUB, null)))
                .allSatisfy(hub -> assertThat(hub).isEmpty());
    }

    @Test
    void itNamesItsUrlButNotItsTopic() {
        assertThat(FhircastHub.of(HUB, "secret-topic").orElseThrow().toString())
                .isEqualTo("FhircastHub[url=" + HUB + ", topic=<redacted>]");
    }

    /** Through the serializer Spring Session's Redis repository writes session attributes with. */
    @Test
    void itRoundTripsAsASessionAttribute() {
        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();
        FhircastHub hub = FhircastHub.of(HUB, "topic-1").orElseThrow();

        FhircastHub stored = (FhircastHub) serializer.deserialize(serializer.serialize(hub));

        assertThat(List.of(stored.url(), stored.topic())).containsExactly(HUB, "topic-1");
    }
}
