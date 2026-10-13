import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from '@/providers/AuthProvider'

export default function ProtectedRoute() {
  const auth = useAuth()
  const location = useLocation()
  if (!auth.isAuthenticated) {
    return <Navigate to="/" replace state={{ from: `${location.pathname}${location.search}` }} />
  }
  return <Outlet />
}

