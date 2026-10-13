import { useEffect, useRef, useState, type ChangeEvent, type ClipboardEvent, type KeyboardEvent } from 'react'
import { BookOpenCheck, ImagePlus, LoaderCircle, MessageCircleQuestion, Trash2, Upload } from 'lucide-react'
import { useNavigate } from 'react-router'
import { services } from '@/api/services'
import type { ImageData } from '@/api/types'
import { useCapabilities } from '@/hooks/useCapabilities'
import { useNotice } from '@/providers/NoticeProvider'

type TutorMode = 'explanation' | 'discussion'
const MAX_IMAGE_SIZE = 5 * 1024 * 1024

function fileToImage(file: File): Promise<ImageData> {
  return new Promise((resolve, reject) => {
    if (!['image/jpeg', 'image/png'].includes(file.type)) return reject(new Error('仅支持 JPG 或 PNG 图片'))
    if (file.size > MAX_IMAGE_SIZE) return reject(new Error('图片不能超过 5MB'))
    const reader = new FileReader()
    reader.onload = () => resolve({ name: file.name || '粘贴的图片', url: String(reader.result) })
    reader.onerror = () => reject(new Error('图片读取失败'))
    reader.readAsDataURL(file)
  })
}

export default function TutorStartPage() {
  const navigate = useNavigate()
  const notify = useNotice()
  const { capabilities, isFetched } = useCapabilities()
  const imageEnabled = capabilities.image_input_enabled
  const [mode, setMode] = useState<TutorMode>('explanation')
  const [personalized, setPersonalized] = useState(false)
  const [text, setText] = useState('')
  const [image, setImage] = useState<ImageData | null>(null)
  const [preview, setPreview] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const previousCapability = useRef<boolean | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!isFetched) return
    if (previousCapability.current === true && !imageEnabled && image) {
      setImage(null)
      notify('图片提问已关闭，未发送的图片草稿已清除', 'info')
    }
    previousCapability.current = imageEnabled
  }, [imageEnabled, image, isFetched, notify])
  useEffect(() => () => abortRef.current?.abort(), [])

  const acceptFile = async (file?: File) => {
    if (!file) return
    if (!imageEnabled) return notify('管理员未启用图片提问', 'error')
    try { setImage(await fileToImage(file)) }
    catch (error) { notify(error instanceof Error ? error.message : '图片读取失败', 'error') }
  }
  const onPaste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const file = Array.from(event.clipboardData.items).find(item => item.type.startsWith('image/'))?.getAsFile()
    if (!file) return
    event.preventDefault()
    if (!imageEnabled) return notify('管理员未启用图片提问', 'error')
    void acceptFile(file)
  }
  const onFile = (event: ChangeEvent<HTMLInputElement>) => {
    void acceptFile(event.target.files?.[0])
    event.target.value = ''
  }
  const submit = async () => {
    if (submitting || (!text.trim() && !image)) return
    if (image && !imageEnabled) { setImage(null); return notify('管理员未启用图片提问', 'error') }
    const controller = new AbortController()
    abortRef.current = controller
    setSubmitting(true)
    try {
      let preparedText = text.trim()
      let imageCode: string[] = []
      if (image) {
        const [recognized, geometry] = await Promise.all([
          services.imageInput(image.url, preparedText, controller.signal),
          services.imageToCode(image.url, preparedText, controller.signal).catch(() => ({})),
        ])
        preparedText = [recognized.extracted_text, recognized.structured_description, preparedText].filter(Boolean).join('\n\n')
        imageCode = Object.values(geometry).flat().filter((item): item is string => typeof item === 'string' && Boolean(item.trim()))
      }
      const checked = await services.checkInput(preparedText, controller.signal)
      const processedQuestion = checked.question || preparedText
      const optionalImages = image ? { images: [image], ...(imageCode.length ? { imageCode } : {}) } : {}
      const session = mode === 'explanation'
        ? await services.initExplanation({ processedQuestion, userInput: checked.user_input || text.trim(), personalizedMode: personalized, ...optionalImages }, controller.signal)
        : await services.initDiscussion({ processedQuestion, personalizedMode: personalized, ...optionalImages }, controller.signal)
      navigate(`/ai-tutor/${mode}/${encodeURIComponent(session.session_id)}`)
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) notify(error instanceof Error ? error.message : '提问失败，请稍后再试', 'error')
    } finally {
      setSubmitting(false)
      abortRef.current = null
    }
  }
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void submit() }
  }

  return <div className="tutor-start">
    <header className="page-heading"><span className="eyebrow">AI 导师</span><h1>今天想弄懂什么？</h1><p>输入题目，选择你喜欢的学习方式。</p></header>
    <section className="mode-picker" aria-label="学习模式">
      <button className={`mode-card${mode === 'explanation' ? ' is-active' : ''}`} onClick={() => setMode('explanation')}>
        <BookOpenCheck /><span><strong>逐步讲解</strong><small>拆解步骤，选项互动</small></span>
      </button>
      <button className={`mode-card${mode === 'discussion' ? ' is-active' : ''}`} onClick={() => setMode('discussion')}>
        <MessageCircleQuestion /><span><strong>启发研讨</strong><small>追问思路，共同探索</small></span>
      </button>
    </section>
    <section className="card composer">
      <textarea value={text} onChange={event => setText(event.target.value)} onPaste={onPaste} onKeyDown={onKeyDown}
        placeholder={imageEnabled ? '输入问题，或粘贴一张题目图片…' : '输入你的问题…'} rows={7} maxLength={20_000} autoFocus />
      {image && <div className="draft-image">
        <button className="draft-image__preview" onClick={() => setPreview(true)}><img src={image.url} alt="待发送题目" /><span>{image.name}</span></button>
        <button className="icon-button" aria-label="删除图片" onClick={() => setImage(null)}><Trash2 size={18} /></button>
      </div>}
      <div className="composer__footer">
        <div className="composer__tools">
          {imageEnabled && <><input ref={fileRef} hidden type="file" accept="image/jpeg,image/png" onChange={onFile} /><button className="button button--ghost" onClick={() => fileRef.current?.click()} disabled={Boolean(image)}><ImagePlus size={18} />上传图片</button></>}
          <label className="switch-row"><input type="checkbox" checked={personalized} onChange={event => setPersonalized(event.target.checked)} /><span>个性化引导</span></label>
        </div>
        <button className="button button--primary" onClick={() => void submit()} disabled={submitting || (!text.trim() && !image)}>
          {submitting ? <><LoaderCircle className="spin" size={18} />正在准备题目</> : <><Upload size={18} />开始学习</>}
        </button>
      </div>
      <p className="composer__hint">Enter 提交 · Shift + Enter 换行{imageEnabled ? ' · JPG/PNG，最大 5MB' : ''}</p>
    </section>
    {preview && image && <div className="image-lightbox" role="dialog" aria-modal="true" onClick={() => setPreview(false)}><button className="image-lightbox__close">关闭</button><img src={image.url} alt="放大后的待发送题目" /></div>}
  </div>
}
