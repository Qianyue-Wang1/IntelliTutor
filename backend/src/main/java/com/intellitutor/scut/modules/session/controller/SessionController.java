package com.intellitutor.scut.modules.session.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.session.dto.vo.SessionListItemVO;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理接口。
 *
 * <p>
 * 提供会话列表查询和删除能力，不依赖 Python 端（纯 Java/DB 操作）。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final ExperimentAccessService experimentAccessService;

    /**
     * 查询当前用户的所有有效会话。
     *
     * @return 会话列表，按创建时间降序
     */
    @GetMapping("/sessions")
    public Result<List<SessionListItemVO>> listSessions() {
        String userId = UserContext.getUserId();
        experimentAccessService.rejectLegacyTutorForExperimentStudent(userId);
        List<SessionListItemVO> sessions = sessionService.listSessions(userId);
        return Result.ok(sessions);
    }

    /**
     * 逻辑删除指定会话，同时尝试通知 Python 端释放 in-memory session。
     *
     * @param sessionId Java 会话 ID
     * @return 无 data 的成功响应
     */
    @DeleteMapping("/deletesession/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        String userId = UserContext.getUserId();
        experimentAccessService.rejectLegacyTutorForExperimentStudent(userId);
        sessionService.deleteSession(sessionId, userId);
        return Result.ok();
    }
}
