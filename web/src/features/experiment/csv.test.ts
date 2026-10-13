import { describe, expect, it } from 'vitest'
import { parseCsv } from './csv'

describe('parseCsv', () => {
  it('preserves an empty login_account between student_id and group_code', () => {
    expect(parseCsv(
      'student_id,login_account,group_code\ne2e-direct-001,,DIRECT_LLM\n',
      ['student_id', 'login_account', 'group_code'],
    )).toEqual([{ student_id: 'e2e-direct-001', login_account: '', group_code: 'DIRECT_LLM' }])
  })

  it('supports BOM, CRLF, quoted commas, and escaped quotes', () => {
    expect(parseCsv(
      '\uFEFFstudent_id,login_account,class_code\r\n"student,01","account""01",pilot\r\n',
      ['student_id', 'login_account', 'class_code'],
    )).toEqual([{ student_id: 'student,01', login_account: 'account"01', class_code: 'pilot' }])
  })

  it('reports missing headers before sending the request', () => {
    expect(() => parseCsv('student_id,group\ns01,DIRECT_LLM\n', ['student_id', 'group_code']))
      .toThrow('CSV 缺少表头：group_code')
  })

  it('reports inconsistent row widths', () => {
    expect(() => parseCsv('student_id,login_account,group_code\ns01,DIRECT_LLM\n'))
      .toThrow('CSV 第 2 行应有 3 列，实际为 2 列')
  })
})
