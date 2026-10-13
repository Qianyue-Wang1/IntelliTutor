import { Navigate, useParams } from 'react-router'

export default function LegacyRedirect({ type }: { type: 'explanation' | 'discussion' }) {
  const { id } = useParams()
  return <Navigate replace to={`/ai-tutor/${type}/${encodeURIComponent(id || '')}`} />
}

