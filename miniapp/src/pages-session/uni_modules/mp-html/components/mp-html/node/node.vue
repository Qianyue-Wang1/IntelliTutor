<template>
  <view :id="attrs.id" :class="'_block _'+name+' '+attrs.class" :style="attrs.style">
    <block v-for="(n, i) in nodes" v-bind:key="i">
      <!-- 图片 -->
      <!-- 占位图 -->
      <image v-if="n.name==='img'&&!n.t&&((opts[1]&&!ctrl[i])||ctrl[i]<0)" class="_img" :style="n.attrs.style" :src="ctrl[i]<0?opts[2]:opts[1]" mode="widthFix" />
      <!-- 显示图片 -->
      <!-- #ifdef H5 || (APP-PLUS && VUE2) -->
      <img v-if="n.name==='img'" :id="n.attrs.id" :class="'_img '+n.attrs.class" :style="(ctrl[i]===-1?'display:none;':'')+n.attrs.style" :src="n.attrs.src||(ctrl.load?n.attrs['data-src']:'')" :data-i="i" @load="imgLoad" @error="mediaError" @tap.stop="imgTap" @longpress="imgLongTap" />
      <!-- #endif -->
      <!-- #ifndef H5 || (APP-PLUS && VUE2) -->
      <!-- 表格中的图片，使用 rich-text 防止大小不正确 -->
      <rich-text v-if="n.name==='img'&&n.t" :style="'display:'+n.t" :nodes="[{attrs:{style:n.attrs.style||'',src:n.attrs.src},name:'img'}]" :data-i="i" @tap.stop="imgTap" />
      <!-- #endif -->
      <!-- #ifdef APP-HARMONY -->
      <image v-else-if="n.name==='img'" :id="n.attrs.id" :class="'_img '+n.attrs.class" :style="(ctrl[i]===-1?'display:none;':'')+'width:'+ctrl[i]+'px;'+n.attrs.style" :src="n.attrs.src||(ctrl.load?n.attrs['data-src']:'')" :mode="!n.h?'widthFix':(!n.w?'heightFix':(n.m||'scaleToFill'))" :data-i="i" @load="imgLoad" @error="mediaError" @tap.stop="imgTap" @longpress="imgLongTap" />
      <!-- #endif -->
      <!-- #ifndef H5 || APP-PLUS || MP-KUAISHOU -->
      <image v-else-if="n.name==='img'" :id="n.attrs.id" :class="'_img '+n.attrs.class" :style="(ctrl[i]===-1?'display:none;':'')+'width:'+(ctrl[i]||1)+'px;height:1px;'+n.attrs.style" :src="n.attrs.src" :mode="!n.h?'widthFix':(!n.w?'heightFix':(n.m||'scaleToFill'))" :lazy-load="opts[0]" :webp="n.webp" :show-menu-by-longpress="opts[3]&&!n.attrs.ignore" :image-menu-prevent="!opts[3]||n.attrs.ignore" :data-i="i" @load="imgLoad" @error="mediaError" @tap.stop="imgTap" @longpress="imgLongTap" />
      <!-- #endif -->
      <!-- #ifdef MP-KUAISHOU -->
      <image v-else-if="n.name==='img'" :id="n.attrs.id" :class="'_img '+n.attrs.class" :style="(ctrl[i]===-1?'display:none;':'')+n.attrs.style" :src="n.attrs.src" :lazy-load="opts[0]" :data-i="i" @load="imgLoad" @error="mediaError" @tap.stop="imgTap"></image>
      <!-- #endif -->
      <!-- #ifdef APP-PLUS && VUE3 -->
      <image v-else-if="n.name==='img'" :id="n.attrs.id" :class="'_img '+n.attrs.class" :style="(ctrl[i]===-1?'display:none;':'')+'width:'+(ctrl[i]||1)+'px;'+n.attrs.style" :src="n.attrs.src||(ctrl.load?n.attrs['data-src']:'')" :mode="!n.h?'widthFix':(!n.w?'heightFix':(n.m||''))" :data-i="i" @load="imgLoad" @error="mediaError" @tap.stop="imgTap" @longpress="imgLongTap" />
      <!-- #endif -->
      <!-- 文本 -->
      <!-- #ifdef MP-WEIXIN -->
      <text v-else-if="n.text" :user-select="opts[4]=='force'&&isiOS" decode>{{n.text}}</text>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN || MP-BAIDU || MP-ALIPAY || MP-TOUTIAO -->
      <text v-else-if="n.text" decode>{{n.text}}</text>
      <!-- #endif -->
      <text v-else-if="n.name==='br'">{{'\n'}}</text>
      <!-- 链接 -->
      <view v-else-if="n.name==='a'" :id="n.attrs.id" :class="(n.attrs.href?'_a ':'')+n.attrs.class" hover-class="_hover" :style="'display:inline;'+n.attrs.style" :data-i="i" @tap.stop="linkTap">
        <node name="span" :childs="n.children" :opts="opts" style="display:inherit" />
      </view>
      <!-- 视频 -->
      <!-- #ifdef APP-PLUS -->
      <view v-else-if="n.html" :id="n.attrs.id" :class="'_video '+n.attrs.class" :style="n.attrs.style" v-html="n.html" :data-i="i" @vplay.stop="play" />
      <!-- #endif -->
      <!-- #ifndef APP-PLUS -->
      <video v-else-if="n.name==='video'" :id="n.attrs.id" :class="n.attrs.class" :style="n.attrs.style" :autoplay="n.attrs.autoplay" :controls="n.attrs.controls" :loop="n.attrs.loop" :muted="n.attrs.muted" :object-fit="n.attrs['object-fit']" :poster="n.attrs.poster" :src="n.src[ctrl[i]||0]" :data-i="i" @play="play" @pause="mediaEvent" @fullscreenchange="mediaEvent" @error="mediaError" />
      <!-- #endif -->
      <!-- #ifdef H5 || APP-PLUS -->
      <iframe v-else-if="n.name==='iframe'" :style="n.attrs.style" :allowfullscreen="n.attrs.allowfullscreen" :frameborder="n.attrs.frameborder" :src="n.attrs.src" />
      <embed v-else-if="n.name==='embed'" :style="n.attrs.style" :src="n.attrs.src" />
      <!-- #endif -->
      <!-- #ifndef MP-TOUTIAO || ((H5 || APP-PLUS) && VUE3) -->
      <!-- 音频 -->
      <audio v-else-if="n.name==='audio'" :id="n.attrs.id" :class="n.attrs.class" :style="n.attrs.style" :author="n.attrs.author" :controls="n.attrs.controls" :loop="n.attrs.loop" :name="n.attrs.name" :poster="n.attrs.poster" :src="n.src[ctrl[i]||0]" :data-i="i" @play="play" @pause="mediaEvent" @error="mediaError" />
      <!-- #endif -->
      <view v-else-if="(n.name==='table'&&n.c)||n.name==='li'" :id="n.attrs.id" :class="'_'+n.name+' '+n.attrs.class" :style="n.attrs.style">
        <node v-if="n.name==='li'" :childs="n.children" :opts="opts" />
        <view v-else v-for="(tbody, x) in n.children" v-bind:key="x" :class="'_'+tbody.name+' '+tbody.attrs.class" :style="tbody.attrs.style">
          <node v-if="tbody.name==='td'||tbody.name==='th'" :childs="tbody.children" :opts="opts" />
          <block v-else v-for="(tr, y) in tbody.children" v-bind:key="y">
            <view v-if="tr.name==='td'||tr.name==='th'" :class="'_'+tr.name+' '+tr.attrs.class" :style="tr.attrs.style">
              <node :childs="tr.children" :opts="opts" />
            </view>
            <view v-else :class="'_'+tr.name+' '+tr.attrs.class" :style="tr.attrs.style">
              <view v-for="(td, z) in tr.children" v-bind:key="z" :class="'_'+td.name+' '+td.attrs.class" :style="td.attrs.style">
                <node :childs="td.children" :opts="opts" />
              </view>
            </view>
          </block>
        </view>
      </view>
      
      <!-- 富文本 -->
      <!-- #ifdef H5 || ((MP-WEIXIN || MP-QQ || APP-PLUS || MP-360) && VUE2) -->
      <rich-text v-else-if="!n.c&&(n.l||!handler.isInline(n.name, n.attrs.style))" :id="n.attrs.id" :style="n.f" :user-select="opts[4]" :nodes="[n]" />
      <!-- #endif -->
      <!-- #ifndef H5 || ((MP-WEIXIN || MP-QQ || APP-PLUS || MP-360) && VUE2) -->
      <rich-text v-else-if="!n.c" :id="n.attrs.id" :style="'display:inline;'+n.f" :preview="false" :selectable="opts[4]" :user-select="opts[4]" :nodes="[n]" />
      <!-- #endif -->
      <!-- 继续递归 -->
      <view v-else-if="n.c===2" :id="n.attrs.id" :class="'_block _'+n.name+' '+n.attrs.class" :style="n.f+';'+n.attrs.style">
        <node v-for="(n2, j) in n.children" v-bind:key="j" :style="n2.f" :name="n2.name" :attrs="n2.attrs" :childs="n2.children" :opts="opts" />
      </view>
      <node v-else :style="n.f" :name="n.name" :attrs="n.attrs" :childs="n.children" :opts="opts" />
    </block>
  </view>
</template>
<script module="handler" lang="wxs">
// 行内标签列表
var inlineTags = {
  abbr: true,
  b: true,
  big: true,
  code: true,
  del: true,
  em: true,
  i: true,
  ins: true,
  label: true,
  q: true,
  small: true,
  span: true,
  strong: true,
  sub: true,
  sup: true
}
/**
 * @description 判断是否为行内标签
 */
module.exports = {
  isInline: function (tagName, style) {
    return inlineTags[tagName] || (style || '').indexOf('display:inline') !== -1
  }
}
</script>
<script>

import node from './node'
export default {
  name: 'node',
  options: {
    // #ifdef MP-WEIXIN
    virtualHost: true,
    // #endif
    // #ifdef MP-TOUTIAO
    addGlobalClass: false
    // #endif
  },
  data () {
    return {
      ctrl: {},
      nodes: [],
      // #ifdef MP-WEIXIN
      isiOS: (uni.canIUse('getDeviceInfo') ? uni.getDeviceInfo() : uni.getSystemInfoSync()).system.includes('iOS')
      // #endif
    }
  },
  props: {
    name: String,
    attrs: {
      type: Object,
      default () {
        return {}
      }
    },
    childs: Array,
    opts: Array
  },
  watch: {
    childs: {
		  handler (nodes) {
        // 列表缩短会刷新整个列表，因此进行空填充
        while (this.nodes.length > nodes.length) {
			    nodes.push({})
		    }
        this.nodes = nodes
      },
	    immediate: true
	  }
  },
  components: {

    // #ifndef ((H5 || APP-PLUS) && VUE3) || APP-HARMONY
    node
    // #endif
  },
  mounted () {
    this.$nextTick(() => {
      for (this.root = this.$parent; this.root.$options.name !== 'mp-html'; this.root = this.root.$parent);
    })
    // #ifdef H5 || APP-PLUS
    if (this.opts[0]) {
      let i
      for (i = this.childs.length; i--;) {
        if (this.childs[i].name === 'img') break
      }
      if (i !== -1) {
        this.observer = uni.createIntersectionObserver(this).relativeToViewport({
          top: 500,
          bottom: 500
        })
        this.observer.observe('._img', res => {
          if (res.intersectionRatio) {
            this.$set(this.ctrl, 'load', 1)
            this.observer.disconnect()
          }
        })
      }
    }
    // #endif
  },
  beforeDestroy () {
    // #ifdef H5 || APP-PLUS
    if (this.observer) {
      this.observer.disconnect()
    }
    // #endif
  },
  methods:{
    // #ifdef MP-WEIXIN
    toJSON () { return this },
    // #endif
    /**
     * @description 播放视频事件
     * @param {Event} e
     */
    play (e) {
      const i = e.currentTarget.dataset.i
      const node = this.childs[i]
      this.root.$emit('play', {
        source: node.name,
        attrs: {
          ...node.attrs,
          src: node.src[this.ctrl[i] || 0]
        }
      })
      // #ifndef APP-PLUS
      if (this.root.pauseVideo) {
        let flag = false
        const id = e.target.id
        for (let i = this.root._videos.length; i--;) {
          if (this.root._videos[i].id === id) {
            flag = true
          } else {
            this.root._videos[i].pause() // 自动暂停其他视频
          }
        }
        // 将自己加入列表
        if (!flag) {
          const ctx = uni.createVideoContext(id
            // #ifndef MP-BAIDU
            , this
            // #endif
          )
          ctx.id = id
          if (this.root.playbackRate) {
            ctx.playbackRate(this.root.playbackRate)
          }
          this.root._videos.push(ctx)
        }
      }
      // #endif
    },
    /**
     * @description 音视频其他事件
     * @param {Event} e
     */
    mediaEvent (e) {
      const i = e.currentTarget.dataset.i
      const node = this.childs[i]
      this.root.$emit(e.type, {
        ...e.detail,
        source: node.name,
        attrs: {
          ...node.attrs,
          src: node.src[this.ctrl[i] || 0]
        }
      })
    },

    /**
     * @description 图片点击事件
     * @param {Event} e
     */
    imgTap (e) {
      const node = this.childs[e.currentTarget.dataset.i]
      if (node.a) {
        this.linkTap(node.a)
        return
      }
      if (node.attrs.ignore) return
      // #ifdef H5 || APP-PLUS
      node.attrs.src = node.attrs.src || node.attrs['data-src']
      // #endif
      // #ifndef APP-HARMONY
      this.root.$emit('imgtap', node.attrs)
      // #endif
      // #ifdef APP-HARMONY
      this.root.$emit('imgtap', {
        ...node.attrs
      })
      // #endif
      // 自动预览图片
      if (this.root.previewImg) {
        uni.previewImage({
          // #ifdef MP-WEIXIN
          showmenu: this.root.showImgMenu,
          // #endif
          // #ifdef MP-ALIPAY
          enablesavephoto: this.root.showImgMenu,
          enableShowPhotoDownload: this.root.showImgMenu,
          // #endif
          current: parseInt(node.attrs.i),
          urls: this.root.imgList
        })
      }
    },

    /**
     * @description 图片长按
     */
    imgLongTap (e) {
      // #ifdef APP-PLUS
      const attrs = this.childs[e.currentTarget.dataset.i].attrs
      if (this.opts[3] && !attrs.ignore) {
        uni.showActionSheet({
          itemList: ['保存图片'],
          success: () => {
            const save = path => {
              uni.saveImageToPhotosAlbum({
                filePath: path,
                success () {
                  uni.showToast({
                    title: '保存成功'
                  })
                }
              })
            }
            if (this.root.imgList[attrs.i].startsWith('http')) {
              uni.downloadFile({
                url: this.root.imgList[attrs.i],
                success: res => save(res.tempFilePath)
              })
            } else {
              save(this.root.imgList[attrs.i])
            }
          }
        })
      }
      // #endif
    },

    /**
     * @description 图片加载完成事件
     * @param {Event} e
     */
    imgLoad (e) {
      const i = e.currentTarget.dataset.i
      /* #ifndef H5 || (APP-PLUS && VUE2) */
      if (!this.childs[i].w) {
        // 设置原宽度
        this.$set(this.ctrl, i, e.detail.width)
      } else /* #endif */ if ((this.opts[1] && !this.ctrl[i]) || this.ctrl[i] === -1) {
        // 加载完毕，取消加载中占位图
        this.$set(this.ctrl, i, 1)
      }
      this.checkReady()
    },

    /**
     * @description 检查是否所有图片加载完毕
     */
    checkReady () {
      if (this.root && !this.root.lazyLoad) {
        this.root._unloadimgs -= 1
        if (!this.root._unloadimgs) {
          setTimeout(() => {
            this.root.getRect().then(rect => {
              this.root.$emit('ready', rect)
            }).catch(() => {
              this.root.$emit('ready', {})
            })
          }, 350)
        }
      }
    },

    /**
     * @description 链接点击事件
     * @param {Event} e
     */
    linkTap (e) {
      const node = e.currentTarget ? this.childs[e.currentTarget.dataset.i] : {}
      const attrs = node.attrs || e
      const href = attrs.href
      this.root.$emit('linktap', Object.assign({
        innerText: this.root.getText(node.children || []) // 链接内的文本内容
      }, attrs))
      if (href) {
        if (href[0] === '#') {
          // 跳转锚点
          this.root.navigateTo(href.substring(1)).catch(() => { })
        } else if (href.split('?')[0].includes('://')) {
          // 复制外部链接
          if (this.root.copyLink) {
            // #ifdef H5
            window.open(href)
            // #endif
            // #ifdef MP
            uni.setClipboardData({
              data: href,
              success: () =>
                uni.showToast({
                  title: '链接已复制'
                })
            })
            // #endif
            // #ifdef APP-PLUS
            plus.runtime.openWeb(href)
            // #endif
          }
        } else {
          // 跳转页面
          uni.navigateTo({
            url: href,
            fail () {
              uni.switchTab({
                url: href,
                fail () { }
              })
            }
          })
        }
      }
    },

    /**
     * @description 错误事件
     * @param {Event} e
     */
    mediaError (e) {
      const i = e.currentTarget.dataset.i
      const node = this.childs[i]
      // 加载其他源
      if (node.name === 'video' || node.name === 'audio') {
        let index = (this.ctrl[i] || 0) + 1
        if (index > node.src.length) {
          index = 0
        }
        if (index < node.src.length) {
          this.$set(this.ctrl, i, index)
          return
        }
      } else if (node.name === 'img') {
        // #ifdef H5 && VUE3
        if (this.opts[0] && !this.ctrl.load) return
        // #endif
        // 显示错误占位图
        if (this.opts[2]) {
          this.$set(this.ctrl, i, -1)
        }
        this.checkReady()
      }
      if (this.root) {
        this.root.$emit('error', {
          source: node.name,
          attrs: node.attrs,
          // #ifndef H5 && VUE3
          errMsg: e.detail.errMsg
          // #endif
        })
      }
    }
  }
}
</script>
<style>
/* a 标签默认效果 */
._a {
  padding: 1.5px 0 1.5px 0;
  color: #366092;
  word-break: break-all;
}

/* a 标签点击态效果 */
._hover {
  text-decoration: underline;
  opacity: 0.7;
}

/* 图片默认效果 */
._img {
  max-width: 100%;
  -webkit-touch-callout: none;
}

/* 内部样式 */

._block {
  display: block;
}

._b,
._strong {
  font-weight: bold;
}

._code {
  font-family: monospace;
}

._del {
  text-decoration: line-through;
}

._em,
._i {
  font-style: italic;
}

._h1 {
  font-size: 2em;
}

._h2 {
  font-size: 1.5em;
}

._h3 {
  font-size: 1.17em;
}

._h5 {
  font-size: 0.83em;
}

._h6 {
  font-size: 0.67em;
}

._h1,
._h2,
._h3,
._h4,
._h5,
._h6 {
  display: block;
  font-weight: bold;
}

._image {
  height: 1px;
}

._ins {
  text-decoration: underline;
}

._li {
  display: list-item;
}

._ol {
  list-style-type: decimal;
}

._ol,
._ul {
  display: block;
  padding-left: 40px;
  margin: 1em 0;
}

._q::before {
  content: '"';
}

._q::after {
  content: '"';
}

._sub {
  font-size: smaller;
  vertical-align: sub;
}

._sup {
  font-size: smaller;
  vertical-align: super;
}

._thead,
._tbody,
._tfoot {
  display: table-row-group;
}

._tr {
  display: table-row;
}

._td,
._th {
  display: table-cell;
  vertical-align: middle;
}

._th {
  font-weight: bold;
  text-align: center;
}

._ul {
  list-style-type: disc;
}

._ul ._ul {
  margin: 0;
  list-style-type: circle;
}

._ul ._ul ._ul {
  list-style-type: square;
}

._abbr,
._b,
._code,
._del,
._em,
._i,
._ins,
._label,
._q,
._span,
._strong,
._sub,
._sup {
  display: inline;
}

/* #ifdef APP-PLUS */
._video {
  width: 300px;
  height: 225px;
}
/* #endif */

.md-p { margin-block-start: 1em; margin-block-end: 1em; }
.md-table, .md-blockquote { margin-bottom: 16px; }
.md-table { box-sizing: border-box; width: 100%; overflow: auto; border-spacing: 0; border-collapse: collapse; }
.md-tr { background-color: #fff; border-top: 1px solid #c6cbd1; }
.md-table .md-tr:nth-child(2n) { background-color: #f6f8fa; }
.md-th, .md-td { padding: 6px 13px !important; border: 1px solid #dfe2e5; }
.md-th { font-weight: 600; }
.md-blockquote { padding: 0 1em; color: #6a737d; border-left: .25em solid #dfe2e5; }
.md-code { padding: .2em .4em; font-family: monospace; font-size: 85%; background: rgba(27,31,35,.05); border-radius: 3px; }
.md-pre .md-code { padding: 0; font-size: 100%; background: transparent; border: 0; }

/* fonts字体库可自行前往 https://github.com/KaTeX/KaTeX/tree/main/fonts 下载，放入自家的cdn服务上 */
@font-face {
  font-family: "KaTeX_AMS";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_AMS-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_AMS-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_AMS-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Caligraphic";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Caligraphic-Bold.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Caligraphic-Bold.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Caligraphic-Bold.ttf") format("truetype");
  font-weight: bold;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Caligraphic";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Caligraphic-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Caligraphic-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Caligraphic-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Fraktur";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Fraktur-Bold.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Fraktur-Bold.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Fraktur-Bold.ttf") format("truetype");
  font-weight: bold;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Fraktur";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Fraktur-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Fraktur-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Fraktur-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Main";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Bold.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Bold.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Bold.ttf") format("truetype");
  font-weight: bold;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Main";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-BoldItalic.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-BoldItalic.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-BoldItalic.ttf") format("truetype");
  font-weight: bold;
  font-style: italic;
}
@font-face {
  font-family: "KaTeX_Main";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Italic.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Italic.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Italic.ttf") format("truetype");
  font-weight: normal;
  font-style: italic;
}
@font-face {
  font-family: "KaTeX_Main";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Main-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Math";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Math-BoldItalic.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Math-BoldItalic.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Math-BoldItalic.ttf") format("truetype");
  font-weight: bold;
  font-style: italic;
}
@font-face {
  font-family: "KaTeX_Math";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Math-Italic.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Math-Italic.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Math-Italic.ttf") format("truetype");
  font-weight: normal;
  font-style: italic;
}
@font-face {
  font-family: "KaTeX_SansSerif";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Bold.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Bold.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Bold.ttf") format("truetype");
  font-weight: bold;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_SansSerif";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Italic.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Italic.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Italic.ttf") format("truetype");
  font-weight: normal;
  font-style: italic;
}
@font-face {
  font-family: "KaTeX_SansSerif";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_SansSerif-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Script";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Script-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Script-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Script-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Size1";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size1-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size1-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size1-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Size2";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size2-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size2-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size2-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Size3";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size3-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size3-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size3-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Size4";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size4-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size4-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Size4-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
@font-face {
  font-family: "KaTeX_Typewriter";
  src: url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Typewriter-Regular.woff2") format("woff2"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Typewriter-Regular.woff") format("woff"), url("https://cdn.bootcdn.net/ajax/libs/KaTeX/0.16.9/fonts/KaTeX_Typewriter-Regular.ttf") format("truetype");
  font-weight: normal;
  font-style: normal;
}
.katex {
  counter-reset: katexEqnNo mmlEqnNo;
  font: normal 1.21em KaTeX_Main, Times New Roman, serif;
  line-height: 1.2;
  text-indent: 0;
  text-rendering: auto;
}
.katex view,
.katex text {
  -ms-high-contrast-adjust: none !important;
  border-color: currentColor;
}
.katex .katex-mathml {
  /* Accessibility hack to only show to screen readers
         Found at: http://a11yproject.com/posts/how-to-hide-content/ */
  position: absolute;
  clip: rect(1px, 1px, 1px, 1px);
  padding: 0;
  border: 0;
  height: 1px;
  width: 1px;
  overflow: hidden;
}
.katex .katex-html {
  /* \newline is an empty block at top level, between .base elements */
}
.katex .katex-html > .newline {
  display: block;
}
.katex .base {
  position: relative;
  display: inline-block;
  white-space: nowrap;
  width: min-content;
}
.katex .strut {
  display: inline-block;
}
.katex .textbf {
  font-weight: bold;
}
.katex .textit {
  font-style: italic;
}
.katex .textrm {
  font-family: KaTeX_Main;
}
.katex .textsf {
  font-family: KaTeX_SansSerif;
}
.katex .texttt {
  font-family: KaTeX_Typewriter;
}
.katex .mathnormal {
  font-family: KaTeX_Math;
  font-style: italic;
}
.katex .mathit {
  font-family: KaTeX_Main;
  font-style: italic;
}
.katex .mathrm {
  font-style: normal;
}
.katex .mathbf {
  font-family: KaTeX_Main;
  font-weight: bold;
}
.katex .boldsymbol {
  font-family: KaTeX_Math;
  font-weight: bold;
  font-style: italic;
}
.katex .amsrm {
  font-family: KaTeX_AMS;
}
.katex .mathbb,
.katex .textbb {
  font-family: KaTeX_AMS;
}
.katex .mathcal {
  font-family: KaTeX_Caligraphic;
}
.katex .mathfrak,
.katex .textfrak {
  font-family: KaTeX_Fraktur;
}
.katex .mathtt {
  font-family: KaTeX_Typewriter;
}
.katex .mathscr,
.katex .textscr {
  font-family: KaTeX_Script;
}
.katex .mathsf,
.katex .textsf {
  font-family: KaTeX_SansSerif;
}
.katex .mathboldsf,
.katex .textboldsf {
  font-family: KaTeX_SansSerif;
  font-weight: bold;
}
.katex .mathitsf,
.katex .textitsf {
  font-family: KaTeX_SansSerif;
  font-style: italic;
}
.katex .mainrm {
  font-family: KaTeX_Main;
  font-style: normal;
}
.katex .vlist-t {
  display: inline-table;
  table-layout: fixed;
  border-collapse: collapse;
}
.katex .vlist-r {
  display: table-row;
}
.katex .vlist {
  display: table-cell;
  vertical-align: bottom;
  position: relative;
}
.katex .vlist > .katex-span {
  display: block;
  height: 0;
  position: relative;
}
.katex .vlist > .katex-span > .katex-span {
  display: inline-block;
}
.katex .vlist > .katex-span > .pstrut {
  overflow: hidden;
  width: 0;
}
.katex .vlist-t2 {
  margin-right: -2px;
}
.katex .vlist-s {
  display: table-cell;
  vertical-align: bottom;
  font-size: 1px;
  width: 2px;
  min-width: 2px;
}
.katex .vbox {
  display: inline-flex;
  flex-direction: column;
  align-items: baseline;
}
.katex .hbox {
  display: inline-flex;
  flex-direction: row;
  width: 100%;
}
.katex .thinbox {
  display: inline-flex;
  flex-direction: row;
  width: 0;
  max-width: 0;
}
.katex .msupsub {
  text-align: left;
}
.katex .mfrac > .katex-span > .katex-span {
  text-align: center;
}
.katex .mfrac .frac-line {
  display: inline-block;
  width: 100%;
  border-bottom-style: solid;
}
.katex .mfrac .frac-line,
.katex .overline .overline-line,
.katex .underline .underline-line,
.katex .hline,
.katex .hdashline,
.katex .rule {
  min-height: 1px;
}
.katex .mspace {
  display: inline-block;
}
.katex .llap,
.katex .rlap,
.katex .clap {
  width: 0;
  position: relative;
}
.katex .llap > .inner,
.katex .rlap > .inner,
.katex .clap > .inner {
  position: absolute;
}
.katex .llap > .fix,
.katex .rlap > .fix,
.katex .clap > .fix {
  display: inline-block;
}
.katex .llap > .inner {
  right: 0;
}
.katex .rlap > .inner,
.katex .clap > .inner {
  left: 0;
}
.katex .clap > .inner > .katex-span {
  margin-left: -50%;
  margin-right: 50%;
}
.katex .rule {
  display: inline-block;
  border: solid 0;
  position: relative;
}
.katex .overline .overline-line,
.katex .underline .underline-line,
.katex .hline {
  display: inline-block;
  width: 100%;
  border-bottom-style: solid;
}
.katex .hdashline {
  display: inline-block;
  width: 100%;
  border-bottom-style: dashed;
}
.katex .sqrt > .root {
  margin-left: 0.27777778em;
  margin-right: -0.55555556em;
}
.katex .sizing.reset-size1.size1,
.katex .fontsize-ensurer.reset-size1.size1 {
  font-size: 1em;
}
.katex .sizing.reset-size1.size2,
.katex .fontsize-ensurer.reset-size1.size2 {
  font-size: 1.2em;
}
.katex .sizing.reset-size1.size3,
.katex .fontsize-ensurer.reset-size1.size3 {
  font-size: 1.4em;
}
.katex .sizing.reset-size1.size4,
.katex .fontsize-ensurer.reset-size1.size4 {
  font-size: 1.6em;
}
.katex .sizing.reset-size1.size5,
.katex .fontsize-ensurer.reset-size1.size5 {
  font-size: 1.8em;
}
.katex .sizing.reset-size1.size6,
.katex .fontsize-ensurer.reset-size1.size6 {
  font-size: 2em;
}
.katex .sizing.reset-size1.size7,
.katex .fontsize-ensurer.reset-size1.size7 {
  font-size: 2.4em;
}
.katex .sizing.reset-size1.size8,
.katex .fontsize-ensurer.reset-size1.size8 {
  font-size: 2.88em;
}
.katex .sizing.reset-size1.size9,
.katex .fontsize-ensurer.reset-size1.size9 {
  font-size: 3.456em;
}
.katex .sizing.reset-size1.size10,
.katex .fontsize-ensurer.reset-size1.size10 {
  font-size: 4.148em;
}
.katex .sizing.reset-size1.size11,
.katex .fontsize-ensurer.reset-size1.size11 {
  font-size: 4.976em;
}
.katex .sizing.reset-size2.size1,
.katex .fontsize-ensurer.reset-size2.size1 {
  font-size: 0.83333333em;
}
.katex .sizing.reset-size2.size2,
.katex .fontsize-ensurer.reset-size2.size2 {
  font-size: 1em;
}
.katex .sizing.reset-size2.size3,
.katex .fontsize-ensurer.reset-size2.size3 {
  font-size: 1.16666667em;
}
.katex .sizing.reset-size2.size4,
.katex .fontsize-ensurer.reset-size2.size4 {
  font-size: 1.33333333em;
}
.katex .sizing.reset-size2.size5,
.katex .fontsize-ensurer.reset-size2.size5 {
  font-size: 1.5em;
}
.katex .sizing.reset-size2.size6,
.katex .fontsize-ensurer.reset-size2.size6 {
  font-size: 1.66666667em;
}
.katex .sizing.reset-size2.size7,
.katex .fontsize-ensurer.reset-size2.size7 {
  font-size: 2em;
}
.katex .sizing.reset-size2.size8,
.katex .fontsize-ensurer.reset-size2.size8 {
  font-size: 2.4em;
}
.katex .sizing.reset-size2.size9,
.katex .fontsize-ensurer.reset-size2.size9 {
  font-size: 2.88em;
}
.katex .sizing.reset-size2.size10,
.katex .fontsize-ensurer.reset-size2.size10 {
  font-size: 3.45666667em;
}
.katex .sizing.reset-size2.size11,
.katex .fontsize-ensurer.reset-size2.size11 {
  font-size: 4.14666667em;
}
.katex .sizing.reset-size3.size1,
.katex .fontsize-ensurer.reset-size3.size1 {
  font-size: 0.71428571em;
}
.katex .sizing.reset-size3.size2,
.katex .fontsize-ensurer.reset-size3.size2 {
  font-size: 0.85714286em;
}
.katex .sizing.reset-size3.size3,
.katex .fontsize-ensurer.reset-size3.size3 {
  font-size: 1em;
}
.katex .sizing.reset-size3.size4,
.katex .fontsize-ensurer.reset-size3.size4 {
  font-size: 1.14285714em;
}
.katex .sizing.reset-size3.size5,
.katex .fontsize-ensurer.reset-size3.size5 {
  font-size: 1.28571429em;
}
.katex .sizing.reset-size3.size6,
.katex .fontsize-ensurer.reset-size3.size6 {
  font-size: 1.42857143em;
}
.katex .sizing.reset-size3.size7,
.katex .fontsize-ensurer.reset-size3.size7 {
  font-size: 1.71428571em;
}
.katex .sizing.reset-size3.size8,
.katex .fontsize-ensurer.reset-size3.size8 {
  font-size: 2.05714286em;
}
.katex .sizing.reset-size3.size9,
.katex .fontsize-ensurer.reset-size3.size9 {
  font-size: 2.46857143em;
}
.katex .sizing.reset-size3.size10,
.katex .fontsize-ensurer.reset-size3.size10 {
  font-size: 2.96285714em;
}
.katex .sizing.reset-size3.size11,
.katex .fontsize-ensurer.reset-size3.size11 {
  font-size: 3.55428571em;
}
.katex .sizing.reset-size4.size1,
.katex .fontsize-ensurer.reset-size4.size1 {
  font-size: 0.625em;
}
.katex .sizing.reset-size4.size2,
.katex .fontsize-ensurer.reset-size4.size2 {
  font-size: 0.75em;
}
.katex .sizing.reset-size4.size3,
.katex .fontsize-ensurer.reset-size4.size3 {
  font-size: 0.875em;
}
.katex .sizing.reset-size4.size4,
.katex .fontsize-ensurer.reset-size4.size4 {
  font-size: 1em;
}
.katex .sizing.reset-size4.size5,
.katex .fontsize-ensurer.reset-size4.size5 {
  font-size: 1.125em;
}
.katex .sizing.reset-size4.size6,
.katex .fontsize-ensurer.reset-size4.size6 {
  font-size: 1.25em;
}
.katex .sizing.reset-size4.size7,
.katex .fontsize-ensurer.reset-size4.size7 {
  font-size: 1.5em;
}
.katex .sizing.reset-size4.size8,
.katex .fontsize-ensurer.reset-size4.size8 {
  font-size: 1.8em;
}
.katex .sizing.reset-size4.size9,
.katex .fontsize-ensurer.reset-size4.size9 {
  font-size: 2.16em;
}
.katex .sizing.reset-size4.size10,
.katex .fontsize-ensurer.reset-size4.size10 {
  font-size: 2.5925em;
}
.katex .sizing.reset-size4.size11,
.katex .fontsize-ensurer.reset-size4.size11 {
  font-size: 3.11em;
}
.katex .sizing.reset-size5.size1,
.katex .fontsize-ensurer.reset-size5.size1 {
  font-size: 0.55555556em;
}
.katex .sizing.reset-size5.size2,
.katex .fontsize-ensurer.reset-size5.size2 {
  font-size: 0.66666667em;
}
.katex .sizing.reset-size5.size3,
.katex .fontsize-ensurer.reset-size5.size3 {
  font-size: 0.77777778em;
}
.katex .sizing.reset-size5.size4,
.katex .fontsize-ensurer.reset-size5.size4 {
  font-size: 0.88888889em;
}
.katex .sizing.reset-size5.size5,
.katex .fontsize-ensurer.reset-size5.size5 {
  font-size: 1em;
}
.katex .sizing.reset-size5.size6,
.katex .fontsize-ensurer.reset-size5.size6 {
  font-size: 1.11111111em;
}
.katex .sizing.reset-size5.size7,
.katex .fontsize-ensurer.reset-size5.size7 {
  font-size: 1.33333333em;
}
.katex .sizing.reset-size5.size8,
.katex .fontsize-ensurer.reset-size5.size8 {
  font-size: 1.6em;
}
.katex .sizing.reset-size5.size9,
.katex .fontsize-ensurer.reset-size5.size9 {
  font-size: 1.92em;
}
.katex .sizing.reset-size5.size10,
.katex .fontsize-ensurer.reset-size5.size10 {
  font-size: 2.30444444em;
}
.katex .sizing.reset-size5.size11,
.katex .fontsize-ensurer.reset-size5.size11 {
  font-size: 2.76444444em;
}
.katex .sizing.reset-size6.size1,
.katex .fontsize-ensurer.reset-size6.size1 {
  font-size: 0.5em;
}
.katex .sizing.reset-size6.size2,
.katex .fontsize-ensurer.reset-size6.size2 {
  font-size: 0.6em;
}
.katex .sizing.reset-size6.size3,
.katex .fontsize-ensurer.reset-size6.size3 {
  font-size: 0.7em;
}
.katex .sizing.reset-size6.size4,
.katex .fontsize-ensurer.reset-size6.size4 {
  font-size: 0.8em;
}
.katex .sizing.reset-size6.size5,
.katex .fontsize-ensurer.reset-size6.size5 {
  font-size: 0.9em;
}
.katex .sizing.reset-size6.size6,
.katex .fontsize-ensurer.reset-size6.size6 {
  font-size: 1em;
}
.katex .sizing.reset-size6.size7,
.katex .fontsize-ensurer.reset-size6.size7 {
  font-size: 1.2em;
}
.katex .sizing.reset-size6.size8,
.katex .fontsize-ensurer.reset-size6.size8 {
  font-size: 1.44em;
}
.katex .sizing.reset-size6.size9,
.katex .fontsize-ensurer.reset-size6.size9 {
  font-size: 1.728em;
}
.katex .sizing.reset-size6.size10,
.katex .fontsize-ensurer.reset-size6.size10 {
  font-size: 2.074em;
}
.katex .sizing.reset-size6.size11,
.katex .fontsize-ensurer.reset-size6.size11 {
  font-size: 2.488em;
}
.katex .sizing.reset-size7.size1,
.katex .fontsize-ensurer.reset-size7.size1 {
  font-size: 0.41666667em;
}
.katex .sizing.reset-size7.size2,
.katex .fontsize-ensurer.reset-size7.size2 {
  font-size: 0.5em;
}
.katex .sizing.reset-size7.size3,
.katex .fontsize-ensurer.reset-size7.size3 {
  font-size: 0.58333333em;
}
.katex .sizing.reset-size7.size4,
.katex .fontsize-ensurer.reset-size7.size4 {
  font-size: 0.66666667em;
}
.katex .sizing.reset-size7.size5,
.katex .fontsize-ensurer.reset-size7.size5 {
  font-size: 0.75em;
}
.katex .sizing.reset-size7.size6,
.katex .fontsize-ensurer.reset-size7.size6 {
  font-size: 0.83333333em;
}
.katex .sizing.reset-size7.size7,
.katex .fontsize-ensurer.reset-size7.size7 {
  font-size: 1em;
}
.katex .sizing.reset-size7.size8,
.katex .fontsize-ensurer.reset-size7.size8 {
  font-size: 1.2em;
}
.katex .sizing.reset-size7.size9,
.katex .fontsize-ensurer.reset-size7.size9 {
  font-size: 1.44em;
}
.katex .sizing.reset-size7.size10,
.katex .fontsize-ensurer.reset-size7.size10 {
  font-size: 1.72833333em;
}
.katex .sizing.reset-size7.size11,
.katex .fontsize-ensurer.reset-size7.size11 {
  font-size: 2.07333333em;
}
.katex .sizing.reset-size8.size1,
.katex .fontsize-ensurer.reset-size8.size1 {
  font-size: 0.34722222em;
}
.katex .sizing.reset-size8.size2,
.katex .fontsize-ensurer.reset-size8.size2 {
  font-size: 0.41666667em;
}
.katex .sizing.reset-size8.size3,
.katex .fontsize-ensurer.reset-size8.size3 {
  font-size: 0.48611111em;
}
.katex .sizing.reset-size8.size4,
.katex .fontsize-ensurer.reset-size8.size4 {
  font-size: 0.55555556em;
}
.katex .sizing.reset-size8.size5,
.katex .fontsize-ensurer.reset-size8.size5 {
  font-size: 0.625em;
}
.katex .sizing.reset-size8.size6,
.katex .fontsize-ensurer.reset-size8.size6 {
  font-size: 0.69444444em;
}
.katex .sizing.reset-size8.size7,
.katex .fontsize-ensurer.reset-size8.size7 {
  font-size: 0.83333333em;
}
.katex .sizing.reset-size8.size8,
.katex .fontsize-ensurer.reset-size8.size8 {
  font-size: 1em;
}
.katex .sizing.reset-size8.size9,
.katex .fontsize-ensurer.reset-size8.size9 {
  font-size: 1.2em;
}
.katex .sizing.reset-size8.size10,
.katex .fontsize-ensurer.reset-size8.size10 {
  font-size: 1.44027778em;
}
.katex .sizing.reset-size8.size11,
.katex .fontsize-ensurer.reset-size8.size11 {
  font-size: 1.72777778em;
}
.katex .sizing.reset-size9.size1,
.katex .fontsize-ensurer.reset-size9.size1 {
  font-size: 0.28935185em;
}
.katex .sizing.reset-size9.size2,
.katex .fontsize-ensurer.reset-size9.size2 {
  font-size: 0.34722222em;
}
.katex .sizing.reset-size9.size3,
.katex .fontsize-ensurer.reset-size9.size3 {
  font-size: 0.40509259em;
}
.katex .sizing.reset-size9.size4,
.katex .fontsize-ensurer.reset-size9.size4 {
  font-size: 0.46296296em;
}
.katex .sizing.reset-size9.size5,
.katex .fontsize-ensurer.reset-size9.size5 {
  font-size: 0.52083333em;
}
.katex .sizing.reset-size9.size6,
.katex .fontsize-ensurer.reset-size9.size6 {
  font-size: 0.5787037em;
}
.katex .sizing.reset-size9.size7,
.katex .fontsize-ensurer.reset-size9.size7 {
  font-size: 0.69444444em;
}
.katex .sizing.reset-size9.size8,
.katex .fontsize-ensurer.reset-size9.size8 {
  font-size: 0.83333333em;
}
.katex .sizing.reset-size9.size9,
.katex .fontsize-ensurer.reset-size9.size9 {
  font-size: 1em;
}
.katex .sizing.reset-size9.size10,
.katex .fontsize-ensurer.reset-size9.size10 {
  font-size: 1.20023148em;
}
.katex .sizing.reset-size9.size11,
.katex .fontsize-ensurer.reset-size9.size11 {
  font-size: 1.43981481em;
}
.katex .sizing.reset-size10.size1,
.katex .fontsize-ensurer.reset-size10.size1 {
  font-size: 0.24108004em;
}
.katex .sizing.reset-size10.size2,
.katex .fontsize-ensurer.reset-size10.size2 {
  font-size: 0.28929605em;
}
.katex .sizing.reset-size10.size3,
.katex .fontsize-ensurer.reset-size10.size3 {
  font-size: 0.33751205em;
}
.katex .sizing.reset-size10.size4,
.katex .fontsize-ensurer.reset-size10.size4 {
  font-size: 0.38572806em;
}
.katex .sizing.reset-size10.size5,
.katex .fontsize-ensurer.reset-size10.size5 {
  font-size: 0.43394407em;
}
.katex .sizing.reset-size10.size6,
.katex .fontsize-ensurer.reset-size10.size6 {
  font-size: 0.48216008em;
}
.katex .sizing.reset-size10.size7,
.katex .fontsize-ensurer.reset-size10.size7 {
  font-size: 0.57859209em;
}
.katex .sizing.reset-size10.size8,
.katex .fontsize-ensurer.reset-size10.size8 {
  font-size: 0.69431051em;
}
.katex .sizing.reset-size10.size9,
.katex .fontsize-ensurer.reset-size10.size9 {
  font-size: 0.83317261em;
}
.katex .sizing.reset-size10.size10,
.katex .fontsize-ensurer.reset-size10.size10 {
  font-size: 1em;
}
.katex .sizing.reset-size10.size11,
.katex .fontsize-ensurer.reset-size10.size11 {
  font-size: 1.19961427em;
}
.katex .sizing.reset-size11.size1,
.katex .fontsize-ensurer.reset-size11.size1 {
  font-size: 0.20096463em;
}
.katex .sizing.reset-size11.size2,
.katex .fontsize-ensurer.reset-size11.size2 {
  font-size: 0.24115756em;
}
.katex .sizing.reset-size11.size3,
.katex .fontsize-ensurer.reset-size11.size3 {
  font-size: 0.28135048em;
}
.katex .sizing.reset-size11.size4,
.katex .fontsize-ensurer.reset-size11.size4 {
  font-size: 0.32154341em;
}
.katex .sizing.reset-size11.size5,
.katex .fontsize-ensurer.reset-size11.size5 {
  font-size: 0.36173633em;
}
.katex .sizing.reset-size11.size6,
.katex .fontsize-ensurer.reset-size11.size6 {
  font-size: 0.40192926em;
}
.katex .sizing.reset-size11.size7,
.katex .fontsize-ensurer.reset-size11.size7 {
  font-size: 0.48231511em;
}
.katex .sizing.reset-size11.size8,
.katex .fontsize-ensurer.reset-size11.size8 {
  font-size: 0.57877814em;
}
.katex .sizing.reset-size11.size9,
.katex .fontsize-ensurer.reset-size11.size9 {
  font-size: 0.69453376em;
}
.katex .sizing.reset-size11.size10,
.katex .fontsize-ensurer.reset-size11.size10 {
  font-size: 0.83360129em;
}
.katex .sizing.reset-size11.size11,
.katex .fontsize-ensurer.reset-size11.size11 {
  font-size: 1em;
}
.katex .delimsizing.size1 {
  font-family: KaTeX_Size1;
}
.katex .delimsizing.size2 {
  font-family: KaTeX_Size2;
}
.katex .delimsizing.size3 {
  font-family: KaTeX_Size3;
}
.katex .delimsizing.size4 {
  font-family: KaTeX_Size4;
}
.katex .delimsizing.mult .delim-size1 > .katex-span {
  font-family: KaTeX_Size1;
}
.katex .delimsizing.mult .delim-size4 > .katex-span {
  font-family: KaTeX_Size4;
}
.katex .nulldelimiter {
  display: inline-block;
  width: 0.12em;
}
.katex .delimcenter {
  position: relative;
}
.katex .op-symbol {
  position: relative;
}
.katex .op-symbol.small-op {
  font-family: KaTeX_Size1;
}
.katex .op-symbol.large-op {
  font-family: KaTeX_Size2;
}
.katex .op-limits > .vlist-t {
  text-align: center;
}
.katex .accent > .vlist-t {
  text-align: center;
}
.katex .accent .accent-body {
  position: relative;
}
.katex .accent .accent-body:not(.accent-full) {
  width: 0;
}
.katex .overlay {
  display: block;
}
.katex .mtable .vertical-separator {
  display: inline-block;
  min-width: 1px;
}
.katex .mtable .arraycolsep {
  display: inline-block;
}
.katex .mtable .col-align-c > .vlist-t {
  text-align: center;
}
.katex .mtable .col-align-l > .vlist-t {
  text-align: left;
}
.katex .mtable .col-align-r > .vlist-t {
  text-align: right;
}
.katex .svg-align {
  text-align: left;
}
.katex .katex-svg {
  display: block;
  position: absolute;
  width: 100%;
  height: inherit;
  fill: currentColor;
  stroke: currentColor;
  fill-rule: nonzero;
  fill-opacity: 1;
  stroke-width: 1;
  stroke-linecap: butt;
  stroke-linejoin: miter;
  stroke-miterlimit: 4;
  stroke-dasharray: none;
  stroke-dashoffset: 0;
  stroke-opacity: 1;
}
.katex .katex-svg path {
  stroke: none;
}
.katex .katex-svg {
  border-style: none;
  min-width: 0;
  min-height: 0;
  max-width: none;
  max-height: none;
}
.katex .stretchy {
  width: 100%;
  display: block;
  position: relative;
  overflow: hidden;
}
.katex .stretchy::before,
.katex .stretchy::after {
  content: "";
}
.katex .hide-tail {
  width: 100%;
  position: relative;
  overflow: hidden;
}
.katex .halfarrow-left {
  position: absolute;
  left: 0;
  width: 50.2%;
  overflow: hidden;
}
.katex .halfarrow-right {
  position: absolute;
  right: 0;
  width: 50.2%;
  overflow: hidden;
}
.katex .brace-left {
  position: absolute;
  left: 0;
  width: 25.1%;
  overflow: hidden;
}
.katex .brace-center {
  position: absolute;
  left: 25%;
  width: 50%;
  overflow: hidden;
}
.katex .brace-right {
  position: absolute;
  right: 0;
  width: 25.1%;
  overflow: hidden;
}
.katex .x-arrow-pad {
  padding: 0 0.5em;
}
.katex .cd-arrow-pad {
  padding: 0 0.55556em 0 0.27778em;
}
.katex .x-arrow,
.katex .mover,
.katex .munder {
  text-align: center;
}
.katex .boxpad {
  padding: 0 0.3em;
}
.katex .fbox,
.katex .fcolorbox {
  box-sizing: border-box;
  border: 0.04em solid;
}
.katex .cancel-pad {
  padding: 0 0.2em;
}
.katex .cancel-lap {
  margin-left: -0.2em;
  margin-right: -0.2em;
}
.katex .sout {
  border-bottom-style: solid;
  border-bottom-width: 0.08em;
}
.katex .angl {
  box-sizing: border-box;
  border-top: 0.049em solid;
  border-right: 0.049em solid;
  margin-right: 0.03889em;
}
.katex .anglpad {
  padding: 0 0.03889em;
}
.katex .eqn-num::before {
  counter-increment: katexEqnNo;
  content: "(" counter(katexEqnNo) ")";
}
.katex .mml-eqn-num::before {
  counter-increment: mmlEqnNo;
  content: "(" counter(mmlEqnNo) ")";
}
.katex .mtr-glue {
  width: 50%;
}
.katex .cd-vert-arrow {
  display: inline-block;
  position: relative;
}
.katex .cd-label-left {
  display: inline-block;
  position: absolute;
  right: calc(50% + 0.3em);
  text-align: left;
}
.katex .cd-label-right {
  display: inline-block;
  position: absolute;
  left: calc(50% + 0.3em);
  text-align: right;
}
.katex-display {
  display: block;
  margin: 1em 0;
  text-align: center;
}
.katex-display > .katex {
  display: block;
  text-align: center;
  white-space: nowrap;
}
.katex-display > .katex > .katex-html {
  display: block;
  position: relative;
}
.katex-display > .katex > .katex-html > .tag {
  position: absolute;
  right: 0;
}
.katex-display.leqno > .katex > .katex-html > .tag {
  left: 0;
  right: auto;
}
.katex-display.fleqn > .katex {
  text-align: left;
  padding-left: 2em;
}
</style>