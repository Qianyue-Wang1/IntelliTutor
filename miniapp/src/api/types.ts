export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp?: number
}

export interface UserSummary {
  id: string
  email?: string | null
  phone_number?: string | null
  nickname?: string | null
  avatar?: string | null
}

export interface UserInfo extends UserSummary {
  solved_problems: number
  study_days: number
  mastered_knowledge_points: number
  weekly_study_questions: number
  weekly_mastered_knowledge_points: number
  accuracy: number
  roles: string[]
  created_at: string
  updated_at: string
  last_login: string
}

export interface AuthResult {
  access_token: string
  refresh_token: string
  user: UserSummary
}

export interface RefreshResult {
  access_token: string
  refresh_token?: string
}

export interface AuthSession {
  access_token: string
  refresh_token: string
  user: UserSummary
}

export interface ImageData {
  name: string
  url: string
}

export interface KnowledgePoint {
  title: string
  description: string
}

export interface WrongAnswer {
  step_number: string
  operation: string
  wrong_option: string
}

export interface StepNodeData {
  step_number: number
  analysis: string
  operation_prefix: string
  options: string[]
  operation_summary_list: string[]
  user_selected_option: string
}

export interface ExplanationInitData {
  session_id: string
  question: string
  images?: ImageData[]
  imageCode?: string[]
  current_step: number
  total_steps: number
  in_summary: boolean
  knowledge_points: KnowledgePoint[]
  steps_dict: Record<string, string>
  steps_data?: Record<string, Record<string, string>>
  steps_op_prefix_dic: Record<string, string>
}

export interface ExplanationSessionData extends ExplanationInitData {
  wrong_answers: WrongAnswer[]
  steps_info: StepNodeData[]
  steps_history?: StepNodeData[]
}

export interface ExplanationCheckData {
  session_id: string
  question: string
  current_step: number
  total_steps: number
  in_summary: boolean
  knowledge_points: KnowledgePoint[]
  wrong_answers: WrongAnswer[]
  error_analysis: string
}

export interface DiscussionMessage {
  content: string
  type: 'user' | 'assistant' | 'system' | string
  time: string
}

export interface DiscussionStep {
  id: number | string
  title?: string
  description?: string
  content?: string
  isActive: boolean
  isExpanded: boolean
  isMain: boolean
  subSteps: DiscussionStep[]
}

export interface DiscussionInitData {
  session_id: string
  initial_step: string
  analysis: string
  knowledge_points: KnowledgePoint[]
  messages_history: DiscussionMessage[]
  discussion_tree: DiscussionStep[]
}

export interface DiscussionFeedbackData {
  status: 'sub_step' | 'main_step' | 'completed' | string
  current_step?: string
  step?: string
  summary?: string
  discussion_tree: DiscussionStep[]
  messages_history: DiscussionMessage[]
}

export interface DiscussionSessionData {
  session_id: string
  question: string
  personalized_mode: boolean
  images?: ImageData[]
  imageCode?: string[]
  current_step: string
  discussion_tree: DiscussionStep[]
  messages_history: DiscussionMessage[]
  knowledge_points: KnowledgePoint[]
  is_complete: boolean
  summary_text?: string
}

export type DiscussionUpdateStateInput = Omit<DiscussionSessionData, 'personalized_mode'>

export interface SessionListItem {
  session_id: string
  question: string
  type: 'explanation' | 'discussion'
  created_at: string
  last_accessed: string
}

export interface CheckInputData {
  question: string
  user_input: string
}

export interface ImageRecognitionData {
  extracted_text: string
  structured_description: string
}

export type TaskStatusValue = 'queued' | 'processing' | 'success' | 'failed'

export type TaskType =
  | 'EXPLANATION_INIT'
  | 'EXPLANATION_GET_STEP'
  | 'EXPLANATION_COMMIT_ANSWER'
  | 'DISCUSSION_INIT'
  | 'DISCUSSION_FEEDBACK'

export interface TaskAccepted {
  task_id: string
  session_id: string
}

export interface TaskStatus<T = unknown> {
  task_id: string
  status: TaskStatusValue
  data?: T | null
  error_message?: string | null
  created_at?: string
  completed_at?: string | null
}

export interface PendingTask {
  task_id: string
  session_id: string
  task_type: TaskType
  started_at: number
}

export interface BehaviorEvent {
  event_type: string
  timestamp: number
  payload: Record<string, string | number | boolean>
}

export interface BehaviorData {
  session_meta: {
    user_id: string
    session_id: string
    current_mode: string
    current_step_id: string
  }
  event_stream: BehaviorEvent[]
}

export const ErrorCodes = {
  SUCCESS: 200,
  ACCEPTED: 202,
  UNAUTHORIZED: 4010,
  TOKEN_INVALID: 4011,
  TOKEN_EXPIRED: 4012,
  FORBIDDEN: 4030
} as const
