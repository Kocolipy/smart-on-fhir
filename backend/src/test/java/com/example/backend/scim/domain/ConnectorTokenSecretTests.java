package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How a token value is minted, split, digested and compared. */
class ConnectorTokenSecretTests {

    private final SecureRandom random = new SecureRandom();

    @Test
    void a_minted_value_is_its_lookup_id_a_dot_and_a_secret() {
        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);

        assertThat(minted.presentedValue())
                .startsWith(minted.lookupId() + ".")
                .hasSizeGreaterThan(minted.lookupId().length() + 1);
    }

    /**
     * 256 bits of secret material, checked by decoding the half that carries it rather
     * than by asserting a character count — a base64 length assertion would pass on a
     * value that was padded rather than random.
     */
    @Test
    void the_secret_half_carries_at_least_two_hundred_and_fifty_six_bits() {
        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);
        String secret = minted.presentedValue()
                .substring(minted.presentedValue().indexOf('.') + 1);

        byte[] decoded = Base64.getUrlDecoder().decode(secret);

        assertThat(decoded).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    void the_value_is_url_safe_and_unpadded() {
        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);

        assertThat(minted.presentedValue()).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }

    /**
     * A thousand mints with no repeated lookup id. Not a test of the generator's
     * quality — that is {@link SecureRandom}'s — but of this class not reusing one, as
     * it would if the lookup id were derived from something with a small domain.
     */
    @Test
    void every_mint_produces_a_fresh_lookup_id_and_value() {
        Set<String> lookupIds = new HashSet<>();
        Set<String> values = new HashSet<>();

        for (int mint = 0; mint < 1000; mint++) {
            ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);
            lookupIds.add(minted.lookupId());
            values.add(minted.presentedValue());
        }

        assertThat(lookupIds).hasSize(1000);
        assertThat(values).hasSize(1000);
    }

    @Test
    void a_minted_value_parses_back_to_the_same_lookup_id_and_digest() {
        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);

        Optional<ConnectorTokenSecret.Presented> parsed =
                ConnectorTokenSecret.parse(minted.presentedValue());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().lookupId()).isEqualTo(minted.lookupId());
        assertThat(parsed.get().digest().matches(minted.digest())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "nodotatall",
        ".secretonly",
        "lookuponly.",
        "too.many.dots",
    })
    void a_value_that_is_not_one_of_this_services_tokens_does_not_parse(String value) {
        assertThat(ConnectorTokenSecret.parse(value)).isEmpty();
    }

    @Test
    void a_null_value_does_not_parse() {
        assertThat(ConnectorTokenSecret.parse(null)).isEmpty();
    }

    /**
     * The digest covers the COMPLETE value, not the secret half. Two values sharing a
     * secret but differing in lookup id must not digest alike, or a stolen secret would
     * authenticate as any token that reused it.
     */
    @Test
    void the_digest_covers_the_whole_value_not_only_the_secret() {
        ConnectorTokenDigest one = ConnectorTokenDigest.of("lookupA.sharedsecret");
        ConnectorTokenDigest other = ConnectorTokenDigest.of("lookupB.sharedsecret");

        assertThat(one.matches(other)).isFalse();
    }

    @Test
    void a_digest_survives_a_round_trip_through_storage() {
        ConnectorTokenDigest original = ConnectorTokenDigest.of("abc.def");

        ConnectorTokenDigest restored =
                ConnectorTokenDigest.ofStoredBytes(original.toStoredBytes());

        assertThat(restored.matches(original)).isTrue();
    }

    @Test
    void a_digest_is_thirty_two_bytes_of_sha256() {
        assertThat(ConnectorTokenDigest.of("abc.def").toStoredBytes()).hasSize(32);
    }

    /**
     * The stored bytes are a copy in both directions, so nothing can reach into a
     * digest and change what it compares against.
     */
    @Test
    void the_stored_bytes_cannot_be_mutated_in_place() {
        ConnectorTokenDigest digest = ConnectorTokenDigest.of("abc.def");
        byte[] handed = digest.toStoredBytes();

        handed[0] = (byte) ~handed[0];

        assertThat(digest.matches(ConnectorTokenDigest.of("abc.def"))).isTrue();

        byte[] source = ConnectorTokenDigest.of("abc.def").toStoredBytes();
        ConnectorTokenDigest fromStorage = ConnectorTokenDigest.ofStoredBytes(source);
        source[0] = (byte) ~source[0];

        assertThat(fromStorage.matches(ConnectorTokenDigest.of("abc.def"))).isTrue();
    }

    /**
     * A digest must never render as bytes, because a record's generated toString
     * would. Asserted as an exact equality rather than by searching the rendering for
     * hex pairs: some two-character hex pairs occur inside the word "redacted" itself,
     * so a contains-check would fail on a correct implementation.
     */
    @Test
    void a_digest_does_not_render_its_bytes() {
        ConnectorTokenDigest digest = ConnectorTokenDigest.of("abc.def");

        assertThat(digest.toString()).isEqualTo("ConnectorTokenDigest[redacted]");
        assertThat(digest.toString())
                .doesNotContain(Base64.getEncoder().encodeToString(digest.toStoredBytes()));
    }

    @Test
    void equal_digests_are_equal_and_hash_alike_while_a_different_one_is_not_equal() {
        ConnectorTokenDigest digest = ConnectorTokenDigest.of("abc.def");
        ConnectorTokenDigest same = ConnectorTokenDigest.of("abc.def");
        ConnectorTokenDigest other = ConnectorTokenDigest.of("abc.xyz");

        assertThat(digest).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(digest).isNotEqualTo(other);
        assertThat(digest).isNotEqualTo("abc.def");
    }
}
