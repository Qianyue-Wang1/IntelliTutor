package com.intellitutor.scut.modules.session.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 会话记录实体，对应 PostgreSQL {@code t_session} 表。
 *
 * <p>
 * 字段说明：
 * <ul>
 * <li>{@code pythonSessionId}：Python FastAPI 端分配的 UUID，MQ 任务消费成功后回填。</li>
 * <li>{@code stateSnapshot}：Python {@code /sessionstate} 接口返回的 {@code data} 字段
 * 完整 JSON，用于 Python 重启后调用 {@code /restoresession} 恢复 in-memory session。</li>
 * <li>{@code deleted}：逻辑删除，0=正常，1=已删除，由 MyBatis-Plus 自动处理。</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@TableName("t_session")
public class SessionRecord {

    /** Java 生成的 UUID 主键，前端全程使用此 ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联 t_user.id */
    private String userId;

    /** 会话类型：explanation | discussion */
    private String sessionType;

    /** 原始题目文本 */
    private String question;

    /** 会话状态：active | completed | expired */
    private String status;

    /** Owning immutable experiment Tutor run, if this is a formal-study session. */
    private String experimentTutorRunId;

    /** Python FastAPI 端分配的 session_id（MQ 消费后回填，代理时使用） */
    private String pythonSessionId;

    /**
     * Python sessionstate 完整响应快照（JSONB → Java 侧序列化为 JSON 字符串）。
     * 用于 Python 重启后的会话恢复（disaster recovery）。
     */
    private String stateSnapshot;

    /** 记录创建时间，由 MyBatis-Plus MetaHandler 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 最近一次代理请求时间 */
    private LocalDateTime lastAccessedAt;

    private OffsetDateTime completedAt;

    /** 逻辑删除标记：0=正常，1=已删除 */
    @TableLogic
    private Integer deleted;
}
