#!/usr/bin/env bash
# The outbox package and DomainEventPublisher are deliberately copied into each
# publishing service (ADR-008, "Duplicated outbox implementation"). Fail when the
# copies differ in anything other than their package name, so a fix can't land in
# one copy only. The outbox tests are adapted per service and aren't checked.
set -euo pipefail

cd "$(dirname "$0")/.."

services=(patient doctor appointment)
reference=${services[0]}
src_dir() { echo "$1-service/src/main/java/com/healthtech/$1"; }
normalize() { sed "s/com\.healthtech\.$1\b/com.healthtech.SERVICE/g" "$2"; }

status=0
for svc in "${services[@]}"; do
  [ -d "$(src_dir "$svc")/outbox" ] || { echo "missing $(src_dir "$svc")/outbox"; status=1; }
done

# Union of file names across all copies, so a file added to one copy is caught too.
files=$(
  for svc in "${services[@]}"; do
    ls "$(src_dir "$svc")/outbox" 2>/dev/null | sed 's|^|outbox/|' || true
  done | sort -u
  echo event/DomainEventPublisher.java
)

for file in $files; do
  ref="$(src_dir "$reference")/$file"
  for svc in "${services[@]:1}"; do
    other="$(src_dir "$svc")/$file"
    if [ ! -f "$ref" ] || [ ! -f "$other" ]; then
      echo "$file exists in only some services"
      status=1
      break
    fi
    if ! diff -u --label "$reference/$file" --label "$svc/$file" \
        <(normalize "$reference" "$ref") <(normalize "$svc" "$other"); then
      status=1
    fi
  done
done

if [ "$status" -eq 0 ]; then
  echo "outbox and DomainEventPublisher copies are identical"
else
  echo "copies differ: apply the change to every service (ADR-008)" >&2
fi
exit "$status"
