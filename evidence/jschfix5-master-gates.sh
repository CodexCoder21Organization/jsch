#!/bin/bash
set -eu
base=/home/u/lanes/util20261014/out
clear_reports() { python3 -c 'from pathlib import Path; import shutil; p=Path("target/surefire-reports"); shutil.rmtree(p) if p.exists() else None'; }
./mvnw -B impsort:sort formatter:format '-Dformatter.includes=**/Session.java,**/PendingCloseBoundTest.java' '-Dimpsort.includes=**/Session.java,**/PendingCloseBoundTest.java' > "$base/jschfix5-final-format.log" 2>&1
git add src/test/java/com/jcraft/jsch/PendingCloseBoundTest.java
git commit -m 'Resume held peer messages through normal serialized handling' -m 'Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>'
cp src/main/java/com/jcraft/jsch/Session.java "$base/jschfix5-final-Session.java"
restore_product() { cp "$base/jschfix5-final-Session.java" src/main/java/com/jcraft/jsch/Session.java; }
trap restore_product EXIT
clear_reports
git show 1cb9816e:src/main/java/com/jcraft/jsch/Session.java > src/main/java/com/jcraft/jsch/Session.java
set +e
./mvnw -B -Dtest=PendingCloseBoundTest test > "$base/jschfix5-final-reversal.log" 2>&1
red_rc=$?
set -e
test "$red_rc" -ne 0
python3 "$base/jschfix5-gate.py" final-reversal 6 red
restore_product
clear_reports
./mvnw -B -Dtest=PendingCloseBoundTest test > "$base/jschfix5-final-green.log" 2>&1
python3 "$base/jschfix5-gate.py" final-green 6 green
clear_reports
./mvnw -B test > "$base/jschfix5-master-suite.log" 2>&1
python3 "$base/jschfix5-gate.py" master-suite 525 green
set +e
./mvnw -B impsort:check formatter:validate > "$base/jschfix5-global-validation.log" 2>&1
format_rc=$?
set -e
test "$format_rc" -ne 0
python3 -c 'from pathlib import Path; s=Path("/home/u/lanes/util20261014/out/jschfix5-global-validation.log").read_text(); assert "ChannelLateOpenTest.java" in s and "has not been previously formatted" in s'
./mvnw -B formatter:validate '-Dformatter.includes=**/Session.java,**/PendingCloseBoundTest.java' > "$base/jschfix5-changed-validation.log" 2>&1
git diff --exit-code 1cb9816e -- src/test/java/com/jcraft/jsch/ChannelLateOpenTest.java src/test/java/com/jcraft/jsch/ChannelLateOpenRekeyTest.java
echo 'MASTER GATES COMPLETE; inherited protected-file formatter constraint preserved'
