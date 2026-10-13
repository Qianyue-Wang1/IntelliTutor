import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import type { BehaviorData } from '@/api/types'
import { useBehaviorTracker } from './behavior'

function TrackerProbe({ enabled, onExport }: { enabled: boolean; onExport: (data?: BehaviorData) => void }) {
  const exportBehavior = useBehaviorTracker({
    enabled,
    userId: 'student-1',
    sessionId: 'session-1',
    currentMode: 'explanation_mode',
    currentStep: '2',
  })
  return <button onClick={() => onExport(exportBehavior('B'))}>export</button>
}

afterEach(() => {
  vi.useRealTimers()
})

test('exports the Python behavior protocol for explanation mode', () => {
  vi.useFakeTimers()
  vi.setSystemTime(1_000)
  const onExport = vi.fn()
  render(<TrackerProbe enabled onExport={onExport} />)

  act(() => window.dispatchEvent(new Event('blur')))
  vi.setSystemTime(3_500)
  act(() => window.dispatchEvent(new Event('focus')))
  fireEvent.click(screen.getByRole('button', { name: 'export' }))

  const data = onExport.mock.calls[0]?.[0] as BehaviorData
  expect(data.session_meta).toEqual({
    user_id: 'student-1',
    session_id: 'session-1',
    current_mode: 'explanation_mode',
    current_step_id: '2',
  })
  expect(data.event_stream).toEqual(expect.arrayContaining([
    expect.objectContaining({ event_type: 'window_blur' }),
    expect.objectContaining({ event_type: 'window_focus', payload: { time_away_ms: 2_500 } }),
    expect.objectContaining({
      event_type: 'step_submit',
      payload: expect.objectContaining({ submitted_content: 'B', total_time_spent_ms: 2_500 }),
    }),
  ]))
})

test('returns no behavior data when tracking is disabled', () => {
  const onExport = vi.fn()
  render(<TrackerProbe enabled={false} onExport={onExport} />)

  fireEvent.click(screen.getByRole('button', { name: 'export' }))

  expect(onExport).toHaveBeenCalledWith(undefined)
})
