import { request } from './request'
import { waitForAcceptedTask } from './task'
import type {
  ApiResponse,
  AuthResult,
  BehaviorData,
  CheckInputData,
  DiscussionFeedbackData,
  DiscussionInitData,
  DiscussionSessionData,
  DiscussionUpdateStateInput,
  ExplanationCheckData,
  ExplanationInitData,
  ExplanationSessionData,
  ImageData,
  ImageRecognitionData,
  RefreshResult,
  SessionListItem,
  StepNodeData,
  TaskAccepted,
  UserInfo,
  UserSummary,
  WrongAnswer
} from './types'

export const authApi = {
  sendLoginEmailCode: (data: { email: string; password: string }) =>
    request<null, typeof data>({ path: '/v1/auth/login_email_verify', method: 'POST', data, auth: false }),
  sendLoginPhoneCode: (data: { phone_number: string; password: string }) =>
    request<null, typeof data>({ path: '/v1/auth/login_phone_verify', method: 'POST', data, auth: false }),
  login: (data: { email?: string; phone_number?: string; password: string; verify_code: string }) =>
    request<AuthResult, typeof data>({ path: '/v1/auth/login', method: 'POST', data, auth: false }),
  sendRegisterEmailCode: (data: { email: string; password: string }) =>
    request<null, typeof data>({ path: '/v1/auth/register_email_verify', method: 'POST', data, auth: false }),
  sendRegisterPhoneCode: (data: { phone_number: string; password: string }) =>
    request<null, typeof data>({ path: '/v1/auth/register_phone_verify', method: 'POST', data, auth: false }),
  register: (data: { email?: string; phone_number?: string; password: string; verify_code: string }) =>
    request<AuthResult, typeof data>({ path: '/v1/auth/register', method: 'POST', data, auth: false }),
  refresh: (refreshToken: string) => request<RefreshResult, { refresh_token: string }>({
    path: '/v1/auth/refresh',
    method: 'POST',
    data: { refresh_token: refreshToken },
    auth: false
  })
}

export const userApi = {
  getInfo: (userId: string) => request<UserInfo>({ path: `/v1/user/${encodeURIComponent(userId)}` }),
  updateNickname: (userId: string, nickname: string) => request<UserSummary, { new_nickname: string }>({
    path: `/v1/user/${encodeURIComponent(userId)}/change_user_info`,
    method: 'POST',
    data: { new_nickname: nickname }
  }),
  updateAvatar: (userId: string, avatar: string) => request<{ avatar: string }, { avatar: string }>({
    path: `/v1/user/${encodeURIComponent(userId)}/change_avatar`,
    method: 'POST',
    data: { avatar }
  })
}

export const sessionApi = {
  list: () => request<SessionListItem[]>({ path: '/v1/sessions' }),
  remove: (sessionId: string) => request<null>({
    path: `/v1/deletesession/${encodeURIComponent(sessionId)}`,
    method: 'DELETE'
  })
}

export const toolApi = {
  checkInput: (userInput: string) => request<CheckInputData, { user_input: string }>({
    path: '/v1/checkinput',
    method: 'POST',
    data: { user_input: userInput }
  }),
  recognizeImage: (imgStr: string, userText: string) => request<
    ImageRecognitionData,
    { imgStr: string; userText: string }
  >({
    path: '/v1/imginput',
    method: 'POST',
    data: { imgStr, userText }
  })
}

export interface TaskCallOptions {
  isCancelled?: () => boolean
}

const LONG_REQUEST_TIMEOUT_MS = 780000

export const explanationApi = {
  init: async (data: {
    processedQuestion: string
    userInput?: string
    personalizedMode?: boolean
    images?: ImageData[]
  }, options: TaskCallOptions = {}): Promise<ApiResponse<ExplanationInitData>> => {
    const accepted = await request<TaskAccepted, typeof data>({
      path: '/v1/explanation/init',
      method: 'POST',
      data
    })
    return waitForAcceptedTask<ExplanationInitData>(accepted, 'EXPLANATION_INIT', options)
  },
  getStep: async (sessionId: string, options: TaskCallOptions = {}): Promise<ApiResponse<StepNodeData>> => {
    const accepted = await request<TaskAccepted>({
      path: `/v1/explanation/getstep/${encodeURIComponent(sessionId)}`
    })
    return waitForAcceptedTask<StepNodeData>(accepted, 'EXPLANATION_GET_STEP', options)
  },
  submitAnswer: async (data: { session_id: string; option: string }, options: TaskCallOptions = {}) => {
    const accepted = await request<TaskAccepted, typeof data>({
      path: '/v1/explanation/commitanswer',
      method: 'POST',
      data
    })
    return waitForAcceptedTask<ExplanationCheckData>(accepted, 'EXPLANATION_COMMIT_ANSWER', options)
  },
  getState: (sessionId: string) => request<ExplanationSessionData>({
    path: `/v1/explanation/sessionstate/${encodeURIComponent(sessionId)}`,
    timeout: LONG_REQUEST_TIMEOUT_MS
  }),
  updateState: (data: {
    session_id: string
    imageCode?: string[]
    current_step: number
    in_summary: boolean
    wrong_answers: WrongAnswer[]
  }) => request<null, typeof data>({ path: '/v1/explanation/updatestate', method: 'POST', data })
}

export const discussionApi = {
  init: async (data: {
    processedQuestion: string
    images?: ImageData[]
    personalizedMode?: boolean
  }, options: TaskCallOptions = {}): Promise<ApiResponse<DiscussionInitData>> => {
    const accepted = await request<TaskAccepted, typeof data>({
      path: '/v1/discussion/init',
      method: 'POST',
      data
    })
    return waitForAcceptedTask<DiscussionInitData>(accepted, 'DISCUSSION_INIT', options)
  },
  feedback: async (data: {
    session_id: string
    feedback: string
    behavior_data?: BehaviorData
  }, options: TaskCallOptions = {}) => {
    const accepted = await request<TaskAccepted, typeof data>({
      path: '/v1/discussion/feedback',
      method: 'POST',
      data
    })
    return waitForAcceptedTask<DiscussionFeedbackData>(accepted, 'DISCUSSION_FEEDBACK', options)
  },
  getState: (sessionId: string) => request<DiscussionSessionData>({
    path: `/v1/discussion/sessionstate/${encodeURIComponent(sessionId)}`,
    timeout: LONG_REQUEST_TIMEOUT_MS
  }),
  updateState: (data: DiscussionUpdateStateInput) => request<DiscussionSessionData, DiscussionUpdateStateInput>({
    path: '/v1/discussion/updatestate',
    method: 'POST',
    data
  })
}
