import { afterEach, describe, expect, it, vi } from 'vitest'
import { chooseAndEncodeImage } from '@/utils/image'

afterEach(() => {
  vi.unstubAllGlobals()
})

function installImageUni(size: number): void {
  vi.stubGlobal('uni', {
    chooseMedia: (options: UniNamespace.ChooseMediaOption) => options.success?.({
      type: 'image',
      tempFiles: [{ tempFilePath: 'wxfile://question.jpg', size, fileType: 'image' }]
    } as UniNamespace.ChooseMediaSuccessCallbackResult),
    getFileInfo: (options: UniNamespace.GetFileInfoOptions) => options.success?.({
      size: 1024,
      digest: '',
      errMsg: 'getFileInfo:ok'
    }),
    compressImage: (options: UniNamespace.CompressImageOptions) => options.success?.({
      tempFilePath: 'wxfile://compressed.jpg'
    }),
    getFileSystemManager: () => ({
      readFile: (options: UniNamespace.ReadFileOption) => options.success?.({
        data: 'YWJj',
        errMsg: 'readFile:ok'
      })
    })
  } as unknown as typeof uni)
}

describe('image input', () => {
  it('rejects an original image larger than 5 MB', async () => {
    installImageUni(5 * 1024 * 1024 + 1)
    await expect(chooseAndEncodeImage()).rejects.toThrow('图片大小不能超过 5 MB')
  })

  it('compresses and converts an oversized image to a data url', async () => {
    installImageUni(3 * 1024 * 1024)
    const result = await chooseAndEncodeImage()
    expect(result.tempPath).toBe('wxfile://compressed.jpg')
    expect(result.dataUrl).toBe('data:image/jpeg;base64,YWJj')
    expect(result.size).toBe(1024)
  })
})
