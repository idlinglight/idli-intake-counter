# Security

A single-user hobby project, maintained on a best-effort basis — there is no
response-time promise.

## Reporting a vulnerability

Please report privately, via GitHub's **Report a vulnerability** button
(Security tab → Advisories), rather than in a public issue or pull request.

## Scope

The threat model is one owner — who is also the operator — one password, one
deployment ([ADR-0005](docs/adr/0005-single-user-scope.md)); multi-user
concerns are out of scope by design.

Known and intended: twenty wrong passwords within an hour close password
*login* until the operator restarts the backend; existing sessions are not
affected. That anybody can do this is the accepted price of bounding what
password checking can cost
([ADR-0009](docs/adr/0009-bounded-password-checks.md)) — not a finding.
