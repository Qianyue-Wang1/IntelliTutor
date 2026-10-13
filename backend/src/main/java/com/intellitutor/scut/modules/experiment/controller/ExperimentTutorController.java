package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.ExperimentTutorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/experiment/me/tutor")
@RequiredArgsConstructor
public class ExperimentTutorController {
    private final ExperimentTutorService service;
    @PostMapping("/start") public Result<ExperimentDtos.TutorStartVO> start(@RequestBody ExperimentDtos.TutorStartReq req) { return Result.ok(service.start(UserContext.getUserId(), req)); }
    @GetMapping("/direct/{sessionId}") public Result<Map<String,Object>> state(@PathVariable String sessionId) { return Result.ok(service.directState(UserContext.getUserId(), sessionId)); }
    @PostMapping("/direct/{sessionId}/followup") public Result<Map<String,String>> followup(@PathVariable String sessionId, @RequestBody @Valid ExperimentDtos.DirectFollowupReq req) { return Result.accepted(Map.of("task_id", service.directFollowup(UserContext.getUserId(), sessionId, req), "session_id", sessionId)); }
    @PostMapping("/direct/{sessionId}/complete") public Result<Void> complete(@PathVariable String sessionId, @RequestBody @Valid ExperimentDtos.TutorCompleteReq req) { service.completeDirect(UserContext.getUserId(), sessionId, req); return Result.ok(); }
    @PostMapping("/{sessionId}/complete") public Result<Void> completeStructured(@PathVariable String sessionId, @RequestBody @Valid ExperimentDtos.TutorCompleteReq req) { service.completeStructured(UserContext.getUserId(), sessionId, req); return Result.ok(); }
}
