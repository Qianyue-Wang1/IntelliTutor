import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import { holdTutorCompletion, releaseTutorCompletion } from '@/features/tutor/completionHold'

const mocks = vi.hoisted(() => ({
  context: {
    experiment_mode: true,
    assigned: true,
    next_route: '/experiment/waiting',
  },
}))

vi.mock('@/providers/ExperimentProvider', () => ({ useExperiment: () => ({ context: mocks.context, loading: false }) }))

import ExperimentRouteGuard from './ExperimentRouteGuard'

const tutorPath = '/ai-tutor/discussion/session-1'
const recordsPath = '/experiment/records'

beforeEach(() => sessionStorage.clear())

function renderGuard(initialPath = tutorPath) {
  return render(<MemoryRouter initialEntries={[initialPath]}><Routes>
    <Route element={<ExperimentRouteGuard />}>
      <Route path="/ai-tutor/discussion/:sessionId" element={<div>总结确认页</div>} />
      <Route path="/experiment/waiting" element={<div>等待页</div>} />
      <Route path="/experiment/records" element={<div>本机实验记录</div>} />
    </Route>
  </Routes></MemoryRouter>)
}

test('keeps the exact completed tutor route open while acknowledgement is pending', () => {
  holdTutorCompletion(tutorPath)
  renderGuard()

  expect(screen.getByText('总结确认页')).toBeInTheDocument()
  releaseTutorCompletion(tutorPath)
})

test('redirects to the authoritative experiment route without a completion hold', async () => {
  renderGuard()

  expect(await screen.findByText('等待页')).toBeInTheDocument()
})

test('keeps completed participants on the records page for data export', () => {
  mocks.context.next_route = '/experiment/credential'
  renderGuard(recordsPath)

  expect(screen.getByText('本机实验记录')).toBeInTheDocument()
})
