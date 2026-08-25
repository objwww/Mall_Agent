package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.PromptSnapshot;
import com.trade.mall.agent.llm.PromptVersionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * JDBC/MySQL（数据库）版提示词历史仓库。没有再造配置平台：一张 append-friendly（可追加）表
 * 保存 version（版本）+ prompt（正文），current 标记只决定新 Diagnosis 使用哪一版。
 */
public final class JdbcPromptVersionStore implements PromptVersionStore {
    private final DataSource dataSource;
    private final LongSupplier clock;

    public JdbcPromptVersionStore(DataSource dataSource, LongSupplier clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public PromptSnapshot current() {
        String sql = "SELECT prompt_version, prompt_text FROM agent_prompt_version WHERE is_current=1 "
            + "ORDER BY created_at DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("no current prompt version configured");
            return new PromptSnapshot(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            throw new IllegalStateException("cannot load current prompt version", e);
        }
    }

    @Override
    public Optional<PromptSnapshot> find(String version) {
        String sql = "SELECT prompt_version, prompt_text FROM agent_prompt_version WHERE prompt_version=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new PromptSnapshot(rs.getString(1), rs.getString(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot load prompt version: " + version, e);
        }
    }

    /** 发布新版本；版本内容不可覆盖，同一事务只保留一个 current。 */
    public void publish(String version, String prompt) {
        PromptSnapshot snapshot = new PromptSnapshot(version, prompt);
        try (Connection c = dataSource.getConnection()) {
            boolean ac = c.getAutoCommit(); c.setAutoCommit(false);
            try {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO agent_prompt_version(prompt_version,prompt_text,is_current,created_at) VALUES (?,?,0,?)")) {
                    insert.setString(1, snapshot.version()); insert.setString(2, snapshot.prompt()); insert.setLong(3, clock.getAsLong());
                    insert.executeUpdate();
                }
                try (PreparedStatement clear = c.prepareStatement("UPDATE agent_prompt_version SET is_current=0 WHERE is_current=1")) {
                    clear.executeUpdate();
                }
                try (PreparedStatement activate = c.prepareStatement("UPDATE agent_prompt_version SET is_current=1 WHERE prompt_version=?")) {
                    activate.setString(1, snapshot.version()); activate.executeUpdate();
                }
                c.commit();
            } catch (RuntimeException | SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally { try { c.setAutoCommit(ac); } catch (SQLException ignored) {} }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot publish prompt version: " + version, e);
        }
    }
}

