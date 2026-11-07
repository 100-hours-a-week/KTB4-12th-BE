#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

: "${PROFILING_RECOVERY_ACTION:?Set PROFILING_RECOVERY_ACTION}"

exec ./gradlew :main-backend:profilingRecovery --console=plain
