package com.example.backend.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.infrastructure.persistence.entity.AuditEventEntity;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The two write paths into {@code audit_events} — the entity the fail-closed path persists, and
 * the hand-written {@code INSERT} the fail-open path runs on the audit append pool — write the same
 * columns. A column added to one and not the other is a row half the events would leave empty.
 */
class IsolatedAuditEventPersistenceAdapterTests {

    private static final Pattern COLUMN_LIST =
            Pattern.compile("INSERT INTO audit_events \\(([^)]*)\\)", Pattern.DOTALL);

    @Test
    void theFailOpenInsertWritesExactlyTheColumnsTheEntityMaps() {
        assertThat(insertColumns()).containsExactlyInAnyOrderElementsOf(entityColumns());
    }

    private static List<String> insertColumns() {
        Matcher matcher = COLUMN_LIST.matcher(IsolatedAuditEventPersistenceAdapter.INSERT);
        assertThat(matcher.find()).as("the INSERT names its columns").isTrue();
        return Arrays.stream(matcher.group(1).split(",")).map(String::strip).toList();
    }

    /** Each mapped field's column, by its explicit name or Boot's default snake-case naming. */
    private static List<String> entityColumns() {
        return Arrays.stream(AuditEventEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Column.class))
                .map(IsolatedAuditEventPersistenceAdapterTests::columnName)
                .toList();
    }

    private static String columnName(Field field) {
        String explicit = field.getAnnotation(Column.class).name();
        return explicit.isEmpty()
                ? field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase()
                : explicit;
    }
}
