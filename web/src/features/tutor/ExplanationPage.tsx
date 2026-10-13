import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, LoaderCircle, RotateCcw } from 'lucide-react'
import { useLocation, useNavigate, useParams } from 'react-router'
import { ApiError } from '@/api/client'
import { services } from '@/api/services'
import type { ExplanationCheck, ExplanationSession, StepNodeData } from '@/api/types'
import MathContent from '@/components/MathContent'
import QuestionPanel from '@/components/QuestionPanel'
import { useNotice } from '@/providers/NoticeProvider'
import { useExperiment } from '@/providers/ExperimentProvider'
import { useAuth } from '@/providers/AuthProvider'
import { useExperimentTelemetry } from '@/features/experiment/telemetry'
import { useBehaviorTracker } from './behavior'

type StepVisualState = 'completed' | 'current' | 'pending'

function mergeStep(session: ExplanationSession, step: StepNodeData): ExplanationSession {
  const stepsInfo = (session.steps_info || []).filter(item => item.step_number !== step.step_number)
  const stepsHistory = (session.steps_history || []).filter(item => item.step_number !== step.step_number)
  if (session.in_summary) stepsHistory.push(step)
  else stepsInfo.push(step)
  return {
    ...session,
    steps_info: stepsInfo.sort((left, right) => left.step_number - right.step_number),
    steps_history: stepsHistory.sort((left, right) => left.step_number - right.step_number),
  }
}

function loadedSteps(session: ExplanationSession): StepNodeData[] {
  const byNumber = new Map<number, StepNodeData>()
  for (const step of session.steps_info || []) byNumber.set(step.step_number, step)
  for (const step of session.steps_history || []) byNumber.set(step.step_number, step)
  return [...byNumber.values()].sort((left, right) => left.step_number - right.step_number)
}

function findStep(session: ExplanationSession, number: number): StepNodeData | undefined {
  return loadedSteps(session).find(step => step.step_number === number)
}

function updateStepSelection(session: ExplanationSession, number: number, option: string): ExplanationSession {
  const select = (steps: StepNodeData[] | undefined) => (steps || []).map(step => (
    step.step_number === number ? { ...step, user_selected_option: option } : step
  ))
  return { ...session, steps_info: select(session.steps_info), steps_history: select(session.steps_history) }
}

function cleanStepTitle(value?: string): string {
  if (!value) return ''
  const operationSection = value.match(/(?:^|\n)#{0,6}\s*具体操作\s*[:：]?\s*([\s\S]*?)(?=\n#{1,6}\s|$)/)?.[1]
  return (operationSection || value)
    .replace(/<[^>]*>/g, ' ')
    .replace(/\$+/g, '')
    .replace(/[#>*_`~]/g, ' ')
    .replace(/^步骤\s*\d+\s*[:：、.-]?\s*/i, '')
    .replace(/\s+/g, ' ')
    .trim()
}

export function getStepTitle(session: ExplanationSession, number: number): string {
  const key = `步骤${number}`
  const loadedStep = findStep(session, number)
  const candidates = [
    session.learning_path?.find(step => step.step_number === number)?.title,
    session.steps_op_prefix_dic?.[key],
    loadedStep?.operation_prefix,
    session.steps_data?.[key]?.['具体操作'],
    session.steps_dict?.[key],
  ]
  return candidates.map(cleanStepTitle).find(Boolean) || '等待生成步骤内容'
}

function getStepState(number: number, current: number, completed: boolean): StepVisualState {
  if (completed || number < current) return 'completed'
  if (number === current) return 'current'
  return 'pending'
}

export default function ExplanationPage() {
  const { sessionId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const notify = useNotice()
  const queryClient = useQueryClient()
  const { context, refresh } = useExperiment()
  const { user } = useAuth()
  useExperimentTelemetry('EXPLANATION_TUTOR', Boolean(context?.experiment_mode && context.assigned))
  const [selected, setSelected] = useState('')
  const [feedback, setFeedback] = useState<ExplanationCheck | null>(null)
  const [working, setWorking] = useState(false)
  const [advancing, setAdvancing] = useState(false)
  const [completionError, setCompletionError] = useState('')
  const [completionRetry, setCompletionRetry] = useState(0)
  const [reviewTarget, setReviewTarget] = useState<{ sessionId: string; currentStep: number; stepNumber: number } | null>(null)
  const sessionQueryKey = useMemo(() => ['explanation', sessionId] as const, [sessionId])
  const query = useQuery({ queryKey: sessionQueryKey, queryFn: ({ signal }) => services.explanation(sessionId, signal), enabled: Boolean(sessionId), retry: 1 })
  const session = query.data ?? null
  const completed = Boolean(session?.in_summary && session.current_step === 0)
  const reviewMode = new URLSearchParams(location.search).get('review') === '1'
  const reviewStepNumber = reviewTarget && reviewTarget.sessionId === sessionId && reviewTarget.currentStep === session?.current_step
    ? reviewTarget.stepNumber
    : null
  const currentStep = session && session.current_step > 0 ? findStep(session, session.current_step) : undefined
  const restoredFeedback = session?.current_answer_result
  const activeFeedback = feedback ?? (restoredFeedback ? {
    session_id: session!.session_id,
    question: session!.question,
    current_step: session!.current_step,
    total_steps: session!.total_steps,
    in_summary: session!.in_summary,
    knowledge_points: session!.knowledge_points,
    wrong_answers: session!.wrong_answers,
    ...restoredFeedback,
  } : null)
  const answered = Boolean(activeFeedback?.awaiting_next)
  const stepQuery = useQuery({
    queryKey: ['explanation-step', sessionId, session?.current_step, Boolean(session?.in_summary), session?.wrong_answers?.length || 0],
    queryFn: ({ signal }) => services.explanationStep(session!.session_id, signal),
    enabled: Boolean(session && session.current_step > 0 && !currentStep),
    staleTime: Infinity,
    retry: (failureCount, error) => error instanceof ApiError && error.code === 6011 ? failureCount < 5 : failureCount < 1,
    retryDelay: attempt => Math.min(300 * 2 ** attempt, 1_200),
  })
  const exportBehavior = useBehaviorTracker({
    enabled: Boolean(session?.personalized_mode),
    userId: user?.id || session?.user_id || 'default',
    sessionId,
    currentMode: 'explanation_mode',
    currentStep: String(session?.current_step || ''),
  })

  useEffect(() => {
    if (!session || !stepQuery.data || currentStep) return
    queryClient.setQueryData<ExplanationSession>(sessionQueryKey, current => mergeStep(current ?? session, stepQuery.data))
  }, [currentStep, queryClient, session, sessionQueryKey, stepQuery.data])

  useEffect(() => {
    if (!completed || reviewMode) return
    let cancelled = false
    const leaveCompletedSession = async () => {
      try {
        if (context?.experiment_mode && context.assigned) {
          await services.completeTutor(sessionId)
          const result = await refresh() as { data?: { next_route?: string }; error?: unknown } | undefined
          if (result?.error) throw result.error
          if (!cancelled) navigate(result?.data?.next_route || '/experiment/waiting', { replace: true })
        } else if (!cancelled) {
          navigate('/ai-tutor', { replace: true })
        }
      } catch (error) {
        if (!cancelled) setCompletionError(error instanceof Error ? error.message : '下一任务暂时无法打开')
      }
    }
    void leaveCompletedSession()
    return () => { cancelled = true }
  }, [completed, completionRetry, context?.assigned, context?.experiment_mode, navigate, refresh, reviewMode])

  const answer = async () => {
    if (!session || !selected || working) return
    setWorking(true); setFeedback(null)
    try {
      const result = await services.answerStep(session.session_id, selected, exportBehavior(selected), String(currentStep?.step_number ?? session.current_step))
      let next: ExplanationSession = {
        ...session,
        ...result,
      }
      if (currentStep) next = updateStepSelection(next, currentStep.step_number, selected)
      setFeedback(result)
      queryClient.setQueryData(sessionQueryKey, next)
      setReviewTarget(null)
      if (!(next.in_summary && next.current_step === 0)) {
        try {
          await services.updateExplanation({ session_id: next.session_id, imageCode: next.imageCode, current_step: next.current_step, in_summary: next.in_summary, wrong_answers: next.wrong_answers || [] })
        } catch (error) {
          notify(error instanceof Error ? `学习进度保存失败：${error.message}` : '学习进度保存失败', 'error')
        }
      }
    } catch (error) { notify(error instanceof Error ? error.message : '答案提交失败', 'error') }
    finally { setWorking(false) }
  }

  const advance = async () => {
    if (!session || !answered || advancing) return
    setAdvancing(true)
    try {
      const next = await services.advanceExplanation(session.session_id)
      queryClient.setQueryData(sessionQueryKey, { ...next, session_id: session.session_id })
      setFeedback(null); setSelected(''); setReviewTarget(null)
    } catch (error) {
      notify(error instanceof Error ? `进入下一步失败：${error.message}` : '进入下一步失败', 'error')
    } finally {
      setAdvancing(false)
    }
  }

  if (query.isError) return <div className="empty-page"><h1>无法打开会话</h1><p>{query.error.message}</p><button className="button button--soft" onClick={() => query.refetch()}><RotateCcw size={17} />重试</button></div>
  if (query.isLoading || !session) return <div className="loading-page"><LoaderCircle className="spin" /><p>正在恢复讲解会话…</p></div>
  if (completed && !reviewMode) return <div className="loading-page"><LoaderCircle className="spin" /><p>{completionError || '正在进入下一任务…'}</p>{completionError && <button type="button" className="button button--soft" onClick={() => { setCompletionError(''); setCompletionRetry(value => value + 1) }}><RotateCcw size={17} />重试</button>}</div>
  const defaultReviewStepNumber = completed && reviewMode ? loadedSteps(session).at(-1)?.step_number ?? null : null
  const displayedReviewStepNumber = reviewStepNumber ?? defaultReviewStepNumber
  const reviewStep = displayedReviewStepNumber == null ? undefined : findStep(session, displayedReviewStepNumber)
  const isReviewing = Boolean(reviewStep)
  const displayedStep = reviewStep ?? currentStep
  const progress = completed ? 100 : session.total_steps ? Math.min(100, Math.round((session.current_step / session.total_steps) * 100)) : 0
  const progressText = completed ? '已完成' : session.in_summary ? '总结' : `${session.current_step} / ${session.total_steps}`
  const displayedStepTitle = displayedStep ? getStepTitle(session, displayedStep.step_number) : ''
  const displayedFeedback = isReviewing && displayedStep
    ? session.answer_results?.[String(displayedStep.step_number)] ?? null
    : activeFeedback
  return <div className="session-page explanation-workbench">
    <div className="explanation-overview">
      <div className="explanation-overview__meta">
        <h1 className="eyebrow">逐步讲解</h1>
        <span className="explanation-overview__count">{progressText}</span>
      </div>
      <div className="explanation-overview__progress" role="progressbar" aria-label="学习进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}><span style={{ width: `${progress}%` }} /></div>
      <QuestionPanel
        question={session.question}
        images={session.images}
        knowledge={session.knowledge_points}
        coreKnowledge={session.core_knowledge}
        problemSolvingHint={session.problem_solving_hint}
        learningPath={session.learning_path}
        imageCode={session.imageCode}
        showKnowledgeDetails
        defaultHintExpanded
      />
    </div>
    <section className="lesson-layout">
      <aside className="step-path" aria-label="循序渐进">
        <div className="step-path__heading"><h2>循序渐进</h2><span>共 {session.total_steps} 步</span></div>
        <ol className="step-path__list">{Array.from({ length: session.total_steps }, (_, index) => index + 1).map(number => {
          const state = getStepState(number, session.current_step, completed && reviewMode)
          const loaded = findStep(session, number)
          const viewable = Boolean(loaded && state !== 'pending')
          const viewing = displayedReviewStepNumber === number && viewable
          const stateLabel = viewing ? '正在回看' : state === 'completed' ? '已完成' : state === 'current' ? '当前' : '未开始'
          return <li className={`step-path__item is-${state}${viewing ? ' is-viewing' : ''}`} key={number} aria-current={state === 'current' ? 'step' : undefined}>
            <button
              type="button"
              className="step-path__action"
              disabled={!viewable}
              aria-pressed={viewing}
              onClick={() => setReviewTarget(!completed && state === 'current' ? null : { sessionId, currentStep: session.current_step, stepNumber: number })}
            >
              <span className="step-path__node" aria-hidden="true">{state === 'completed' ? '✓' : String(number).padStart(2, '0')}</span>
              <span className="step-path__body">
                <span className="step-path__number">步骤 {number}</span>
                <strong className="step-path__title">{getStepTitle(session, number)}</strong>
                <span className="step-path__status">{stateLabel}</span>
              </span>
            </button>
          </li>
        })}</ol>
      </aside>
      <article className="card lesson-card">
        {!displayedStep ? stepQuery.isError ? <div className="step-load-error">
          <strong>当前步骤加载失败</strong>
          <p>{stepQuery.error instanceof Error ? stepQuery.error.message : '请稍后重试'}</p>
          <button type="button" className="button button--soft" onClick={() => void stepQuery.refetch()}><RotateCcw size={17} />重新加载步骤</button>
        </div> : <div className="loading-inline"><LoaderCircle className="spin" />正在生成当前步骤…</div> : <>
          <div className="current-step-heading">
            <span className="eyebrow">{isReviewing ? '回看阶段' : session.in_summary ? '总结' : '当前学习阶段'} · 步骤 {displayedStep.step_number}</span>
            <h2>{displayedStepTitle}</h2>
          </div>
          <section className="learning-explanation" aria-label="知识讲解">
            <span className="eyebrow">知识讲解</span>
            <MathContent content={displayedStep.analysis || displayedStep.operation_prefix} />
          </section>
          <div className="mastery-check-heading"><span className="eyebrow">掌握检验</span><p>请选择最符合本阶段核心知识的答案。</p></div>
          <div className="option-list">{displayedStep.options?.map((option, index) => {
            const key = String.fromCharCode(65 + index)
            const chosen = isReviewing
              ? (displayedFeedback?.selected_option || displayedStep.user_selected_option) === key
              : selected === key
            return <button
              type="button"
              key={`${key}-${option}`}
              className={`option${chosen ? ' is-selected' : ''}${isReviewing ? ' is-readonly' : ''}`}
              disabled={isReviewing || working || answered}
              aria-pressed={chosen}
              onClick={() => setSelected(key)}
            >
              <span className="option__key">{key}</span>
              <MathContent className="option__content" content={option} />
              {isReviewing && chosen && <span className="option__choice-marker">你的选择</span>}
            </button>
          })}</div>
          {isReviewing && !displayedStep.user_selected_option && <p className="history-selection-note">该历史步骤未记录所选选项。</p>}
          {displayedFeedback && <div className={`feedback-box${displayedFeedback.is_correct ? ' is-correct' : ' is-incorrect'}`}>
            <strong>{displayedFeedback.is_correct ? '回答正确' : '需要再巩固一下'}</strong>
            <p>正确答案：{displayedFeedback.correct_option}</p>
            <MathContent content={displayedFeedback.answer_explanation || ('error_analysis' in displayedFeedback ? displayedFeedback.error_analysis || '' : '')} />
          </div>}
          {!isReviewing && !answered && <button className="button button--primary lesson-submit" disabled={!selected || working} onClick={() => void answer()}>{working ? <><LoaderCircle className="spin" size={18} />正在检查</> : <>提交检验 <ArrowRight size={18} /></>}</button>}
          {!isReviewing && answered && <button className="button button--primary lesson-submit" disabled={advancing} onClick={() => void advance()}>{advancing ? <><LoaderCircle className="spin" size={18} />正在进入下一步</> : <>进入下一步 <ArrowRight size={18} /></>}</button>}
        </>}
      </article>
    </section>
  </div>
}
