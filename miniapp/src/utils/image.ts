const MAX_ORIGINAL_BYTES = 5 * 1024 * 1024
const MAX_ENCODED_BYTES = 2 * 1024 * 1024

export interface EncodedImage {
  name: string
  tempPath: string
  dataUrl: string
  size: number
}

interface ChooseImageOptions {
  maxEncodedBytes?: number
}

function chooseMedia(): Promise<{ path: string; size: number }> {
  return new Promise((resolve, reject) => {
    uni.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: result => {
        const file = result.tempFiles[0]
        if (!file) {
          reject(new Error('未选择图片'))
          return
        }
        resolve({ path: file.tempFilePath, size: file.size })
      },
      fail: error => reject(new Error(error.errMsg || '选择图片失败'))
    })
  })
}

function getFileSize(filePath: string): Promise<number> {
  return new Promise((resolve, reject) => {
    uni.getFileInfo({
      filePath,
      success: result => resolve(result.size),
      fail: error => reject(new Error(error.errMsg || '读取图片信息失败'))
    })
  })
}

function compress(filePath: string, quality: number): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.compressImage({
      src: filePath,
      quality,
      success: result => resolve(result.tempFilePath),
      fail: error => reject(new Error(error.errMsg || '图片压缩失败'))
    })
  })
}

function readBase64(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const fileSystem = uni.getFileSystemManager()
    fileSystem.readFile({
      filePath,
      encoding: 'base64',
      success: result => {
        if (typeof result.data !== 'string') {
          reject(new Error('图片编码失败'))
          return
        }
        resolve(result.data)
      },
      fail: error => reject(new Error(error.errMsg || '图片读取失败'))
    })
  })
}

function inferMimeType(filePath: string): string {
  const normalized = filePath.toLowerCase()
  if (normalized.endsWith('.png')) return 'image/png'
  if (normalized.endsWith('.webp')) return 'image/webp'
  return 'image/jpeg'
}

export async function chooseAndEncodeImage(options: ChooseImageOptions = {}): Promise<EncodedImage> {
  const selected = await chooseMedia()
  if (selected.size > MAX_ORIGINAL_BYTES) throw new Error('图片大小不能超过 5 MB')

  const limit = options.maxEncodedBytes || MAX_ENCODED_BYTES
  let path = selected.path
  let size = selected.size

  for (const quality of [80, 60, 40]) {
    if (size <= limit) break
    path = await compress(path, quality)
    size = await getFileSize(path)
  }

  if (size > limit) throw new Error(`图片压缩后仍超过 ${Math.ceil(limit / 1024)} KB`)
  const base64 = await readBase64(path)
  const extension = inferMimeType(path).split('/')[1]

  return {
    name: `question-${Date.now()}.${extension}`,
    tempPath: path,
    dataUrl: `data:${inferMimeType(path)};base64,${base64}`,
    size
  }
}

