package com.trade.mall.agent.runtime.infrastructure;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 独立 mall-agent 的最小 DataSource（数据源）。不引入连接池框架；生产若已有池化 DataSource，
 * DurableMallAgentRuntime 本身仍可直接接受它。这个实现只负责让 standalone main 可运行。
 */
public final class DriverManagerDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    public DriverManagerDataSource(String url, String user, String password) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("jdbc url must not be blank");
        this.url = url;
        this.user = Objects.requireNonNullElse(user, "");
        this.password = Objects.requireNonNullElse(password, "");
    }

    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    @Override public Connection getConnection(String username, String pwd) throws SQLException { return DriverManager.getConnection(url, username, pwd); }
    @Override public PrintWriter getLogWriter() throws SQLException { return DriverManager.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { DriverManager.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return DriverManager.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { if (iface.isInstance(this)) return iface.cast(this); throw new SQLException("not a wrapper for " + iface); }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}

