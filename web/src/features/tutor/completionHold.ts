const COMPLETION_HOLD_PREFIX = 'intellitutor:tutor-completion-hold:'

function storageKey(pathname: string): string {
  return `${COMPLETION_HOLD_PREFIX}${pathname}`
}

export function holdTutorCompletion(pathname: string): void {
  if (typeof window !== 'undefined') window.sessionStorage.setItem(storageKey(pathname), '1')
}

export function releaseTutorCompletion(pathname: string): void {
  if (typeof window !== 'undefined') window.sessionStorage.removeItem(storageKey(pathname))
}

export function isTutorCompletionHeld(pathname: string): boolean {
  return typeof window !== 'undefined' && window.sessionStorage.getItem(storageKey(pathname)) === '1'
}
