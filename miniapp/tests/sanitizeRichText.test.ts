import { describe, expect, it } from 'vitest'
import { sanitizeRichTextSource } from '@/utils/sanitizeRichText'

describe('rich text sanitization', () => {
  it('escapes raw html and event handlers', () => {
    const result = sanitizeRichTextSource('<img src=x onerror="alert(1)"><script>alert(2)</script>')
    expect(result).not.toContain('<img')
    expect(result).not.toContain('<script>')
    expect(result).toContain('&lt;img')
  })

  it('removes markdown image sources and link targets', () => {
    const result = sanitizeRichTextSource('![追踪图](https://evil.test/pixel) [点击](javascript:alert(1))')
    expect(result).toBe('追踪图 点击')
    expect(result).not.toContain('https://')
    expect(result).not.toContain('javascript:')
  })

  it('neutralizes bare and reference-style external links', () => {
    const result = sanitizeRichTextSource('https://evil.test [资料][ref]\n[ref]: https://evil.test/doc')
    expect(result).not.toContain('https://')
    expect(result).toContain('https&#58;//evil.test')
    expect(result).toContain('资料')
    expect(result).not.toContain('[ref]:')
  })

  it('keeps ordinary formulas but removes dangerous latex commands', () => {
    const result = sanitizeRichTextSource('$x^2 + y^2$ $$\\href{javascript:alert(1)}{click} + z$$')
    expect(result).toContain('$x^2 + y^2$')
    expect(result).not.toContain('\\href')
  })

  it('keeps code fenced so the markdown parser can render it as code', () => {
    const result = sanitizeRichTextSource('```html\n<img onerror=alert(1)>\n```')
    expect(result).toContain('```html')
    expect(result).toContain('<img onerror=alert(1)>')
  })
})
