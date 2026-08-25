package com.trade.mall.agent.orchestration.infrastructure;

import com.trade.mall.agent.orchestration.NonFundExecutionStore;
import com.trade.mall.agent.proposal.ActionType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.LongSupplier;

/** JDBC/MySQL（数据库）版非资金动作最小耐久记录。 */
public final class JdbcNonFundExecutionStore implements NonFundExecutionStore {
    private final DataSource dataSource;
    private final LongSupplier clock;
    public JdbcNonFundExecutionStore(DataSource dataSource, LongSupplier clock) { this.dataSource=dataSource; this.clock=clock; }

    @Override public Optional<Entry> find(String operationId) {
        String sql="SELECT action_type,params_hash,state FROM agent_nonfund_execution WHERE operation_id=?";
        try(Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setString(1,operationId); try(ResultSet rs=ps.executeQuery()) {
                return rs.next()?Optional.of(new Entry(operationId, ActionType.valueOf(rs.getString(1)), rs.getString(2), State.valueOf(rs.getString(3)))):Optional.empty();
            }
        } catch(SQLException e){ throw new IllegalStateException("cannot load non-fund execution: "+operationId,e); }
    }

    @Override public void createPending(String operationId, ActionType actionType, String paramsHash) {
        Entry old=find(operationId).orElse(null);
        if(old!=null){
            if(old.actionType()!=actionType || !old.paramsHash().equals(paramsHash)) throw new IllegalStateException("non-fund operation binding drift: "+operationId);
            return;
        }
        String sql="INSERT INTO agent_nonfund_execution(operation_id,action_type,params_hash,state,updated_at) VALUES (?,?,?,'PENDING',?)";
        try(Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setString(1,operationId); ps.setString(2,actionType.name()); ps.setString(3,paramsHash); ps.setLong(4,clock.getAsLong()); ps.executeUpdate();
        } catch(SQLException e){
            // 并发创建时重新读取并校验绑定；其他 SQL 错误不吞。
            Entry concurrent=find(operationId).orElse(null);
            if(concurrent!=null && concurrent.actionType()==actionType && concurrent.paramsHash().equals(paramsHash)) return;
            throw new IllegalStateException("cannot create non-fund execution: "+operationId,e);
        }
    }

    @Override public void mark(String operationId, State state) {
        if(state==State.PENDING) throw new IllegalArgumentException("mark requires terminal state");
        try(Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(
            "UPDATE agent_nonfund_execution SET state=?,updated_at=? WHERE operation_id=?")) {
            ps.setString(1,state.name()); ps.setLong(2,clock.getAsLong()); ps.setString(3,operationId);
            if(ps.executeUpdate()!=1) throw new IllegalStateException("non-fund execution missing: "+operationId);
        } catch(SQLException e){ throw new IllegalStateException("cannot update non-fund execution: "+operationId,e); }
    }
}

