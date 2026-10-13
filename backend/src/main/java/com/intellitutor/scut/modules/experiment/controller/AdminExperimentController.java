package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.ExperimentAdminService;
import com.intellitutor.scut.modules.experiment.service.ExperimentExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/v1/admin/experiments")
@RequiredArgsConstructor
public class AdminExperimentController {
    private final ExperimentAdminService service;
    private final ExperimentExportService exports;

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(service.listExperiments(UserContext.getUserId()));
    }

    @PostMapping("/import")
    public Result<ExperimentDtos.ImportResult> importConfig(@RequestBody @Valid ExperimentDtos.ConfigImportReq req) {
        return Result.ok(service.importConfig(UserContext.getUserId(), req));
    }
    @PostMapping("/students/import")
    public Result<ExperimentDtos.StudentImportResult> importStudents(@RequestBody @Valid ExperimentDtos.StudentImportReq req) {
        return Result.ok(service.importStudents(UserContext.getUserId(), req));
    }
    @PostMapping("/groups/import")
    public Result<ExperimentDtos.ImportResult> importGroups(@RequestBody @Valid ExperimentDtos.GroupImportReq req) {
        return Result.ok(service.importGroups(UserContext.getUserId(), req));
    }
    @GetMapping("/{experimentId}/dashboard")
    public Result<Map<String, Object>> dashboard(@PathVariable String experimentId) {
        return Result.ok(service.dashboard(UserContext.getUserId(), experimentId));
    }
    @PostMapping("/{experimentId}/stage/advance")
    public Result<Map<String, Object>> advance(@PathVariable String experimentId, @RequestBody @Valid ExperimentDtos.AdvanceStageReq req) {
        return Result.ok(service.advance(UserContext.getUserId(), experimentId, req));
    }
    @PostMapping("/{experimentId}/students/withdraw")
    public Result<Void> withdraw(@PathVariable String experimentId, @RequestBody @Valid ExperimentDtos.MutationReasonReq req) {
        service.participation(UserContext.getUserId(), experimentId, "WITHDRAWN", req); return Result.ok();
    }
    @PostMapping("/{experimentId}/students/exclude")
    public Result<Void> exclude(@PathVariable String experimentId, @RequestBody @Valid ExperimentDtos.MutationReasonReq req) {
        service.participation(UserContext.getUserId(), experimentId, "EXCLUDED", req); return Result.ok();
    }
    @PostMapping("/{experimentId}/password/reset")
    public Result<ExperimentDtos.ResetPasswordVO> resetPassword(@PathVariable String experimentId, @RequestBody @Valid ExperimentDtos.ResetPasswordReq req) {
        return Result.ok(service.resetPassword(UserContext.getUserId(), experimentId, req));
    }
    @PostMapping("/{experimentId}/invite-codes")
    public Result<ExperimentDtos.InviteCodeVO> createInviteCode(@PathVariable String experimentId) {
        return Result.ok(service.createInviteCode(UserContext.getUserId(), experimentId));
    }
    @PostMapping("/{experimentId}/personalization/retry")
    public Result<Map<String, Object>> retryPersonalization(@PathVariable String experimentId, @RequestBody @Valid ExperimentDtos.PersonalizationRetryReq req) {
        return Result.ok(service.retryPersonalization(UserContext.getUserId(), experimentId, req));
    }

    @GetMapping(value = "/{experimentId}/export/assessment-results", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportAssessmentResults(@PathVariable String experimentId) {
        return download(service.exportResults(UserContext.getUserId(), experimentId),
                "assessment-results.csv", "text/csv;charset=UTF-8");
    }

    @GetMapping(value = "/{experimentId}/export/tutor-runs", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportTutorRuns(@PathVariable String experimentId) {
        return download(service.exportTutorRuns(UserContext.getUserId(), experimentId),
                "tutor-runs.csv", "text/csv;charset=UTF-8");
    }

    @GetMapping(value = "/{experimentId}/export/events", produces = "application/x-ndjson;charset=UTF-8")
    public ResponseEntity<String> exportEvents(@PathVariable String experimentId) {
        return download(service.exportEvents(UserContext.getUserId(), experimentId),
                "experiment-events.jsonl", "application/x-ndjson;charset=UTF-8");
    }

    @GetMapping(value = "/{experimentId}/export/archive", produces = "application/zip")
    public ResponseEntity<byte[]> exportArchive(@PathVariable String experimentId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"experiment-" + experimentId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(exports.exportZip(UserContext.getUserId(), experimentId));
    }

    private ResponseEntity<String> download(String content, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }
}
