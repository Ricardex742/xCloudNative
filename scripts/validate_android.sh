#!/usr/bin/env bash
set -euo pipefail

gradle --no-daemon --max-workers=2 --stacktrace -Pkotlin.compiler.execution.strategy=in-process \
  -Pxcloud.lintAudit :app:verifyRuntimeArtifacts :app:testDebugUnitTest :app:lintDebug "$@"
python3 scripts/check_lint.py app/build/reports/lint-audit-debug.xml
python3 scripts/release_validation.py jvm app/build/test-results/testDebugUnitTest
