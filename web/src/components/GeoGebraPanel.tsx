import { useEffect, useId, useState } from 'react'

declare global {
  interface Window { GGBApplet?: new (options: Record<string, unknown>, useBrowserForJS?: boolean) => { inject: (id: string) => void } }
}

let scriptPromise: Promise<void> | null = null
function loadGeoGebra(): Promise<void> {
  if (window.GGBApplet) return Promise.resolve()
  if (scriptPromise) return scriptPromise
  scriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://www.geogebra.org/apps/deployggb.js'
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('GeoGebra 加载失败'))
    document.head.appendChild(script)
  })
  return scriptPromise
}

export default function GeoGebraPanel({ commands }: { commands?: string[] }) {
  const id = `ggb-${useId().replace(/:/g, '')}`
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    if (!commands?.length) return
    let active = true
    loadGeoGebra().then(() => {
      if (!active || !window.GGBApplet) return
      const applet = new window.GGBApplet({
        appName: 'geometry', width: 680, height: 420, showToolBar: false, showAlgebraInput: false,
        enableShiftDragZoom: true,
        appletOnLoad: (api: { evalCommand: (command: string) => void }) => commands.forEach(command => api.evalCommand(command)),
      }, true)
      applet.inject(id)
    }).catch(() => active && setFailed(true))
    return () => { active = false }
  }, [commands, id])
  if (!commands?.length) return null
  if (failed) return <p className="muted">图形组件暂时无法加载，原题图片仍可查看。</p>
  return <div className="geogebra-wrap"><div id={id} /></div>
}

