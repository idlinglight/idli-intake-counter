# CI and dependency maintenance

Notes for maintaining `.github/`: the Dependabot config and the workflows.
They live here, not as comments in those files, because editing those files
has side effects. Any change to `dependabot.yml` on main makes Dependabot
re-check every ecosystem, and a workflow edit re-runs that workflow on merge,
image builds and chart publish included. Comments in those files explain the
lines next to them; learnings and procedures go here.

## Dependabot: settings outside `dependabot.yml`

Repo-settings toggles, verified 2026-09-06:

- **Dependabot alerts: on.** They are the signal for transitive CVEs, which
  version updates never fix (Dependabot can't refresh in-range transitives
  for npm — a version-bump PR never drags a fix along). An alert = likely
  manual `npm update <transitive>` (see PR #19, #30).
  - Alerts show up when a merge changes a lockfile (GitHub re-scans the
    dependency graph), not when the advisory is published (seen 2026-10-02,
    10-10, 11-07).
  - If no fixed version fits every parent's range, `npm update` can't help
    (2026-11-07: the minimatch fix exists only in 11.x, while all parents
    pin 10.x), and an `overrides` entry would force an untested major on
    them. That alone doesn't make the alert tolerable: first assess how the
    vulnerable code is reached here, and with whose input. If the risk is
    acceptable, dismiss as "tolerable risk" with the re-check trigger in the
    comment (GitHub limits its length); Dependabot alerts 4 and 27 went that
    way. If not, the remaining options are an override tested on its own,
    replacing the parent, or dropping the dependency.
  - GitHub auto-dismisses some dev-scope alerts, HIGH ones included, and the
    web UI hides them; `gh api repos/<owner>/<repo>/dependabot/alerts` lists
    them.
- **Dependabot security-update PRs: off, on purpose.** They ignore cooldown
  and bump straight to latest, i.e. they systematically break the 3-day
  guard even when no bump to latest is needed. Keep them off.
- **Docker base images never get alerts** (not in the dependency graph).
  The two runtime bases are pinned by digest since 2026-11-15 (ADR-0010),
  so their updates come as version-update PRs instead, on the Friday run.
  For a critical nginx/temurin advisory don't wait for that — bump by hand:
  the index digest from `docker buildx imagetools inspect <image>:<tag>`
  (top-level "Digest:"), plus the new tag for nginx.

## Review traps for bump PRs

- **Docker bumps + action bumps inside build-and-push:** PR CI runs the
  verify jobs only — images are built AFTER merge to main, so a broken
  base-image or buildx bump merges green. Pre-merge proof: push the PR head
  to `build-image/<name>`, then `gh workflow run backend.yml --ref
  build-image/<name>` (+ `frontend.yml`). Always dispatch: it is the one
  trigger in those workflows without a `paths:` filter.
- **A runtime base-image bump is the one bump whose image is worth
  deploying for its own sake.** npm dev-dependency rounds mostly change what
  CI runs, not what is served; a new nginx or JRE changes what runs in the
  cluster. The merge builds exactly one image, backend or frontend. Its base
  is the intended difference, not necessarily the only one: the build-stage
  images float, so the same build may pick up a newer maven/JDK or node.
- **Pinned base images: what the PRs look like is not yet seen**
  (2026-11-15, nothing arrived yet). Expected: nginx as a patch bump that
  moves tag and digest together; the JRE (`21-jre@sha256:…`) as a
  digest-only bump, whose title names two digests and no Java version —
  `docker run --rm --entrypoint java eclipse-temurin@<digest> -version`
  says which one it is. Also unverified: whether the 3-day cooldown applies
  to a digest-only move at all (there is no release date to count from), so
  don't read "the PR exists" as "it is three days old" — the registry shows
  when the tag was pushed. A PR that changes the tag's shape or drops the
  `-jre` suffix: close it and bump by hand.
- **A Maven wrapper bump is two lines, and Dependabot's version of it is
  not mergeable as is.** `backend/.mvn/wrapper/maven-wrapper.properties`
  carries `distributionSha256Sum` next to `distributionUrl` (since
  2026-11-15): `./mvnw` refuses a Maven download that does not match — in
  CI's verify job and on a developer machine, the two places it runs (the
  image build uses the maven image instead). To bump by hand:
  - Maven Central publishes a `.sha512` for the zip, no `.sha256`. Download
    the zip, compare `shasum -a 512` with the published `.sha512` (the same
    file is on `downloads.apache.org/maven/`, a second host to compare
    with), then put `shasum -a 256` into the properties file.
  - `MAVEN_USER_HOME=$(mktemp -d) ./mvnw -v` proves it: a fresh download,
    validated. A wrong hash ends in "Failed to validate Maven distribution
    SHA-256".
  - Dependabot's own bump (PR #78, 2026-11-15, before the hash line
    existed) changed the URL and rewrote `mvnw.cmd` with CRLF line endings
    in the committed file — which `.gitattributes` (`*.cmd text eol=crlf`)
    then shows as modified forever, as after PR #51 (repaired in PR #54).
    Not yet seen: what it does with the hash line. If it keeps the old
    hash, verify fails loudly; if it drops the line, the PR is green and
    the check is gone — read the diff. Either way, redo the bump by hand
    and let Dependabot close its PR.
- **springdoc majors track Spring Boot majors** — a springdoc major PR only
  makes sense together with the matching Boot line.
- **Boot minors/majors can relocate classes/packages** (seen live: 4.1 moved
  `TestRestTemplate`). `./mvnw verify` in PR CI catches it at compile time;
  expect occasional import-fix commits on top of Boot bump PRs.
- **Lockstep packages vs. cooldown:** cooldown applies per dependency, so a
  pair that must move together gets split when its halves publish hours
  apart. Seen 2026-10-30: `eslint-plugin-oxlint` peer-pins `oxlint` to its
  own minor and publishes 0–18 h after it; the weekly run found oxlint
  1.90.0 past the 3 days and the plugin 37 min short, so the group PR bumped
  oxlint alone and `npm ci` failed with ERESOLVE (PR #65). Dependabot opens
  such a PR anyway: it writes the lockfile with `npm install --force` (seen
  in its job log, 2026-11-07). Exempting the plugin from cooldown doesn't
  help: it could then run ahead of oxlint.
  Fix: first check that the matching plugin version exists (`npm view
  eslint-plugin-oxlint versions`); the plugin sometimes skips one.
  - It exists but is younger than 3 days: once it clears cooldown, comment
    `@dependabot recreate`. On PR #65 that closed the PR and opened a
    complete one (PR #66).
  - It doesn't exist (2026-11-06: the plugin went 1.90.0 → 1.92.0, PR #68):
    recreate can't help. Push `npm install -D oxlint@~<main's minor>` onto
    the Dependabot branch, merge the rest, and let a later weekly run bump
    the pair. After such a push, don't comment `@dependabot rebase` or
    `recreate` on that PR: it would drop the commit.

## Merging several bump PRs in one round

Each merge to main builds only what its paths touch, so the order of the
merges decides which image tags the round ends with.

- **A PR that touches both `backend.yml` and `frontend.yml` goes last.** Its
  merge builds both images from the final tree, so both deploy candidates
  carry the same `sha-` tag. Done 2026-11-13: a `docker/login-action` bump
  (PR #71) went in after the four other PRs of that round. In a round
  without such a PR, the candidates are the last merge that built the
  backend image and the last one that built the frontend image.
- **Two npm PRs in one round** (the minor/patch group plus a major, which
  arrives on its own): Dependabot wrote both lockfiles against the same
  main. After merging the first, comment `@dependabot rebase` on the second.
  Dependabot then writes that lockfile again on the new main, and PR CI runs
  on the combination that will land. Seen 2026-11-13 (PR #74, then PR #75):
  GitHub showed PR #75 as mergeable without the rebase; the rebased branch
  was there about 2.5 minutes after the comment, with the same package
  changes as before.
  - The rebase writes a new lockfile and changes the head SHA. Compare its
    package changes with the ones already reviewed before merging.
  - Not on a PR that carries a pushed commit: see the lockstep trap above.

## Tool versions in workflow inputs

Dependabot bumps the actions (`uses:` lines), not the tool versions passed
to them as inputs: `node-version`, `java-version`, and the Helm `version` in
`chart.yml`. Those move by hand.

Helm is pinned (`v4.3.1` since 2026-11-15) because `azure/setup-helm`
otherwise installs the newest release at the minute the job runs — the chart
would be linted, packaged and published by a tool version nobody chose,
possibly hours old (v4.3.2 was two days old that day, inside the 3-day guard
the dependency bumps keep). To move it: pick a release at least three days
old (https://github.com/helm/helm/releases), change `HELM_VERSION` in
`chart.yml`, and expect the merge to republish the current chart version
under a new digest — the same effect as any other `chart.yml` edit.

## Runner images

Every job pins `runs-on: ubuntu-26.04` instead of `ubuntu-latest`, so moving
to a new runner image happens when we choose, not when GitHub migrates the
label. The move from 24.04 happened 2026-10-30, ahead of the `ubuntu-latest`
migration (rollout 2026-10-19 → 2026-11-19,
https://github.com/actions/runner-images/issues/14748), after a green
`build-image/` run on 26.04: both verify jobs and both image builds.
Dependabot doesn't bump these labels; GitHub announces image retirements in
that repository.

Moving to a new image:

1. On a branch, change every `runs-on:` in `.github/workflows/`. A local
   actionlint may not know a brand-new label yet (1.7.12 didn't know
   `ubuntu-26.04`); `actionlint -ignore 'label "<label>" is unknown'`.
2. Push it as `build-image/<name>`, then check which runs started. On
   2026-10-30 that push alone ran `backend.yml` and `frontend.yml` in full,
   verify AND build-and-push (the commit touched both files, which their
   `paths:` filters include). Dispatch (`gh workflow run backend.yml --ref
   build-image/<name>`) only what didn't start, or the images build twice.
   The PR alone runs only the verify jobs and the chart lint.
3. Don't dispatch `chart.yml` from a branch: its publish job runs on every
   non-PR event and would push the chart from that branch. Chart publish
   first runs on the new image after merge.
4. Each job's log names the image that actually ran under
   "Set up job → Runner Image".

Merging touches all three workflows, so it rebuilds both images (same
source, new `sha-` tags) and republishes the unchanged chart version (new
digest under the same tag). Consumers pinning the chart by digest see no
change.
