package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.experiment.dto.ExperimentContextVO;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.ExperimentEventService;
import com.intellitutor.scut.modules.experiment.service.ExperimentOrchestrationService;
import com.intellitutor.scut.modules.experiment.service.NoAiLearningService;
import com.intellitutor.scut.modules.experiment.service.ExperimentCredentialService;
import com.intellitutor.scut.modules.experiment.service.LocalParticipantExperimentService;
import com.intellitutor.scut.modules.experiment.service.ParticipantExperimentExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/experiment/me")
@RequiredArgsConstructor
public class StudentExperimentController {
    private final ExperimentOrchestrationService orchestration;
    private final ExperimentEventService events;
    private final NoAiLearningService noAiLearning;
    private final ExperimentCredentialService credentials;
    private final LocalParticipantExperimentService participants;
    private final ParticipantExperimentExportService participantExport;

    @PostMapping("/restart")
    public Result<com.intellitutor.scut.modules.user.dto.vo.LoginVO> restart() {
        return Result.ok(participants.restart(UserContext.getUserId()));
    }

    @GetMapping("/records")
    public Result<java.util.List<ExperimentDtos.LocalRecordVO>> records() {
        return Result.ok(participantExport.records(UserContext.getUserId()));
    }

    @GetMapping(value = "/records/{assignmentId}/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(@PathVariable String assignmentId) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=experiment-record-" + assignmentId + ".xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(participantExport.exportWorkbook(UserContext.getUserId(), assignmentId));
    }

    @GetMapping("/context")
    public Result<ExperimentContextVO> context() {
        return Result.ok(orchestration.context(UserContext.getUserId()));
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid ExperimentDtos.MandatoryPasswordReq req) {
        orchestration.changeMandatoryPassword(UserContext.getUserId(), req);
        return Result.ok();
    }

    @PostMapping("/mode")
    public Result<ExperimentDtos.CommandResult> selectMode(@RequestBody @Valid ExperimentDtos.SelectModeReq req) {
        return Result.ok(orchestration.selectMode(UserContext.getUserId(), req));
    }

    @PostMapping("/learning-method")
    public Result<ExperimentDtos.CommandResult> selectLearningMethod(
            @RequestBody @Valid ExperimentDtos.SelectLearningMethodReq req) {
        return Result.ok(orchestration.selectLearningMethod(UserContext.getUserId(), req));
    }

    @PostMapping("/lecture/start")
    public Result<Void> startLecture() {
        orchestration.startLecture(UserContext.getUserId());
        return Result.ok();
    }

    @PostMapping("/lecture/heartbeat")
    public Result<Void> heartbeat(@RequestBody ExperimentDtos.HeartbeatReq req) {
        orchestration.heartbeatLecture(UserContext.getUserId(), req);
        return Result.ok();
    }

    @PostMapping("/lecture/complete")
    public Result<Void> completeLecture() {
        orchestration.completeLecture(UserContext.getUserId());
        return Result.ok();
    }

    @GetMapping("/no-ai")
    public Result<ExperimentDtos.NoAiLectureVO> noAiLecture() {
        return Result.ok(noAiLearning.loadLecture(UserContext.getUserId()));
    }

    @PostMapping("/no-ai/scroll")
    public Result<Void> recordNoAiScroll(@RequestBody @Valid ExperimentDtos.NoAiScrollReq req) {
        noAiLearning.recordScroll(UserContext.getUserId(), req.progress());
        return Result.ok();
    }

    @PostMapping("/no-ai/complete")
    public Result<Void> completeNoAiLecture(@RequestBody @Valid ExperimentDtos.NoAiCompleteReq req) {
        noAiLearning.complete(UserContext.getUserId(), req.idempotencyKey());
        return Result.ok();
    }

    @PostMapping("/credential")
    public Result<ExperimentDtos.CredentialVO> issueCredential(@RequestBody @Valid ExperimentDtos.CredentialIssueReq req) {
        return Result.ok(credentials.issue(UserContext.getUserId(), req.idempotencyKey()));
    }

    @GetMapping(value = "/credential/{credentialId}/png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> credentialPng(@PathVariable String credentialId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(credentials.png(UserContext.getUserId(), credentialId));
    }

    @PostMapping("/events/batch")
    public Result<Void> events(@RequestBody @Valid ExperimentDtos.EventBatchReq req) {
        events.recordClientBatch(UserContext.getUserId(), req);
        return Result.ok();
    }
}
