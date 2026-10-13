import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { services } from '@/api/services'
import { NoticeProvider } from '@/providers/NoticeProvider'
import TutorStartPage from './TutorStartPage'

let imageEnabled = false
vi.mock('@/hooks/useCapabilities', () => ({
  useCapabilities: () => ({ capabilities: { image_input_enabled: imageEnabled }, isFetched: true }),
}))
vi.mock('@/api/services', () => ({ services: {
  imageInput: vi.fn(), imageToCode: vi.fn(), checkInput: vi.fn(),
  initExplanation: vi.fn(), initDiscussion: vi.fn(),
} }))

function renderPage() {
  return render(<MemoryRouter><NoticeProvider><TutorStartPage /></NoticeProvider></MemoryRouter>)
}

test('does not render image controls when disabled and blocks image paste', async () => {
  imageEnabled = false
  renderPage()
  expect(screen.queryByRole('button', { name: '上传图片' })).not.toBeInTheDocument()
  const file = new File(['image'], 'question.png', { type: 'image/png' })
  fireEvent.paste(screen.getByRole('textbox'), { clipboardData: { items: [{ type: 'image/png', getAsFile: () => file }] } })
  expect(await screen.findByText('管理员未启用图片提问')).toBeInTheDocument()
})

test('renders upload when enabled and clears a draft after runtime disable', async () => {
  imageEnabled = true
  const view = renderPage()
  expect(screen.getByRole('button', { name: '上传图片' })).toBeInTheDocument()
  const input = view.container.querySelector('input[type="file"]') as HTMLInputElement
  fireEvent.change(input, { target: { files: [new File(['image'], 'question.png', { type: 'image/png' })] } })
  await screen.findByText('question.png')
  imageEnabled = false
  view.rerender(<MemoryRouter><NoticeProvider><TutorStartPage /></NoticeProvider></MemoryRouter>)
  await waitFor(() => expect(screen.queryByText('question.png')).not.toBeInTheDocument())
  expect(screen.getByText('图片提问已关闭，未发送的图片草稿已清除')).toBeInTheDocument()
})

test('accepts an image-only question and validates recognized text before init', async () => {
  imageEnabled = true
  vi.mocked(services.imageInput).mockResolvedValueOnce({ extracted_text: '求三角形 ABC 的面积', structured_description: 'A、B、C 为三个顶点' })
  vi.mocked(services.imageToCode).mockResolvedValueOnce({ commands: ['A=(0,0)'] })
  vi.mocked(services.checkInput).mockResolvedValueOnce({ question: '求三角形 ABC 的面积', user_input: '' })
  vi.mocked(services.initExplanation).mockResolvedValueOnce({ session_id: 'session-1' } as never)
  const view = renderPage()
  const input = view.container.querySelector('input[type="file"]') as HTMLInputElement
  fireEvent.change(input, { target: { files: [new File(['image'], 'geometry.png', { type: 'image/png' })] } })
  await screen.findByText('geometry.png')
  fireEvent.click(screen.getByRole('button', { name: /开始学习/ }))

  await waitFor(() => expect(services.checkInput).toHaveBeenCalledWith(
    expect.stringContaining('求三角形 ABC 的面积'), expect.any(AbortSignal),
  ))
  expect(services.initExplanation).toHaveBeenCalledWith(expect.objectContaining({
    processedQuestion: '求三角形 ABC 的面积', images: [expect.objectContaining({ name: 'geometry.png' })], imageCode: ['A=(0,0)'],
  }), expect.any(AbortSignal))
})

test('starts ordinary tutoring in basic mode by default', async () => {
  imageEnabled = false
  vi.mocked(services.checkInput).mockResolvedValueOnce({ question: '什么是 PCA', user_input: '' })
  vi.mocked(services.initExplanation).mockResolvedValueOnce({ session_id: 'session-basic' } as never)
  renderPage()

  const personalized = screen.getByRole('checkbox', { name: '个性化引导' })
  expect(personalized).not.toBeChecked()
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '什么是 PCA' } })
  fireEvent.click(screen.getByRole('button', { name: /开始学习/ }))

  await waitFor(() => expect(services.initExplanation).toHaveBeenCalledWith(
    expect.objectContaining({ personalizedMode: false }),
    expect.any(AbortSignal),
  ))
})

test('starts ordinary discussion tutoring in basic mode by default', async () => {
  imageEnabled = false
  vi.mocked(services.checkInput).mockResolvedValueOnce({ question: '什么是 PCA', user_input: '' })
  vi.mocked(services.initDiscussion).mockResolvedValueOnce({ session_id: 'discussion-basic' } as never)
  renderPage()

  fireEvent.click(screen.getByRole('button', { name: /启发研讨/ }))
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '什么是 PCA' } })
  fireEvent.click(screen.getByRole('button', { name: /开始学习/ }))

  await waitFor(() => expect(services.initDiscussion).toHaveBeenCalledWith(
    expect.objectContaining({ personalizedMode: false }),
    expect.any(AbortSignal),
  ))
})
