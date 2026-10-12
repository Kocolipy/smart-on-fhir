package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Semgrep {@code be-log-record-outside-log-event} is held to {@link LogEvent} itself, so the
 * guard cannot fall behind the module it guards: the shapes its message tells a call site to
 * use are exactly the module's public shapes, and its patterns refuse a record opened on a
 * logger at every slf4j level. {@code semgrep/tests/service-security.java} is the other half —
 * the records it plants are what the rule must report, checked by {@code semgrep --test} in the
 * gate's Semgrep step.
 */
class LogRecordGuardTests {

    private static final Path RULES = Path.of("semgrep", "rules", "service-security.yml");

    private static final String RULE_ID = "be-log-record-outside-log-event";

    /** Where the message lists the shapes: after this phrase, up to the sentence's full stop. */
    private static final Pattern SHAPE_LIST =
            Pattern.compile("one of LogEvent's shapes: ([^.]+)\\.");

    private static Map<String, Object> rule;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readTheRule() throws IOException {
        try (Reader reader = Files.newBufferedReader(RULES)) {
            Map<String, Object> document = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(reader);
            rule = ((List<Map<String, Object>>) document.get("rules")).stream()
                    .filter(each -> RULE_ID.equals(each.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(RULE_ID + " is not in " + RULES));
        }
    }

    /** The message names every shape {@link LogEvent} exposes, and nothing it does not. */
    @Test
    void theMessageListsEveryShapeTheModuleExposes() {
        Matcher listed = SHAPE_LIST.matcher(String.valueOf(rule.get("message")));
        assertThat(listed.find()).as("the message lists the shapes").isTrue();
        Set<String> named = Arrays.stream(listed.group(1).split(","))
                .map(String::strip)
                .collect(Collectors.toSet());

        assertThat(named).containsExactlyInAnyOrderElementsOf(shapes());
    }

    /** The module's shapes are the fifteen ADR 0003's "Records are built in shapes" names. */
    @Test
    void theModuleExposesTheShapesTheAdrNames() {
        assertThat(shapes()).containsExactlyInAnyOrder(
                "success", "successAtWarn", "refused", "error", "withError", "jobScheduled",
                "jobStart", "jobEnd", "jobFailed", "jobSummary", "requestEnd", "outboundStart",
                "outboundEnd", "outboundFailed", "jwksRefetchWarning");
    }

    /**
     * A record opened on a logger is refused at every level — {@code ERROR} among them — by the
     * fluent opener and the classic call alike, under either spelling of the logger's type, and
     * so is the level-agnostic opener.
     */
    @Test
    void thePatternsRefuseARecordOpenedAtEveryLevel() {
        Set<String> patterns = patterns();
        for (String type : List.of("Logger", "org.slf4j.Logger")) {
            for (Level level : Level.values()) {
                String name = level.name().toLowerCase(Locale.ROOT);
                String fluent = "at" + name.substring(0, 1).toUpperCase(Locale.ROOT)
                        + name.substring(1);
                assertThat(patterns).contains(
                        "(" + type + " $LOG)." + fluent + "()",
                        "(" + type + " $LOG)." + name + "(...)");
            }
            assertThat(patterns).contains(
                    "(" + type + " $LOG).atLevel(...)",
                    "(" + type + " $LOG).makeLoggingEventBuilder(...)");
        }
    }

    /** {@link LogEvent}'s public static methods that build a record: the shapes. */
    private static Set<String> shapes() {
        return Arrays.stream(LogEvent.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == LoggingEventBuilder.class)
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> patterns() {
        List<Map<String, Object>> clauses = (List<Map<String, Object>>) rule.get("patterns");
        return clauses.stream()
                .filter(clause -> clause.containsKey("pattern-either"))
                .flatMap(clause -> ((List<Map<String, String>>) clause.get("pattern-either"))
                        .stream())
                .map(alternative -> alternative.get("pattern"))
                .collect(Collectors.toSet());
    }
}
