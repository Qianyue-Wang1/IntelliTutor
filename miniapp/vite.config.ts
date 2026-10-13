import { defineConfig } from 'vite'
import uniModule from '@dcloudio/vite-plugin-uni'
import { fileURLToPath, URL } from 'node:url'

type UniPluginFactory = typeof uniModule

function resolveUniPlugin(moduleValue: unknown): UniPluginFactory {
  let candidate = moduleValue
  for (let depth = 0; depth < 3 && typeof candidate !== 'function'; depth += 1) {
    candidate = (candidate as { default?: unknown } | null)?.default
  }
  if (typeof candidate !== 'function') throw new TypeError('无法加载 @dcloudio/vite-plugin-uni')
  return candidate as UniPluginFactory
}

const uni = resolveUniPlugin(uniModule)

export default defineConfig(({ mode }) => ({
  // Docker 中将 H5 发布到 /h5/；小程序构建不使用该公共路径。
  base: process.env.UNI_PLATFORM === 'h5' ? '/h5/' : '/',
  // 纯 TypeScript 单测不需要启动 uni-app 编译器，避免测试环境加载 H5 编译别名。
  plugins: mode === 'test' ? [] : [uni()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts']
  }
}))
