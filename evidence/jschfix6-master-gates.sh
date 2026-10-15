#!/usr/bin/env bash
set -eu
base=/home/u/lanes/util20261014/out
work="$base/jschfix6-reversal-work"
mkdir -p "$work/src/com/jcraft/jsch" "$work/classes"
git show 48ad5294:src/main/java/com/jcraft/jsch/Session.java > "$work/src/com/jcraft/jsch/Session.java"
probe_cp=$(python3 - <<'PY'
import xml.etree.ElementTree as E
r=E.parse('target/surefire-reports/TEST-com.jcraft.jsch.FailedOpenReplyTest.xml').getroot()
print(next(p.get('value') for p in r.findall('properties/property') if p.get('name')=='java.class.path'))
PY
)
probe_cp="$probe_cp:/home/u/.m2/repository/org/junit/platform/junit-platform-launcher/1.14.4/junit-platform-launcher-1.14.4.jar"
console=/home/u/.cache/coursier/v1/https/maven-central.storage-download.googleapis.com/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar
[ -f "$console" ]
javac -cp "$probe_cp" -d "$work/classes" "$work/src/com/jcraft/jsch/Session.java" > "$base/jschfix6-reversal-compile.log" 2>&1
set +e
timeout 90 java -cp "$work/classes:$probe_cp:$console" org.junit.platform.console.ConsoleLauncher execute --select-class com.jcraft.jsch.FailedOpenReplyTest --reports-dir "$base/jschfix6-reversal-results" --disable-banner --details=summary > "$base/jschfix6-reversal.log" 2>&1
rc=$?
set -e
[ "$rc" -eq 1 ]
python3 "$base/jschfix6-gate.py" reversal 9 red "$base/jschfix6-reversal-results"
rm -rf "$work"
rm -rf target/surefire-reports
./mvnw -B test > "$base/jschfix6-master-suite.log" 2>&1
python3 "$base/jschfix6-gate.py" master-suite 534 green
set +e
./mvnw -B impsort:check formatter:validate > "$base/jschfix6-global-validation.log" 2>&1
format_rc=$?
set -e
[ "$format_rc" -ne 0 ]
python3 - <<'PY'
from pathlib import Path
s=Path('/home/u/lanes/util20261014/out/jschfix6-global-validation.log').read_text()
assert 'ChannelLateOpenTest.java' in s and 'has not been previously formatted' in s
PY
./mvnw -B formatter:validate '-Dformatter.includes=**/Session.java,**/FailedOpenReplyTest.java' > "$base/jschfix6-changed-validation.log" 2>&1
git diff --exit-code 48ad5294 -- src/test/java/com/jcraft/jsch/ChannelLateOpenTest.java src/test/java/com/jcraft/jsch/ChannelLateOpenRekeyTest.java src/test/java/com/jcraft/jsch/PendingCloseBoundTest.java
