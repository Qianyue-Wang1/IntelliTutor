package com.intellitutor.scut.modules.task.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 前端轮询任务状态响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusVO {

    /** 任务 ID */
    private String taskId;

    /** queued | processing | success | failed */
    private String status;

    /** status=success 时为 Python 响应 data 字段 */
    private Object data;

    /** status=failed 时的错误原因 */
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
