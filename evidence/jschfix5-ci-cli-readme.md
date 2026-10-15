# KompileRemoteBuildCli

Command-line client for the [KompileRemoteBuildServiceServer](https://github.com/CodexCoder21Organization/KompileRemoteBuildServiceServer). Renders the analytics endpoints as either human-readable tables or raw JSON.

## Subcommands

| Subcommand | Purpose |
|---|---|
| `health` | Service health snapshot (`healthy`, `lastIngestAt`, total runs ingested). |
| `list-repos` | All repositories that have ingested runs. |
| `repo-stats --repo NAME [--since-days N]` | Per-repo build success/failure counts in a window. |
| `flakey-tests --repo NAME [--since-days N]` | Per-repo flakey-test list, sorted by flake rate. |
| `histogram --repo NAME --test NAME [--since-days N] [--buckets N]` | Successful-only duration histogram with ASCII bar chart and p50/p95/p99. |
| `trend --repo NAME --test NAME [--since-days N] [--granularity DAY\|WEEK]` | Pass-rate trend points. |
| `slowest [--repo NAME] [--top N] [--since-days N]` | Top-N slowest tests by p95 successful duration. |
| `repo-success [--since-days N]` | Per-repo build success rates, ascending. |
| `droplet-comparison [--repo NAME] [--test NAME] [--since-days N]` | Per-droplet-size p50/p95 comparison. |
| `ingest` | Forces an immediate ingest cycle on the server. |

Run `kompile-remote-build <subcommand> --help` for per-subcommand options.

## Server URL

| Source | Precedence |
|---|---|
| `--server URL` flag | Highest |
| `KOMPILE_REMOTE_BUILD_URL` env var | Middle |
| `http://localhost:8080` | Default |

## Output

| Flag | Output |
|---|---|
| (none) | Human-readable table or summary, printed to stdout. |
| `--json` | Raw JSON exactly as returned by the server, indented for readability. Pipe directly to `jq`. |

## Building

```bash
./scripts/build.bash kompile.remotebuild.cli.buildFatJar output/kompile-remote-build-cli.jar
java -jar output/kompile-remote-build-cli.jar --help
```

Built artifact: `kompile.remotebuild.cli:kompile-remote-build-cli:0.0.2`.

## Examples

```bash
# Top 10 slowest tests in the last week
java -jar kompile-remote-build-cli.jar slowest --top 10 --since-days 7

# Flakey tests in UrlResolver
java -jar kompile-remote-build-cli.jar flakey-tests --repo UrlResolver

# Histogram with ASCII chart for a single test
java -jar kompile-remote-build-cli.jar histogram \
  --repo UrlResolver --test openSandboxedConnectionTimeoutTest --since-days 30

# JSON output for scripting
java -jar kompile-remote-build-cli.jar slowest --top 5 --json | jq '.[].testName'
```

## Sample histogram output

```
test    : openSandboxedConnectionTimeoutTest
samples : 142
p50     : 1230 ms
p95     : 4500 ms
p99     : 8200 ms

RANGE (ms)             COUNT DISTRIBUTION
----------------------------------------------------------------------
[100, 500]                12 ███
[500, 1000]               34 ██████████
[1000, 2000]              58 ████████████████████████
[2000, 5000]              28 ███████████
[5000, 10000]             10 ███
```

## Related projects

Part of the **kompile-remote-build** build-analytics ecosystem — it ingests build/test history from [`url://buildtest/`](https://github.com/CodexCoder21Organization/BuildTestApi) and derives flakiness, duration, and pass-rate statistics over `kotlin.build` CI runs:

- [kotlin-build-ci](https://github.com/CodexCoder21Organization/kotlin-build-ci) — GitHub App that runs the `kotlin.build (remote)` CI checks and records each build/test run into `url://buildtest/`, the upstream data source these analytics consume.
- [KompileRemoteBuildApi](https://github.com/CodexCoder21Organization/KompileRemoteBuildApi) — API interfaces and data types (`url://kompile-remote-build/`).
- [KompileRemoteBuildEmbedded](https://github.com/CodexCoder21Organization/KompileRemoteBuildEmbedded) — in-process implementation; ingests from `url://buildtest/` and computes the statistics.
- [KompileRemoteBuildServiceServer](https://github.com/CodexCoder21Organization/KompileRemoteBuildServiceServer) — hosts the Embedded implementation behind `url://kompile-remote-build/` (this CLI's server).
- [KompileRemoteBuildWui](https://github.com/CodexCoder21Organization/KompileRemoteBuildWui) — server-side-rendered web dashboard.
