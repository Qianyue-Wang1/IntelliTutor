# Qwen3-32B 三卡部署与并发验证

目标硬件为同一台 Linux 服务器上的三张 A800 80GB。每卡加载一份 Qwen3-32B FP16，vLLM 使用 DP=3、TP=1，统一提供一个 OpenAI 兼容接口。Direct、Basic、Full 共用副本池，教学状态仍按实验记录隔离。应用不训练或加载 LoRA。

## 1. GPU 服务器

准备完整的 Qwen3-32B 模型目录和支持内置 DP、`--default-chat-template-kwargs` 的 vLLM 环境。在该环境中验证版本后，固定同一版本用于实验。脚本检查安装版本与 `VLLM_VERSION` 完全一致，不会自动升级。

```bash
export VLLM_MODEL_PATH=/实际模型目录/Qwen3-32B
export VLLM_API_KEY=替换为实际的本地服务密钥
# 用在服务器上经过验证的精确版本号替换下方值。
export VLLM_VERSION=已验证的精确版本号
export CUDA_VISIBLE_DEVICES=0,1,2
bash deploy/vllm/start.sh
```

初始参数：每卡 GPU 显存预算 0.90、上下文总长 16384、max-num-seqs=8、批处理 token 预算 4096。开启分块预填充和前缀缓存，统一关闭 Qwen3 思考模式。输出质量应在正式实验前验证。

启动日志必须显示三个推理引擎。记录每卡可用 KV Cache 容量；序列上限 8 不保证能同时运行 8 个完整 16K 上下文。权重约占 61 GiB，运行缓冲与 KV Cache 仍需额外显存。遇到频繁抢占先降低并发或实际上下文；有余量时再测试 token 预算 8192 和序列上限 12/16。

```bash
curl http://127.0.0.1:8001/v1/models -H "Authorization: Bearer $VLLM_API_KEY"
nvidia-smi
```

模型名应为 `qwen3-32b`。API 服务应放在应用可访问的受控网络中。

## 2. 应用机器

在项目根目录 `.env` 配置：

```dotenv
VLLM_API_URL=http://GPU服务器IP:8001/v1
VLLM_API_KEY=与GPU服务器一致的密钥
VLLM_MODEL=qwen3-32b
VLLM_REQUEST_TIMEOUT_SECONDS=180
VLLM_MAX_OUTPUT_TOKENS=4096
VLLM_MAX_OUTPUT_TOKENS_LARGE=4096
VLLM_MAX_OUTPUT_TOKENS_SMALL=1024
VLLM_MAX_OUTPUT_TOKENS_FAST=1024
PYTHON_BLOCKING_WORKERS=12
PYTHON_MAX_OPERATIONS=12
PYTHON_OPERATION_TIMEOUT_SECONDS=600
INFERENCE_CONCURRENCY=6
INFERENCE_MAX_CONCURRENCY=12
INFERENCE_PREFETCH=1
IMAGE_INPUT_ENABLED=false
```

若模型与 Docker 在同一宿主机，地址使用 `http://host.docker.internal:8001/v1`；直接运行 Python 且模型同机时使用 `http://127.0.0.1:8001/v1`，配置放在 `python/.env`。容器中的 localhost 不指向宿主机。

文本任务默认统一使用 `VLLM_MODEL`；如需覆盖，可配置 `VLLM_MODEL_INSTRUCT/LARGE/SMALL/FAST/REASONING`，并在 Compose 的 Python environment 中显式传入。当前部署不配置这些覆盖，三个教学模式使用相同权重。

Qwen3-32B 是文本模型。图片接口在未配置 `VLLM_MODEL_VL` 时返回 503，不会转向云端或文本模型。旧 `QWEN_*`、`OPENROUTER_*`、`CEREBRAS_*` 等变量不再使用。

应用升级前等待现有推理任务完成，再停止 backend，确认旧队列已排空。新版 Redis 会话锁的值改为任务 ID，旧格式排队消息不能由新版消费者继续执行；不要在有旧推理消息的情况下直接滚动升级。保留数据库和数据卷。

```bash
docker compose config --quiet
docker compose up -d --build python backend
docker compose logs --since 5m python backend
```

保持 Python 单进程，不能增加 Uvicorn workers 或直接扩容 Python 容器。当前 session 和互斥锁保存在进程内存；扩展副本的是 vLLM。

## 3. 并发与超时

Java 消费者初始 6 个，最多 12 个，单消费者预取 1 条。Python 最多处理 12 个业务操作，同步操作在线程池执行。相同 session 串行，不同 session 可以并行。快照复制发生在释放会话锁前。初始化尚未完成时，读取或修改该会话的请求会等待初始化结束；失败或取消的初始化会清理临时会话。

单次模型请求预算 180 秒，完整业务预算 600 秒（包括 Python 准入等待和会话锁等待）。Java 代理读取超时沿用 720 秒；Redis 锁 3600 秒覆盖现有队列 TTL 1800 秒与处理时间。锁只由对应任务原子释放。增加任一时间预算前需重新核对整条链路。

业务操作超时后，同步线程可能需要完成正在执行的 IO；Python 会等线程结束才释放会话锁。后续模型请求会检查剩余业务预算。这里的线程取消不是强制终止；极端网络缓慢时实际耗时可能超过预算，应关注代理超时和排队情况。

缺少 vLLM 地址或模型配置时 Python 启动失败。健康接口只检查存活，不验证 GPU 服务可用性。自动网络重试关闭；已有结构化输出修复流程仍按原规则执行。被长度上限截断的回复按失败处理，不将残缺 JSON 当成有效数据。

## 4. 模型层压测

使用安装了 OpenAI Python SDK 的环境运行：

```bash
export VLLM_API_URL=http://127.0.0.1:8001/v1
python deploy/vllm/benchmark.py --concurrency 3 6 10 15 --requests 30 --output /tmp/vllm-short.json
python deploy/vllm/benchmark.py --concurrency 3 6 10 --requests 30 --repeat 50 --output /tmp/vllm-long.json
```

`--repeat` 只制造更长的合成输入，不代表真实 Full 上下文；正式调优还要使用匿名化的实际提示词长度分布。脚本记录非流式请求 P50/P95 完成时间、成功率和总输出 tokens/s，不测首 token 延迟。输出达到 max-tokens 是压测的生成长度限制；业务层另行验证是否截断。

压测时用 `nvidia-smi dmon` 和 vLLM `/metrics` 观察三卡活动、各副本排队、KV Cache、前缀缓存与抢占。先找可接受的 P95，再比较吞吐量；在线用户阅读页面时显卡空闲属于正常情况。

## 5. 完整业务验收

分别用 5 个、10 个独立实验记录混合运行 Direct、Basic、Full：

- Direct 初始讲解与追问正常；失败不写入空消息。
- Basic/Full 的逐步讲解、答案判定、自由研讨可同时执行。
- Full 第一轮不读取历史状态，后续轮次读取对应实验的状态。
- 同一 session 重复提交不发生重复推进或并发画像覆盖。
- 一个用户模型调用变慢时，其他用户与 `/healthz` 仍能响应。
- 输出截断、模型服务不可达和操作超时会使任务明确失败。
- 快照恢复、刷新回看、实验数据导出保持正常。

记录业务任务 P95、错误率、JSON 修复次数和 GPU 指标。仅当新参数改善目标指标且回归通过时，才调整并发和 batch 预算。本仓库在无 A800 环境的机器上不能确认 GPU 容量或真实性能。

## 6. 本地回归

```bash
cd python
python -m unittest discover -s tests
```

```bash
cd backend
mvn test
```

另执行 `bash -n deploy/deploy.sh deploy/vllm/start.sh` 和 `docker compose config --quiet`。不执行 `docker compose down -v`。
