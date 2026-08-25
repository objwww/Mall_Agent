package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.SkillSnapshot;
import com.trade.mall.agent.llm.SkillVersionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/** MySQL Skill 不可变版本仓库。 */
public final class JdbcSkillVersionStore implements SkillVersionStore {
    private final DataSource dataSource;
    private final LongSupplier clock;

    public JdbcSkillVersionStore(DataSource dataSource, LongSupplier clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override public SkillSnapshot current() {
        String sql = "SELECT skill_version,skill_instructions FROM agent_skill_version WHERE is_current=1 ORDER BY created_at DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("no current skill version configured");
            return new SkillSnapshot(rs.getString(1), rs.getString(2));
        } catch (SQLException e) { throw new IllegalStateException("cannot load current skill version", e); }
    }

    @Override public Optional<SkillSnapshot> find(String version) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT skill_version,skill_instructions FROM agent_skill_version WHERE skill_version=?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new SkillSnapshot(rs.getString(1), rs.getString(2))) : Optional.empty();
            }
        } catch (SQLException e) { throw new IllegalStateException("cannot load skill version: " + version, e); }
    }

    @Override public void publish(String version, String instructions) {
        SkillSnapshot snapshot = new SkillSnapshot(version, instructions);
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit(); c.setAutoCommit(false);
            try {
                lockHistory(c);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO agent_skill_version(skill_version,skill_instructions,is_current,created_at) VALUES (?,?,0,?)")) {
                    ps.setString(1, snapshot.version()); ps.setString(2, snapshot.instructions()); ps.setLong(3, clock.getAsLong()); ps.executeUpdate();
                }
                activate(c, version); c.commit();
            } catch (RuntimeException | SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally { try { c.setAutoCommit(autoCommit); } catch (SQLException ignored) {} }
        } catch (SQLException e) { throw new IllegalStateException("cannot publish skill version: " + version, e); }
    }

    @Override public List<SkillVersionInfo> history(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT skill_version,is_current,created_at FROM agent_skill_version ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SkillVersionInfo> result = new ArrayList<>();
                while (rs.next()) result.add(new SkillVersionInfo(rs.getString(1), rs.getBoolean(2), rs.getLong(3)));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw new IllegalStateException("cannot list skill versions", e); }
    }

    @Override public SkillSnapshot activate(String version) {
        SkillSnapshot snapshot = find(version).orElseThrow(() -> new IllegalArgumentException("skill version not found: " + version));
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit(); c.setAutoCommit(false);
            try { lockHistory(c); activate(c, version); c.commit(); return snapshot; }
            catch (RuntimeException | SQLException e) { try { c.rollback(); } catch (SQLException ignored) {} throw e; }
            finally { try { c.setAutoCommit(autoCommit); } catch (SQLException ignored) {} }
        } catch (SQLException e) { throw new IllegalStateException("cannot activate skill version: " + version, e); }
    }

    private static void lockHistory(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT skill_version FROM agent_skill_version ORDER BY skill_version FOR UPDATE");
             ResultSet rs = ps.executeQuery()) { while (rs.next()) { /* 串行化发布和切换。 */ } }
    }

    private static void activate(Connection c, String version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE agent_skill_version SET is_current=0 WHERE is_current=1")) { ps.executeUpdate(); }
        try (PreparedStatement ps = c.prepareStatement("UPDATE agent_skill_version SET is_current=1 WHERE skill_version=?")) {
            ps.setString(1, version);
            if (ps.executeUpdate() != 1) throw new IllegalArgumentException("skill version not found: " + version);
        }
    }
}
