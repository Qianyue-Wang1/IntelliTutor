import { onUnmounted, ref } from 'vue'

export function useCountdown() {
  const remaining = ref(0)
  let timer: ReturnType<typeof setInterval> | null = null

  function stop(): void {
    if (timer) clearInterval(timer)
    timer = null
  }

  function start(seconds = 60): void {
    stop()
    remaining.value = seconds
    timer = setInterval(() => {
      remaining.value -= 1
      if (remaining.value <= 0) stop()
    }, 1000)
  }

  onUnmounted(stop)
  return { remaining, start, stop }
}

