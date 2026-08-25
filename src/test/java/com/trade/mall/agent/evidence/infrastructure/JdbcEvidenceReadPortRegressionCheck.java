package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.collector.RefundEvidenceCollector;
import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;
import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.OrderRecord;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.runtime.DurableMallAgentRuntime;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/** V10：真实 JDBC（数据库）证据读取适配器的失败语义回归检查，不依赖真实 MySQL。 */
public final class JdbcEvidenceReadPortRegressionCheck {
    private static int passed;

    public static void main(String[] args) {
        orderMapsAndSetsDriverTimeout();
        refundAmbiguityFailsClosed();
        sqlFailureBecomesUnavailableNotEmpty();
        afterSaleAmbiguityFailsClosed();
        refundLogOrderAndFieldsArePreserved();
        refundLogOverflowFailsClosedInsteadOfTruncating();
        runtimeExposesSeparateEvidenceReadDataSourceConstructor();
        timeoutMustBePositive();
        System.out.println("V10 JdbcEvidenceReadPortRegressionCheck: " + passed + " / 8");
    }

    private static void orderMapsAndSetsDriverTimeout() {
        FakeDataSource ds = new FakeDataSource((sql, params) -> List.of(row(
            "id", 7L, "order_sn", "O-7", "status", 1, "member_username", "alice",
            "pay_amount", new BigDecimal("12.34"), "create_time", new Timestamp(1234L))));
        Optional<OrderRecord> found = new JdbcOrderReadPort(ds, Duration.ofSeconds(1)).findByOrderSn("O-7");
        check(found.isPresent(), "order should be present");
        check(found.get().id() == 7L && "O-7".equals(found.get().orderSn()), "order mapping");
        check(ds.lastQueryTimeout == 1, "JDBC query timeout must be set");
        check(ds.readOnlyHintSeen, "readOnly hint should be attempted");
        passed++;
    }

    private static void refundAmbiguityFailsClosed() {
        List<Map<String,Object>> rows = List.of(refundRow(1L, "R-1"), refundRow(2L, "R-2"));
        FakeDataSource ds = new FakeDataSource((sql, params) -> rows);
        JdbcRefundReadPort port = new JdbcRefundReadPort(ds, Duration.ofSeconds(1));
        expect(DataSourceUnavailableException.class, () -> port.findByOrderSn("O-1"),
            "multiple refunds must not silently choose one");
        EvidenceResult<?> result = new RefundEvidenceCollector(port).collect("O-1");
        check(result instanceof EvidenceResult.Unavailable<?>, "ambiguous refund must become UNAVAILABLE");
        passed++;
    }

    private static void sqlFailureBecomesUnavailableNotEmpty() {
        FakeDataSource ds = new FakeDataSource((sql, params) -> { throw new SqlFailure("socket timeout"); });
        EvidenceResult<?> result = new RefundEvidenceCollector(
            new JdbcRefundReadPort(ds, Duration.ofSeconds(1))).collect("O-2");
        check(result instanceof EvidenceResult.Unavailable<?>, "SQL failure must be UNAVAILABLE");
        check(!(result instanceof EvidenceResult.Empty<?>), "SQL failure must never be EMPTY");
        passed++;
    }

    private static void afterSaleAmbiguityFailsClosed() {
        FakeDataSource ds = new FakeDataSource((sql, params) -> List.of(
            row("id", 1L, "order_sn", "O-3", "status", 40, "reason", "a", "handle_note", "n1"),
            row("id", 2L, "order_sn", "O-3", "status", 40, "reason", "b", "handle_note", "n2")));
        expect(DataSourceUnavailableException.class,
            () -> new JdbcAfterSaleReadPort(ds, Duration.ofSeconds(1)).findByOrderSn("O-3"),
            "multiple after-sales must fail closed");
        passed++;
    }

    private static void refundLogOrderAndFieldsArePreserved() {
        FakeDataSource ds = new FakeDataSource((sql, params) -> List.of(
            logRow(11L, "CHANNEL_UNKNOWN", 0),
            logRow(12L, "CHANNEL_SUCCESS", 1)));
        List<RefundLogRecord> rows = new JdbcRefundLogReadPort(ds, Duration.ofSeconds(1)).findByOrderSn("O-4");
        check(rows.size() == 2 && rows.get(0).id() == 11L && rows.get(1).id() == 12L,
            "refund log sequence must be preserved");
        check(!rows.get(0).success() && rows.get(1).success(), "success flag mapping");
        check(ds.lastQueryTimeout == 1, "refund log query timeout must be set");
        passed++;
    }

    private static void refundLogOverflowFailsClosedInsteadOfTruncating() {
        List<Map<String,Object>> many = new ArrayList<>();
        for (int i = 1; i <= JdbcRefundLogReadPort.MAX_ROWS + 1; i++) {
            many.add(logRow(i, "REFUND_EXECUTE", 1));
        }
        FakeDataSource ds = new FakeDataSource((sql, params) -> many);
        expect(DataSourceUnavailableException.class,
            () -> new JdbcRefundLogReadPort(ds, Duration.ofSeconds(1)).findByOrderSn("O-5"),
            "oversized log history must not be silently truncated");
        passed++;
    }

    private static void runtimeExposesSeparateEvidenceReadDataSourceConstructor() {
        boolean found = java.util.Arrays.stream(DurableMallAgentRuntime.class.getConstructors())
            .map(c -> c.getParameterTypes())
            .anyMatch(types -> types.length >= 2 && types[0] == DataSource.class && types[1] == DataSource.class);
        check(found, "runtime must support separate runtime/evidence DataSource");
        passed++;
    }

    private static void timeoutMustBePositive() {
        expect(IllegalArgumentException.class,
            () -> new JdbcOrderReadPort(new FakeDataSource((sql, params) -> List.of()), Duration.ZERO),
            "zero timeout must be rejected");
        passed++;
    }

    private static Map<String,Object> refundRow(long id, String refundSn) {
        return row("id", id, "refund_sn", refundSn, "return_apply_id", 9L, "order_sn", "O-1",
            "status", 1, "refund_amount", new BigDecimal("10.00"), "error_msg", null, "finish_time", null);
    }

    private static Map<String,Object> logRow(long id, String action, int success) {
        return row("id", id, "refund_sn", "R-4", "order_sn", "O-4", "action", action,
            "channel_code", "mock", "success", success, "error_code", null, "error_msg", null,
            "trace_id", "T-" + id, "create_time", new Timestamp(1000L + id));
    }

    private static Map<String,Object> row(Object... kv) {
        Map<String,Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Throwable> void expect(Class<T> type, Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + " (no exception)");
        } catch (Throwable t) {
            if (!type.isInstance(t)) throw new AssertionError(message + " (got " + t + ")", t);
        }
    }

    private static final class SqlFailure extends RuntimeException {
        private SqlFailure(String message) { super(message); }
    }

    private static final class FakeDataSource implements DataSource {
        private final BiFunction<String, Map<Integer,Object>, List<Map<String,Object>>> provider;
        int lastQueryTimeout;
        boolean readOnlyHintSeen;

        FakeDataSource(BiFunction<String, Map<Integer,Object>, List<Map<String,Object>>> provider) {
            this.provider = provider;
        }

        @Override public Connection getConnection() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "setReadOnly" -> { readOnlyHintSeen = true; yield null; }
                case "close" -> null;
                case "isClosed" -> false;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                default -> defaultValue(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement statement(String sql) {
            Map<Integer,Object> params = new HashMap<>();
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setQueryTimeout" -> { lastQueryTimeout = (Integer) args[0]; yield null; }
                case "setString", "setInt", "setLong", "setObject" -> { params.put((Integer) args[0], args[1]); yield null; }
                case "executeQuery" -> {
                    List<Map<String,Object>> rows;
                    try {
                        rows = provider.apply(sql, Map.copyOf(params));
                    } catch (SqlFailure e) {
                        throw new SQLException(e.getMessage(), e);
                    }
                    yield resultSet(rows);
                }
                case "close" -> null;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, handler);
        }

        private ResultSet resultSet(List<Map<String,Object>> rows) {
            final int[] index = {-1};
            final Object[] last = {null};
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getString" -> { last[0] = value(rows, index[0], args[0]); yield last[0] == null ? null : String.valueOf(last[0]); }
                case "getLong" -> { last[0] = value(rows, index[0], args[0]); yield last[0] == null ? 0L : ((Number) last[0]).longValue(); }
                case "getInt" -> { last[0] = value(rows, index[0], args[0]); yield last[0] == null ? 0 : ((Number) last[0]).intValue(); }
                case "getBigDecimal" -> { last[0] = value(rows, index[0], args[0]); yield (BigDecimal) last[0]; }
                case "getTimestamp" -> { last[0] = value(rows, index[0], args[0]); yield (Timestamp) last[0]; }
                case "wasNull" -> last[0] == null;
                case "close" -> null;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                default -> defaultValue(method.getReturnType());
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, handler);
        }

        private static Object value(List<Map<String,Object>> rows, int index, Object key) {
            if (index < 0 || index >= rows.size()) throw new IllegalStateException("cursor not on row");
            if (!(key instanceof String column)) throw new UnsupportedOperationException("only column labels supported");
            return rows.get(index).get(column);
        }

        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }
}

