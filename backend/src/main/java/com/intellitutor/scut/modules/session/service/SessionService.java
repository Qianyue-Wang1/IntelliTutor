package com.intellitutor.scut.modules.session.service;

import com.intellitutor.scut.modules.session.dto.vo.SessionListItemVO;
import com.intellitutor.scut.modules.session.entity.SessionRecord;

import java.util.List;

/**
 * 会话管理服务接口。
 *
 * <p>
 * 负责 Java 侧会话的生命周期管理：创建、查询、状态更新、快照持久化、
 * Python sessionId 绑定，以及 Python 侧 session 灾后恢复。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
public interface SessionService {

    /**
     * 查询指定用户的所有有效会话列表（已按创建时间降序）。
     *
     * @param userId Java 用户 ID
     * @return 会话列表 VO
     */
    List<SessionListItemVO> listSessions(String userId);

    /**
     * 逻辑删除会话，并尝试通知 Python 端释放 in-memory session。
     * Python 侧删除失败不影响 Java 侧逻辑删除。
     *
     * @param sessionId Java sessionId
     * @param userId    当前用户 ID（用于鉴权，防止越权删除）
     */
    void deleteSession(String sessionId, String userId);

    /**
     * 创建会话记录，持久化到 PostgreSQL，返回 Java sessionId。
     * python_session_id 在此时为空，待 MQ 消费完成后回填。
     *
     * @param userId      当前用户 ID
     * @param sessionType "explanation" | "discussion"
     * @param question    原始题目文本
     * @return Java sessionId（UUID）
     */
    String createSessionRecord(String userId, String sessionType, String question);

    /** Create the one session owned by a formal experiment Tutor run. */
    String createExperimentSession(String userId, String sessionType, String question, String tutorRunId);

    /**
     * MQ 消费成功后，回填 Python 端分配的 session_id。
     * 同时更新 Redis 中的 session 元数据缓存。
     *
     * @param sessionId       Java sessionId
     * @param pythonSessionId Python 端 session_id
     */
    void bindPythonSessionId(String sessionId, String pythonSessionId);

    /**
     * 将 Python sessionstate 完整响应（data 字段）序列化后持久化到 t_session.state_snapshot。
     * 用于 Python 宕机恢复时重建 in-memory session。
     *
     * @param sessionId    Java sessionId
     * @param snapshotJson state_snapshot JSON 字符串
     */
    void updateStateSnapshot(String sessionId, String snapshotJson);

    /**
     * 会话恢复：Python 返回 404 时，将 state_snapshot 重新投递给 Python
     * 的 restoresession 接口，拿到新 pythonSessionId 后回填 DB 和 Redis。
     *
     * @param session     Java SessionRecord（必须含有效 stateSnapshot）
     * @param sessionType "explanation" | "discussion"
     * @return 新的 Python sessionId
     * @throws com.intellitutor.scut.common.exception.BusinessException SESSION_NOT_EXIST
     *                                                                  若
     *                                                                  stateSnapshot
     *                                                                  为空（无快照无法恢复）
     */
    String recoverPythonSession(SessionRecord session, String sessionType);

    /**
     * 刷新会话最后访问时间（每次代理请求后调用）。
     *
     * @param sessionId Java sessionId
     */
    void touchSession(String sessionId);

    /**
     * 根据 Java sessionId 查询 SessionRecord 并校验所属用户。
     *
     * @param sessionId Java sessionId
     * @param userId    当前用户 ID
     * @return SessionRecord
     * @throws com.intellitutor.scut.common.exception.BusinessException
     *                                                                  SESSION_NOT_EXIST
     *                                                                  若不存在或已删除；FORBIDDEN
     *                                                                  若不属于该用户
     */
    SessionRecord getAndValidateSession(String sessionId, String userId);

    /**
     * 根据 Java sessionId 查询 SessionRecord，校验所属用户和会话类型。
     *
     * @param sessionId    Java sessionId
     * @param userId       当前用户 ID
     * @param expectedType 期望会话类型：explanation | discussion
     * @return SessionRecord
     * @throws com.intellitutor.scut.common.exception.BusinessException
     *                                                                  PARAM_ERROR
     *                                                                  若会话类型不匹配
     */
    SessionRecord getAndValidateSession(String sessionId, String userId, String expectedType);

    /**
     * 根据 Java sessionId 查询 SessionRecord（不校验用户，仅供内部 MQ 消费者使用）。
     *
     * @param sessionId Java sessionId
     * @return SessionRecord，不存在时返回 null
     */
    SessionRecord getSessionById(String sessionId);
}
