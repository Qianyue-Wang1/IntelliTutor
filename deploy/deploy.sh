#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ ! -f .env ]]; then
  echo "缺少 .env：请先执行 cp .env.example .env 并填写生产配置。" >&2
  exit 1
fi

if grep -q 'CHANGE_ME' .env; then
  echo ".env 仍包含 CHANGE_ME，请先替换全部基础设施密钥。" >&2
  exit 1
fi

if ! grep -Eq '^VLLM_API_URL=https?://.+/v1/?$' .env || ! grep -Eq '^VLLM_MODEL=.+$' .env; then
  echo ".env 必须配置 VLLM_API_URL（以 /v1 结尾）和 VLLM_MODEL。" >&2
  exit 1
fi

model_dir="$(sed -n 's/^MODEL_DIR=//p' .env | tail -n 1)"
mkdir -p "${model_dir:-./models}"

docker compose --env-file .env config --quiet
docker compose --env-file .env build --pull
docker compose --env-file .env up -d --remove-orphans
docker compose --env-file .env ps
