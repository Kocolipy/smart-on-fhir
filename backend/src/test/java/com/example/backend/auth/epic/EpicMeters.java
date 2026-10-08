package com.example.backend.auth.epic;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Epic Login's meters as one reading: every series of the {@code epic.login} counter, the
 * {@code epic.outbound} timer and the {@code epic.outbound.errors} counter, each by its name and
 * every tag it carries — {@code epic.login{outcome=refused,reason=INVALID_STATE}} — to its count.
 *
 * <p>A test reads them before and after a Login and compares the {@link #change}, so a series
 * with an unexpected tag, or an expected one that did not move, fails the same comparison.
 */
final class EpicMeters {

    private static final String[] NAMES = {"epic.login", "epic.outbound", "epic.outbound.errors"};

    private EpicMeters() {
    }

    /**
     * How an Epic Login ended, as the meters see it: its {@code epic.login} outcome and reason,
     * the calls to Epic it timed on {@code epic.outbound}, and the call it counted on
     * {@code epic.outbound.errors}, if any.
     */
    record Ending(String outcome, String reason, List<String> calls, String failedCall) {

        /** A Login that signed the clinician in, having timed {@code calls}. */
        static Ending success(String... calls) {
            return new Ending("success", "none", List.of(calls), null);
        }

        /** A Login refused for {@code reason}, having timed {@code calls}. */
        static Ending refused(String reason, String... calls) {
            return new Ending("refused", reason, List.of(calls), null);
        }

        /**
         * A Login Epic was unavailable for, as {@code failedCall} failed, having timed
         * {@code calls}.
         */
        static Ending unavailable(String failedCall, String... calls) {
            return new Ending("unavailable", "EPIC_UNAVAILABLE", List.of(calls), failedCall);
        }

        /** The {@link #change} a Login ending this way makes, and nothing else. */
        Map<String, Double> expected() {
            Map<String, Double> expected = new TreeMap<>();
            expected.put(series("epic.login", "outcome", outcome, "reason", reason), 1.0);
            for (String call : calls) {
                expected.merge(series("epic.outbound", "call", call), 1.0, Double::sum);
            }
            if (failedCall != null) {
                expected.put(series("epic.outbound.errors", "call", failedCall), 1.0);
            }
            return expected;
        }
    }

    /** Every Epic Login series in {@code meters} now, to its count. */
    static Map<String, Double> read(MeterRegistry meters) {
        Map<String, Double> counts = new TreeMap<>();
        for (String name : NAMES) {
            for (Meter meter : meters.find(name).meters()) {
                counts.put(key(meter), count(meter));
            }
        }
        return counts;
    }

    /** Each series whose count moved from {@code before} to {@code after}, to how far it moved. */
    static Map<String, Double> change(Map<String, Double> before, Map<String, Double> after) {
        Map<String, Double> moved = new TreeMap<>();
        after.forEach((series, count) -> {
            double change = count - before.getOrDefault(series, 0.0);
            if (change != 0) {
                moved.put(series, change);
            }
        });
        return moved;
    }

    /** {@code name{key=value,…}}, the tags in key order. */
    static String series(String name, String... tags) {
        StringBuilder key = new StringBuilder(name).append('{');
        for (int i = 0; i < tags.length; i += 2) {
            key.append(i == 0 ? "" : ",").append(tags[i]).append('=').append(tags[i + 1]);
        }
        return key.append('}').toString();
    }

    private static String key(Meter meter) {
        return meter.getId().getName() + meter.getId().getTags().stream()
                .map(tag -> tag.getKey() + "=" + tag.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static double count(Meter meter) {
        return switch (meter) {
            case Counter counter -> counter.count();
            case Timer timer -> timer.count();
            default -> throw new IllegalStateException("not an Epic Login meter: " + meter.getId());
        };
    }
}
