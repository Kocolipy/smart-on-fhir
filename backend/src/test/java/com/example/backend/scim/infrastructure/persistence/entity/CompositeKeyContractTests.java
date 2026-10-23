package com.example.backend.scim.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The equality contract of the two JPA composite keys in this package.
 *
 * <p>These exist because Hibernate keys its persistence context by them: two reads of the same
 * membership must resolve to one managed instance, and a key whose {@code equals} ignored one of
 * its two columns would collapse distinct rows onto each other — a membership of the wrong Group,
 * or an {@code externalId} alias attributed to the wrong connector. Neither failure is visible
 * from an integration test, because the wrong answer is still a well-formed one.
 *
 * <p>Tested rather than justified for exactly that reason: nothing in the application calls
 * either class's {@code equals} in Java, so only a direct test can reach its branches, and "the framework calls it" is not evidence that it does
 * the right thing when it does.
 *
 * <p>Each case runs against both keys and walks all four branches — same instance, wrong type, one
 * field differing, the other field differing — because a two-field {@code equals} has a distinct way to be wrong
 * for each field, and an assertion on only one of them passes against a key that ignores the
 * other.
 */
class CompositeKeyContractTests {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    /**
     * Each key built from its two columns, beside a key of the other type: a Group membership is
     * (group, user), and an {@code externalId} alias (connector, resource) — connector-scoped, so
     * two connectors holding an alias for one resource are separate rows.
     */
    record KeyType(BiFunction<UUID, UUID, Object> key, BiFunction<UUID, UUID, Object> otherType) {}

    static Stream<Named<KeyType>> keys() {
        return Stream.of(
                Named.of("group membership",
                        new KeyType(ScimGroupMemberId::new, ScimExternalIdEntity.Key::new)),
                Named.of("externalId alias",
                        new KeyType(ScimExternalIdEntity.Key::new, ScimGroupMemberId::new)));
    }

    @ParameterizedTest
    @MethodSource("keys")
    void a_key_equals_itself_and_an_identical_key(KeyType type) {
        Object key = type.key().apply(FIRST, SECOND);

        assertThat(key).isEqualTo(key).isEqualTo(type.key().apply(FIRST, SECOND));
        assertThat(key.hashCode()).isEqualTo(type.key().apply(FIRST, SECOND).hashCode());
    }

    @ParameterizedTest
    @MethodSource("keys")
    void a_differing_first_column_makes_a_different_key(KeyType type) {
        assertThat(type.key().apply(FIRST, SECOND)).isNotEqualTo(type.key().apply(THIRD, SECOND));
        // Not a requirement of the equals/hashCode contract, but of the key's use: Hibernate's
        // persistence context keys entities by it, and a constant hash degrades every lookup
        // across a large membership to a linear scan.
        assertThat(type.key().apply(FIRST, SECOND).hashCode())
                .isNotEqualTo(type.key().apply(THIRD, SECOND).hashCode());
    }

    @ParameterizedTest
    @MethodSource("keys")
    void a_differing_second_column_makes_a_different_key(KeyType type) {
        assertThat(type.key().apply(FIRST, SECOND)).isNotEqualTo(type.key().apply(FIRST, THIRD));
    }

    @ParameterizedTest
    @MethodSource("keys")
    void a_value_of_another_type_is_not_equal(KeyType type) {
        assertThat(type.key().apply(FIRST, SECOND))
                .isNotEqualTo(type.otherType().apply(FIRST, SECOND))
                .isNotEqualTo(null)
                .isNotEqualTo("not a key");
    }

    /**
     * The property Hibernate actually relies on: equal keys collide in a hash container, so two
     * reads of one row resolve to one entry rather than two.
     */
    @ParameterizedTest
    @MethodSource("keys")
    void equal_keys_are_one_entry_in_a_hash_container(KeyType type) {
        Map<Object, String> byKey = new HashMap<>();
        byKey.put(type.key().apply(FIRST, SECOND), "first");
        byKey.put(type.key().apply(FIRST, SECOND), "second");
        byKey.put(type.key().apply(FIRST, THIRD), "other");

        assertThat(byKey).hasSize(2);
        assertThat(byKey.get(type.key().apply(FIRST, SECOND))).isEqualTo("second");
    }
}
