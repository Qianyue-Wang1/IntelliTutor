import { apiDownload, apiRequest, waitForTask } from './client'
import { userIdFromToken, readAuth } from './auth-storage'
import type {
  AuthResult, Capabilities, DiscussionFeedback, DiscussionSession, DiscussionStep,
  BehaviorData, ExplanationCheck, ExplanationSession, ImageData, SessionListItem, StepNodeData,
  TaskAccepted, User, UserInfo, WrongAnswer, ExperimentContext, Assessment, SavedAnswer,
  TutorStart, DirectSession, ExperimentRegistrationOptions, ExperimentRegistrationRequest, ExperimentRegistrationResult,
  NoAiLecture, ExperimentCredential,
} from './types'

const userPath = (): string => {
  const id = userIdFromToken(readAuth().accessToken)
  if (!id) throw new Error('登录状态已失效')
  return `/v1/user/${encodeURIComponent(id)}`
}

export const services = {
  capabilities: () => apiRequest<Capabilities>('/v1/config/capabilities', { auth: false }),
  experimentLogin: (loginAccount: string, password: string) => apiRequest<AuthResult>('/v1/auth/experiment-login', {
    method: 'POST', auth: false, body: { login_account: loginAccount, password },
  }),
  startLocalExperiment: (name: string, phoneNumber: string) => apiRequest<AuthResult>('/v1/auth/local-experiment/start', {
    method: 'POST', auth: false, body: { name, phone_number: phoneNumber },
  }),
  experimentRegistrationOptions: () => apiRequest<ExperimentRegistrationOptions>('/v1/auth/experiment-registration/registration-options', { auth: false }),
  sendExperimentRegistrationSms: (phoneNumber: string) => apiRequest<null>('/v1/auth/experiment-registration/registration-sms', {
    method: 'POST', auth: false, body: { phone_number: phoneNumber },
  }),
  registerExperiment: (body: ExperimentRegistrationRequest) => apiRequest<ExperimentRegistrationResult>('/v1/auth/experiment-registration/register', {
    method: 'POST', auth: false, body,
  }),
  experimentContext: () => apiRequest<ExperimentContext>('/v1/experiment/me/context'),
  restartLocalExperiment: () => apiRequest<AuthResult>('/v1/experiment/me/restart', { method: 'POST' }),
  localExperimentRecords: () => apiRequest<Array<{ assignment_id: string; name: string; phone_number: string; selected_mode: string | null; state: string; started_at: string | null; completed_at: string | null; exportable: boolean }>>('/v1/experiment/me/records'),
  exportLocalExperimentRecord: (assignmentId: string) => apiDownload(`/v1/experiment/me/records/${encodeURIComponent(assignmentId)}/export`),
  selectExperimentMode: (mode: 'DIRECT_LLM' | 'BASIC_TUTOR' | 'FULL_TUTOR' | 'NO_AI', stateVersion: number) =>
    apiRequest<{ next_route: string }>('/v1/experiment/me/mode', {
      method: 'POST', body: { mode, state_version: stateVersion, idempotency_key: crypto.randomUUID() },
    }),
  experimentPassword: (currentPassword: string, newPassword: string) => apiRequest<null>('/v1/experiment/me/password', {
    method: 'POST', body: { current_password: currentPassword, new_password: newPassword },
  }),
  lectureStart: () => apiRequest<null>('/v1/experiment/me/lecture/start', { method: 'POST' }),
  lectureHeartbeat: (activeSeconds: number, metrics?: unknown) => apiRequest<null>('/v1/experiment/me/lecture/heartbeat', {
    method: 'POST', body: { active_seconds: activeSeconds, client_timestamp: new Date().toISOString(), metrics },
  }),
  lectureComplete: () => apiRequest<null>('/v1/experiment/me/lecture/complete', { method: 'POST' }),
  noAiLecture: () => apiRequest<NoAiLecture>('/v1/experiment/me/no-ai'),
  noAiScroll: (progress: number) => apiRequest<null>('/v1/experiment/me/no-ai/scroll', { method: 'POST', body: { progress } }),
  noAiComplete: () => apiRequest<null>('/v1/experiment/me/no-ai/complete', { method: 'POST', body: { idempotency_key: crypto.randomUUID() } }),
  experimentCredential: () => apiRequest<ExperimentCredential>('/v1/experiment/me/credential', { method: 'POST', body: { idempotency_key: crypto.randomUUID() } }),
  credentialPng: (credentialId: string) => apiDownload(`/v1/experiment/me/credential/${encodeURIComponent(credentialId)}/png`),
  experimentEvents: (events: unknown[], keepalive = false) => apiRequest<null>('/v1/experiment/me/events/batch', { method: 'POST', body: { events }, keepalive }),
  assessmentStart: () => apiRequest<Assessment>('/v1/experiment/me/assessment/start', { method: 'POST' }),
  assessment: (attemptId: string) => apiRequest<Assessment>(`/v1/experiment/me/assessment/${encodeURIComponent(attemptId)}`),
  questionViewed: (attemptId: string, questionId: string) => apiRequest<null>(`/v1/experiment/me/assessment/${encodeURIComponent(attemptId)}/view`, {
    method: 'POST', body: { question_id: questionId, client_timestamp: new Date().toISOString() },
  }),
  saveAnswer: (attemptId: string, questionId: string, answer: unknown, revision: number, idempotencyKey: string) =>
    apiRequest<SavedAnswer>(`/v1/experiment/me/assessment/${encodeURIComponent(attemptId)}/answer`, {
      method: 'PUT', body: { question_id: questionId, answer, revision, idempotency_key: idempotencyKey, client_timestamp: new Date().toISOString() },
    }),
  submitAssessment: (attemptId: string, idempotencyKey: string) => apiRequest<null>(`/v1/experiment/me/assessment/${encodeURIComponent(attemptId)}/submit`, {
    method: 'POST', body: { idempotency_key: idempotencyKey },
  }),
  tutorStart: async (mode?: 'explanation' | 'discussion') => {
    const start = await apiRequest<TutorStart>('/v1/experiment/me/tutor/start', { method: 'POST', body: { mode, idempotency_key: crypto.randomUUID() } })
    if (start.task_id) await waitForTask<unknown>({ task_id: start.task_id, session_id: start.session_id })
    return start
  },
  directSession: (sessionId: string) => apiRequest<DirectSession>(`/v1/experiment/me/tutor/direct/${encodeURIComponent(sessionId)}`),
  directFollowup: async (sessionId: string, message: string) => {
    const accepted = await apiRequest<TaskAccepted>(`/v1/experiment/me/tutor/direct/${encodeURIComponent(sessionId)}/followup`, {
      method: 'POST', body: { message, idempotency_key: crypto.randomUUID(), client_timestamp: new Date().toISOString() },
    })
    // A task result may come from an older Python worker that only returns the
    // new messages. Reload the authoritative session so completion flags and
    // immutable question metadata cannot be lost when the UI updates its cache.
    await waitForTask<unknown>(accepted)
    return apiRequest<DirectSession>(`/v1/experiment/me/tutor/direct/${encodeURIComponent(sessionId)}`)
  },
  directComplete: (sessionId: string) => apiRequest<null>(`/v1/experiment/me/tutor/direct/${encodeURIComponent(sessionId)}/complete`, {
    method: 'POST', body: { idempotency_key: crypto.randomUUID() },
  }),
  completeTutor: (sessionId: string) => apiRequest<null>(`/v1/experiment/me/tutor/${encodeURIComponent(sessionId)}/complete`, {
    method: 'POST', body: { idempotency_key: crypto.randomUUID() },
  }),
  adminExperiments: () => apiRequest<Array<Record<string, unknown>>>('/v1/admin/experiments'),
  adminDashboard: (experimentId: string) => apiRequest<Record<string, unknown>>(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/dashboard`),
  adminImportConfig: (body: unknown) => apiRequest<Record<string, unknown>>('/v1/admin/experiments/import', { method: 'POST', body }),
  adminImportStudents: (body: unknown) => apiRequest<Record<string, unknown>>('/v1/admin/experiments/students/import', { method: 'POST', body }),
  adminImportGroups: (body: unknown) => apiRequest<Record<string, unknown>>('/v1/admin/experiments/groups/import', { method: 'POST', body }),
  adminAdvance: (experimentId: string, expectedVersion: number) => apiRequest<Record<string, unknown>>(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/stage/advance`, { method: 'POST', body: { expected_version: expectedVersion } }),
  adminResetPassword: (experimentId: string, assignmentId: string) => apiRequest<Record<string, unknown>>(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/password/reset`, { method: 'POST', body: { assignment_id: assignmentId } }),
  adminCreateInviteCode: (experimentId: string) => apiRequest<{ code: string }>(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/invite-codes`, { method: 'POST' }),
  adminRetryPersonalization: (experimentId: string, tutorRunId: string) => apiRequest<Record<string, unknown>>(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/personalization/retry`, { method: 'POST', body: { tutor_run_id: tutorRunId } }),
  adminEndParticipation: (experimentId: string, assignmentId: string, kind: 'withdraw' | 'exclude', reason: string) => apiRequest<null>(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/students/${kind}`, { method: 'POST', body: { assignment_id: assignmentId, reason } }),
  adminExport: (experimentId: string, kind: 'assessment-results' | 'tutor-runs' | 'events') => apiDownload(`/v1/admin/experiments/${encodeURIComponent(experimentId)}/export/${kind}`),
  sendAuthCode: (kind: 'login' | 'register', account: string, password: string) => {
    const email = account.includes('@')
    return apiRequest<null>(`/v1/auth/${kind}_${email ? 'email' : 'phone'}_verify`, {
      method: 'POST', auth: false, body: email ? { email: account, password } : { phone_number: account, password },
    })
  },
  authenticate: (kind: 'login' | 'register', account: string, password: string, verifyCode: string) =>
    apiRequest<AuthResult>(`/v1/auth/${kind}`, {
      method: 'POST', auth: false,
      body: account.includes('@')
        ? { email: account, password, verify_code: verifyCode }
        : { phone_number: account, password, verify_code: verifyCode },
    }),
  userInfo: () => apiRequest<UserInfo>(userPath()),
  updateUser: (body: Record<string, unknown>) => apiRequest<User>(`${userPath()}/change_user_info`, { method: 'POST', body }),
  updateAvatar: (avatar: string) => apiRequest<{ avatar: string }>(`${userPath()}/change_avatar`, { method: 'POST', body: { avatar } }),
  verifyOriginal: (channel: 'email' | 'phone', password: string) => apiRequest<null>(`${userPath()}/verify_original_${channel}`, { method: 'POST', body: { password } }),
  sendChangeContactCode: (channel: 'email' | 'phone', value: string, originalCode?: string) =>
    apiRequest<null>(`${userPath()}/change_${channel}_verify`, {
      method: 'POST',
      body: channel === 'email'
        ? { new_email: value, original_verify_code: originalCode }
        : { new_phone_number: value, original_verify_code: originalCode },
    }),
  sendPasswordCode: (password: string, type: 'email' | 'phone') =>
    apiRequest<null>(`${userPath()}/change_password_verify`, { method: 'POST', body: { password, type } }),
  changePassword: (body: { old_password?: string; new_password: string; type: 'email' | 'phone'; verify_code: string }) =>
    apiRequest<null>(`${userPath()}/change_password`, { method: 'POST', body }),
  sessions: () => apiRequest<SessionListItem[]>('/v1/sessions'),
  deleteSession: (id: string) => apiRequest<null>(`/v1/deletesession/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  checkInput: (userInput: string, signal?: AbortSignal) =>
    apiRequest<{ question: string; user_input: string }>('/v1/checkinput', { method: 'POST', body: { user_input: userInput }, signal }),
  imageInput: (image: string, text: string, signal?: AbortSignal) =>
    apiRequest<{ extracted_text: string; structured_description?: string }>('/v1/imginput', { method: 'POST', body: { imgStr: image, userText: text }, signal }),
  imageToCode: (image: string, text: string, signal?: AbortSignal) =>
    apiRequest<Record<string, string[]>>('/v1/imgtocode', { method: 'POST', body: { imgStr: image, userText: text }, signal }),
  initExplanation: async (body: { processedQuestion: string; userInput?: string; personalizedMode: boolean; images?: ImageData[]; imageCode?: string[] }, signal?: AbortSignal) => {
    const accepted = await apiRequest<TaskAccepted>('/v1/explanation/init', { method: 'POST', body, signal })
    return waitForTask<ExplanationSession>(accepted, signal)
  },
  explanation: async (id: string, signal?: AbortSignal) => {
    const session = await apiRequest<ExplanationSession>(`/v1/explanation/sessionstate/${encodeURIComponent(id)}`, { signal })
    return { ...session, session_id: id }
  },
  explanationStep: async (id: string, signal?: AbortSignal) => {
    const accepted = await apiRequest<TaskAccepted>(`/v1/explanation/getstep/${encodeURIComponent(id)}`, { signal })
    return waitForTask<StepNodeData>(accepted, signal)
  },
  answerStep: async (id: string, option: string, behaviorData?: BehaviorData, activeStepId?: string, signal?: AbortSignal) => {
    const accepted = await apiRequest<TaskAccepted>('/v1/explanation/commitanswer', { method: 'POST', body: { session_id: id, option, behavior_data: behaviorData, active_step_id: activeStepId }, signal })
    return waitForTask<ExplanationCheck>(accepted, signal)
  },
  advanceExplanation: (id: string) => apiRequest<ExplanationSession>('/v1/explanation/advance', {
    method: 'POST', body: { session_id: id },
  }),
  updateExplanation: (body: { session_id: string; imageCode?: string[]; current_step: number; in_summary: boolean; wrong_answers: WrongAnswer[] }) =>
    apiRequest<null>('/v1/explanation/updatestate', { method: 'POST', body }),
  initDiscussion: async (body: { processedQuestion: string; personalizedMode: boolean; images?: ImageData[]; imageCode?: string[] }, signal?: AbortSignal) => {
    const accepted = await apiRequest<TaskAccepted>('/v1/discussion/init', { method: 'POST', body, signal })
    return waitForTask<DiscussionSession>(accepted, signal)
  },
  discussion: async (id: string, signal?: AbortSignal) => {
    const session = await apiRequest<DiscussionSession>(`/v1/discussion/sessionstate/${encodeURIComponent(id)}`, { signal })
    return { ...session, session_id: id }
  },
  discussionFeedback: async (id: string, feedback: string, submissionType: 'option' | 'free_text', behaviorData?: BehaviorData, activeStepId?: string, signal?: AbortSignal) => {
    const accepted = await apiRequest<TaskAccepted>('/v1/discussion/feedback', { method: 'POST', body: { session_id: id, feedback, submission_type: submissionType, behavior_data: behaviorData, active_step_id: activeStepId }, signal })
    return waitForTask<DiscussionFeedback>(accepted, signal)
  },
  updateDiscussion: async (body: { session_id: string; question: string; images?: ImageData[]; imageCode?: string[]; current_step: string; discussion_tree: DiscussionStep[]; messages_history: unknown[]; knowledge_points: unknown[]; is_complete: boolean; summary_text?: string }) => {
    const session = await apiRequest<DiscussionSession>('/v1/discussion/updatestate', { method: 'POST', body })
    return { ...session, session_id: body.session_id }
  },
}
