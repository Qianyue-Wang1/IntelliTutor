import { useId, useState } from 'react'
import { ChevronDown, Expand } from 'lucide-react'
import type { CoreKnowledgePoint, ImageData, KnowledgePoint, LearningPathStep, ProblemSolvingHint } from '@/api/types'
import MathContent from './MathContent'
import GeoGebraPanel from './GeoGebraPanel'

export default function QuestionPanel({
  question,
  images = [],
  knowledge = [],
  coreKnowledge = [],
  problemSolvingHint = null,
  learningPath = [],
  imageCode = [],
  showKnowledgeDetails = false,
  defaultHintExpanded = false,
}: {
  question: string
  images?: ImageData[]
  knowledge?: KnowledgePoint[]
  coreKnowledge?: CoreKnowledgePoint[]
  problemSolvingHint?: ProblemSolvingHint | null
  learningPath?: LearningPathStep[]
  imageCode?: string[]
  showKnowledgeDetails?: boolean
  defaultHintExpanded?: boolean
}) {
  const [preview, setPreview] = useState<string | null>(null)
  const [knowledgeExpanded, setKnowledgeExpanded] = useState(false)
  const [hintExpanded, setHintExpanded] = useState(defaultHintExpanded)
  const knowledgeDetailsId = useId()
  const hintDetailsId = useId()
  const displayedKnowledge = coreKnowledge.length > 0
    ? coreKnowledge.map(point => ({ title: point.name, description: point.content }))
    : knowledge
  const displayedLearningPath = learningPath.length > 0
    ? learningPath
    : coreKnowledge.map((point, index) => {
      const nextPoint = coreKnowledge[index + 1]
      return {
        step_number: index + 1,
        title: point.name,
        knowledge_points: [point.name],
        connection: nextPoint
          ? `本步讲清${point.content}，为下一步“${nextPoint.name}”建立基础。`
          : `结合前面所学，本步讲清${point.content}，完成整体理解。`,
        assessment_focus: `确认是否理解${point.name}的核心含义和作用`,
      }
    })

  return <section className="card question-card">
    <div className="section-heading"><span className="eyebrow">当前学习内容</span></div>
    <MathContent content={question} />
    {images.length > 0 && <div className="question-images">
      {images.map(image => <button key={image.url} className="question-image" onClick={() => setPreview(image.url)}>
        <img src={image.url} alt={image.name || '题目图片'} /><Expand size={16} aria-hidden="true" />
      </button>)}
    </div>}
    {displayedKnowledge.length > 0 && <div className="knowledge-list" aria-label="相关知识点">
      {displayedKnowledge.map(point => <span className="knowledge-chip" title={point.description} key={`${point.title}-${point.description}`}>{point.title}</span>)}
    </div>}
    {showKnowledgeDetails && displayedKnowledge.length > 0 && <div className="knowledge-details">
      <button
        className="knowledge-details__toggle"
        type="button"
        aria-expanded={knowledgeExpanded}
        aria-controls={knowledgeDetailsId}
        onClick={() => setKnowledgeExpanded(value => !value)}
      >
        <span>知识点解析</span>
        <span className="knowledge-details__action">{knowledgeExpanded ? '收起' : '展开'}<ChevronDown size={17} aria-hidden="true" /></span>
      </button>
      <div
        className={`knowledge-details__content${knowledgeExpanded ? ' is-open' : ''}`}
        id={knowledgeDetailsId}
        aria-hidden={!knowledgeExpanded}
      >
        <div className="knowledge-details__inner">
          <ul className="knowledge-details__list">
            {displayedKnowledge.map(point => <li key={`${point.title}-${point.description}`}>
              <h3>{point.title}</h3>
              <MathContent content={point.description} />
            </li>)}
          </ul>
        </div>
      </div>
    </div>}
    {(displayedLearningPath.length > 0 || problemSolvingHint) && <div className="knowledge-details solving-hint">
      <button
        className="knowledge-details__toggle"
        type="button"
        aria-expanded={hintExpanded}
        aria-controls={hintDetailsId}
        onClick={() => setHintExpanded(value => !value)}
      >
        <span>学习指引</span>
        <span className="knowledge-details__action">{hintExpanded ? '收起' : '展开'}<ChevronDown size={17} aria-hidden="true" /></span>
      </button>
      <div
        className={`knowledge-details__content${hintExpanded ? ' is-open' : ''}`}
        id={hintDetailsId}
        aria-hidden={!hintExpanded}
      >
        <div className="knowledge-details__inner">
          <div className="solving-hint__track">
            {displayedLearningPath.length > 0 ? displayedLearningPath.map(step => <article className="solving-hint__stage" key={step.step_number}>
              <span className="solving-hint__marker" aria-hidden="true">{step.step_number}</span>
              <div>
                <h3>步骤 {step.step_number} · {step.title}</h3>
                <p className="learning-path__knowledge"><strong>本步知识点：</strong>{step.knowledge_points.join('、')}</p>
                <MathContent content={step.connection} />
              </div>
            </article>) : problemSolvingHint && <>
            <article className="solving-hint__stage solving-hint__stage--basic">
              <span className="solving-hint__marker" aria-hidden="true">1</span>
              <div>
                <h3>学习起点</h3>
                <MathContent content={problemSolvingHint.basic_idea} />
              </div>
            </article>
            <article className="solving-hint__stage">
              <span className="solving-hint__marker" aria-hidden="true">2</span>
              <div>
                <h3>推荐路径</h3>
                <MathContent content={problemSolvingHint.skill_enlightenment} />
              </div>
            </article>
            <article className="solving-hint__stage">
              <span className="solving-hint__marker" aria-hidden="true">3</span>
              <div>
                <h3>知识关联</h3>
                <MathContent content={problemSolvingHint.core_mathematical_thought} />
              </div>
            </article>
            <article className="solving-hint__stage">
              <span className="solving-hint__marker" aria-hidden="true">4</span>
              <div>
                <h3>关键方法</h3>
                <MathContent content={problemSolvingHint.related_method} />
              </div>
            </article>
            </>}
          </div>
        </div>
      </div>
    </div>}
    <GeoGebraPanel commands={imageCode} />
    {preview && <div className="image-lightbox" role="dialog" aria-modal="true" aria-label="图片预览" onClick={() => setPreview(null)}>
      <button className="image-lightbox__close">关闭</button><img src={preview} alt="放大后的题目图片" />
    </div>}
  </section>
}
