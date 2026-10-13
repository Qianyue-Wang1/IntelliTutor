import { Navigate, Outlet, useLocation } from 'react-router'
import { useExperiment } from '@/providers/ExperimentProvider'
import { isTutorCompletionHeld } from '@/features/tutor/completionHold'

export default function ExperimentRouteGuard() {
  const { context, loading } = useExperiment()
  const location = useLocation()
  if (loading || !context) return <div className="loading-page"><p>正在核对实验进度…</p></div>
  if (!context.experiment_mode || !context.assigned) return <Outlet />
  const allowed = context.next_route === location.pathname || location.pathname === '/experiment/records' || isTutorCompletionHeld(location.pathname)
  if (!allowed) return <Navigate to={context.next_route} replace />
  return <Outlet />
}
