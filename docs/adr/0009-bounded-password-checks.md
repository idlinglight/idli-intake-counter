# ADR-0009: Password checks are bounded — a concurrency cap and a login fuse

Status: accepted, 2026-09-20

## Context

Checking a password means one bcrypt verification — a few hundred milliseconds
of CPU at the cost factor the hashes are minted with (12) — and anybody can ask
for one: with a login POST, or with an `Authorization: Basic` header on any
`/api` path, public ones included (the Basic filter authenticates before
authorization gets a say). Nothing bounded how many of them run.

The damage path is short. A modest flood of such requests occupies the cores
and the worker threads, the liveness probe times out, the kubelet restarts the
pod — and the sessions live in memory (ADR-0005), so the owner is logged out
everywhere and cannot log back in while the flood lasts. Availability only:
nothing is disclosed, and guessing a generated password at a few attempts per
second is not a realistic threat.

The usual answers need to tell callers apart, and the app cannot. Depending on
the cluster's load balancer every request arrives from the same internal
address (it does where this is deployed), and a forwarded-for header is worth
what the edge that sets it is worth. Every per-client bucket then collapses
into a single global one, and the classic account lockout becomes a way for
anybody to lock the one account there is.

ADR-0005's amendment supplies the two premises used below: the user is also the
operator, and under attack that operator ranks bounded resource use and a loud
signal above availability.

## Decision

Both bounds sit in one place: a `PasswordEncoder` wrapper
(`GuardedPasswordEncoder`) in front of the bcrypt encoder. Every password check
passes through it — form login, HTTP Basic, and the dummy check Spring Security
runs for an unknown username — so the bound holds by construction. A request
filter was the alternative: more visible, but it has to keep a list of "what
triggers a check" in sync by hand, and it holds its permit for the whole
request rather than for the bcrypt.

1. **Cap.** At most `idli.auth.max-concurrent-checks` (default 2) bcrypt
   computations run at once. A caller that finds no free permit is refused
   immediately with **429** and `Retry-After: 1`, before any bcrypt. Nobody
   queues: waiting callers would park exactly the worker threads the probe
   needs.
2. **Fuse.** After `idli.auth.fuse.max-failed-checks` (default 20) failed
   checks within `idli.auth.fuse.window` (default 1 h), password verification
   switches off: every further check is refused with **418**, before any
   bcrypt, until the process restarts. Only wrong passwords count (unknown
   usernames included) — not the 429 refusals, not successful checks. The
   window keeps stray typos and drive-by probes from adding up over weeks.
3. **Sessions are out of it.** A request authenticated by its session cookie
   never reaches the encoder. Whoever is logged in stays logged in — through a
   flood, and with the fuse blown. The same goes for any way of authenticating
   that needs no password check.
4. The liveness probe tolerates 3 s instead of 1 s (chart 0.5.1): the restart
   *is* the damage.

### Why a fuse, and why it stays blown

The cap bounds the rate — that many cores, for as long as somebody keeps
sending. Only failing closed bounds the total: at most 20 checks (plus at most
permits − 1 already in flight) per intervention of the operator. Staying just
under the limit buys 19 checks an hour — on the order of ten CPU-seconds.

It is a fuse and not a circuit breaker: nothing closes it again but a restart
— deleting the backend pod is the release. Resetting it after a cool-down would
bound the cost almost as well. Staying blown was chosen because nothing is
known yet about whether this ever happens at all: for a case without
observations, the cheap way to handle it is loudly. Revisit with data.

The price: anybody can close password login for everybody — which here means
for one person, and only for *new* logins. That is the lockout trade ADR-0005's
amendment makes affordable.

### Why 418

The blown fuse needs an answer that the SPA, and the operator behind curl or an
access log, can tell apart from "wrong password" (401) and "busy" (429). Folded
into 401 it would send the owner hunting for a typo that is not there — the
failure class the password-hash guard at startup already exists for.

The candidates with a fitting meaning are all taken: 429 means "busy, retry"
here, and a retry hint would be a lie; 503 is what the ingress itself answers
while the pod is away; 403 is what a missing CSRF token gets; 423 belongs to
WebDAV, whose specification expects a WebDAV body with it.

418 has no meaning left to violate. RFC 9110 (§15.5.19) lists it as "(Unused)":
reserved, because after the April-1st RFC 2324 servers kept using it for
purposes of their own, and for that reason never assignable to anything else.
That is what qualifies it: no proxy, framework or browser between this app and
its owner produces or interprets a 418, so one can only mean "fuse blown" — and
using it to decline a request without saying why is existing practice (MDN
notes sites answering unwanted automated requests with it). A status code
picked for being guaranteed free of other meanings, not for the teapot. (Spring
deprecated its enum constant for it in 7.0; the code uses the number.)

## Consequences

- A flood of password checks costs the capped number of cores for a few
  seconds and after that only plain request handling; the probes stay green
  and sessions stay up.
- While it lasts the owner cannot log in *newly* either — 429s first, then
  418. Accepted: by then the incident has priority over the tool.
- The app bounds what one request can cost, not how many arrive. Request
  volume stays the platform's business — edge limits where the edge can tell
  callers apart, instance caps, budget alarms — or the operator's bigger
  switch: scale the release to zero, take the ingress away.
- A blown fuse is announced once in the backend log (WARN) and by the login
  form, which names the release. docs/SMOKETEST.md has the gesture.
- Every restart re-arms the fuse, whatever caused it — a deploy included.
- The fuse is application-wide state, and the test suite shares one cached
  context: the tests raise the limit
  (`backend/src/test/resources/config/application.yaml`), and
  `LoginFuseFlowTest` brings a context of its own.
