package com.example.backend;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * A database at the driver's boundary, for a unit test of an adapter that speaks JDBC: each
 * statement the adapter prepares is answered from a script, keyed on the start of its SQL, and
 * every statement it executes is recorded — its SQL, the arguments bound to it, and the query
 * timeout set on it — so the test reads back what reached the driver.
 *
 * <p>A query answers with a single-column result: its scripted rows, in order, one answer per
 * execution. An update answers with its scripted row count, or the failure scripted for it. A
 * statement the script does not name fails the test.
 */
public final class ScriptedJdbc {

    private final Map<String, Deque<Supplier<Object>>> answers = new LinkedHashMap<>();

    private final List<Executed> executed = new ArrayList<>();

    /** One statement as it reached the driver. */
    public record Executed(String sql, List<Object> arguments, int queryTimeoutSeconds) {
    }

    /** The next execution of a query starting {@code sql} returns these rows, of one column. */
    public ScriptedJdbc query(String sql, Object... rows) {
        List<Object> result = java.util.Arrays.asList(rows.clone());
        return answer(sql, () -> result);
    }

    /** The next execution of an update starting {@code sql} reports this many rows. */
    public ScriptedJdbc update(String sql, int rows) {
        return answer(sql, () -> rows);
    }

    /** The next execution of a statement starting {@code sql} fails with {@code failure}. */
    public ScriptedJdbc failing(String sql, SQLException failure) {
        return answer(sql, () -> {
            throw new Failed(failure);
        });
    }

    private ScriptedJdbc answer(String sql, Supplier<Object> answer) {
        answers.computeIfAbsent(sql, key -> new ArrayDeque<>()).add(answer);
        return this;
    }

    /** Every statement executed, in order. */
    public List<Executed> executed() {
        return List.copyOf(executed);
    }

    public DataSource dataSource() {
        return proxy(DataSource.class, "ScriptedJdbc", (self, method, arguments) ->
                switch (method.getName()) {
                    case "getConnection" -> connection();
                    case "getLoginTimeout" -> 0;
                    default -> null;
                });
    }

    private Connection connection() {
        return proxy(Connection.class, "ScriptedJdbc connection", (self, method, arguments) ->
                switch (method.getName()) {
                    case "prepareStatement" -> statement((String) arguments[0]);
                    case "getAutoCommit" -> true;
                    case "isReadOnly" -> false;
                    default -> null;
                });
    }

    private PreparedStatement statement(String sql) {
        Deque<Supplier<Object>> scripted = answers.entrySet().stream()
                .filter(entry -> sql.stripLeading().startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no answer scripted for: " + sql));
        Map<Integer, Object> bound = new java.util.TreeMap<>();
        int[] timeout = {0};
        return proxy(PreparedStatement.class, sql, (self, method, arguments) -> {
            String name = method.getName();
            if (name.startsWith("set") && arguments != null && arguments.length >= 2
                    && arguments[0] instanceof Integer index) {
                bound.put(index, name.equals("setNull") ? null : arguments[1]);
                return null;
            }
            return switch (name) {
                case "setQueryTimeout" -> {
                    timeout[0] = (Integer) arguments[0];
                    yield null;
                }
                case "executeQuery" -> rows(run(sql, bound, timeout[0], scripted));
                case "executeUpdate" -> (Integer) run(sql, bound, timeout[0], scripted);
                case "getUpdateCount" -> -1;
                default -> null;
            };
        });
    }

    private Object run(String sql, Map<Integer, Object> bound, int timeout,
            Deque<Supplier<Object>> scripted) throws SQLException {
        executed.add(new Executed(sql, new ArrayList<>(bound.values()), timeout));
        Supplier<Object> answer = scripted.poll();
        if (answer == null) {
            throw new AssertionError("no further answer scripted for: " + sql);
        }
        try {
            return answer.get();
        } catch (Failed failed) {
            throw failed.failure;
        }
    }

    private static ResultSet rows(Object answer) {
        List<?> rows = (List<?>) answer;
        int[] cursor = {-1};
        boolean[] wasNull = {false};
        ResultSetMetaData metaData = proxy(ResultSetMetaData.class, "ScriptedJdbc columns",
                (self, method, arguments) -> method.getName().equals("getColumnCount") ? 1 : null);
        return proxy(ResultSet.class, "ScriptedJdbc rows", (self, method, arguments) -> {
            String name = method.getName();
            if (name.equals("next")) {
                return ++cursor[0] < rows.size();
            }
            if (name.equals("getMetaData")) {
                return metaData;
            }
            if (name.equals("wasNull")) {
                return wasNull[0];
            }
            if (name.startsWith("get") && arguments != null && arguments.length >= 1) {
                Object value = rows.get(cursor[0]);
                wasNull[0] = value == null;
                return switch (name) {
                    case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                    case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                    case "getString" -> value == null ? null : value.toString();
                    default -> value;
                };
            }
            return null;
        });
    }

    /**
     * A proxy of {@code type}: the methods every proxy answers alike — {@code Object}'s, and the
     * open-and-unwrapped answers of JDBC's {@code isClosed} and {@code isWrapperFor} — are
     * answered here, once, and every other call goes to {@code handler}.
     */
    private static <T> T proxy(Class<T> type, String label, InvocationHandler handler) {
        InvocationHandler common = (self, method, arguments) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == arguments[0];
            case "toString" -> label;
            case "isClosed", "isWrapperFor" -> false;
            default -> handler.invoke(self, method, arguments);
        };
        return type.cast(Proxy.newProxyInstance(
                ScriptedJdbc.class.getClassLoader(), new Class<?>[] {type}, common));
    }

    /** A scripted failure, carried out of a supplier that may not throw a checked one. */
    private static final class Failed extends RuntimeException {

        private final SQLException failure;

        Failed(SQLException failure) {
            super(failure);
            this.failure = failure;
        }
    }
}
