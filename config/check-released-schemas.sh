#!/bin/sh
# Released Room schemas are frozen (DEC-14, DEC-88; testing.md § 9 `schema` job). Run from the repository root.
#
# The repository has no remote (local commits only), so the reference is not a merge base with origin/main but
# the commit that added the version to config/released-schema-versions.txt (release checklist M-20). The schema
# file of every listed version must exist and be identical in that commit, in HEAD and in the working tree.
# A version listed but not committed yet compares the working tree with HEAD.
set -u

list=config/released-schema-versions.txt
dir=app/schemas/com.doro.organizer.data.db.AppDatabase
failed=0

# The list is append-only (M-20): a version that was ever committed to it stays frozen even if its line is lost later.
listed=$(tr -d ' \t\r' < "$list")
for v in $(git log -p --no-textconv --format= -- "$list" | sed -n 's/^+[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p' | sort -u); do
  if ! printf '%s\n' "$listed" | grep -qx "$v"; then
    echo "released version removed from $list: $v"
    failed=1
  fi
done

while IFS= read -r line || [ -n "$line" ]; do
  v=$(printf '%s' "$line" | tr -d ' \t\r')
  case "$v" in '' | '#'*) continue ;; esac
  case "$v" in *[!0-9]*) echo "not a schema version in $list: $v"; failed=1; continue ;; esac
  f="$dir/$v.json"
  release=$(git log --reverse --format=%H -G "^[[:space:]]*$v[[:space:]]*\$" -- "$list" | head -n 1)
  base=${release:-HEAD}
  if ! git cat-file -e "$base:$f" 2>/dev/null; then
    echo "released schema not committed: $f"
    failed=1
  elif ! git diff --quiet "$base" HEAD -- "$f" || ! git diff --quiet "$base" -- "$f"; then
    echo "released schema changed: $f"
    failed=1
  fi
done < "$list"

exit "$failed"
