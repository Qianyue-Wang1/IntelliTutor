import { render, screen } from '@testing-library/react'
import MathContent from './MathContent'
import { expect, test } from 'vitest'

test('renders formulas and removes malicious html', () => {
  const { container } = render(<MathContent content={'面积是 $a^2$ <img src=x onerror="alert(1)"><script>alert(1)</script>'} />)
  expect(screen.getByText(/面积是/)).toBeInTheDocument()
  expect(container.querySelector('.katex')).toBeInTheDocument()
  expect(container.querySelector('script')).not.toBeInTheDocument()
  expect(container.querySelector('img')?.hasAttribute('onerror')).toBe(false)
})

test('renders formulas wrapped in LaTex slash delimiters', () => {
  const { container } = render(<MathContent content={'样本空间 \\(\\mathcal{X} = \\{x_1, x_2\\}\\)\\n\\n\\[\\sum_i x_i\\]'} />)

  expect(container.querySelectorAll('.katex')).toHaveLength(2)
  expect(container.textContent).not.toContain('\\mathcal')
  expect(container.textContent).not.toContain('\\sum')
})
