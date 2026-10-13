# IntelliTutor 微信小程序

基于 uni-app Vue 3、TypeScript 和 Vite 的微信小程序首版。它复用现有 Java API、JWT 与 `202 + task_id` 异步协议，不包含微信 `openid` 登录，也不新增后端接口。

## 已实现范围

- 邮箱/手机号登录注册、验证码倒计时和登录状态恢复。
- 文字提问、相机/相册单图提问、5 MB 原图校验及 2 MB 压缩上限。
- 分步讲解：知识点、步骤、选项、错误分析、总结及 `sessionstate` 恢复。
- 启发研讨：折叠步骤树、消息流、个性化触摸行为摘要、完成总结及状态恢复。
- 会话历史、下拉刷新、打开与二次确认删除。
- 个人统计、头像、昵称和退出登录。
- `mp-html 2.5.2` 的 Markdown/LaTeX 固定构建，位于 `pages-session` 分包。

知识图谱、图形重建、图交互对话、联系方式/密码修改、微信登录和多语言不在首版范围内。

## 运行要求

- Node.js 20 LTS（`package.json` 限定 `>=20 <23`）。
- 微信开发者工具及可用的小程序 AppID。
- 已启动并可从开发设备访问的 Java、PostgreSQL、Redis、RabbitMQ 和 Python 服务。

```bash
npm install
npm run vendor:mp-html
npm run type-check
npm test
npm run dev:mp-weixin
```

生产构建：

```bash
npm run build:mp-weixin
```

构建结果在 `dist/build/mp-weixin`，使用微信开发者工具导入该目录。

暂以网页方式发布时，可构建 H5：

```bash
VITE_API_BASE_URL=/api npm run build:h5
```

构建结果在 `dist/build/h5`，公共资源路径为 `/h5/`。仓库根目录的 Docker
部署会自动构建并发布到 `https://你的域名/h5/`，详细步骤见
[`docs/docker-deployment.md`](../docs/docker-deployment.md)。

## 配置

1. 把 `src/manifest.json` 中 `mp-weixin.appid` 的空值替换为部署用 AppID。
2. 将 `.env.development` 中的示例地址替换为开发电脑的局域网地址。真机不能访问 `localhost` 或 `127.0.0.1`。
3. 部署前必须把 `.env.production` 中 `https://api.example.com/api` 替换为真实 API 地址。
4. 生产 API 必须使用 HTTPS，并在微信公众平台配置为 `request` 合法域名；服务端证书链需受微信支持。
5. AppID、合法域名、隐私保护指引和相机/相册用途声明由部署环境维护，仓库不保存密钥或用户凭证。

生产构建会拒绝空地址或非 HTTPS 的 `VITE_API_BASE_URL`。

## 协议与恢复机制

所有 API 类型集中在 `src/api/types.ts`。请求层基于 `uni.request`：

- 自动附加 `Authorization: Bearer <token>`。
- 接受业务码 `200` 与 `202`。
- `4010/4011/4012` 触发单例 Token 刷新，并将原请求最多重放一次。
- AI 请求每秒轮询 `/v1/task/{taskId}`，总超时 300 秒，网络错误按 1、2、4 秒重试。
- `task_id/session_id/task_type/started_at` 写入本地缓存；页面切后台或小程序重启不会重复创建 AI 任务。
- 最终结果的 `session_id` 始终使用 Java `202` 响应中的 ID，不信任 Python 内部会话 ID。

仅 Token、用户摘要和待处理任务会持久化；密码、验证码、Base64 图片及长会话内容不会写入本地缓存。

## 安全边界

- 用户资料接口的 `userId` 仅从 Pinia 登录 Store 读取，页面参数不能覆盖；后端 JWT 所有权校验仍是最终边界。
- 用户原始输入用 `<text>` 显示。
- AI 富文本在 Markdown 解析前保护公式/代码、转义原始 HTML、移除图片源、链接目标、引用式链接和可点击裸链接。
- LaTeX 的 URL、远程图片及 HTML 注入命令被删除；`mp-html` 禁止图片预览、图片菜单和复制链接。
- 富文本中的远程图片不渲染；题图只允许本机选择产生的 Data URL/微信临时路径。

## 自动化验收

```bash
npm run type-check
npm test
npm run build:mp-weixin
```

单元测试覆盖业务码、并发 Token 刷新与重放、任务成功/失败/网络重试/超时/恢复、Java 会话 ID 覆盖、图片大小与压缩、恶意 HTML/Markdown/LaTeX 过滤。

发布前还需在真实服务和至少一台 iOS、Android 设备上完成：登录注册、文字/图片题、两种学习模式、历史恢复、跨用户会话拒绝、弱网与切后台、相机授权、长公式、恶意富文本及退出后的路由保护。
