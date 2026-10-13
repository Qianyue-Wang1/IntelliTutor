export function parseCsv(text: string, requiredHeaders: string[] = []): Array<Record<string, string>> {
  const source = text.replace(/^\uFEFF/, '')
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false

  const finishField = () => {
    row.push(field.trim())
    field = ''
  }
  const finishRow = () => {
    finishField()
    if (row.some(value => value !== '')) rows.push(row)
    row = []
  }

  for (let index = 0; index < source.length; index += 1) {
    const character = source[index]
    if (quoted) {
      if (character === '"') {
        if (source[index + 1] === '"') {
          field += '"'
          index += 1
        } else {
          quoted = false
        }
      } else {
        field += character
      }
      continue
    }

    if (character === '"' && field === '') quoted = true
    else if (character === ',') finishField()
    else if (character === '\n') finishRow()
    else if (character === '\r') {
      if (source[index + 1] === '\n') index += 1
      finishRow()
    } else field += character
  }

  if (quoted) throw new Error('CSV 中存在未闭合的引号')
  if (field !== '' || row.length > 0) finishRow()
  if (rows.length === 0) throw new Error('CSV 不能为空')

  const headers = rows[0]
  if (headers.some(header => !header)) throw new Error('CSV 表头不能为空')
  if (new Set(headers).size !== headers.length) throw new Error('CSV 表头不能重复')
  const missing = requiredHeaders.filter(header => !headers.includes(header))
  if (missing.length > 0) throw new Error(`CSV 缺少表头：${missing.join(', ')}`)
  if (rows.length === 1) throw new Error('CSV 没有数据行')

  return rows.slice(1).map((values, index) => {
    if (values.length !== headers.length) {
      throw new Error(`CSV 第 ${index + 2} 行应有 ${headers.length} 列，实际为 ${values.length} 列`)
    }
    return Object.fromEntries(values.map((value, column) => [headers[column], value]))
  })
}
