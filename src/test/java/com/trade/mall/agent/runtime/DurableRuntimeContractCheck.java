package com.trade.mall.agent.runtime;

import com.trade.mall.agent.alert.AlertPort;
import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.execution.port.ActionPort;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.llm.LlmClient;
import com.trade.mall.agent.llm.LlmRequest;
import com.trade.mall.agent.llm.LlmResponse;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisState;
import com.trade.mall.agent.orchestration.infrastructure.FileDiagnosisRunStore;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.ParamsHashing;
import com.trade.mall.agent.proposal.Proposal;
import com.trade.mall.agent.proposal.VerificationPlan;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** 不依赖 MySQL 的 V5 组装/恢复契约检查；数据库 SQL 本身由 V4 contract check 单独检查。 */
public final class DurableRuntimeContractCheck {
    private static int passed;

    public static void main(String[] args) throws Exception {
        proposalOwnsOperationIdentity();
        fileStoreCanScanExecutingCheckpoints();
        durableRuntimeCanBeComposedWithoutInMemoryRepositories();
        System.out.println("DurableRuntimeContractCheck: " + passed + "/" + passed + " passed");
    }

    private static void proposalOwnsOperationIdentity() {
        Map<String, String> params = Map.of("refundSn", "R-100", "amount", "100");
        Proposal refund = new Proposal("P-1", ActionType.REFUND_RETRY, params, ParamsHashing.sha256(params), "F-1",
            new VerificationPlan("REFUND_LOG", "verify current refund", "R-100", 10));
        check("R-100".equals(refund.operationId()), "refund operationId must reuse refundSn correlation");

        Map<String, String> readParams = Map.of("orderSn", "O-1");
        Proposal read = new Proposal("P-2", ActionType.ORDER_STATUS_RESYNC, readParams, ParamsHashing.sha256(readParams), "F-2",
            new VerificationPlan("ORDER_DB", "verify order state"));
        check("P-2".equals(read.operationId()), "non-correlated action should fall back to proposalId");
    }

    private static void fileStoreCanScanExecutingCheckpoints() throws Exception {
        Path dir = Files.createTempDirectory("mallagent-v5-checkpoint-");
        FileDiagnosisRunStore store = new FileDiagnosisRunStore(dir);
        store.save(new DiagnosisRun("T-1", "D-1", DiagnosisState.EXECUTING, 7,
            null, null, null, null, null, null));
        store.save(new DiagnosisRun("T-2", "D-2", DiagnosisState.AWAITING_APPROVAL, 5,
            null, null, null, null, "A-2", null));
        List<DiagnosisRun> executing = store.findByState(DiagnosisState.EXECUTING, 10);
        check(executing.size() == 1 && "D-1".equals(executing.get(0).diagnosisId()),
            "state scan must return only EXECUTING checkpoints");
    }

    private static void durableRuntimeCanBeComposedWithoutInMemoryRepositories() {
        DataSource noIoDataSource = new NoIoDataSource();
        AlertPort alerts = new AlertPort() {
            @Override public void critical(String code, String message) {}
            @Override public void warning(String code, String message) {}
        };
        ActionPort actionPort = new ActionPort() {
            @Override public PortOutcome execute(ActionCommand command) { return new PortOutcome.Inconclusive("not used"); }
            @Override public PortOutcome query(String operationId) { return new PortOutcome.Inconclusive("not used"); }
        };
        LlmClient llm = new LlmClient() {
            @Override public String modelId() { return "check-model"; }
            @Override public LlmResponse complete(LlmRequest request) { return new LlmResponse("{}", modelId()); }
            @Override public boolean healthy() { return true; }
            @Override public void shutdown(Duration grace) {}
        };

        try (DurableMallAgentRuntime runtime = new DurableMallAgentRuntime(
            noIoDataSource,
            modelId -> llm,
            new InMemoryPromptVersionStore("v1", "system"),
            "check-model",
            "tool-v1",
            List.of(),
            List.of(),
            (approverId, operationId) -> true,
            () -> true,
            actionPort,
            (actionType, params) -> {},
            alerts,
            System::currentTimeMillis)) {
            check(runtime.diagnosisRunStore().getClass().getSimpleName().equals("JdbcDiagnosisRunStore"),
                "runtime must wire JDBC diagnosis store");
            check(runtime.executionRepository().getClass().getSimpleName().equals("JdbcActionExecutionRepository"),
                "runtime must wire JDBC execution repository");
            check(runtime.eventLedger().getClass().getSimpleName().equals("JdbcEventLedger"),
                "runtime must wire JDBC event ledger");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    /** 构造期禁止发生数据库 I/O；真正连接只应在业务调用/恢复周期发生。 */
    private static final class NoIoDataSource implements DataSource {
        @Override public Connection getConnection() { throw new AssertionError("unexpected DB I/O during composition"); }
        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

