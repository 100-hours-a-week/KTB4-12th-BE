#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
AI_DIR="$(cd -- "$SCRIPT_DIR/../../../KTB4-12th-AI-mq-consumer/profiling" && pwd)"
export UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/gift-profiling-uv-cache}"
export PYTHONPATH="$AI_DIR/src${PYTHONPATH:+:$PYTHONPATH}"
export PROFILING_BENCH_CALLER_DIR="$PWD"
cd "$AI_DIR"
exec uv run --frozen python "$SCRIPT_DIR/bench.py" "$@"
