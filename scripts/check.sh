#!/usr/bin/env bash
# Builds and tests the pure-Kotlin core with nothing but kotlinc and a JDK.
# Usage: scripts/check.sh            (KOTLINC may point at a kotlinc binary)
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
OUT=build/check
rm -rf "$OUT" && mkdir -p "$OUT"

compile() { # name, classpath, source dirs...
  local name=$1 cp=$2; shift 2
  echo "compile $name"
  "$KOTLINC" -nowarn -jvm-target 17 ${cp:+-cp "$cp"} -d "$OUT/$name.jar" "$@" 2>&1 | grep -v '^Picked up' || true
  test -f "$OUT/$name.jar"
}
run() { # main class, classpath
  echo "test $1"
  java -cp "$2" "$1"
}

STD="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
compile testkit "" testkit/src/main/kotlin
compile formula "" formula/src/main/kotlin
compile formula-test "$OUT/formula.jar:$OUT/testkit.jar" formula/src/test/kotlin
run dev.afterdrop.formula.FormulaTestsKt "$OUT/formula-test.jar:$OUT/formula.jar:$OUT/testkit.jar:$STD"
if [ -d spec/src/main/kotlin ] && [ -n "$(find spec/src/main/kotlin -name '*.kt' | head -1)" ]; then
  compile spec "$OUT/formula.jar" spec/src/main/kotlin
  compile spec-test "$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar" spec/src/test/kotlin
  run dev.afterdrop.spec.SpecTestsKt "$OUT/spec-test.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar:$STD"
fi
compile engine "$OUT/spec.jar:$OUT/formula.jar" engine/src/main/kotlin
compile engine-test "$OUT/engine.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar" engine/src/test/kotlin
run dev.afterdrop.engine.EngineTestsKt "$OUT/engine-test.jar:$OUT/engine.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar:$STD"
compile composer "$OUT/spec.jar:$OUT/formula.jar" composer/src/main/kotlin
compile composer-test "$OUT/composer.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar" composer/src/test/kotlin
run dev.afterdrop.composer.ComposerTestsKt "$OUT/composer-test.jar:$OUT/composer.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar:$STD"
AI="$OUT/composer.jar:$OUT/spec.jar:$OUT/formula.jar"
compile ai "$AI" ai/src/main/kotlin
compile ai-test "$OUT/ai.jar:$AI:$OUT/testkit.jar" ai/src/test/kotlin
run dev.afterdrop.ai.AiTestsKt "$OUT/ai-test.jar:$OUT/ai.jar:$AI:$OUT/testkit.jar:$STD"
compile data "$OUT/spec.jar:$OUT/formula.jar" data/src/main/kotlin
compile data-test "$OUT/data.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar" data/src/test/kotlin
run dev.afterdrop.data.DataTestsKt "$OUT/data-test.jar:$OUT/data.jar:$OUT/spec.jar:$OUT/formula.jar:$OUT/testkit.jar:$STD"
echo "ALL CHECKS PASSED"
