import { useEffect, useRef } from 'react'
import type { BehaviorData, BehaviorEvent } from '@/api/types'

interface BehaviorTrackerOptions {
  enabled: boolean
  userId: string
  sessionId: string
  currentMode: 'explanation_mode' | 'discussion_mode'
  currentStep: string
}

export function useBehaviorTracker({ enabled, userId, sessionId, currentMode, currentStep }: BehaviorTrackerOptions) {
  const events = useRef<BehaviorEvent[]>([])
  const startedAt = useRef(0)
  const blurredAt = useRef<number | null>(null)

  useEffect(() => {
    if (!enabled) { events.current = []; return }
    const record = (event_type: string, payload: Record<string, unknown> = {}) => {
      events.current.push({ event_type, timestamp: Date.now(), payload })
      if (events.current.length > 250) events.current.shift()
    }
    const click = (event: MouseEvent) => record('click', { target: (event.target as HTMLElement)?.dataset?.trackId || (event.target as HTMLElement)?.tagName })
    const keydown = (event: KeyboardEvent) => record('keydown', { key: event.key, ctrl: event.ctrlKey, shift: event.shiftKey })
    const paste = (event: ClipboardEvent) => record('clipboard_action', { action: 'paste', pasted_length: event.clipboardData?.getData('text').length || 0 })
    const blur = () => {
      blurredAt.current = Date.now()
      record('window_blur', { reason: 'tab_switch_or_minimize' })
    }
    const focus = () => {
      record('window_focus', { time_away_ms: blurredAt.current === null ? 0 : Date.now() - blurredAt.current })
      blurredAt.current = null
    }
    document.addEventListener('click', click, true)
    document.addEventListener('keydown', keydown, true)
    document.addEventListener('paste', paste, true)
    window.addEventListener('blur', blur)
    window.addEventListener('focus', focus)
    startedAt.current = Date.now()
    return () => {
      document.removeEventListener('click', click, true)
      document.removeEventListener('keydown', keydown, true)
      document.removeEventListener('paste', paste, true)
      window.removeEventListener('blur', blur)
      window.removeEventListener('focus', focus)
      events.current = []
      blurredAt.current = null
    }
  }, [enabled])

  return (answer: string): BehaviorData | undefined => {
    if (!enabled) return undefined
    events.current.push({ event_type: 'step_submit', timestamp: Date.now(), payload: { submitted_content: answer, total_time_spent_ms: Date.now() - startedAt.current } })
    const result: BehaviorData = {
      session_meta: { user_id: userId, session_id: sessionId, current_mode: currentMode, current_step_id: currentStep },
      event_stream: [...events.current],
    }
    events.current = []
    startedAt.current = Date.now()
    return result
  }
}
