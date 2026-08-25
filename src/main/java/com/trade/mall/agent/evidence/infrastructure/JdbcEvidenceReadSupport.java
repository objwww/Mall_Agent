package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** JDBC（数据库访问）只读证据适配器的包内公共机械代码；不承载任何业务判定。 */
final class JdbcEvidenceReadSupport {
    private JdbcEvidenceReadSupport() {}

    static DataSource requireDataSource(DataSource dataSource) {
        return Objects.requireNonNull(dataSource, "dataSource");
    }

    static int timeoutSeconds(Duration queryTimeout) {
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        if (queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("queryTimeout must be > 0");
        }
        long seconds = Math.max(1L, queryTimeout.toSeconds());
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    static void requireOrderSn(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            throw new IllegalArgumentException("orderSn must not be blank");
        }
    }

    static <T> Optional<T> querySingle(DataSource dataSource, int queryTimeoutSeconds,
                                       String table, String orderSn, String sql, RowMapper<T> mapper) {
        requireOrderSn(orderSn);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            markReadOnly(connection);
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, orderSn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                T row = mapper.map(rs);
                if (rs.next()) {
                    throw new DataSourceUnavailableException(
                        table + " returned multiple rows for singular orderSn=" + orderSn
                            + "; refusing to guess which business record is authoritative");
                }
                return Optional.of(row);
            }
        } catch (DataSourceUnavailableException e) {
            throw e;
        } catch (SQLException e) {
            throw unavailable(table, orderSn, e);
        }
    }

    static void configureReadOnlyStatement(Connection connection, PreparedStatement ps, int queryTimeoutSeconds) {
        markReadOnly(connection);
        try {
            ps.setQueryTimeout(queryTimeoutSeconds);
        } catch (SQLException e) {
            throw unavailable("JDBC statement", "N/A", e);
        }
    }

    static long epochMillis(Timestamp timestamp) {
        return timestamp == null ? 0L : timestamp.getTime();
    }

    static Long nullableEpochMillis(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.getTime();
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static DataSourceUnavailableException unavailable(String table, String orderSn, SQLException e) {
        String message = e.getMessage();
        String detail = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        return new DataSourceUnavailableException(
            table + " query failed for orderSn=" + orderSn + ": " + detail, e);
    }

    private static void markReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException ignored) {
            // SELECT-only 数据库账号才是安全边界；驱动不支持 readOnly hint 不应把事实误判为不可用。
        }
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}

