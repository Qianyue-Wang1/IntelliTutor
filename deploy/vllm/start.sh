#!/usr/bin/env bash
set -Eeuo pipefail
# Source a trusted GPU-server environment file before running; never load application secrets here.
: "${VLLM_MODEL_PATH:?Set VLLM_MODEL_PATH to the local Qwen3-32B directory}"
: "${VLLM_API_KEY:?Set VLLM_API_KEY}"
: "${VLLM_VERSION:?Set VLLM_VERSION to the exact version validated on this GPU server}"
[[ -d "$VLLM_MODEL_PATH" ]] || { echo 'Model directory does not exist.' >&2; exit 1; }
command -v nvidia-smi >/dev/null
command -v vllm >/dev/null
installed_version="$(python -c 'import importlib.metadata; print(importlib.metadata.version("vllm"))')"
[[ "$installed_version" == "$VLLM_VERSION" ]] || { echo 'Installed vLLM version differs from VLLM_VERSION.' >&2; exit 1; }
export CUDA_VISIBLE_DEVICES="${CUDA_VISIBLE_DEVICES:-0,1,2}"
IFS=',' read -r -a devices <<< "$CUDA_VISIBLE_DEVICES"
[[ "${#devices[@]}" == 3 ]] || { echo 'Exactly three distinct GPUs are required.' >&2; exit 1; }
[[ "${devices[0]}" != "${devices[1]}" && "${devices[0]}" != "${devices[2]}" && "${devices[1]}" != "${devices[2]}" ]] || exit 1
for device in "${devices[@]}"; do
  nvidia-smi -i "$device" --query-gpu=name,memory.total --format=csv,noheader
 done
exec vllm serve "$VLLM_MODEL_PATH" \
  --served-model-name "${VLLM_MODEL:-qwen3-32b}" \
  --host "${VLLM_HOST:-0.0.0.0}" --port "${VLLM_PORT:-8001}" \
  --api-key "$VLLM_API_KEY" --dtype float16 \
  --tensor-parallel-size 1 --data-parallel-size 3 \
  --gpu-memory-utilization "${VLLM_GPU_MEMORY_UTILIZATION:-0.90}" \
  --max-model-len "${VLLM_MAX_MODEL_LEN:-16384}" \
  --max-num-seqs "${VLLM_MAX_NUM_SEQS:-8}" \
  --max-num-batched-tokens "${VLLM_MAX_BATCHED_TOKENS:-4096}" \
  --enable-chunked-prefill --enable-prefix-caching \
  --default-chat-template-kwargs '{"enable_thinking": false}'
