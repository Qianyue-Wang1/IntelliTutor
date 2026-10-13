import { beforeEach, expect, test, vi } from 'vitest'
import { ExperimentTelemetryQueue } from './telemetry'

const event = (id: string) => ({
  client_event_id: id,
  event_type: 'PAGE_ENTER',
  client_timestamp: '2026-10-11T09:00:00.000Z',
  data: { page: 'ASSESSMENT' },
})

beforeEach(() => localStorage.clear())

test('persists an event before sending and removes it only after acknowledgement', async () => {
  const send = vi.fn().mockResolvedValue(undefined)
  const queue = new ExperimentTelemetryQueue('participant-a', send)

  queue.enqueue(event('event-1'))
  expect(JSON.parse(localStorage.getItem('intellitutor:experiment-events:participant-a') || '[]')).toHaveLength(1)

  await queue.flush()
  expect(send).toHaveBeenCalledWith([event('event-1')])
  expect(localStorage.getItem('intellitutor:experiment-events:participant-a')).toBeNull()
})

test('keeps failed uploads for a later retry', async () => {
  const send = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(undefined)
  const queue = new ExperimentTelemetryQueue('participant-a', send)
  queue.enqueue(event('event-1'))

  await queue.flush()
  expect(queue.size()).toBe(1)
  await queue.flush()
  expect(queue.size()).toBe(0)
})

test('never drains another participant queue', async () => {
  const send = vi.fn().mockResolvedValue(undefined)
  const first = new ExperimentTelemetryQueue('participant-a', send)
  const second = new ExperimentTelemetryQueue('participant-b', send)
  first.enqueue(event('event-a'))
  second.enqueue(event('event-b'))

  await second.flush()

  expect(send).toHaveBeenCalledWith([event('event-b')])
  expect(first.size()).toBe(1)
})

test('exposes only the current participant events for unload delivery', () => {
  const send = vi.fn().mockResolvedValue(undefined)
  const first = new ExperimentTelemetryQueue('participant-a', send)
  const second = new ExperimentTelemetryQueue('participant-b', send)
  first.enqueue(event('event-a'))
  second.enqueue(event('event-b'))

  expect(second.pending()).toEqual([event('event-b')])
})
