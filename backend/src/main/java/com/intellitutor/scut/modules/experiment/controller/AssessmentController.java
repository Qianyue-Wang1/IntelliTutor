package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.AssessmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/experiment/me/assessment")
@RequiredArgsConstructor
public class AssessmentController {
    private final AssessmentService service;

    @PostMapping("/start") public Result<ExperimentDtos.AssessmentVO> start() { return Result.ok(service.start(UserContext.getUserId())); }
    @GetMapping("/{attemptId}") public Result<ExperimentDtos.AssessmentVO> load(@PathVariable String attemptId) { return Result.ok(service.load(UserContext.getUserId(), attemptId)); }
    @PostMapping("/{attemptId}/view") public Result<Void> viewed(@PathVariable String attemptId, @RequestBody @Valid ExperimentDtos.QuestionViewedReq req) { service.viewed(UserContext.getUserId(), attemptId, req); return Result.ok(); }
    @PutMapping("/{attemptId}/answer") public Result<ExperimentDtos.SavedAnswerVO> save(@PathVariable String attemptId, @RequestBody @Valid ExperimentDtos.AnswerSaveReq req) { return Result.ok(service.save(UserContext.getUserId(), attemptId, req)); }
    @PostMapping("/{attemptId}/submit") public Result<Void> submit(@PathVariable String attemptId, @RequestBody @Valid ExperimentDtos.SubmitAssessmentReq req) { service.submit(UserContext.getUserId(), attemptId, req); return Result.ok(); }
}
