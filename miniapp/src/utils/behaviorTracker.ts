import type { BehaviorData, BehaviorEvent } from '@/api/types'

export class MiniBehaviorTracker {
  private readonly events: BehaviorEvent[] = []
  private readonly startedAt = Date.now()

  record(eventType: string, payload: Record<string, string | number | boolean> = {}): void {
    this.events.push({ event_type: eventType, timestamp: Date.now(), payload })
  }

  export(userId: string, sessionId: string, stepId: string, feedbackLength: number): BehaviorData {
    this.record('feedback_submit', {
      feedback_length: feedbackLength,
      page_duration_ms: Date.now() - this.startedAt
    })
    return {
      session_meta: {
        user_id: userId,
        session_id: sessionId,
        current_mode: 'discussion',
        current_step_id: stepId
      },
      event_stream: [...this.events]
    }
  }
}

