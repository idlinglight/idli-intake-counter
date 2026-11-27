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
  The two runtime bases and the backend's build base (`21-jdk`) are pinned
  by digest since 2026-11-15 (ADR-0010), so their updates come as
  version-update PRs instead, on the Friday run.
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
  is the intended difference, not necessarily the only one for the
  frontend: its build image (`node:26-alpine`) floats, so the same build may
  pick up a newer node. The backend's build JDK is pinned and its Maven
  comes from the wrapper, so a backend base bump changes nothing else that
  matters (the build stage's `unzip` comes unpinned from the Ubuntu
  archive; it only unpacks Maven). A bump of `21-jdk` alone changes what
  compiles the jar, not what runs.
- **Pinned base images: a digest-move PR says nothing about what moved.**
  Its title names two short digests and no version ("Bump eclipse-temurin
  from `0a3f329` to `db5cc63`"), also for nginx when the same tag was
  rebuilt. First seen 2026-11-20 (PR #82, PR #83), in the week of a Java
  and an nginx security release — and neither PR carried the fix:
  - The Temurin PR moved both `FROM` lines of `backend/Dockerfile`
    (`21-jdk` and `21-jre`, each to its own digest) in one PR titled after
    the `21-jdk` digests. All Linux manifests in the old and new index were
    identical; only the `windows/amd64` entries had been rebuilt. An index
    digest moves when any platform in it does.
  - The nginx PR was a rebuild of the same `1.30.6` tag. The fixed
    `1.30.7` image was two days old and did not get proposed: the 3-day
    cooldown does hold back a new tag. Whether it applies to a digest-only
    move is still unverified (there is no release date to count from), so
    don't read "the PR exists" as "it is three days old".
  - The fixed Temurin build reached the tag platform by platform within
    hours (amd64 first; the index digest changed twice in one evening
    while arm64 still was the old manifest). A pin taken during such a
    rollout is followed by another digest PR.

  So for every such PR, look at what the digests are:
  - `docker buildx imagetools inspect <image>@<digest> --format '{{json
    .}}'` shows each platform's image config without pulling:
    `JAVA_VERSION` / `NGINX_VERSION` in `.image["linux/arm64"].config.Env`,
    and `created`.
  - `docker buildx imagetools inspect --raw <image>@<digest>` lists the
    per-platform manifest digests; compare old and new to see which
    platforms changed at all.
  - These calls count against Docker Hub's anonymous pull limit (a 429
    after a few dozen). `https://hub.docker.com/v2/repositories/<repo>/tags/<tag>`
    (`library/eclipse-temurin` for the official image) answers the same
    for a tag: index digest, per-platform digests and push times.

  If the PR is not the update that is wanted, bump by hand (see "Docker
  base images never get alerts" above) and let Dependabot close its PR.
  A PR that changes the tag's shape or drops the `-jre` suffix: close it
  and bump by hand. Only the `21-jre` line changes what runs.
- **A Maven wrapper bump is two lines, and Dependabot's version of it is
  not mergeable as is: it also rewrites `mvnw.cmd`.**
  `backend/.mvn/wrapper/maven-wrapper.properties`
  carries `distributionSha256Sum` next to `distributionUrl` (since
  2026-11-15): `./mvnw` refuses a Maven download that does not match — in
  CI's verify job, on a developer machine, and in the backend image build,
  which uses the wrapper too. To bump by hand:
  - Maven Central publishes a `.sha512` for the zip, no `.sha256`. Download
    the zip, compare `shasum -a 512` with the published `.sha512` (the same
    file is on `downloads.apache.org/maven/`, a second host to compare
    with), then put `shasum -a 256` into the properties file.
  - `MAVEN_USER_HOME=$(mktemp -d) ./mvnw -v` proves it: a fresh download,
    validated. A wrong hash ends in "Failed to validate Maven distribution
    SHA-256".
  - The same message with a correct hash means `unzip` is missing: the
    wrapper script then downloads the `.tar.gz` instead of the `.zip`, and
    the hash is the zip's. That is why `backend/Dockerfile` installs unzip
    in its build stage.
  - Dependabot's own bump rewrites `mvnw.cmd` with CRLF line endings in
    the committed file, every time so far (PR #51, PR #78, PR #88). The
    content is otherwise the same, but `backend/.gitattributes` (`*.cmd
    text eol=crlf`) then shows the file as modified forever (repaired once
    in PR #54). The PR is green all the same, so read the diff: 189 changed
    lines in `mvnw.cmd` that `git diff --ignore-cr-at-eol` reduces to none.
  - With the hash line in place (PR #88, 2026-11-27, Maven 3.10.1 →
    3.10.2) Dependabot changed the URL and the hash together, and the hash
    was right. One data point: still check it as above, and still read the
    diff for a dropped line (the PR would be green and the check gone).
  - Repair on the Dependabot branch instead of redoing the bump: `git
    checkout origin/main -- backend/mvnw.cmd`, commit, push. The squash
    merge then touches only the properties file (and whatever else the
    group bumped). Don't comment `@dependabot rebase` or `recreate`
    afterwards: it would drop the commit. PR CI does not build the image,
    and the image build downloads Maven through the wrapper too, so add
    the `build-image/` pretest from the first trap.
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
