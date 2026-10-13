import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import AppShell from '@/components/AppShell'
import ProtectedRoute from '@/components/ProtectedRoute'
import ExperimentRouteGuard from '@/components/ExperimentRouteGuard'
import {
  AdminExperimentPage, AssessmentPage, DirectTutorPage, ExperimentEntry,
  CompletionCredentialPage, ExperimentPasswordPage, FinishedPage, LecturePage, ModeSelectionPage, NoAiLearningPage, TutorLaunchPage, TutorModePage, WaitingPage,
} from '@/features/experiment/ExperimentPages'
import LocalExperimentStartPage from '@/features/experiment/LocalExperimentStartPage'
import ExperimentRecordsPage from '@/features/experiment/ExperimentRecordsPage'

const TutorStartPage = lazy(() => import('@/features/tutor/TutorStartPage'))
const ExplanationPage = lazy(() => import('@/features/tutor/ExplanationPage'))
const DiscussionPage = lazy(() => import('@/features/tutor/DiscussionPage'))
const AuthPage = lazy(() => import('@/pages/AuthPage'))
const Home = lazy(() => import('@/pages/Home'))
const LegacyRedirect = lazy(() => import('@/pages/LegacyRedirect'))
const NotFound = lazy(() => import('@/pages/NotFound'))
const ProfilePage = lazy(() => import('@/pages/ProfilePage'))

export default function App() {
  return <Suspense fallback={<div className="loading-page"><p>正在加载页面…</p></div>}><Routes>
    <Route element={<AppShell />}>
      <Route index element={<LocalExperimentStartPage />} />
      <Route path="home" element={<Home />} />
      <Route path="login" element={<AuthPage kind="login" />} />
      <Route path="register" element={<AuthPage kind="register" />} />
      <Route element={<ProtectedRoute />}>
        <Route path="admin/experiment" element={<AdminExperimentPage />} />
        <Route element={<ExperimentRouteGuard />}>
          <Route path="experiment" element={<ExperimentEntry />} />
          <Route path="experiment/password" element={<ExperimentPasswordPage />} />
          <Route path="experiment/lecture" element={<LecturePage />} />
          <Route path="experiment/waiting" element={<WaitingPage />} />
          <Route path="experiment/assessment/:stage" element={<AssessmentPage />} />
          <Route path="experiment/mode" element={<ModeSelectionPage />} />
          <Route path="experiment/no-ai" element={<NoAiLearningPage />} />
          <Route path="experiment/credential" element={<CompletionCredentialPage />} />
          <Route path="experiment/records" element={<ExperimentRecordsPage />} />
          <Route path="experiment/tutor/mode" element={<TutorModePage />} />
          <Route path="experiment/tutor/start" element={<TutorLaunchPage />} />
          <Route path="experiment/tutor/direct/:sessionId" element={<DirectTutorPage />} />
          <Route path="experiment/finished" element={<FinishedPage />} />
          <Route path="ai-tutor" element={<TutorStartPage />} />
          <Route path="ai-tutor/explanation/:sessionId" element={<ExplanationPage />} />
          <Route path="ai-tutor/discussion/:sessionId" element={<DiscussionPage />} />
          <Route path="me" element={<ProfilePage />} />
        </Route>
      </Route>
      <Route path="new-question" element={<Navigate to="/ai-tutor" replace />} />
      <Route path="explanation/:id" element={<LegacyRedirect type="explanation" />} />
      <Route path="discussion/:id" element={<LegacyRedirect type="discussion" />} />
      <Route path="usercenter" element={<Navigate to="/me" replace />} />
      <Route path="*" element={<NotFound />} />
    </Route>
  </Routes></Suspense>
}
