package com.trade.mall.agent.approval.infrastructure;

import com.trade.mall.agent.approval.AuthorizationPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * 复用 mall（商城后台）既有 RBAC（基于角色的权限控制）的只读授权适配器。
 * 只认启用管理员 + 已分配的工单处理资源；operationId（操作编号）不参与权限提升。
 */
public final class JdbcMallAdminAuthorizationPort implements AuthorizationPort {
    private static final String SUPPORT_MANAGE_RESOURCE = "/support/admin/tickets/**";
    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public JdbcMallAdminAuthorizationPort(DataSource dataSource, Duration queryTimeout) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        long seconds = Math.max(1, Objects.requireNonNull(queryTimeout, "queryTimeout").toSeconds());
        this.queryTimeoutSeconds = (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    @Override
    public boolean isAuthorizedApprover(String approverId, String operationId) {
        if (approverId == null || approverId.isBlank() || operationId == null || operationId.isBlank()) return false;
        long adminId;
        try { adminId = Long.parseLong(approverId); }
        catch (NumberFormatException invalid) { return false; }

        String sql = "SELECT 1 FROM ums_admin a "
            + "JOIN ums_admin_role_relation ar ON ar.admin_id=a.id "
            + "JOIN ums_role_resource_relation rr ON rr.role_id=ar.role_id "
            + "JOIN ums_resource r ON r.id=rr.resource_id "
            + "WHERE a.id=? AND a.status=1 AND r.url=? LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            c.setReadOnly(true);
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, adminId);
            ps.setString(2, SUPPORT_MANAGE_RESOURCE);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException unavailable) {
            // 授权系统不可用时 fail-closed（失败即收紧），绝不把“查不到权限”解释成允许。
            return false;
        }
    }
}

