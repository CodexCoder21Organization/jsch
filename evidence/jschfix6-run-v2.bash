#!/usr/bin/env bash
set -eu
slot=''
for attempt in $(seq 1 20); do
  for i in 1 2 4; do
    candidate=/home/u/lanes/util20261014/out/build-slot-$i
    if mkdir "$candidate" 2>/dev/null; then slot=$candidate; break; fi
  done
  [ -z "$slot" ] || break
  sleep 30
done
[ -n "$slot" ] || exit 70
printf 'jschfix6 %s\n' "$(date -u +%FT%TZ)" > "$slot/owner"
trap 'rm -f "$slot/owner"; rmdir "$slot"' EXIT
cd "${JSCH_DIR:-/home/u/lanes/util20261014/workspace/jschfix6}"
export LC_ALL=C.UTF-8
export JAVA_HOME
if [ -n "${JSCH_JAVA_HOME:-}" ]; then JAVA_HOME="$JSCH_JAVA_HOME"; else JAVA_HOME=$(coursier java-home --jvm "${JSCH_JVM:-25}"); fi
export PATH="$JAVA_HOME/bin:$PATH"
df -h /
[ "$(df --output=avail -k / | tail -1)" -ge 6291456 ] || exit 125
if [ "${1:-}" = --task-script ]; then shift; bash "$@"; else ./mvnw -B "$@"; fi
