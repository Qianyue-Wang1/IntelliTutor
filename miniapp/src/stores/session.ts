import { ref } from 'vue'
import { defineStore } from 'pinia'
import type {
  DiscussionInitData,
  DiscussionSessionData,
  ExplanationInitData,
  ExplanationSessionData
} from '@/api/types'

export const useSessionStore = defineStore('session', () => {
  const explanation = ref<ExplanationInitData | ExplanationSessionData | null>(null)
  const discussion = ref<DiscussionInitData | DiscussionSessionData | null>(null)

  function setExplanation(data: ExplanationInitData | ExplanationSessionData): void {
    explanation.value = data
  }

  function setDiscussion(data: DiscussionInitData | DiscussionSessionData): void {
    discussion.value = data
  }

  function clear(): void {
    explanation.value = null
    discussion.value = null
  }

  return { explanation, discussion, setExplanation, setDiscussion, clear }
})

