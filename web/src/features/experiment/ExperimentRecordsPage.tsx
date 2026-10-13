import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { services } from '@/api/services'
import { useAuth } from '@/providers/AuthProvider'

type RecordItem = { assignment_id: string; name: string; phone_number: string; selected_mode: string | null; state: string; started_at: string | null; completed_at: string | null; exportable: boolean }
const displayTime = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '—'
const displayDuration = (record: RecordItem) => {
  if (!record.started_at || !record.completed_at) return null
  const minutes = Math.max(0, Math.round((new Date(record.completed_at).getTime() - new Date(record.started_at).getTime()) / 60_000))
  return `${minutes} 分钟`
}
export default function ExperimentRecordsPage() {
  const navigate = useNavigate(); const { login } = useAuth(); const [records, setRecords] = useState<RecordItem[]>([]); const [error, setError] = useState('')
  useEffect(() => { void services.localExperimentRecords().then(setRecords).catch(reason => setError(reason instanceof Error ? reason.message : '无法读取记录')) }, [])
  const download = async (record: RecordItem) => { try { const blob = await services.exportLocalExperimentRecord(record.assignment_id); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = `experiment-record-${record.assignment_id}.xlsx`; link.click(); URL.revokeObjectURL(link.href) } catch (reason) { setError(reason instanceof Error ? reason.message : '导出失败') } }
  const restart = async () => { try { login(await services.restartLocalExperiment()); navigate('/experiment', { replace: true }) } catch (reason) { setError(reason instanceof Error ? reason.message : '无法开始新实验') } }
  return <main className="experiment-narrow"><section className="card experiment-card"><h1>本机实验记录</h1><p>只有完整完成的记录可以导出 Excel；中止或进行中的记录会保留，但不可导出。</p>{error && <p className="form-error">{error}</p>}{records.map(record => <article key={record.assignment_id} className="experiment-record"><strong>{record.name} · {record.selected_mode || '未选择模式'}</strong><span>开始：{displayTime(record.started_at)}{record.completed_at && ` · 完成：${displayTime(record.completed_at)}${displayDuration(record) ? ` · 用时：${displayDuration(record)}` : ''}`}</span><button className="button button--soft" disabled={!record.exportable} onClick={() => void download(record)}>{record.exportable ? '下载数据 Excel' : '未完成，不能导出'}</button></article>)}<button className="button button--primary" onClick={() => void restart()}>从头开始一次新实验</button><button className="button button--soft" onClick={() => navigate('/experiment')}>返回当前实验</button></section></main>
}
