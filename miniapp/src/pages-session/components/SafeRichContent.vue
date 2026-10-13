<template>
  <view class="rich-shell">
    <mp-html
      class="rich-content"
      :content="safeContent"
      :markdown="true"
      :selectable="true"
      :copy-link="false"
      :show-img-menu="false"
      :preview-img="false"
      :scroll-table="true"
      container-style="display:block;width:100%;min-width:0;line-height:1.72;"
    />
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MpHtml from '@/pages-session/uni_modules/mp-html/components/mp-html/mp-html.vue'
import { sanitizeRichTextSource } from '@/utils/sanitizeRichText'

const props = defineProps<{ content: string }>()
const safeContent = computed(() => sanitizeRichTextSource(props.content))
</script>

<style scoped lang="scss">
.rich-shell {
  display: block;
  width: 100%;
  min-width: 0;
}

.rich-content {
  display: block;
  width: 100%;
  min-width: 0;
  color: inherit;
  line-height: 1.72;
  overflow-wrap: anywhere;
  word-break: break-word;
}
</style>
