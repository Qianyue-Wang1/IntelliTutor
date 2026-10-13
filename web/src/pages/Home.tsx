import { ArrowRight } from 'lucide-react'
import { Link, Navigate } from 'react-router'
import { useAuth } from '@/providers/AuthProvider'
import { useExperiment } from '@/providers/ExperimentProvider'
import { useCapabilities } from '@/hooks/useCapabilities'

export default function Home() {
  const { capabilities } = useCapabilities()
  const auth = useAuth()
  const { context } = useExperiment()
  if (capabilities.experiment_mode_enabled && auth.isAuthenticated) {
    return <Navigate to={auth.user?.roles?.includes('ADMIN') ? '/admin/experiment' : context?.next_route || '/experiment'} replace />
  }
  return <div className="home-page">
    <section className="hero">
      <div className="hero__copy">
        <span className="eyebrow">你的专属 AI 导师</span>
        <h1>不只给答案，<br />更带你找到解题方法。</h1>
        <p>选择逐步讲解或启发研讨，让每一次提问都变成学懂知识的开始。</p>
        <Link className="button button--primary button--large" to="/ai-tutor">开始提问 <ArrowRight size={19} /></Link>
      </div>
      <div className="hero__visual" aria-hidden="true">
        <img className="hero__lemon" src="/favicon.svg" alt="" />
        <div className="hero__bubble">先说说你卡在哪一步？</div>
        <div className="hero__formula">a² + b² = c²</div>
      </div>
    </section>
  </div>
}
