const developmentFallback = 'http://192.168.1.100:8090/api'
const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
const resolvedBaseUrl = configuredBaseUrl || (import.meta.env.PROD ? '' : developmentFallback)
const isH5Build = process.env.UNI_PLATFORM === 'h5'

if (!resolvedBaseUrl) {
  throw new Error('生产构建必须配置 VITE_API_BASE_URL')
}

if (
  import.meta.env.PROD
  && !resolvedBaseUrl.startsWith('https://')
  && !(isH5Build && resolvedBaseUrl.startsWith('/'))
) {
  throw new Error('生产环境小程序 API 必须使用 HTTPS；H5 构建也可使用同源相对路径')
}

export const API_BASE_URL = resolvedBaseUrl.replace(/\/+$/, '')
