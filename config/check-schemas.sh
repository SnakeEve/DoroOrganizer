#!/bin/sh
# The `schema` gate (testing.md § 9, DEC-14, DEC-88). Run from the repository root with a JDK on the path.
# Fails when the exported Room schema differs from the committed file, a new schema file is uncommitted,
# or a released schema version was changed (config/check-released-schemas.sh).
set -eu

# Compiling runs KSP, which exports the Room schema into app/schemas.
./gradlew --quiet :app:compileDebugKotlin

git diff --exit-code -- app/schemas
pending=$(git status --porcelain -- app/schemas)
if [ -n "$pending" ]; then
  echo "uncommitted schema files:"
  echo "$pending"
  exit 1
fi

sh config/check-released-schemas.sh
