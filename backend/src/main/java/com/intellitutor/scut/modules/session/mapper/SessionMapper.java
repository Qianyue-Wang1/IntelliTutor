package com.intellitutor.scut.modules.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话记录 Mapper，继承 MyBatis-Plus BaseMapper 获得基础 CRUD。
 *
 * <p>
 * 补充自定义 update 方法，只更新必要字段，避免全量覆盖。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionRecord> {

    /**
     * 回填 Python 端 session_id，仅在 MQ 消费成功后调用一次。
     */
    @Update("UPDATE t_session SET python_session_id = #{pythonSessionId} WHERE id = #{id} AND deleted = 0")
    int updatePythonSessionId(@Param("id") String id,
            @Param("pythonSessionId") String pythonSessionId);

    /**
     * 更新状态快照（JSONB 字段），使用 PostgreSQL JSONB 类型转换。
     */
    @Update("UPDATE t_session SET state_snapshot = #{snapshot}::jsonb WHERE id = #{id} AND deleted = 0")
    int updateStateSnapshot(@Param("id") String id,
            @Param("snapshot") String snapshot);

    /**
     * 刷新最后访问时间，在每次代理请求时调用（不影响其他字段）。
     */
    @Update("UPDATE t_session SET last_accessed_at = #{accessedAt} WHERE id = #{id} AND deleted = 0")
    int updateLastAccessedAt(@Param("id") String id,
            @Param("accessedAt") LocalDateTime accessedAt);

    /**
     * 更新会话状态（active / completed / expired）。
     */
    @Update("UPDATE t_session SET status = #{status} WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") String id, @Param("status") String status);
}
