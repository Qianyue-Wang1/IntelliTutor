import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, CheckCircle2, ChevronDown, ChevronRight, LoaderCircle, Send, Trash2 } from 'lucide-react'
import { useLocation, useNavigate, useParams } from 'react-router'
import { services } from '@/api/services'
import type { DiscussionMessage, DiscussionSession, DiscussionStep } from '@/api/types'
import MathContent from '@/components/MathContent'
import QuestionPanel from '@/components/QuestionPanel'
import { useNotice } from '@/providers/NoticeProvider'
import { useAuth } from '@/providers/AuthProvider'
import { useBehaviorTracker } from './behavior'
import { useExperiment } from '@/providers/ExperimentProvider'
import { useExperimentTelemetry } from '@/features/experiment/telemetry'
import { releaseTutorCompletion } from './completionHold'

const COMPLETION_ACKNOWLEDGEMENT = '🎉 恭喜！问题研讨已完成。'

export function normalizeDiscussionSummary(value?: string): string {
  const text = value?.trim() || ''
  if (!text) return ''
  if (text.includes('## 问题总结')) return text.slice(text.indexOf('## 问题总结')).trim()
  try {
    const legacy = JSON.parse(text) as { problem_review?: unknown; solution_steps?: unknown; answer_summary?: unknown }
    if (legacy && typeof legacy === 'object') {
      const lines = ['## 问题总结']
      if (legacy.problem_review) lines.push('', `- **问题回顾**：${String(legacy.problem_review)}`)
      if (Array.isArray(legacy.solution_steps) && legacy.solution_steps.length) {
        lines.push('', '- **解题步骤**：', ...legacy.solution_steps.map((step, index) => `  ${index + 1}. ${String(step)}`))
      }
      if (legacy.answer_summary) lines.push('', `- **答案总结**：${String(legacy.answer_summary)}`)
      return lines.join('\n')
    }
  } catch {
    // Non-JSON summaries are valid legacy plain text.
  }
  return `## 问题总结\n\n${text}`
}

export function discussionMessagesForDisplay(session: DiscussionSession): DiscussionMessage[] {
  const messages = session.messages_history || []
  if (!session.is_complete) return messages
  const summary = normalizeDiscussionSummary(session.summary_text)
  if (!summary || messages.some(message => message.content.trim() === summary)) return messages
  const last = messages.at(-1)
  const withoutAcknowledgement = last?.content.trim() === COMPLETION_ACKNOWLEDGEMENT ? messages.slice(0, -1) : messages
  return [...withoutAcknowledgement, { content: summary, type: 'system', time: last?.time || '' }]
}

function changeTree(nodes: DiscussionStep[], id: string | number, action: 'toggle' | 'delete'): DiscussionStep[] {
  if (action === 'delete') return nodes.filter(node => node.id !== id).map(node => ({ ...node, subSteps: changeTree(node.subSteps || [], id, action) }))
  return nodes.map(node => node.id === id ? { ...node, isExpanded: !node.isExpanded } : { ...node, subSteps: changeTree(node.subSteps || [], id, action) })
}

function activeDiscussionStepId(nodes: DiscussionStep[]): string | undefined {
  for (const node of nodes) {
    if (node.isActive) return String(node.id)
    const nested = activeDiscussionStepId(node.subSteps || [])
    if (nested) return nested
  }
  return undefined
}

function TreeNode({ node, depth, onChange }: { node: DiscussionStep; depth: number; onChange: (id: string | number, action: 'toggle' | 'delete') => void }) {
  const hasChildren = Boolean(node.subSteps?.length)
  return <li className={`tree-node${node.isActive ? ' is-active' : ''}`}>
    <div className="tree-node__row" style={{ paddingLeft: `${Math.min(depth, 5) * 18}px` }}>
      <button className="tree-toggle" onClick={() => onChange(node.id, 'toggle')} disabled={!hasChildren} aria-label={node.isExpanded ? '收起子步骤' : '展开子步骤'}>{hasChildren ? (node.isExpanded ? <ChevronDown /> : <ChevronRight />) : <span />}</button>
      <div><strong>{node.title || `步骤 ${node.id}`}</strong>{(node.description || node.content) && <MathContent content={node.description || node.content || ''} />}</div>
      {!node.isMain && <button className="icon-button tree-delete" aria-label="删除子步骤" onClick={() => onChange(node.id, 'delete')}><Trash2 size={15} /></button>}
    </div>
    {hasChildren && node.isExpanded && <ul>{node.subSteps.map(child => <TreeNode key={child.id} node={child} depth={depth + 1} onChange={onChange} />)}</ul>}
  </li>
}

export default function DiscussionPage() {
  const { sessionId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const notify = useNotice()
  const queryClient = useQueryClient()
  const { context, refresh } = useExperiment()
  const { user } = useAuth()
  useExperimentTelemetry('DISCUSSION_TUTOR', Boolean(context?.experiment_mode && context.assigned))
  const [sessionOverride, setSessionOverride] = useState<DiscussionSession | null>(null)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [finishing, setFinishing] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const query = useQuery({ queryKey: ['discussion', sessionId], queryFn: ({ signal }) => services.discussion(sessionId, signal), enabled: Boolean(sessionId), retry: 1 })
  const session = sessionOverride ?? query.data ?? null
  const setSession = setSessionOverride
  const displayMessages = useMemo(() => session ? discussionMessagesForDisplay(session) : [], [session])
  useEffect(() => {
    const messageList = scrollRef.current
    if (!messageList) return
    if (typeof messageList.scrollTo === 'function') messageList.scrollTo({ top: messageList.scrollHeight, behavior: 'smooth' })
    else messageList.scrollTop = messageList.scrollHeight
  }, [displayMessages.length])
  useEffect(() => {
    if (!session?.is_complete || !context?.experiment_mode || !context.assigned) return
    let cancelled = false
    void (async () => {
      try {
        await services.completeTutor(sessionId)
        const result = await refresh() as { data?: { next_route?: string } } | undefined
        if (!cancelled) navigate(result?.data?.next_route || '/experiment/waiting', { replace: true })
      } catch (error) {
        if (!cancelled) notify(error instanceof Error ? error.message : '下一任务暂时无法打开', 'error')
      }
    })()
    return () => { cancelled = true }
  }, [context?.assigned, context?.experiment_mode, navigate, notify, refresh, session?.is_complete])
  const exportBehavior = useBehaviorTracker({
    enabled: Boolean(session?.personalized_mode),
    userId: user?.id || session?.user_id || 'default',
    sessionId,
    currentMode: 'discussion_mode',
    currentStep: session?.current_step || '',
  })

  const persist = async (next: DiscussionSession) => {
    try { await services.updateDiscussion({ ...next }) }
    catch { notify('步骤状态暂未同步，稍后发送消息时会再次同步', 'info') }
  }
  const onTreeChange = (id: string | number, action: 'toggle' | 'delete') => {
    if (!session) return
    const next = { ...session, discussion_tree: changeTree(session.discussion_tree || [], id, action) }
    setSession(next); queryClient.setQueryData(['discussion', sessionId], next); void persist(next)
  }
  const send = async (selectedText?: string) => {
    const text = (selectedText ?? input).trim()
    const submissionType = selectedText === undefined ? 'free_text' : 'option'
    if (!session || !text || sending || session.is_complete) return
    const isAdvanceRetry = text === '重试进入下一步'
    const optimistic = {
      ...session,
      current_options: [],
      messages_history: isAdvanceRetry
        ? session.messages_history
        : [...(session.messages_history || []), { content: text, type: 'user', time: new Date().toISOString() }],
    }
    setSession(optimistic); setInput(''); setSending(true)
    try {
      const result = await services.discussionFeedback(session.session_id, text, submissionType, exportBehavior(text), activeDiscussionStepId(session.discussion_tree || []))
      const next: DiscussionSession = {
        ...optimistic,
        discussion_tree: result.discussion_tree || optimistic.discussion_tree,
        messages_history: result.messages_history || optimistic.messages_history,
        current_options: result.current_options || [],
        pending_advance_feedback: result.pending_advance_feedback || '',
        pending_advance_reason: result.pending_advance_reason || '',
        is_complete: result.status === 'completed' || optimistic.is_complete,
        summary_text: result.summary || optimistic.summary_text,
      }
      setSession(next); queryClient.setQueryData(['discussion', sessionId], next); await persist(next)
    } catch (error) { setSession(session); if (submissionType === 'free_text') setInput(text); notify(error instanceof Error ? error.message : '消息发送失败', 'error') }
    finally { setSending(false) }
  }
  const keydown = (event: KeyboardEvent<HTMLTextAreaElement>) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send() } }
  const completeTutoring = async () => {
    if (finishing) return
    setFinishing(true)
    try {
      if (context?.experiment_mode && context.assigned) {
        await services.completeTutor(sessionId)
        const result = await refresh() as { data?: { next_route?: string }; error?: unknown } | undefined
        if (result?.error) throw result.error
        releaseTutorCompletion(location.pathname)
        navigate(result?.data?.next_route || '/experiment/waiting', { replace: true })
      } else {
        releaseTutorCompletion(location.pathname)
        navigate('/ai-tutor', { replace: true })
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '下一任务暂时无法打开', 'error')
      setFinishing(false)
    }
  }

  if (query.isError) return <div className="empty-page"><h1>无法打开会话</h1><p>{query.error.message}</p><button className="button button--soft" onClick={() => query.refetch()}>重试</button></div>
  if (query.isLoading || !session) return <div className="loading-page"><LoaderCircle className="spin" /><p>正在恢复研讨会话…</p></div>
  if (session.is_complete && context?.experiment_mode && context.assigned) return <div className="loading-page"><LoaderCircle className="spin" /><p>本轮学习已完成，正在进入小后测…</p></div>
  return <div className="session-page discussion-page">
    <div className="session-toolbar"><div><span className="eyebrow">启发研讨</span><h1>把思路聊清楚</h1></div>{session.personalized_mode && <span className="status-pill">个性化引导中</span>}</div>
    <div className="discussion-layout">
      <div className="discussion-left">
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
        <section className="card tree-card"><div className="section-heading"><h2>思考路径</h2><span className="muted">点击展开步骤</span></div>
          {session.discussion_tree?.length ? <ul className="discussion-tree">{session.discussion_tree.map(node => <TreeNode key={node.id} node={node} depth={0} onChange={onTreeChange} />)}</ul> : <p className="muted">对话开始后，这里会形成思考路径。</p>}
        </section>
      </div>
      <section className="card chat-card">
        <div className="chat-card__heading"><div><span className="eyebrow">对话</span><h2>和导师一起推演</h2></div></div>
        <div className="messages" ref={scrollRef}>{displayMessages.map((message, index) => <article className={`message message--${message.type === 'user' ? 'user' : 'assistant'}${session.is_complete && index === displayMessages.length - 1 ? ' message--summary' : ''}`} key={`${message.time}-${index}`}>
          <MathContent content={message.content} /><time>{message.time ? new Date(message.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : ''}</time>
        </article>)}
          {!session.is_complete && Boolean(session.current_options?.length) && <div className="discussion-choices" aria-label="快捷作答">
            {session.current_options!.includes('重试进入下一步') ? <>
              <button
                type="button"
                className="button button--soft"
                data-telemetry-id="discussion-next-step-retry"
                disabled={sending}
                onClick={() => void send('重试进入下一步')}
              >重试进入下一步</button>
              {session.current_options!.includes('结束研讨并进入小后测（剩余目标未完成）') && <button
                type="button"
                className="button button--soft"
                data-telemetry-id="discussion-next-step-end"
                disabled={sending}
                onClick={() => void send('结束研讨并进入小后测（剩余目标未完成）')}
              >结束研讨并进入小后测</button>}
            </> : session.current_options!.filter(option => !['不知道', '我不知道'].includes(option.trim())).map((option, index) => <button
              type="button"
              className="discussion-choice"
              disabled={sending}
              onClick={() => void send(option)}
              key={`${index}-${option}`}
            ><span>{String.fromCharCode(65 + index)}</span>{option}</button>)}
          </div>}
        </div>
        {session.is_complete ? <div className="discussion-completion-action"><span><CheckCircle2 size={18} />本次研讨已完成</span><button type="button" className="button button--primary" disabled={finishing} onClick={() => void completeTutoring()}>{finishing ? <><LoaderCircle className="spin" size={18} />正在进入下一任务</> : <>完成辅导 <ArrowRight size={18} /></>}</button></div> : <div className="chat-composer">
          <textarea value={input} onChange={event => setInput(event.target.value)} onKeyDown={keydown} rows={3} placeholder="提问、补充，或说明哪里没理解…" />
          <button data-track-id="discussion-send" className="button button--primary" disabled={!input.trim() || sending} onClick={() => void send()}>{sending ? <LoaderCircle className="spin" size={18} /> : <Send size={18} />}发送</button>
        </div>}
      </section>
    </div>
  </div>
}
