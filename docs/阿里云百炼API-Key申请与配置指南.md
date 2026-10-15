# 阿里云百炼 API Key 申请与配置指南

本指南供**在自己电脑本地部署 IntelliTutor 的参与者**使用。完成后，API Key 仅保存在该电脑项目根目录的 `.env` 文件中，用于 Basic Tutor、Full Tutor 和 Direct LLM 的模型调用。

请勿把 API Key 截图、发送给他人、填写进问卷，或上传到 GitHub、Gitee 等代码仓库。

## 1. 开始前了解

- 需要一个可登录阿里云控制台的账号和稳定网络。
- 本实验使用阿里云百炼的 OpenAI 兼容接口；不需要安装 Python、Node.js 或本地大模型。
- 请使用**华北 2（北京）**地域：新人免费额度仅适用于该地域，且 API Key、可用模型和接入地址均与地域绑定，不能跨地域混用。
- 百炼首次开通后会自动发放新人免费额度，无需手动领取；官方说明该额度通常自开通/模型发布/模型申请通过中较晚的时间起计算，有效期为 90 天。刚开通后额度显示可能有延迟，通常两小时内生效。

官方参考：

- [新人免费额度与用完即停规则](https://help.aliyun.com/zh/model-studio/new-free-quota)
- [获取与配置阿里云百炼 API Key](https://help.aliyun.com/zh/model-studio/get-api-key/)

## 2. 开通百炼并确认免费额度

1. 用浏览器登录阿里云账号。
2. 打开[阿里云百炼控制台（华北 2／北京）](https://bailian.console.aliyun.com/?region=cn-beijing)。
3. 第一次进入时，阅读并同意服务协议，完成百炼开通。
4. 在控制台确认右上角地域为“华北 2（北京）”。
5. 打开左边“费用用量—免费额度”页面，查看目标模型是否显示剩余额度和到期时间。

如果刚开通但暂未看到额度，请等待一段时间后刷新。不要为了重新领取额度重复注册账号：同一实名认证主体不会再次获得新人额度。

## 3. 创建通用 API Key

1. 在百炼控制台进入“API Key”或“密钥管理”页面，并再次确认地域为“华北 2（北京）”。
2. 单击“创建 API Key”。
3. 对个人独立部署，按页面默认选择：
   - 归属账号：当前阿里云主账号；
   - 归属业务空间：`默认业务空间`；
   - 权限：若页面提供模型权限范围，请确保允许本实验配置中指定的文本模型。
4. 创建完成后，单击新 Key 旁的复制图标，立即粘贴到安全的临时位置。

请创建**通用 API Key**，不要使用 Token Plan / Coding Plan 专属 Key。官方说明专属 Key 不消耗新人免费额度，可能直接产生按量费用。

> API Key 有权以你的阿里云账号调用模型。若复制后页面不再显示完整 Key，请重新创建一个新 Key；不要把 Key 发给研究人员以外的任何人。

## 4. 写入 IntelliTutor 的 `.env`

先按[本地部署手册](本地部署手册.md)完成项目解压、Docker 安装，并在项目根目录创建 `.env`：

```powershell
Copy-Item .env.example .env
notepad .env
```

在 `.env` 中找到下列 `QWEN_` 配置。保留或填写如下值；只把第 2 行的内容替换为你刚复制的 API Key：

```dotenv
QWEN_API_URL=https://dashscope.aliyuncs.com/compatible-mode/v1/
QWEN_API_KEY=粘贴你自己的百炼通用APIKey
QWEN_MODEL_INSTRUCT=qwen-plus
QWEN_MODEL_LARGE=qwen-plus
QWEN_MODEL_SMALL=qwen3-8b
QWEN_MODEL_VL=
QWEN_MODEL_VLFAST=
QWEN_MODEL_REASONING=qwen-plus
```

不要修改其他服务商的模型名来“凑齐”配置，也不要把 API Key 放入前端代码。为了保证实验条件可比，若上述任一模型在百炼控制台不可用、没有免费额度或提示无权限，请先联系研究人员确认统一替代模型，不要自行换成其他模型。

保存 `.env` 后，在项目根目录执行：

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

前一条命令无输出表示配置格式可被 Docker Compose 读取；它不会验证 Key 是否有效。`python`、`backend`、`web` 显示 `healthy` 后，打开 <http://localhost/> 开始实验。

## 5. 防止免费额度耗尽后意外扣费

1. 在百炼控制台的“免费额度”页面，找到本实验使用的模型。
2. 为这些模型开启“免费额度用完即停”。
3. 如账号已实名认证，同时在阿里云费用中心设置消费预警。

开启“用完即停”后，额度耗尽时百炼会拒绝新的调用（通常为 HTTP 403 / `AllocationQuota.FreeTierOnly`），实验中的 AI 页面将不能继续。这能避免超额扣费；若出现该错误，请停止实验并联系研究人员，不要关闭保护开关后继续调用。

未实名认证账号的免费额度耗尽后通常无法继续调用；已实名认证账号若未开启该保护，免费额度耗尽后可能自动进入按量付费。请在开始实验前确认额度充足。

## 6. 常见问题

### Q：容器已经 healthy，为什么 AI 讲解仍报错？

容器健康只说明服务启动成功，不代表 Key、模型名、账号余额或免费额度有效。检查：

1. `.env` 中 `QWEN_API_KEY` 是否完整粘贴，前后没有引号、空格或换行；
2. 百炼控制台地域是否为华北 2（北京）；
3. 对应模型是否有可用额度、权限和可调用状态；
4. 阿里云账号是否欠费；
5. 是否开启“用完即停”且额度已经耗尽。

仍无法解决时，保留发生时间和以下日志，但不要把 `.env` 或 API Key 一并发送：

```powershell
docker compose logs --since 15m python backend
```

### Q：实验结束后可以删除 API Key 吗？

可以。确认 Excel 数据已经下载并提交后，可在百炼控制台删除该实验专用 Key；删除后本地系统将无法继续进行 AI 模式调用，也不会再通过该 Key 产生费用。

### Q：一个 Key 能用于多个模型吗？

可以。百炼的通用 API Key 不绑定单一模型；实际调用哪个模型由 `.env` 中的模型变量决定。不同模型的新人免费额度彼此独立，某个模型额度用尽时不会自动切换到另一个模型。
