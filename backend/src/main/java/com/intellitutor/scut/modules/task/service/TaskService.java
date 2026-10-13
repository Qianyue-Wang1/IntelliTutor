package com.intellitutor.scut.modules.task.service;

import com.intellitutor.scut.modules.session.dto.req.DiscussionFeedbackReq;
import com.intellitutor.scut.modules.session.dto.req.DiscussionInitReq;
import com.intellitutor.scut.modules.session.dto.req.ExplanationInitReq;
import com.intellitutor.scut.modules.session.dto.req.StepOptionReq;
import com.intellitutor.scut.modules.task.dto.vo.TaskStatusVO;
import java.util.Map;

/**
 * 异步推理任务服务。
 */
public interface TaskService {

    String publishExplanationInit(String sessionId, String userId, ExplanationInitReq req);

    String publishDiscussionInit(String sessionId, String userId, DiscussionInitReq req);

    String publishGetStep(String javaSessionId, String pythonSessionId, String userId);

    String publishCommitAnswer(String javaSessionId, String userId, StepOptionReq req);

    String publishDiscussionFeedback(String javaSessionId, String userId, DiscussionFeedbackReq req);

    String publishDirectInit(String sessionId, String userId, Map<String, Object> req);

    String publishDirectFollowup(String sessionId, String userId, Map<String, Object> req);

    TaskStatusVO getTaskStatus(String taskId, String userId);

    void markTaskProcessing(String taskId);

    void markTaskSuccess(String taskId, String resultJson);

    void markTaskFailed(String taskId, String errorMessage);
}
