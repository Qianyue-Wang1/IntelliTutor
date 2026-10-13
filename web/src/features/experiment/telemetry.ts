import { useEffect, useRef } from 'react'
import { services } from '@/api/services'
import { useAuth } from '@/providers/AuthProvider'

export type TelemetryEvent = {
  client_event_id: string
  event_type: string
  client_timestamp: string
  data: Record<string, unknown>
}

export class ExperimentTelemetryQueue {
  private flushing = false

  constructor(private readonly participantId: string, private readonly send: (events: TelemetryEvent[]) => Promise<unknown>) {}

  enqueue(event: TelemetryEvent) { this.write([...this.read(), event]) }
  size() { return this.read().length }
  pending() { return this.read().slice(0, 100) }

  async flush(): Promise<boolean> {
    if (this.flushing || this.size() === 0) return true
    this.flushing = true
    const batch = this.read().slice(0, 100)
    try {
      await this.send(batch)
      const acknowledged = new Set(batch.map(event => event.client_event_id))
      this.write(this.read().filter(event => !acknowledged.has(event.client_event_id)))
      return true
    } catch { return false }
    finally { this.flushing = false }
  }

  private key() { return `intellitutor:experiment-events:${this.participantId}` }
  private read(): TelemetryEvent[] {
    try {
      const parsed = JSON.parse(localStorage.getItem(this.key()) || '[]')
      return Array.isArray(parsed) ? parsed.filter(event => event && typeof event.client_event_id === 'string') : []
    } catch { return [] }
  }
  private write(events: TelemetryEvent[]) {
    if (events.length === 0) localStorage.removeItem(this.key())
    else localStorage.setItem(this.key(), JSON.stringify(events))
  }
}

export function useExperimentTelemetry(page: string, enabled = true) {
  const { user } = useAuth()
  const entered = useRef(0)
  const metrics = useRef({ clicks: 0, clickTargets: {} as Record<string, number>, maxScroll: 0, keys: 0, backspaces: 0, inputEvents: 0 })
  useEffect(() => {
    const participantId = user?.id
    if (!enabled || !participantId) return
    const queue = new ExperimentTelemetryQueue(participantId, services.experimentEvents)
    let retryTimer: number | undefined
    let retryDelay = 1_000
    let left = false
    const deliver = () => {
      void queue.flush().then(sent => {
        if (sent) retryDelay = 1_000
        else retryTimer = window.setTimeout(deliver, retryDelay = Math.min(retryDelay * 2, 30_000))
      })
    }
    const event = (eventType: string, data: Record<string, unknown> = {}): TelemetryEvent => ({
      client_event_id: crypto.randomUUID(), event_type: eventType,
      client_timestamp: new Date().toISOString(), data: { page, ...data },
    })
    const record = (eventType: string, data: Record<string, unknown> = {}) => { queue.enqueue(event(eventType, data)); deliver() }
    entered.current = Date.now()
    record('PAGE_ENTER')
    const blur = () => record(document.hidden ? 'PAGE_BLUR' : 'PAGE_FOCUS')
    const paste = () => record('PASTE')
    const click = (domEvent: MouseEvent) => {
      metrics.current.clicks += 1
      const element = domEvent.target instanceof Element ? domEvent.target.closest('button,a,input,textarea,select,label') : null
      const target = element?.tagName.toLowerCase() || 'content'
      metrics.current.clickTargets[target] = (metrics.current.clickTargets[target] || 0) + 1
      const controlId = element?.getAttribute('data-telemetry-id')
      if (controlId) record('CONTROL_CLICK', { control_id: controlId })
    }
    const scroll = () => {
      const scrollable = Math.max(1, document.documentElement.scrollHeight - window.innerHeight)
      metrics.current.maxScroll = Math.max(metrics.current.maxScroll, Math.min(100, Math.round(window.scrollY / scrollable * 100)))
    }
    const keydown = (domEvent: KeyboardEvent) => {
      if (!(domEvent.target instanceof HTMLInputElement || domEvent.target instanceof HTMLTextAreaElement)) return
      metrics.current.keys += 1
      if (domEvent.key === 'Backspace' || domEvent.key === 'Delete') metrics.current.backspaces += 1
    }
    const input = (domEvent: Event) => {
      if (domEvent.target instanceof HTMLInputElement || domEvent.target instanceof HTMLTextAreaElement) metrics.current.inputEvents += 1
    }
    const flush = () => {
      const current = metrics.current
      if (current.clicks + current.keys + current.inputEvents === 0 && current.maxScroll === 0) return
      metrics.current = { clicks: 0, clickTargets: {}, maxScroll: 0, keys: 0, backspaces: 0, inputEvents: 0 }
      record('CLICK', { count: current.clicks, target_counts: current.clickTargets })
      record('PAGE_SCROLL', { max_percent: current.maxScroll })
      record('TYPING_METRICS', { key_count: current.keys, correction_key_count: current.backspaces, input_event_count: current.inputEvents })
    }
    const deliverOnLeave = () => {
      flush()
      if (!left) {
        left = true
        queue.enqueue(event('PAGE_LEAVE', { duration_ms: Date.now() - entered.current }))
      }
      const pending = queue.pending()
      if (pending.length > 0) void services.experimentEvents(pending, true).catch(() => undefined)
    }
    document.addEventListener('visibilitychange', blur)
    document.addEventListener('paste', paste)
    document.addEventListener('click', click)
    document.addEventListener('keydown', keydown)
    document.addEventListener('input', input)
    window.addEventListener('scroll', scroll, { passive: true })
    window.addEventListener('pagehide', deliverOnLeave)
    const flushTimer = window.setInterval(flush, 30_000)
    return () => {
      window.clearInterval(flushTimer)
      if (retryTimer) window.clearTimeout(retryTimer)
      document.removeEventListener('visibilitychange', blur)
      document.removeEventListener('paste', paste)
      document.removeEventListener('click', click)
      document.removeEventListener('keydown', keydown)
      document.removeEventListener('input', input)
      window.removeEventListener('scroll', scroll)
      window.removeEventListener('pagehide', deliverOnLeave)
      deliverOnLeave()
    }
  }, [enabled, page, user?.id])
}
