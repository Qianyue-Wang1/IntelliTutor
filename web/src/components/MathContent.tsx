import { useMemo } from 'react'
import DOMPurify from 'dompurify'
import katex from 'katex'
import { marked } from 'marked'

function renderMath(source: string): string {
  const tokens: string[] = []
  const escaped = source.replace(/\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|\$\$([\s\S]+?)\$\$|\$([^\n$]+?)\$/g, (_, bracket: string, parenthesized: string, block: string, inline: string) => {
    const latex = bracket ?? parenthesized ?? block ?? inline
    try {
      const html = katex.renderToString(latex, { displayMode: bracket !== undefined || block !== undefined, throwOnError: false, strict: 'ignore' })
      const index = tokens.push(html) - 1
      return `@@MATH_${index}@@`
    } catch {
      return latex
    }
  })
  let html = marked.parse(escaped, { async: false }) as string
  tokens.forEach((token, index) => { html = html.replace(`@@MATH_${index}@@`, token) })
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['aria-hidden'],
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed'],
  })
}

export default function MathContent({ content, className = '' }: { content: string; className?: string }) {
  const html = useMemo(() => renderMath(content || ''), [content])
  return <div className={`rich-content ${className}`} dangerouslySetInnerHTML={{ __html: html }} />
}

