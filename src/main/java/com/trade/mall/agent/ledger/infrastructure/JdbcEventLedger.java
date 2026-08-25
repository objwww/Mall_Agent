package com.trade.mall.agent.ledger.infrastructure;

import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventLedger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC/MySQL（数据库）版事件账本。
 *
 * <p>独立调用 {@link #append(DomainEvent)} 时自己获取连接；Approval/Execution（审批/执行）
 * 仓储为了兑现“状态 + 事件同一本地事务”，会调用带 {@link Connection} 的重载，让事件和
 * 聚合状态使用同一条数据库连接提交。这里刻意不引入事务框架。</p>
 */
public final class JdbcEventLedger implements EventLedger {

    private final DataSource dataSource;

    public JdbcEventLedger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean append(DomainEvent event) {
        try (Connection connection = dataSource.getConnection()) {
            return append(connection, event);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot append event: " + event.eventId(), e);
        }
    }

    /** 同一事务内追加；仅把 MySQL 1062（event_id 主键重复）解释成幂等。 */
    public boolean append(Connection connection, DomainEvent event) throws SQLException {
        String sql = "INSERT INTO agent_event "
            + "(event_id, aggregate_id, event_type, seq_no, payload, occurred_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, event.eventId());
            ps.setString(2, event.aggregateId());
            ps.setString(3, event.eventType());
            ps.setInt(4, event.seqNo());
            ps.setString(5, event.payload());
            ps.setLong(6, event.occurredAt());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // MySQL duplicate-key vendor code = 1062。只吞主键重复，不使用 INSERT IGNORE，
            // 避免把字段截断/其他数据质量问题也悄悄降级成“幂等重复”。
            if (e.getErrorCode() == 1062) return false;
            throw e;
        }
    }

    @Override
    public boolean exists(String eventId) {
        try (Connection connection = dataSource.getConnection()) {
            return exists(connection, eventId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot query event: " + eventId, e);
        }
    }

    /** 供聚合仓储在自己的事务连接里做事件幂等预检。 */
    public boolean exists(Connection connection, String eventId) throws SQLException {
        String sql = "SELECT 1 FROM agent_event WHERE event_id = ? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public List<DomainEvent> eventsOf(String aggregateId) {
        String sql = "SELECT event_id, aggregate_id, event_type, seq_no, payload, occurred_at "
            + "FROM agent_event WHERE aggregate_id = ? ORDER BY occurred_at, event_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DomainEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new DomainEvent(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getLong(6)));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot query events for aggregate: " + aggregateId, e);
        }
    }
}

