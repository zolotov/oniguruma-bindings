# Benchmarks

This module contains the shared JMH benchmark inputs and the Gradle-owned reporting
pipeline for the `oniguruma-jni` and `oniguruma-ffm` bindings. CI is intentionally a
thin shell: everything that produces, compares, or renders data lives in
`buildSrc/src/main/kotlin/me/zolotov/oniguruma/build/BenchmarkReportTask.kt` and the
static dashboard under `benchmarks/site/`, so the whole thing is portable to any CI
that can run `./gradlew` and publish a directory of static files.

The benchmark implementations live next to the code they measure
(`oniguruma-jni/src/jmh/`, `oniguruma-ffm/src/jmh/`); this module only holds the
shared inputs (`OnigurumaBenchmarkInputs`) so both bindings measure identical work.

## Running Locally

```bash
# Full benchmarks for one binding (5 warmups, 5 iterations, 1s each — what CI runs)
./gradlew :oniguruma-jni:jmh
./gradlew :oniguruma-ffm:jmh

# Quick benchmarks (2 warmups, 3 iterations, 500ms each) for a local smoke check
./gradlew :oniguruma-jni:jmh -PbenchmarkProfile=quick

# CI-style aggregate report + Pages bundle (runs both suites in the full profile)
./gradlew :benchmarks:ciBenchmark

# Compare against the published history (what CI does)
curl -fsSL https://zolotov.github.io/oniguruma-bindings/data/history.json -o /tmp/history.json
./gradlew :benchmarks:ciBenchmark \
  -PbenchmarkHistoryFile=/tmp/history.json \
  -PbenchmarkSiteUrl=https://zolotov.github.io/oniguruma-bindings
```

After `ciBenchmark`, open `benchmarks/build/ci/site/index.html` directly in a browser —
the dashboard embeds its data in `data/data.js`, so it works from `file://` without a
web server. `benchmarks/build/ci/report/summary.md` is the markdown version of the same
comparison.

Gradle properties understood by the pipeline:

| Property                         | Default | Meaning                                                                                                                                                                                                          |
|----------------------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `benchmarkProfile`               | `full`  | `quick` shortens warmup/measurement for local smoke checks; anything else (including CI) runs the full profile                                                                                                                                  |
| `benchmarkHistoryFile`           | none    | Existing `history.json` to use as the comparison baseline and to extend                                                                                                                                          |
| `benchmarkSiteUrl`               | none    | Published Pages URL, embedded into reports and the step summary                                                                                                                                                  |
| `benchmarkPrNumber`              | none    | Pull request number. Enables the per-PR site payload (`site/data/prs/<n>/`), `report/pr-history.json`, and the "Benchmark Runs in This PR" summary section                                                       |
| `benchmarkPrHistoryFile`         | none    | Previously published run history for this PR; the current run is appended to it. `benchmarkHistoryFile` stays the comparison baseline                                                                            |
| `benchmarkHistoryLimit`          | `90`    | Number of runs retained in `history.json` (and per-PR histories)                                                                                                                                                 |
| `benchmarkSignificanceThreshold` | `0.03`  | Fallback noise threshold for measurements without confidence intervals. When both runs carry JMH 99.9% confidence intervals (the normal case), significance is decided per benchmark by interval overlap instead |

## Benchmark Categories

Both bindings run the same suite (shared inputs from `OnigurumaBenchmarkInputs`),
so every measurement exists once per binding and the dashboard pairs them up into a
JNI vs FFM comparison:

- **OnigurumaCreateBenchmark** — regex compilation, string wrapping (small and 64 KiB
  texts), and the error path for an invalid pattern
- **OnigurumaMatchBenchmark** — matching a precompiled regex against a precreated string

The raw JMH results are written to `<module>/build/results/jmh/results.json`.
The aggregate CI report, summary markdown, raw JSON payloads, and Pages bundle are written to `benchmarks/build/ci/`:

```
benchmarks/build/ci/
├── raw/              # untouched JMH reports (jni.json, ffm.json)
├── report/
│   ├── current.json     # this run, normalized schema
│   ├── comparison.json  # per-measurement delta vs the baseline
│   ├── history.json     # bounded run history (baseline + this run)
│   ├── pr-history.json  # PR runs only (PR runs; previous PR history + this run)
│   └── summary.md       # markdown summary (also appended to the GitHub step summary)
└── site/             # deployable static dashboard (template + data)
    └── data/prs/<n>/ # per-PR payload (PR runs; same three-file contract as data/)
```

## GitHub Workflow

`.github/workflows/benchmarks.yaml` keeps the workflow intentionally thin:

- install Java, Rust, cmake, and Gradle (both native libraries are compiled on the
  runner before the benchmarks execute)
- download the published `history.json` from the existing Pages site (404 = fresh start;
  any other failure aborts the run so a transient outage can never wipe the trend data);
  for PR runs, additionally download that PR's published run history
- run `./gradlew :benchmarks:ciBenchmark` (full profile: 5 warmups, 5 iterations, 1s each)
- upload `benchmarks/build/ci/` as the workflow artifact
- on pull requests, upsert a sticky PR comment with `report/summary.md`
- deploy to GitHub Pages after every `main` push and every non-fork PR run; on PR close,
  redeploy without that PR's data (see "Pull requests on Pages" below)

The Gradle task owns the rest:

- running the `:oniguruma-jni:jmh` and `:oniguruma-ffm:jmh` suites
- normalizing results into one JSON schema
- comparing the current run to the latest published baseline (per-benchmark confidence-interval overlap decides significance)
- writing `summary.md` and appending it to `GITHUB_STEP_SUMMARY` when present
- assembling the static dashboard (`benchmarks/site/` template + generated `data/`)

### Pull requests

PR runs compare against the latest run published from `main` and surface the result in
four places: the sticky PR comment, the workflow step summary, the
`benchmark-report-*` artifact (which contains the full site — download it and open
`site/index.html` to browse a PR run interactively), and the published Pages dashboard
(`…/?pr=<number>`). PR results are never merged into the main trend history.

Every push to a PR appends a run to that PR's own history, so the sticky comment's
"Benchmark Runs in This PR" table and the PR dashboard's trend charts cover all of the
PR's runs, each compared against the same main baseline.

Fork PRs run on `ubuntu-latest` regardless of `BENCHMARK_RUNNER` (untrusted code must
not execute on a persistent self-hosted machine), skip the PR comment because the
fork token is read-only, and never publish to Pages (no OIDC token for fork events).

### Pull requests on Pages

The dashboard's main view lists active PRs (from `data/prs/index.json`); `?pr=<n>`
renders the standard dashboard from `data/prs/<n>/{latest,comparison,history}.json`,
where `history.json` holds only that PR's runs.

`actions/deploy-pages` always replaces the entire site, so every deploy first runs
`benchmarks/scripts/merge-pages-data.sh`, which carries forward whatever the run did
not produce itself: PR deploys fetch the live main-branch data, main deploys fetch all
live PR data, and both preserve the other PRs. Deploys are serialized through the
`benchmark-pages-deploy` concurrency group. Any fetch failure other than HTTP 404
aborts the deploy rather than silently dropping published data. (GitHub keeps at most
one pending job per concurrency group, so under heavy parallel PR traffic a PR's deploy
can occasionally be superseded; its data reappears with that PR's next run.)

When a PR closes, a cleanup job assembles the site from the template plus live data
minus the closed PR and redeploys, so `data/prs/` only ever holds open PRs.

### One-time repository setup

1. Settings → Pages → Build and deployment → Source: **GitHub Actions**.
2. Settings → Environments → `github-pages` → Deployment branches and tags:
   **No restriction**. PR-triggered deploys present the merge ref
   (`refs/pull/<n>/merge`), which deployment branch policies can never match (they
   only apply to real branches — patterns like `pull/*/merge` were tried and are
   rejected at deploy time), so the default main-only policy blocks PR deploys.
   Equivalent CLI:
   ```bash
   gh api -X PUT repos/{owner}/{repo}/environments/github-pages \
     --input - <<< '{"deployment_branch_policy": null}'
   ```
   This is safe here: only the benchmarks workflow requests `pages: write`, and fork
   PRs never reach the deploy job nor get an OIDC token.
3. Optional repository variables (Settings → Secrets and variables → Actions → Variables):
   - `BENCHMARK_RUNNER` — label of a self-hosted runner for stable numbers
     (see below). Falls back to `ubuntu-latest`.
   - `GCP_RUNNER_WIF_PROVIDER`, `GCP_RUNNER_SERVICE_ACCOUNT`, `GCP_RUNNER_INSTANCE` —
     enable on-demand start of the GCE runner (see "On-demand start and stop" below).
     When unset, the start-runner job is skipped and benchmarks run as before.

### Viewing the data

- Dashboard: `https://zolotov.github.io/oniguruma-bindings` — current snapshot, JNI vs
  FFM comparison, sparklines, and expandable per-benchmark history charts. The
  "Largest Regressions/Improvements" panels appear only on PR and local seeded runs
  (where a baseline comparison is the point); the published main dashboard relies on
  the trend charts instead.
- Machine-readable JSON next to it: `…/data/latest.json`, `…/data/comparison.json`,
  `…/data/history.json` (schema version 1; `history.json` is also the seed the next
  run consumes, so it is the canonical trend store).
- Active PRs: listed on the main dashboard; each links to `…/?pr=<number>` backed by
  `…/data/prs/<number>/{latest,comparison,history}.json` and indexed in
  `…/data/prs/index.json`.
- Per-run: workflow step summary and the uploaded `benchmark-report-*` artifact.

## Self-hosted benchmark runner on Google Compute Engine

GitHub-hosted runners are shared VMs; their numbers are noisy across runs. For stable
trends, register a dedicated GCE instance as a self-hosted runner and point
`BENCHMARK_RUNNER` at it.

This repository shares the `kodepoint-benchmarks` instance
(`projects/benchmarks-502005/zones/us-central1-b`) with the
[kodepoint](https://github.com/zolotov/kodepoint) project. GitHub runners are
registered per repository, so the one machine runs two runner installations
(`~/actions-runner` for kodepoint, `~/actions-runner-oniguruma` for this repo), both
labeled `gce-benchmark`. A runner executes one job at a time per registration, but the
two repositories' benchmark workloads are rare enough that interleaved jobs from both
repos on the same machine have not been a concern — and each job still has the whole
machine to itself while it runs. Unlike the GitHub-hosted fallback, the machine needs
its compilation toolchain preinstalled only in part: `setup-java`/`setup-gradle`
provision toolchains into the runner tool cache, the Rust action installs rustup into
the runner's home, and `cmake`/`build-essential` come from apt (the workflow's
install step is an idempotent no-op after the first run).

To register this repository's runner on the (already existing) instance:

```bash
gcloud compute ssh kodepoint-benchmarks --zone=us-central1-b --project=benchmarks-502005
mkdir actions-runner-oniguruma && cd actions-runner-oniguruma
curl -o actions-runner.tar.gz -L https://github.com/actions/runner/releases/download/v<version>/actions-runner-linux-x64-<version>.tar.gz
tar xzf actions-runner.tar.gz
./config.sh --url https://github.com/zolotov/oniguruma-bindings \
  --token <registration-token> \
  --labels gce-benchmark \
  --name gce-benchmark-oniguruma \
  --unattended
sudo ./svc.sh install && sudo ./svc.sh start   # run as a systemd service
```

(Repo Settings → Actions → Runners → New self-hosted runner shows the exact commands
with a fresh registration token; tokens expire after ~1 hour.)

Then set the repository variable `BENCHMARK_RUNNER` to `gce-benchmark`. To switch back
to GitHub-hosted runners, delete the variable.

Notes:

- Keep exactly one benchmark runner per label *per repository* — two machines with the
  same label would interleave and produce incomparable numbers. Note that switching
  machine types resets comparability of the trend history for the same reason.
- Security: fork PRs are already routed away from the self-hosted runner by the
  workflow. Additionally set Settings → Actions → General → Fork pull request
  workflows to "Require approval for all outside collaborators".
- GCE bills per second while the instance is in `RUNNING` state, working or idle
  (a stopped instance costs only its disk, a few dollars per month). The on-demand
  start/stop below keeps the machine stopped between jobs.

### On-demand start and stop

With both pieces in place the instance starts when a benchmark job is triggered and
stops itself 30 minutes after the last job — for a few runs a day that is dollars,
not hundreds, per month.

**1. Auto-stop when idle (on the VM).** Already installed for kodepoint and shared by
both runners: a systemd timer (`benchmark-idle-shutdown.timer`) powers the VM off
after 30 minutes without a running GitHub Actions job. It watches for any
`Runner.Worker` process, so a job from either repository counts as activity. Powering
off from inside the guest moves the instance to `TERMINATED`, which ends compute
billing. See the kodepoint benchmarks README for the script.

The 30-minute tail means consecutive pushes reuse the warm machine. Rare race to be
aware of: if the VM powers off in the same instant a new job is assigned, that job
stays queued — re-run the workflow (it starts the instance again).

**2. Auto-start from the workflow (keyless, via Workload Identity Federation).**
The `start-runner` job in `benchmarks.yaml` calls the Compute API to start the
instance before the benchmark job runs; it activates only when the repository
variables below are set. The workload identity pool, provider, and the
`gh-benchmark-starter` service account already exist for kodepoint; sharing them with
this repository only required widening the provider's attribute condition to trust
both repositories:

```bash
gcloud iam workload-identity-pools providers update-oidc github-actions \
  --project=benchmarks-502005 --location=global --workload-identity-pool=github \
  --attribute-condition="assertion.repository in ['zolotov/kodepoint', 'zolotov/oniguruma-bindings']"

gcloud iam service-accounts add-iam-policy-binding \
  gh-benchmark-starter@benchmarks-502005.iam.gserviceaccount.com \
  --project=benchmarks-502005 \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/347686456107/locations/global/workloadIdentityPools/github/attribute.repository/zolotov/oniguruma-bindings"
```

The repository variables (mirroring kodepoint's):

```
GCP_RUNNER_WIF_PROVIDER=projects/347686456107/locations/global/workloadIdentityPools/github/providers/github-actions
GCP_RUNNER_SERVICE_ACCOUNT=gh-benchmark-starter@benchmarks-502005.iam.gserviceaccount.com
GCP_RUNNER_INSTANCE=projects/benchmarks-502005/zones/us-central1-b/instances/kodepoint-benchmarks
```

No key files or secrets are involved — the workflow exchanges GitHub's OIDC token for
a short-lived GCP token at run time. The service account can only start/stop this one
instance, and the runner VM itself carries no service account (it executes repository
code and must not hold cloud credentials).
