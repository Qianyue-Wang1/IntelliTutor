<template>
  <view class="discussion-tree">
    <view
      v-for="item in visibleNodes"
      :key="item.key"
      class="tree-node"
      :class="{ active: item.node.isActive, child: item.level > 0 }"
      :style="item.indentStyle"
    >
      <view class="node-head" @tap="toggleNode(item)">
        <view class="node-main">
          <text class="node-number">步骤{{ item.number }}</text>
          <view class="node-copy">
            <SafeRichContent :content="item.body" />
          </view>
        </view>
        <view
          v-if="item.hasChildren"
          :class="{ toggle: true, expanded: item.expanded }"
        ></view>
      </view>
    </view>

    <view v-if="visibleNodes.length === 0" class="tree-empty">
      <text>暂无解题步骤</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DiscussionStep } from '@/api/types'
import SafeRichContent from './SafeRichContent.vue'

interface FlatDiscussionNode {
  key: string
  number: string
  level: number
  node: DiscussionStep
  body: string
  hasChildren: boolean
  expanded: boolean
  indentStyle: string
}

const props = defineProps<{
  nodes: DiscussionStep[]
}>()

// 只保存用户手动点击后的覆盖状态；未覆盖时直接使用后端节点状态。
const expandedOverrides = ref<Record<string, boolean>>({})

// 模板只渲染单层列表；任意深度的树遍历统一在 TypeScript 中完成。
const visibleNodes = computed<FlatDiscussionNode[]>(() => {
  const result: FlatDiscussionNode[] = []
  flattenVisibleNodes(Array.isArray(props.nodes) ? props.nodes : [], result)
  return result
})

function flattenVisibleNodes(
  nodes: DiscussionStep[],
  result: FlatDiscussionNode[],
  parentNumber = '',
  level = 0,
  parentKey = ''
): void {
  nodes.forEach((node, index) => {
    const number = getStepNumber(node, index, parentNumber)
    const key = parentKey ? `${parentKey}.${index}` : String(index)
    const children = Array.isArray(node.subSteps) ? node.subSteps : []
    const hasChildren = children.length > 0
    const expanded = getExpandedState(key, node)

    result.push({
      key,
      number,
      level,
      node,
      body: getNodeBody(node),
      hasChildren,
      expanded,
      indentStyle: level > 0 ? `margin-left: ${level * 24}rpx;` : ''
    })

    if (hasChildren && expanded) {
      flattenVisibleNodes(children, result, number, level + 1, key)
    }
  })
}

function getExpandedState(key: string, node: DiscussionStep): boolean {
  if (Object.prototype.hasOwnProperty.call(expandedOverrides.value, key)) {
    return Boolean(expandedOverrides.value[key])
  }
  return Boolean(node.isExpanded || node.isActive || hasActiveChild(node))
}

function toggleNode(item: FlatDiscussionNode): void {
  if (!item.hasChildren) return
  expandedOverrides.value = {
    ...expandedOverrides.value,
    [item.key]: !item.expanded
  }
}

function hasActiveChild(node: DiscussionStep): boolean {
  const children = Array.isArray(node.subSteps) ? node.subSteps : []
  return children.some(child => Boolean(child.isActive || hasActiveChild(child)))
}

function getStepNumber(step: DiscussionStep, index: number, parentNumber: string): string {
  if (parentNumber) return `${parentNumber}.${index + 1}`
  return normalizeStepId(step.id) || String(index + 1)
}

function normalizeStepId(id: DiscussionStep['id']): string {
  const value = String(id ?? '').trim().replace(/-/g, '.')
  return /^\d+(?:\.\d+)*$/.test(value) ? value : ''
}

function getNodeBody(node: DiscussionStep): string {
  const description = stripStepPrefix(node.description || '')
  const content = stripStepPrefix(node.content || '')
  const title = stripTrailingColon(node.title || '')
  if (description) return description
  if (content) return title ? `${title}：${content}` : content
  return title || '步骤内容加载中'
}

function stripStepPrefix(value: string): string {
  return String(value || '').trim().replace(/^步骤\s*\d+(?:\.\d+)*\s*[:：]\s*/, '')
}

function stripTrailingColon(value: string): string {
  return String(value || '').trim().replace(/[:：]\s*$/, '')
}
</script>

<style scoped lang="scss">
.discussion-tree { width: 100%; }
.tree-node { box-sizing: border-box; margin-bottom: 10rpx; }
.tree-node:last-child { margin-bottom: 0; }
.node-head { display: flex; align-items: flex-start; gap: 10rpx; padding: 14rpx 15rpx; border: 1rpx solid $border; border-radius: 8rpx; background: $surface; }
.tree-node.child .node-head { padding: 12rpx 13rpx; background: $surface-raised; }
.tree-node.active > .node-head { border-color: $primary; background: linear-gradient(180deg, $surface 0%, #f4faf7 100%); }
.node-head:active { background: $surface-muted; }
.node-main { display: flex; flex: 1; min-width: 0; align-items: flex-start; gap: 8rpx; }
.node-number { flex: none; color: $primary; font-size: 24rpx; font-weight: 700; line-height: 1.58; white-space: nowrap; }
.node-copy { flex: 1; min-width: 0; }
.node-copy :deep(.rich-shell) { color: $text-primary; font-size: 24rpx; line-height: 1.58; }
.node-copy :deep(.rich-content) { margin: 0; }
.toggle { flex: none; width: 14rpx; height: 14rpx; margin-top: 11rpx; border-right: 3rpx solid $primary; border-bottom: 3rpx solid $primary; transform: rotate(45deg); }
.toggle.expanded { transform: rotate(225deg); }
.tree-empty { padding: 20rpx; color: $text-muted; font-size: 24rpx; text-align: center; }
</style>
