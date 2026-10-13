package com.intellitutor.scut.modules.experiment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class ExperimentDtos {
    private ExperimentDtos() {}

    public record ExperimentLoginReq(@NotBlank String loginAccount, @NotBlank @Size(min = 8, max = 128) String password) {}
    public record PublicExperimentRegisterReq(@NotBlank @Size(max = 120) String name,
                                              @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phoneNumber,
                                              @NotBlank @Size(min = 8, max = 128) String password,
                                              @NotBlank String method, @NotBlank String proof) {}
    public record LocalParticipantStartReq(@NotBlank @Size(max = 120) String name,
                                           @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phoneNumber) {}
    public record LocalRecordVO(String assignmentId, String name, String phoneNumber, String selectedMode, String state,
                                OffsetDateTime startedAt, OffsetDateTime completedAt, boolean exportable) {}
    public record PublicExperimentSmsReq(@NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phoneNumber) {}
    public record MandatoryPasswordReq(@NotBlank @Size(min = 8, max = 128) String currentPassword,
                                       @NotBlank @Size(min = 8, max = 128) String newPassword) {}
    public record HeartbeatReq(long activeSeconds, OffsetDateTime clientTimestamp, JsonNode metrics) {}
    public record EventReq(@NotBlank String clientEventId, @NotBlank String eventType,
                           String questionId, String sessionId, OffsetDateTime clientTimestamp, JsonNode data) {}
    public record EventBatchReq(@NotEmpty @Size(max = 100) List<@Valid EventReq> events) {}
    public record TutorStartReq(String mode, String idempotencyKey) {}
    public record TutorCompleteReq(@NotBlank String idempotencyKey) {}
    public record DirectFollowupReq(@NotBlank @Size(max = 4000) String message,
                                    @NotBlank String idempotencyKey, OffsetDateTime clientTimestamp) {}
    public record SelectModeReq(@NotBlank String mode, @NotBlank String idempotencyKey, long stateVersion) {}
    public record SelectLearningMethodReq(int round, @NotBlank String method, @NotBlank String idempotencyKey,
                                          long stateVersion) {}
    public record CommandResult(String selectedMode, String studentState, int round, long stateVersion,
                                String nextRoute) {}
    public record TutorStartVO(String runId, String groupCode, int round, String mode, String sessionId,
                               String taskId, boolean resumed, String route) {}
    public record NoAiLectureVO(int round, String markdown, boolean bottomReached) {}
    public record NoAiScrollReq(@DecimalMin("0.0") @DecimalMax("100.0") double progress) {}
    public record NoAiCompleteReq(@NotBlank String idempotencyKey) {}
    public record CredentialIssueReq(@NotBlank String idempotencyKey) {}
    public record CredentialVO(String id, String credentialNo, String participantName, String participantCode,
                               String topic, String selectedMode, OffsetDateTime issuedAt) {}

    public record AnswerSaveReq(@NotBlank String questionId, JsonNode answer, int revision,
                                @NotBlank String idempotencyKey, OffsetDateTime clientTimestamp) {}
    public record QuestionViewedReq(@NotBlank String questionId, OffsetDateTime clientTimestamp) {}
    public record SubmitAssessmentReq(@NotBlank String idempotencyKey) {}
    public record QuestionVO(String id, String code, String type, String prompt, JsonNode options,
                             JsonNode images, int sequenceNo) {}
    public record SavedAnswerVO(String questionId, JsonNode answer, int revision, int changeCount) {}
    public record AssessmentVO(String attemptId, String code, String title, String status,
                               OffsetDateTime startedAt, OffsetDateTime deadline, List<QuestionVO> questions,
                               List<SavedAnswerVO> answers) {}

    public record ConfigImportReq(@NotBlank String code, @NotBlank String name, @NotBlank String configVersion,
                                  @NotEmpty List<@Valid StageImport> stages,
                                  @NotEmpty List<@Valid QuestionImport> questions,
                                  @NotEmpty List<@Valid AssessmentImport> assessments,
                                  boolean dryRun) {}
    public record StageImport(@NotBlank String code, @NotBlank String type, int sequenceNo, Integer round,
                              Integer durationSeconds, @NotBlank String title, String lectureMarkdown,
                              String assessmentCode, List<StageGroupImport> groups) {}
    public record StageGroupImport(@NotBlank String groupCode, boolean participating,
                                   String tutorQuestionCode, String assessmentCode, String modePolicy) {}
    public record QuestionImport(@NotBlank String code, @NotBlank String type, @NotBlank String prompt,
                                 JsonNode options, JsonNode answerKey, BigDecimal numericTolerance,
                                 JsonNode images, JsonNode metadata) {}
    public record AssessmentImport(@NotBlank String code, @NotBlank String type, @NotBlank String title,
                                   Integer durationSeconds, String groupCode,
                                   @NotEmpty List<AssessmentQuestionImport> questions) {}
    public record AssessmentQuestionImport(@NotBlank String questionCode, int sequenceNo, BigDecimal points) {}
    public record ImportResult(String experimentId, boolean dryRun, List<String> errors,
                               List<String> warnings, Map<String, Integer> counts) {}

    public record StudentImportReq(@NotBlank String experimentId, @NotEmpty List<@Valid StudentImportRow> students,
                                   boolean dryRun) {}
    public record StudentImportRow(@NotBlank String studentId, @NotBlank String loginAccount, String classCode) {}
    public record StudentCredential(String studentId, String loginAccount, String initialPassword) {}
    public record StudentImportResult(boolean dryRun, List<String> errors, List<StudentCredential> credentials) {}
    public record GroupImportReq(@NotBlank(message = "experiment_id 不能为空") String experimentId,
                                 @NotEmpty(message = "groups 不能为空") List<@Valid GroupImportRow> groups,
                                 boolean dryRun) {}
    public record GroupImportRow(String studentId, String loginAccount,
                                 @NotBlank(message = "group_code 不能为空") String groupCode) {}
    public record MutationReasonReq(@NotBlank String assignmentId, @NotBlank String reason) {}
    public record ResetPasswordReq(@NotBlank String assignmentId) {}
    public record ResetPasswordVO(String loginAccount, String temporaryPassword) {}
    public record InviteCodeVO(String code) {}
    public record PersonalizationRetryReq(@NotBlank String tutorRunId) {}
    public record AdvanceStageReq(long expectedVersion) {}
}
