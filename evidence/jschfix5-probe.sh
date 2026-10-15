#!/bin/bash
set -eu
base=/home/u/lanes/util20261014/out
probe_cp=$(cat "$base/revjsch1-probe/classpath.txt")
mkdir -p "$base/jschfix5-probe-classes"
javac -cp "target/classes:$probe_cp" -d "$base/jschfix5-probe-classes" "$base/jschfix5-probe-src/com/jcraft/jsch/PendingCloseOrderingProbe.java"
java -cp "$base/jschfix5-probe-classes:target/classes:$probe_cp" org.junit.platform.console.ConsoleLauncher execute --select-method com.jcraft.jsch.PendingCloseOrderingProbe#repeatedLateConfirmationsProduceOneCloseAfterExchange --config junit.jupiter.execution.parallel.enabled=true --config junit.jupiter.execution.parallel.mode.default=concurrent --config junit.jupiter.execution.parallel.config.strategy=fixed --config junit.jupiter.execution.parallel.config.fixed.parallelism=8 --reports-dir "$base/jschfix5-ordering-baseline-results" --disable-banner --details=summary
