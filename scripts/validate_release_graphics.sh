#!/usr/bin/env bash
set -euo pipefail

report_dir=${1:?Informe o diretório dos relatórios gráficos.}
mkdir -p "$report_dir"
for suite in image_filter fsr1 image_treatment reconstruction image_lab motion_fence; do
  python3 "scripts/verify_$suite.py" | tee "$report_dir/$suite.log"
done
