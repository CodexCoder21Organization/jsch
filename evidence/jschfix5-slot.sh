#!/bin/bash
set -eu
base=/home/u/lanes/util20261014/out
slot=''
end=$((SECONDS+600))
while [ "$SECONDS" -lt "$end" ]; do
  for n in 2 4 1; do
    if mkdir "$base/build-slot-$n" 2>/dev/null; then slot="$base/build-slot-$n"; break; fi
  done
  [ -n "$slot" ] && break
  sleep 30
done
[ -n "$slot" ] || { echo 'No local slot within ten minutes'; exit 124; }
printf 'jschfix5 %s\n' "$(date -u)" > "$slot/owner"
trap 'rm -f "$slot/owner"; rmdir "$slot"' EXIT
[ "$(df --output=avail -k / | tail -1)" -ge 6291456 ] || exit 125
export JAVA_HOME="${TASK_JAVA_HOME:-/home/u/.cache/coursier/arc/https/github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%252B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz/jdk-25.0.4.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
export LC_ALL=C.UTF-8
timeout --signal=TERM --kill-after=10 600 "$@"
