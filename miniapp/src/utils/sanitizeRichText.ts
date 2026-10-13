const DANGEROUS_LATEX_COMMANDS = /\\(?:href|url|includegraphics|htmlClass|htmlId|htmlStyle|htmlData)\s*(?:\[[^\]]*\])?\s*\{[^}]*\}/gi
const CONTROL_CHARACTERS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function sanitizeProtectedToken(token: string): string {
  if (token.startsWith('$') || token.startsWith('\\(') || token.startsWith('\\[')) {
    return token.replace(DANGEROUS_LATEX_COMMANDS, '')
  }
  return token
}

/**
 * mp-html 的输入预处理：保留代码和数学定界符，转义其他原始 HTML，
 * 并将 Markdown 图片、链接降级为纯文本，避免外部资源与危险协议。
 */
export function sanitizeRichTextSource(input: unknown): string {
  const source = String(input ?? '').replace(CONTROL_CHARACTERS, '')
  const tokens: string[] = []
  const placeholder = (token: string): string => {
    const index = tokens.push(sanitizeProtectedToken(token)) - 1
    return `INTELLITUTORPROTECTED${index}TOKEN`
  }

  const protectedSource = source
    .replace(/```[\s\S]*?```/g, placeholder)
    .replace(/`[^`\n]+`/g, placeholder)
    .replace(/\$\$[\s\S]*?\$\$/g, placeholder)
    .replace(/\\\[[\s\S]*?\\\]/g, placeholder)
    .replace(/\\\([\s\S]*?\\\)/g, placeholder)
    .replace(/\$[^$\n]+\$/g, placeholder)

  const withoutRemoteContent = protectedSource
    .replace(/!\[([^\]]*)\]\((?:[^()\n]|\([^()\n]*\))*\)/g, '$1')
    .replace(/\[([^\]]+)\]\((?:[^()\n]|\([^()\n]*\))*\)/g, '$1')
    .replace(/!\[([^\]]*)\]\[[^\]]*\]/g, '$1')
    .replace(/\[([^\]]+)\]\[[^\]]*\]/g, '$1')
    .replace(/^\s*\[[^\]]+\]:\s*\S+.*$/gm, '')

  let safe = escapeHtml(withoutRemoteContent)
  safe = safe
    .replace(/\b(https?|ftp|mailto|javascript|data):/gi, '$1&#58;')
    .replace(/\bwww\./gi, 'www&#46;')
  tokens.forEach((token, index) => {
    safe = safe.replace(`INTELLITUTORPROTECTED${index}TOKEN`, token)
  })
  return safe
}
