#!/bin/bash
set -eu
base=/home/u/lanes/util20261014/out
./mvnw -B net.revelc.code.formatter:formatter-maven-plugin:2.29.0:format net.revelc.code:impsort-maven-plugin:1.13.0:sort '-Dformatter.includes=**/Session.java,**/PendingCloseBoundTest.java' '-Dimpsort.includes=**/Session.java,**/PendingCloseBoundTest.java' > "$base/jschfix5-release-format.log" 2>&1
git diff --exit-code 039816e -- src/test/java/com/jcraft/jsch/ChannelLateOpenTest.java src/test/java/com/jcraft/jsch/ChannelLateOpenRekeyTest.java
git add Readme.md pom.xml src/main/java/com/jcraft/jsch/Session.java src/test/java/com/jcraft/jsch/PendingCloseBoundTest.java
git commit -m 'Publish reader5 with one close notice per abandoned channel open' -m 'Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>'
git fetch origin
git rebase origin/wip/jschfix3-reader4
./mvnw -B package > "$base/jschfix5-release-suite.log" 2>&1
python3 "$base/jschfix5-gate.py" release-suite 233 green
python3 "$base/jschfix5-verify-artifacts.py"
echo 'RELEASE GATES AND ARTIFACT CHECK COMPLETE'
