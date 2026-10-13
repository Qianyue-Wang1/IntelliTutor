export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  errors?: Array<{ field: string; message: string }>
}

export interface User {
  id: string
  email?: string | null
  phone_number?: string | null
  nickname?: string | null
  avatar?: string | null
  login_account?: string | null
  must_change_password?: boolean
  roles?: string[]
}

export interface UserInfo extends User {
  roles?: string[]
  created_at?: string
  updated_at?: string
  last_login?: string
}

export interface AuthResult {
  access_token: string
  refresh_token: string
  user: User
}

export interface ExperimentRegistrationOptions { methods: Array<'SMS_VERIFIED' | 'INVITE_CODE'> }
export interface ExperimentRegistrationRequest {
  name: string
  phone_number: string
  password: string
  method: 'SMS_VERIFIED' | 'INVITE_CODE'
  proof: string
}
export interface ExperimentRegistrationResult { user_id: string; login_account: string }
export interface ExperimentCredential {
  id: string
  credential_no: string
  participant_name: string
  participant_code: string
  topic: string
  selected_mode: string
  issued_at: string
}

export interface ImageData { name: string; url: string }
export interface KnowledgePoint { title: string; description: string }
export interface BehaviorEvent {
  event_type: string
  timestamp: number
  payload: Record<string, unknown>
}
export interface BehaviorData {
  session_meta: {
    user_id: string
    session_id: string
    current_mode: 'explanation_mode' | 'discussion_mode'
    current_step_id: string
  }
  event_stream: BehaviorEvent[]
}
export type KnowledgeType = '概念类' | '运算类' | '公式类' | '定理类'
export type KnowledgeLevel = '基础层级' | '核心方法层级'
export interface CoreKnowledgePoint {
  id: number
  name: string
  type: KnowledgeType
  level: KnowledgeLevel
  content: string
}
export interface ProblemSolvingHint {
  basic_idea: string
  skill_enlightenment: string
  core_mathematical_thought: string
  related_method: string
}
export interface LearningPathStep {
  step_number: number
  title: string
  knowledge_points: string[]
  connection: string
  assessment_focus: string
}
export interface WrongAnswer { step_number: string | number; operation: string; wrong_option: string }

export interface StepNodeData {
  step_number: number
  analysis: string
  operation_prefix: string
  options: string[]
  operation_summary_list: string[]
  user_selected_option?: string
}

export interface ExplanationAnswerResult {
  is_correct: boolean
  selected_option: string
  correct_option: string
  answer_explanation: string
  awaiting_next: boolean
}

export interface ExplanationSession {
  session_id: string
  question: string
  personalized_mode: boolean
  adaptive_mode: boolean
  cross_session_enabled: boolean
  persist_learner_state: boolean
  user_id?: string
  images?: ImageData[]
  imageCode?: string[]
  current_step: number
  total_steps: number
  in_summary: boolean
  knowledge_points: KnowledgePoint[]
  core_knowledge?: CoreKnowledgePoint[]
  problem_solving_hint?: ProblemSolvingHint | null
  learning_path?: LearningPathStep[]
  wrong_answers: WrongAnswer[]
  steps_info: StepNodeData[]
  steps_history?: StepNodeData[]
  steps_dict: Record<string, string>
  steps_data?: Record<string, Record<string, string>>
  steps_op_prefix_dic: Record<string, string>
  error_analysis?: string
  current_answer_result?: ExplanationAnswerResult | null
  answer_results?: Record<string, ExplanationAnswerResult>
  awaiting_next?: boolean
}

export interface ExplanationCheck {
  session_id: string
  question: string
  current_step: number
  total_steps: number
  in_summary: boolean
  knowledge_points: KnowledgePoint[]
  core_knowledge?: CoreKnowledgePoint[]
  problem_solving_hint?: ProblemSolvingHint | null
  learning_path?: LearningPathStep[]
  wrong_answers: WrongAnswer[]
  error_analysis?: string
  is_correct?: boolean
  selected_option?: string
  correct_option?: string
  answer_explanation?: string
  awaiting_next?: boolean
}

export interface DiscussionMessage { content: string; type: string; time: string }
export interface DiscussionStep {
  id: string | number
  title?: string
  description?: string
  content?: string
  isActive: boolean
  isExpanded: boolean
  isMain: boolean
  subSteps: DiscussionStep[]
}

export interface DiscussionSession {
  session_id: string
  question: string
  personalized_mode: boolean
  adaptive_mode: boolean
  cross_session_enabled: boolean
  persist_learner_state: boolean
  user_id?: string
  images?: ImageData[]
  imageCode?: string[]
  current_step: string
  discussion_tree: DiscussionStep[]
  messages_history: DiscussionMessage[]
  knowledge_points: KnowledgePoint[]
  core_knowledge?: CoreKnowledgePoint[]
  problem_solving_hint?: ProblemSolvingHint | null
  learning_path?: LearningPathStep[]
  current_options?: string[]
  pending_advance_feedback?: string
  pending_advance_reason?: string
  is_complete: boolean
  summary_text?: string
}

export interface DiscussionFeedback {
  status: 'sub_step' | 'main_step' | 'completed' | string
  step?: string
  summary?: string
  discussion_tree: DiscussionStep[]
  messages_history: DiscussionMessage[]
  current_options?: string[]
  pending_advance_feedback?: string
  pending_advance_reason?: string
}

export interface SessionListItem {
  session_id: string
  question: string
  type: 'explanation' | 'discussion'
  status?: string
  created_at: string
  last_accessed: string
}

export interface TaskAccepted { task_id: string; session_id?: string }
export interface TaskStatus<T> {
  task_id: string
  status: 'queued' | 'processing' | 'success' | 'failed'
  data?: T | null
  error_message?: string | null
}

export interface Capabilities { image_input_enabled: boolean; experiment_mode_enabled?: boolean }

export interface ExperimentContext {
  experiment_mode: boolean
  assigned: boolean
  experiment_id?: string | null
  experiment_name?: string | null
  global_stage?: string | null
  student_state?: string | null
  participation_status?: string | null
  group_code?: string | null
  round?: number
  tutor_mode?: string | null
  session_id?: string | null
  attempt_id?: string | null
  deadline?: string | null
  state_version?: number
  next_route: string
  allowed_actions: string[]
  waiting_message?: string | null
  stage_content?: { markdown?: string } | null
  server_time: string
}

export interface NoAiLecture {
  round: number
  markdown: string
  bottomReached: boolean
}

export interface ExperimentQuestion {
  id: string
  code: string
  type: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'NUMERIC' | 'LIKERT'
  prompt: string
  options?: string[] | Record<string, string> | null
  images?: unknown
  sequence_no: number
}
export interface SavedAnswer { question_id: string; answer: unknown; revision: number; change_count: number }
export interface Assessment {
  attempt_id: string
  code: string
  title: string
  status: string
  started_at: string
  deadline?: string | null
  questions: ExperimentQuestion[]
  answers: SavedAnswer[]
}
export interface TutorStart {
  run_id: string
  group_code: string
  round: number
  mode: string
  session_id: string
  task_id?: string | null
  resumed: boolean
  route: string
}
export interface DirectMessage { role: 'user' | 'assistant'; content: string; time: string; kind: string }
export interface DirectSession { session_id: string; display_question?: string; messages: DirectMessage[]; initial_explanation_generated: boolean }
