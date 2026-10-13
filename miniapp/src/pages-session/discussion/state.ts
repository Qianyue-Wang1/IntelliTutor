import type { DiscussionFeedbackData, DiscussionSessionData, DiscussionStep } from '@/api/types'

export function normalizeDiscussionSession(data: DiscussionSessionData): DiscussionSessionData {
  return {
    ...data,
    images: data.images || [],
    imageCode: data.imageCode || [],
    discussion_tree: normalizeDiscussionTree(data.discussion_tree || [], data.current_step),
    messages_history: data.messages_history || [],
    knowledge_points: data.knowledge_points || []
  }
}

export function mergeDiscussionFeedback(
  current: DiscussionSessionData,
  result: DiscussionFeedbackData
): DiscussionSessionData {
  return normalizeDiscussionSession({
    ...current,
    current_step: result.current_step || result.step || current.current_step,
    discussion_tree: result.discussion_tree?.length ? result.discussion_tree : current.discussion_tree,
    messages_history: result.messages_history?.length ? result.messages_history : current.messages_history,
    is_complete: result.status === 'completed' ? true : current.is_complete,
    summary_text: result.status === 'completed' ? (result.summary || '') : current.summary_text
  })
}

export function normalizeDiscussionTree(steps: DiscussionStep[], currentStep = ''): DiscussionStep[] {
  const currentStepNumber = parseCurrentStepNumber(currentStep)
  const shouldSyncActiveStep = Boolean(
    currentStepNumber && hasDiscussionStepNumber(steps, currentStepNumber)
  )

  return normalizeDiscussionSteps(steps, '', currentStepNumber, shouldSyncActiveStep)
}

export function parseCurrentStepNumber(currentStep: string): string {
  const match = String(currentStep || '').match(/^\s*步骤\s*(\d+(?:\.\d+)*)\s*(?:[:：]|$)/)
  return match?.[1] || ''
}

function normalizeDiscussionSteps(
  steps: DiscussionStep[],
  parentNumber: string,
  currentStepNumber: string,
  shouldSyncActiveStep: boolean
): DiscussionStep[] {
  return steps.map((step, index) => {
    const stepNumber = getStepNumber(step, index, parentNumber)
    const subSteps = normalizeDiscussionSteps(
      Array.isArray(step.subSteps) ? step.subSteps : [],
      stepNumber,
      currentStepNumber,
      shouldSyncActiveStep
    )
    const hasActiveChild = subSteps.some(hasActiveDiscussionStep)
    const isActive = shouldSyncActiveStep
      ? stepNumber === currentStepNumber
      : Boolean(step.isActive)

    return {
      ...step,
      id: step.id ?? index + 1,
      title: step.title || '',
      description: step.description || '',
      content: step.content || '',
      isActive,
      isExpanded: Boolean(step.isExpanded || isActive || hasActiveChild),
      isMain: step.isMain !== false,
      subSteps
    }
  })
}

function hasDiscussionStepNumber(
  steps: DiscussionStep[],
  targetNumber: string,
  parentNumber = ''
): boolean {
  return steps.some((step, index) => {
    const stepNumber = getStepNumber(step, index, parentNumber)
    if (stepNumber === targetNumber) return true
    const subSteps = Array.isArray(step.subSteps) ? step.subSteps : []
    return hasDiscussionStepNumber(subSteps, targetNumber, stepNumber)
  })
}

function getStepNumber(step: DiscussionStep, index: number, parentNumber: string): string {
  if (parentNumber) return `${parentNumber}.${index + 1}`
  return normalizeStepId(step.id) || String(index + 1)
}

function normalizeStepId(id: DiscussionStep['id']): string {
  const value = String(id ?? '').trim().replace(/-/g, '.')
  return /^\d+(?:\.\d+)*$/.test(value) ? value : ''
}

function hasActiveDiscussionStep(step: DiscussionStep): boolean {
  return Boolean(step.isActive || (step.subSteps || []).some(hasActiveDiscussionStep))
}
