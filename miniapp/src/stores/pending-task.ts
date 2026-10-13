import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { PendingTask } from '@/api/types'
import { clearPendingTask, readPendingTask } from '@/utils/storage'

export const usePendingTaskStore = defineStore('pendingTask', () => {
  const current = ref<PendingTask | null>(null)

  function restore(): PendingTask | null {
    current.value = readPendingTask()
    return current.value
  }

  function clear(): void {
    clearPendingTask()
    current.value = null
  }

  return { current, restore, clear }
})

