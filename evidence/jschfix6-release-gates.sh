#!/usr/bin/env bash
set -eu
base=/home/u/lanes/util20261014/out
./mvnw -B net.revelc.code.formatter:formatter-maven-plugin:2.29.0:format net.revelc.code:impsort-maven-plugin:1.13.0:sort '-Dformatter.includes=**/Session.java,**/FailedOpenReplyTest.java' '-Dimpsort.includes=**/Session.java,**/FailedOpenReplyTest.java' > "$base/jschfix6-release-format.log" 2>&1
git diff --exit-code HEAD
cmp src/test/java/com/jcraft/jsch/FailedOpenReplyTest.java ../src/test/java/com/jcraft/jsch/FailedOpenReplyTest.java
git diff --exit-code ae0fffa -- src/main/java/com/jcraft/jsch/Channel.java src/test/java/com/jcraft/jsch/ChannelLateOpenTest.java src/test/java/com/jcraft/jsch/ChannelLateOpenRekeyTest.java src/test/java/com/jcraft/jsch/PendingCloseBoundTest.java
[ "$(df --output=avail -k / | tail -1)" -ge 6291456 ] || exit 125
./mvnw -B package > "$base/jschfix6-release-suite.log" 2>&1
python3 "$base/jschfix6-gate.py" release-suite 242 green
python3 "$base/jschfix6-verify-artifacts.py"
