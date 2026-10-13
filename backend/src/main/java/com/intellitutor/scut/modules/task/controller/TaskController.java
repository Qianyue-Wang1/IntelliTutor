package com.intellitutor.scut.modules.task.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.task.dto.vo.TaskStatusVO;
import com.intellitutor.scut.modules.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async inference task polling endpoints.
 */
@RestController
@RequestMapping("/v1/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{taskId}")
    public Result<TaskStatusVO> getTaskStatus(@PathVariable String taskId) {
        return Result.ok(taskService.getTaskStatus(taskId, UserContext.getUserId()));
    }
}
