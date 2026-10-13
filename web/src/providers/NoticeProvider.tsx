import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { CheckCircle2, CircleAlert, X } from 'lucide-react'

type Tone = 'success' | 'error' | 'info'
interface Notice { id: number; message: string; tone: Tone }
const NoticeContext = createContext<(message: string, tone?: Tone) => void>(() => undefined)

export function NoticeProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Notice[]>([])
  const notify = useCallback((message: string, tone: Tone = 'info') => {
    const id = Date.now() + Math.random()
    setItems(old => [...old, { id, message, tone }])
    window.setTimeout(() => setItems(old => old.filter(item => item.id !== id)), 4200)
  }, [])
  const value = useMemo(() => notify, [notify])
  return <NoticeContext.Provider value={value}>
    {children}
    <div className="notice-stack" aria-live="polite">
      {items.map(item => <div className={`notice notice--${item.tone}`} key={item.id}>
        {item.tone === 'success' ? <CheckCircle2 size={18} /> : <CircleAlert size={18} />}
        <span>{item.message}</span>
        <button aria-label="关闭提示" onClick={() => setItems(old => old.filter(next => next.id !== item.id))}><X size={16} /></button>
      </div>)}
    </div>
  </NoticeContext.Provider>
}

export const useNotice = () => useContext(NoticeContext)

