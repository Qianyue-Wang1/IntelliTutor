import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Clock3, Download, Eye, EyeOff, FileCheck2, LoaderCircle, LockKeyhole, MessageCircleMore, RotateCcw, ShieldCheck, TriangleAlert, UserX } from 'lucide-react'
import { useNavigate, useParams } from 'react-router'
import { services } from '@/api/services'
import type { DirectSession, ExperimentCredential, ExperimentQuestion, SavedAnswer } from '@/api/types'
import MathContent from '@/components/MathContent'
import { useAuth } from '@/providers/AuthProvider'
import { useExperiment } from '@/providers/ExperimentProvider'
import { useNotice } from '@/providers/NoticeProvider'
import { parseCsv } from './csv'
import { useExperimentTelemetry } from './telemetry'

function ExperimentHeader({ label, title }: { label: string; title: string }) {
  const { context } = useExperiment()
  return <header className="experiment-heading">
    <div><span className="eyebrow">{label}</span><h1>{title}</h1></div>
    <div className="experiment-stamp"><span>{context?.global_stage?.replaceAll('_', ' ')}</span><strong>{context?.group_code?.replaceAll('_', ' ') || '尚未分组'}</strong></div>
  </header>
}

function PasswordField({ id, label, value, onChange, autoComplete }: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  autoComplete: 'current-password' | 'new-password'
}) {
  const [visible, setVisible] = useState(false)
  return <div className="form-control">
    <label htmlFor={id}>{label}</label>
    <div className="password-input">
      <input id={id} type={visible ? 'text' : 'password'} value={value} onChange={event => onChange(event.target.value)} autoComplete={autoComplete} />
      <button type="button" className="password-visibility" aria-label={`${visible ? '隐藏' : '显示'}${label}`} aria-pressed={visible} onClick={() => setVisible(current => !current)}>
        {visible ? <EyeOff size={19} /> : <Eye size={19} />}
      </button>
    </div>
  </div>
}

export function ExperimentEntry() {
  const { context, loading } = useExperiment()
  const navigate = useNavigate()
  useEffect(() => { if (!loading && context) navigate(context.next_route, { replace: true }) }, [context, loading, navigate])
  return <div className="loading-page"><LoaderCircle className="spin" /><p>正在恢复你的实验位置…</p></div>
}

export function ExperimentPasswordPage() {
  useExperimentTelemetry('PASSWORD')
  const { refresh } = useExperiment()
  const { updateUser } = useAuth()
  const notice = useNotice()
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [working, setWorking] = useState(false)
  const submit = async () => {
    if (next.length < 8 || next !== confirm) return notice('请确认两次输入一致，且新密码至少 8 位', 'error')
    setWorking(true)
    try {
      await services.experimentPassword(current, next)
      updateUser({ must_change_password: false })
      const result = await refresh() as { data?: { next_route?: string } }
      notice('密码已更新', 'success')
      navigate(result.data?.next_route || '/experiment/waiting', { replace: true })
    } catch (error) { notice(error instanceof Error ? error.message : '密码修改失败', 'error') }
    finally { setWorking(false) }
  }
  return <div className="experiment-narrow"><ExperimentHeader label="首次登录 · 必做" title="设置你的实验密码" />
    <section className="card experiment-card"><LockKeyhole size={32} /><p className="muted">初始密码只用于首次登录。修改后，系统会把你带到知识讲义阶段。</p>
      <div className="form-stack"><PasswordField id="experiment-current-password" label="当前初始密码" value={current} onChange={setCurrent} autoComplete="current-password" />
        <PasswordField id="experiment-new-password" label="新密码" value={next} onChange={setNext} autoComplete="new-password" />
        <PasswordField id="experiment-confirm-password" label="再次输入新密码" value={confirm} onChange={setConfirm} autoComplete="new-password" />
        <button className="button button--primary" disabled={working || !current || !next || !confirm} onClick={() => void submit()}>{working ? '正在保存…' : '保存并继续'}</button>
      </div></section></div>
}

export function LecturePage() {
  useExperimentTelemetry('LECTURE')
  const { context, refresh } = useExperiment()
  const notice = useNotice()
  const navigate = useNavigate()
  const [working, setWorking] = useState(false)
  useEffect(() => { void services.lectureStart().then(() => refresh()).catch(() => undefined) }, [refresh])
  useEffect(() => {
    const timer = window.setInterval(() => void services.lectureHeartbeat(15, { visible: !document.hidden }).catch(() => undefined), 15_000)
    return () => window.clearInterval(timer)
  }, [])
  const complete = async () => {
    setWorking(true)
    try { await services.lectureComplete(); const result = await refresh() as { data?: { next_route?: string } }; navigate(result.data?.next_route || '/experiment/waiting', { replace: true }) }
    catch (error) { notice(error instanceof Error ? error.message : '提交失败', 'error') }
    finally { setWorking(false) }
  }
  return <div className="experiment-reading"><ExperimentHeader label="知识讲义" title="先建立共同的知识基础" />
    <article className="card lecture-sheet"><MathContent content={context?.stage_content?.markdown || '讲义内容尚未配置，请联系实验管理员。'} /></article>
    <div className="experiment-actionbar"><span><ShieldCheck size={18} />完成后将等待管理员统一开启 Pretest</span><button className="button button--primary" disabled={working} onClick={() => void complete()}>{working ? '正在记录…' : '我已完成讲义学习'}</button></div>
  </div>
}

export function WaitingPage() {
  useExperimentTelemetry('WAITING')
  const { context, refresh } = useExperiment()
  return <div className="experiment-narrow waiting-stage"><div className="waiting-orbit"><Clock3 /></div><span className="eyebrow">实验进度已保存</span>
    <h1>等待下一阶段开启</h1><p>{context?.waiting_message || '你已完成当前任务，请等待实验管理员。'}</p>
    <div className="stage-ledger"><span>个人状态</span><strong>{context?.student_state?.replaceAll('_', ' ')}</strong><span>全局阶段</span><strong>{context?.global_stage?.replaceAll('_', ' ')}</strong></div>
    <button className="button button--soft" onClick={() => void refresh()}>立即检查</button><small>页面每 5 秒自动检查一次，开启后会自动进入。</small></div>
}

const experimentModes = [
  { code: 'DIRECT_LLM', title: 'Direct LLM', detail: '围绕本轮主题阅读讲解，并可进行有限追问。' },
  { code: 'BASIC_TUTOR', title: 'Basic Tutor', detail: '根据本轮学习过程调整讲解细致程度。' },
  { code: 'FULL_TUTOR', title: 'Full Tutor', detail: '从第二轮起结合此前轮次的结构化学习状态。' },
  { code: 'NO_AI', title: '无 AI 对照', detail: '阅读固定学习讲义后进入本轮小后测。' },
] as const

export function ModeSelectionPage() {
  useExperimentTelemetry('MODE_SELECTION')
  const { context, refresh } = useExperiment()
  const navigate = useNavigate()
  const notice = useNotice()
  const [selected, setSelected] = useState<(typeof experimentModes)[number] | null>(null)
  const [working, setWorking] = useState(false)
  const confirm = async () => {
    if (!selected || working || context?.state_version === undefined) return
    setWorking(true)
    try {
      const result = await services.selectExperimentMode(selected.code, context.state_version)
      await refresh()
      navigate(result.next_route, { replace: true })
    } catch (error) {
      notice(error instanceof Error ? error.message : '模式选择失败，请刷新后重试', 'error')
      setWorking(false)
    }
  }
  return <div className="experiment-narrow"><ExperimentHeader label="实验设置" title="选择本次实验学习方式" />
    <p className="mode-lock-note"><LockKeyhole size={17} />请按线下分组名单选择。确认后本次实验不可更换模式。</p>
    <div className="experiment-mode-grid">{experimentModes.map(mode => <button key={mode.code} className={`card experiment-mode${selected?.code === mode.code ? ' is-selected' : ''}`} disabled={working} onClick={() => setSelected(mode)}><strong>{mode.title}</strong><span>{mode.detail}</span></button>)}</div>
    {selected && <div className="experiment-actionbar"><span>已选择：<strong>{selected.title}</strong></span><button className="button button--primary" disabled={working} onClick={() => void confirm()}>{working ? '正在锁定…' : `确认选择 ${selected.title}`}</button></div>}
  </div>
}

export function NoAiLearningPage() {
  useExperimentTelemetry('NO_AI_LEARNING')
  const { refresh } = useExperiment()
  const navigate = useNavigate()
  const notice = useNotice()
  const [lecture, setLecture] = useState<{ round: number; markdown: string; bottomReached: boolean } | null>(null)
  const [loading, setLoading] = useState(true)
  const [recordingBottom, setRecordingBottom] = useState(false)
  const [working, setWorking] = useState(false)
  const lectureRef = useRef<HTMLDivElement>(null)
  const autoBottomRecorded = useRef(false)
  const bottomReached = lecture?.bottomReached || false

  useEffect(() => {
    let active = true
    void services.noAiLecture().then(data => {
      if (active) setLecture(data)
    }).catch(error => {
      if (active) notice(error instanceof Error ? error.message : '无法载入本轮学习讲义', 'error')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [notice])

  const recordBottom = useCallback((scrollTop: number, clientHeight: number, scrollHeight: number) => {
    if (bottomReached || recordingBottom || scrollTop + clientHeight < scrollHeight - 4) return
    setRecordingBottom(true)
    void services.noAiScroll(100).then(() => {
      setLecture(current => current ? { ...current, bottomReached: true } : current)
    }).catch(error => {
      notice(error instanceof Error ? error.message : '无法记录阅读进度，请重试', 'error')
    }).finally(() => setRecordingBottom(false))
  }, [bottomReached, notice, recordingBottom])

  useEffect(() => {
    const element = lectureRef.current
    if (!lecture || bottomReached || recordingBottom || autoBottomRecorded.current || !element || element.clientHeight === 0) return
    if (element.scrollHeight <= element.clientHeight + 4) {
      autoBottomRecorded.current = true
      recordBottom(element.scrollTop, element.clientHeight, element.scrollHeight)
    }
  }, [bottomReached, lecture, recordBottom, recordingBottom])

  const complete = async () => {
    if (!bottomReached || working) return
    setWorking(true)
    try {
      await services.noAiComplete()
      const result = await refresh() as { data?: { next_route?: string } }
      navigate(result.data?.next_route || '/experiment/assessment/quiz', { replace: true })
    } catch (error) {
      notice(error instanceof Error ? error.message : '无法进入本轮小后测，请重试', 'error')
      setWorking(false)
    }
  }

  if (loading) return <div className="loading-page"><LoaderCircle className="spin" /><p>正在载入固定学习讲义…</p></div>
  if (!lecture) return <div className="empty-page"><h1>讲义暂不可用</h1><p>请联系实验管理员后重试。</p></div>
  return <div className="experiment-reading"><ExperimentHeader label={`第 ${lecture.round} 轮 · 无 AI 对照`} title="阅读固定学习讲义" />
    <section className="card no-ai-learning"><p className="muted">请完整阅读本轮讲义。阅读到底后，才能进入小后测。</p>
      <div ref={lectureRef} className="no-ai-lecture-scroll" role="region" aria-label="无 AI 讲义正文" tabIndex={0}
        onScroll={event => recordBottom(event.currentTarget.scrollTop, event.currentTarget.clientHeight, event.currentTarget.scrollHeight)}>
        <MathContent content={lecture.markdown} />
      </div>
    </section>
    <div className="experiment-actionbar"><span><ShieldCheck size={18} />{bottomReached ? '已记录完整阅读，可以进入小后测。' : recordingBottom ? '正在记录阅读完成情况…' : '请滚动至讲义底部后继续。'}</span>
      <button className="button button--primary" disabled={!bottomReached || working} onClick={() => void complete()}>{working ? '正在保存…' : '完成学习并进入小后测'}</button></div>
  </div>
}

export function CompletionCredentialPage() {
  useExperimentTelemetry('CREDENTIAL')
  const navigate = useNavigate()
  const notice = useNotice()
  const [credential, setCredential] = useState<ExperimentCredential | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    void services.experimentCredential().then(value => { if (active) setCredential(value) })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : '无法生成完成凭证') })
    return () => { active = false }
  }, [])
  if (error) return <div className="empty-page"><h1>完成凭证暂不可用</h1><p>{error}</p></div>
  if (!credential) return <div className="loading-page"><LoaderCircle className="spin" /><p>正在生成完成凭证…</p></div>
  return <div className="experiment-narrow credential-page"><ExperimentHeader label="实验已完成" title="你已完成本次测试" />
    <section className="card experiment-card credential-card"><p>完成记录已保存。你可以截图留存。</p>
      <dl><dt>参与者</dt><dd>{credential.participant_name}</dd><dt>知识主题</dt><dd>{credential.topic}</dd><dt>实验模式（研究组）</dt><dd>{credential.selected_mode}</dd><dt>参与编号</dt><dd>{credential.participant_code}</dd><dt>凭证编号</dt><dd>{credential.credential_no}</dd><dt>签发时间</dt><dd>{new Date(credential.issued_at).toLocaleString('zh-CN')}</dd></dl>
      <button className="button button--primary" onClick={() => { void services.credentialPng(credential.id).then(blob => {
        const link = document.createElement('a')
        link.href = URL.createObjectURL(blob)
        link.download = `${credential.credential_no}.png`
        link.click()
        URL.revokeObjectURL(link.href)
      }).catch(() => notice('完成凭证下载失败，请稍后重试', 'error')) }}>保存完成凭证</button>
      <button className="button button--soft" onClick={() => { navigator.clipboard?.writeText(credential.credential_no).then(() => notice('凭证编号已复制', 'success')).catch(() => undefined) }}>复制凭证编号</button>
      <button className="button button--primary" onClick={() => navigate('/experiment/records')}>查看记录与导出数据</button>
    </section></div>
}

export function TutorModePage() {
  useExperimentTelemetry('TUTOR_MODE')
  const { context, refresh } = useExperiment()
  const navigate = useNavigate()
  const notice = useNotice()
  const [working, setWorking] = useState<string | null>(null)
  const choose = async (mode: 'explanation' | 'discussion') => {
    setWorking(mode)
    try {
      const run = await services.tutorStart(mode)
      const result = await refresh() as { data?: { next_route?: string } }
      navigate(result.data?.next_route || run.route, { replace: true })
    }
    catch (error) { notice(error instanceof Error ? error.message : 'Tutor 启动失败', 'error'); setWorking(null) }
  }
  return <div className="experiment-narrow"><ExperimentHeader label="本轮学习方式" title="选择这一轮的辅导方式" />
    {context?.round_topic && <section className="card experiment-card"><span className="eyebrow">本轮主题</span><p>{context.round_topic}</p></section>}
    <p className="mode-lock-note"><LockKeyhole size={17} />选择会与本轮会话同时锁定，刷新或重新登录不会改变。</p>
    <div className="experiment-mode-grid"><button className="card experiment-mode" disabled={working !== null} onClick={() => void choose('explanation')}><FileCheck2 /><strong>逐步讲解</strong><span>按关键步骤练习并即时检查理解</span>{working === 'explanation' && <LoaderCircle className="spin" />}</button>
      <button className="card experiment-mode" disabled={working !== null} onClick={() => void choose('discussion')}><MessageCircleMore /><strong>启发研讨</strong><span>通过连续对话共同推演解题路径</span>{working === 'discussion' && <LoaderCircle className="spin" />}</button></div>
  </div>
}

export function TutorLaunchPage() {
  useExperimentTelemetry('TUTOR_LAUNCH')
  const { refresh } = useExperiment()
  const navigate = useNavigate()
  const notice = useNotice()
  useEffect(() => {
    const controller = new AbortController()
    void services.tutorStart().then(async run => {
      const result = await refresh() as { data?: { next_route?: string } }
      if (!controller.signal.aborted) navigate(result.data?.next_route || run.route, { replace: true })
    })
      .catch(error => { if (!controller.signal.aborted) notice(error instanceof Error ? error.message : 'Tutor 启动失败', 'error') })
    return () => controller.abort()
  }, [navigate, notice, refresh])
  return <div className="loading-page"><LoaderCircle className="spin" /><p>正在载入本轮指定题目与会话…</p></div>
}

function optionEntries(question: ExperimentQuestion): Array<[string, string]> {
  if (Array.isArray(question.options)) return question.options.map((value, index) => [String.fromCharCode(65 + index), String(value)])
  return Object.entries(question.options || {}).map(([key, value]) => [key, String(value)])
}

export function AssessmentPage() {
  useExperimentTelemetry('ASSESSMENT')
  const { context, refresh } = useExperiment()
  const navigate = useNavigate()
  const notice = useNotice()
  const queryClient = useQueryClient()
  const [index, setIndex] = useState(0)
  const [answers, setAnswers] = useState<Record<string, SavedAnswer>>({})
  const [saving, setSaving] = useState(false)
  const [seconds, setSeconds] = useState<number | null>(null)
  const query = useQuery({ queryKey: ['experiment-assessment', context?.attempt_id], queryFn: async () => {
    const result = context?.attempt_id ? await services.assessment(context.attempt_id) : await services.assessmentStart()
    setAnswers(Object.fromEntries(result.answers.map(answer => [answer.question_id, answer])))
    return result
  }, retry: 1 })
  const assessment = query.data
  useEffect(() => {
    if (!assessment?.deadline) return
    const tick = () => setSeconds(Math.max(0, Math.ceil((new Date(assessment.deadline!).getTime() - Date.now()) / 1000)))
    tick(); const timer = window.setInterval(tick, 1_000); return () => window.clearInterval(timer)
  }, [assessment?.deadline])
  const question = assessment?.questions[index]
  useEffect(() => { if (assessment && question) void services.questionViewed(assessment.attempt_id, question.id).catch(() => undefined) }, [assessment, question])
  useEffect(() => {
    if (seconds !== 0 || !assessment) return
    void services.assessment(assessment.attempt_id).catch(() => undefined).then(async () => {
      await refresh(); navigate('/experiment/waiting', { replace: true })
    })
  }, [seconds, assessment, refresh, navigate])
  const save = async (value: unknown) => {
    if (!assessment || !question || saving) return
    setSaving(true)
    try {
      const saved = await services.saveAnswer(assessment.attempt_id, question.id, value, answers[question.id]?.revision || 0, crypto.randomUUID())
      setAnswers(current => ({ ...current, [question.id]: saved }))
    } catch (error) { notice(error instanceof Error ? error.message : '答案保存失败', 'error'); void queryClient.invalidateQueries({ queryKey: ['experiment-assessment'] }) }
    finally { setSaving(false) }
  }
  const submit = async () => {
    if (!assessment || !window.confirm('确认提交整份测试？提交后将永久锁定，不能再修改。')) return
    if (assessment.questions.some(item => !['SINGLE_CHOICE', 'LIKERT'].includes(item.type) || optionEntries(item).length < 2)) {
      notice('当前测试包含不受支持的题型，请联系实验管理员', 'error')
      return
    }
    setSaving(true)
    try { await services.submitAssessment(assessment.attempt_id, crypto.randomUUID()); await refresh(); navigate('/experiment/waiting', { replace: true }) }
    catch (error) { notice(error instanceof Error ? error.message : '交卷失败', 'error') }
    finally { setSaving(false) }
  }
  if (query.isError) return <div className="empty-page"><h1>无法打开测试</h1><p>{query.error.message}</p></div>
  if (query.isLoading || !assessment || !question) return <div className="loading-page"><LoaderCircle className="spin" /><p>正在载入测试题目…</p></div>
  const saved = answers[question.id]
  const selected = saved?.answer
  const choices = optionEntries(question)
  const choiceQuestion = (question.type === 'SINGLE_CHOICE' || question.type === 'LIKERT') && choices.length >= 2
  const assessmentValid = assessment.questions.every(item => ['SINGLE_CHOICE', 'LIKERT'].includes(item.type) && optionEntries(item).length >= 2)
  return <div className="assessment-shell"><ExperimentHeader label={assessment.code} title={assessment.title} />
    <div className="assessment-status"><span>题目 {index + 1} / {assessment.questions.length}</span><strong>{saving ? '正在保存…' : saved ? `已保存 · 版本 ${saved.revision}` : '尚未作答'}</strong>{seconds !== null && <span className={seconds < 60 ? 'is-urgent' : ''}><Clock3 size={16} /> {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}</span>}</div>
    <div className="assessment-layout"><nav className="card question-index" aria-label="题目导航">{assessment.questions.map((item, itemIndex) => <button key={item.id} className={`${itemIndex === index ? 'is-current' : ''}${answers[item.id] ? ' is-answered' : ''}`} onClick={() => setIndex(itemIndex)}>{itemIndex + 1}{answers[item.id] && <Check size={13} />}</button>)}</nav>
      <section className="card assessment-question"><span className="eyebrow">第 {index + 1} 题</span><MathContent content={question.prompt} />
        {choiceQuestion ? <div className="assessment-options" role="radiogroup" aria-label={`第 ${index + 1} 题选项`}>{choices.map(([key, value]) => <button data-telemetry-id={`assessment-option-${index + 1}-${key}`} type="button" role="radio" aria-checked={String(selected) === key} disabled={saving} key={key} className={String(selected) === key ? 'is-selected' : ''} onClick={() => void save(key)}><b>{key}</b><MathContent content={value} /></button>)}</div>
          : <div className="assessment-config-error" role="alert"><TriangleAlert size={20} /><div><strong>题型配置不符合实验要求</strong><p>当前实验只支持单选题，请联系实验管理员检查题目配置。</p></div></div>}
      </section></div>
    <footer className="assessment-footer"><button data-telemetry-id="assessment-previous" className="button button--soft" disabled={index === 0} onClick={() => setIndex(value => value - 1)}>上一题</button><span>{Object.keys(answers).length} / {assessment.questions.length} 已作答</span>{index < assessment.questions.length - 1 ? <button data-telemetry-id="assessment-next" className="button button--primary" onClick={() => setIndex(value => value + 1)}>下一题</button> : <button data-telemetry-id="assessment-submit" className="button button--primary" disabled={saving || !assessmentValid} onClick={() => void submit()}>提交整份测试</button>}</footer>
  </div>
}

export function DirectTutorPage() {
  useExperimentTelemetry('DIRECT_TUTOR')
  const { sessionId = '' } = useParams()
  const { refresh } = useExperiment()
  const navigate = useNavigate()
  const notice = useNotice()
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['direct-session', sessionId], queryFn: () => services.directSession(sessionId) })
  const [input, setInput] = useState('')
  const [working, setWorking] = useState(false)
  const followupLimitReached = (query.data?.messages || []).filter(message => message.kind === 'followup').length >= 3
  useEffect(() => {
    if (!followupLimitReached) return
    void refresh().then(result => {
      const nextRoute = (result as { data?: { next_route?: string } }).data?.next_route
      navigate(nextRoute || '/experiment/assessment/quiz', { replace: true })
    })
  }, [followupLimitReached, navigate, refresh])
  const send = async () => {
    if (!input.trim() || followupLimitReached) return
    setWorking(true)
    try { const result = await services.directFollowup(sessionId, input.trim()); queryClient.setQueryData(['direct-session', sessionId], result); setInput('') }
    catch (error) { notice(error instanceof Error ? error.message : '追问失败', 'error') }
    finally { setWorking(false) }
  }
  const complete = async () => {
    setWorking(true)
    try { await services.directComplete(sessionId); await refresh(); navigate('/experiment/waiting', { replace: true }) }
    catch (error) { notice(error instanceof Error ? error.message : '完成状态保存失败', 'error'); setWorking(false) }
  }
  if (query.isLoading || !query.data) return <div className="loading-page"><LoaderCircle className="spin" /><p>正在恢复 Direct LLM 会话…</p></div>
  const session: DirectSession = query.data
  return <div className="direct-shell"><ExperimentHeader label="Direct LLM" title="指定题目讲解" /><section className="card direct-question"><span className="eyebrow">本轮学习主题</span><MathContent content={session.display_question || '本轮学习主题'} /></section>
    <section className="card direct-dialog">{session.messages.filter(message => message.kind !== 'assigned_question').map((message, i) => <article className={`direct-message is-${message.role}`} key={`${message.time}-${i}`}><span>{message.role === 'assistant' ? 'AI' : '你'}</span><MathContent content={message.content} /></article>)}</section>
    <div className="card direct-composer">{followupLimitReached ? <p className="muted">已完成三次追问，正在进入本轮小后测。</p> : <><textarea value={input} onChange={e => setInput(e.target.value)} placeholder="可选：围绕当前题目继续追问…" /><button className="button button--soft" disabled={working || !input.trim()} onClick={() => void send()}>发送追问</button><button className="button button--primary" disabled={working || !session.initial_explanation_generated} onClick={() => void complete()}>完成本轮学习</button></>}</div>
  </div>
}

export function FinishedPage() {
  useExperimentTelemetry('FINISHED')
  const { context } = useExperiment()
  return <div className="experiment-narrow finished-stage"><div className="finish-mark"><Check /></div><span className="eyebrow">记录已锁定</span><h1>{context?.participation_status === 'ACTIVE' ? '实验已完成' : '实验参与已结束'}</h1><p>感谢你的参与。所有已提交答案、Tutor 对话与实验事件均已安全保存，请按照现场管理员指引离场。</p></div>
}

export function AdminExperimentPage() {
  const auth = useAuth()
  const notice = useNotice()
  const isAdmin = auth.user?.roles?.includes('ADMIN')
  const experiments = useQuery({ queryKey: ['admin-experiments'], queryFn: services.adminExperiments, enabled: isAdmin })
  const [chosenExperiment, setChosenExperiment] = useState('')
  const selected = chosenExperiment || String(experiments.data?.[0]?.id || '')
  const dashboard = useQuery({ queryKey: ['admin-dashboard', selected], queryFn: () => services.adminDashboard(selected), enabled: Boolean(selected), refetchInterval: 5_000 })
  const [importResult, setImportResult] = useState<unknown>(null)
  const [dryRun, setDryRun] = useState(true)
  const experiment = dashboard.data?.experiment as Record<string, unknown> | undefined
  const completion = dashboard.data?.completion as Record<string, unknown> | undefined
  const students = (dashboard.data?.students || []) as Array<Record<string, unknown>>
  const anomalies = (dashboard.data?.anomalies || []) as Array<Record<string, unknown>>
  const currentStage = String(experiment?.current_stage_code || '')
  const canImportStudents = Boolean(selected) && currentStage === 'SETUP' && String(experiment?.status || '') === 'DRAFT'
  const canImportGroups = Boolean(selected) && currentStage === 'GROUP_ASSIGNMENT'
  const uploadJson = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]; if (!file) return
    try { const body = JSON.parse(await file.text()) as Record<string, unknown>; const result = await services.adminImportConfig({ ...body, dry_run: dryRun }); setImportResult(result); if (!dryRun) await experiments.refetch() }
    catch (error) { notice(error instanceof Error ? error.message : '配置导入失败', 'error') }
  }
  const uploadStudents = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]; if (!file || !selected) return
    if (!canImportStudents) { notice('学生账号只能在 SETUP 阶段导入', 'error'); event.target.value = ''; return }
    try { const rows = parseCsv(await file.text(), ['student_id', 'login_account', 'class_code']).map(row => ({ student_id: row.student_id, login_account: row.login_account, class_code: row.class_code })); const result = await services.adminImportStudents({ experiment_id: selected, students: rows, dry_run: dryRun }); setImportResult(result); if (!dryRun) await dashboard.refetch() }
    catch (error) { notice(error instanceof Error ? error.message : '学生导入失败', 'error') }
  }
  const uploadGroups = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]; if (!file || !selected) return
    if (!canImportGroups) { notice('请等待全部学生提交 Pretest，并先进入 GROUP_ASSIGNMENT 阶段', 'error'); event.target.value = ''; return }
    try { const rows = parseCsv(await file.text(), ['student_id', 'login_account', 'group_code']).map(row => ({ student_id: row.student_id || null, login_account: row.login_account || null, group_code: row.group_code })); const result = await services.adminImportGroups({ experiment_id: selected, groups: rows, dry_run: dryRun }); setImportResult(result); if (!dryRun) await dashboard.refetch() }
    catch (error) { notice(error instanceof Error ? error.message : '分组导入失败', 'error') }
  }
  const advance = async () => {
    if (!selected || !experiment || !window.confirm(`确认开启 ${String(dashboard.data?.nextStage || '')}？`)) return
    try { await services.adminAdvance(selected, Number(experiment.version)); await dashboard.refetch() }
    catch (error) { notice(error instanceof Error ? error.message : '阶段推进失败', 'error') }
  }
  const download = async (kind: 'assessment-results' | 'tutor-runs' | 'events') => {
    if (!selected) return
    try {
      const blob = await services.adminExport(selected, kind)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a'); link.href = url
      link.download = kind === 'events' ? 'experiment-events.jsonl' : `${kind}.csv`
      link.click(); URL.revokeObjectURL(url)
    } catch (error) { notice(error instanceof Error ? error.message : '导出失败', 'error') }
  }
  const resetPassword = async (row: Record<string, unknown>) => {
    if (!selected || !window.confirm(`确认重置 ${String(row.student_id)} 的临时密码？`)) return
    try { setImportResult(await services.adminResetPassword(selected, String(row.assignment_id))); await dashboard.refetch() }
    catch (error) { notice(error instanceof Error ? error.message : '密码重置失败', 'error') }
  }
  const endParticipation = async (row: Record<string, unknown>, kind: 'withdraw' | 'exclude') => {
    if (!selected) return
    const reason = window.prompt(kind === 'withdraw' ? '请输入退出实验原因' : '请输入排除原因')?.trim()
    if (!reason) return
    try { await services.adminEndParticipation(selected, String(row.assignment_id), kind, reason); await dashboard.refetch() }
    catch (error) { notice(error instanceof Error ? error.message : '操作失败', 'error') }
  }
  const retryPersonalization = async (row: Record<string, unknown>) => {
    if (!selected || !row.tutor_run_id) return
    try { await services.adminRetryPersonalization(selected, String(row.tutor_run_id)); await dashboard.refetch(); notice('learner state 重试已完成', 'success') }
    catch (error) { notice(error instanceof Error ? error.message : '个性化后处理重试失败', 'error') }
  }
  const createInviteCode = async () => {
    if (!selected) return
    try { setImportResult(await services.adminCreateInviteCode(selected)) }
    catch (error) { notice(error instanceof Error ? error.message : '邀请码生成失败', 'error') }
  }
  if (!isAdmin) return <div className="empty-page"><h1>无管理员权限</h1></div>
  return <div className="admin-experiment"><header className="admin-heading"><div><span className="eyebrow">Human Experiment Control</span><h1>实验控制台</h1></div><select value={selected} onChange={e => setChosenExperiment(e.target.value)}>{experiments.data?.map(item => <option key={String(item.id)} value={String(item.id)}>{String(item.name)} · {String(item.config_version)}</option>)}</select></header>
    <section className="admin-control card"><div><span>当前全局阶段</span><strong>{String(experiment?.current_stage_code || '尚未导入实验')}</strong></div><div><span>当前完成率</span><strong>{String(completion?.completed || 0)} / {String(completion?.required || 0)}</strong></div><button className="button button--primary" disabled={!selected || completion?.completed !== completion?.required || !dashboard.data?.nextStage} onClick={() => void advance()}>开启 {String(dashboard.data?.nextStage || '下一阶段')}</button></section>
    <label className="admin-dry-run"><input type="checkbox" checked={dryRun} onChange={e => setDryRun(e.target.checked)} /><span>{dryRun ? '仅校验（不写入数据库）' : '正式导入（将写入数据库）'}</span></label>
    <section className="admin-export-row"><button className="button button--soft" disabled={!selected} onClick={() => void createInviteCode()}>生成一次性邀请码</button></section>
    <section className="admin-import-grid"><label className="card import-card"><strong>实验配置 JSON</strong><span>预定义各组的 Tutor 规则，不会给学生分组</span><input type="file" accept="application/json" onChange={e => void uploadJson(e)} /></label><label className="card import-card" aria-disabled={!canImportStudents}><strong>学生账号 CSV</strong><span>{canImportStudents ? '当前 SETUP 阶段可导入账号' : '仅在实验的 SETUP 阶段开放'}</span><input type="file" accept=".csv,text/csv" disabled={!canImportStudents} onChange={e => void uploadStudents(e)} /></label><label className="card import-card" aria-disabled={!canImportGroups}><strong>Pretest 后学生分组 CSV</strong><span>{canImportGroups ? '当前已进入分组阶段，可以导入 group_code' : '全部 Pretest 提交并进入 GROUP_ASSIGNMENT 后开放'}</span><input type="file" accept=".csv,text/csv" disabled={!canImportGroups} onChange={e => void uploadGroups(e)} /></label></section>
    <section className="admin-export-row"><button className="button button--soft" disabled={!selected} onClick={() => void download('assessment-results')}><Download size={16} />Assessment 结果</button><button className="button button--soft" disabled={!selected} onClick={() => void download('tutor-runs')}><Download size={16} />Tutor 运行</button><button className="button button--soft" disabled={!selected} onClick={() => void download('events')}><Download size={16} />事件 JSONL</button></section>
    {importResult != null && <details className="card import-result" open><summary>最近导入结果（初始密码只在这里返回一次）</summary><pre>{JSON.stringify(importResult, null, 2)}</pre></details>}
    {anomalies.length > 0 && <section className="card admin-anomalies"><div className="section-heading"><h2><TriangleAlert size={20} />异常与阻塞</h2><span>{anomalies.length} 人</span></div>{anomalies.map(row => <div key={String(row.assignment_id)}><strong>{String(row.student_id)}</strong><span>{String(row.anomaly_type)}</span><small>{String(row.student_state)}</small></div>)}</section>}
    <section className="card admin-table-card"><div className="section-heading"><h2>学生进度</h2><span className="muted">每 5 秒刷新</span></div><div className="admin-table-wrap"><table><thead><tr><th>学生</th><th>账号</th><th>组别</th><th>个人状态</th><th>轮次</th><th>Mode</th><th>Tutor / learner state</th><th>Assessment</th><th>最后活动</th><th>管理</th></tr></thead><tbody>{students.map(row => <tr key={String(row.assignment_id)}><td>{String(row.student_id)}</td><td>{String(row.login_account)}</td><td>{String(row.group_code || '—')}</td><td>{String(row.student_state)}</td><td>{String(row.current_round)}</td><td>{String(row.tutor_mode || '—')}</td><td>{String(row.tutor_status || '—')} / {String(row.learner_state_status || '—')}</td><td><code>{String(row.assessment_statuses || '{}')}</code></td><td>{row.last_activity_at ? new Date(String(row.last_activity_at)).toLocaleString('zh-CN') : '—'}</td><td><div className="admin-row-actions"><button title="重置临时密码" onClick={() => void resetPassword(row)}><RotateCcw size={15} /></button>{row.learner_state_status === 'FAILED' && <button title="重试 learner state" onClick={() => void retryPersonalization(row)}><LoaderCircle size={15} /></button>}{row.participation_status === 'ACTIVE' && <><button title="记录退出" onClick={() => void endParticipation(row, 'withdraw')}><UserX size={15} /></button><button title="排除异常数据" onClick={() => void endParticipation(row, 'exclude')}><ShieldCheck size={15} /></button></>}</div></td></tr>)}</tbody></table></div></section>
  </div>
}
