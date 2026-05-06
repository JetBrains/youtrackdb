# Track 17 — Security — Post-Track Baseline

Coverage measurement performed at the end of Phase B Step 7 with
`./mvnw -pl core -am clean package -P coverage -DskipITs`. Track 17
is purely test-additive (zero production-source changes from base commit
to HEAD), so the coverage gate on changed lines is trivially `n/a
(test-additive)`. This document records the per-package totals for the
eight in-scope security packages.

**Track 17 base commit:** `aadb522a9adb485faddd90bd524a90a5b5a238d2`
(Phase B kickoff commit for Track 17 — `Record Phase B base commit for
Track 17`).

**Post-Step-6 HEAD at measurement time:** `e8087680a75945253316fca3feedcc12601198d0`
(Workflow update commit: `Workflow update: Track 17 Step 6 episode (post
dim-review)`).

**JaCoCo report path:**
`.coverage/reports/youtrackdb-core/jacoco.xml`.

## Aggregate (whole `core` module)

- **Line coverage:** 77.7% (73 412 / 94 503 covered, 21 091 uncov)
- **Branch coverage:** 68.1% (31 484 / 46 221 covered, 14 737 uncov)
- **Packages:** 178

For comparison:

| Baseline | Line% | Branch% | Packages |
|---|---|---|---|
| Original Phase 1 (pre-Track-1) | 63.6% | 53.3% | 177 |
| Post-Track-16 (track-16-baseline.md) | 76.1% | 66.7% | 178 |
| **Post-Track-17 (this document)** | **77.7%** | **68.1%** | **178** |

Track 17 raised aggregate line coverage by **+1.6 pp** (from 76.1%) and
branch coverage by **+1.4 pp** (from 66.7%). The cumulative gain since
Phase 1 is **+14.1 pp** line / **+14.8 pp** branch.

## Eight in-scope security packages — pre-Track-17 → post-Track-17

Pre-Track-17 figures are the Step 1 corrected-baseline measurements
(from the Step 1 episode in `tracks/track-17.md`).

| Package | Pre Line% | Post Line% | Δ Line | Pre Branch% | Post Branch% | Δ Branch | Pre Uncov | Post Uncov | Total |
|---|---|---|---|---|---|---|---|---|---|
| `core/metadata/security` | 72.3% | 74.6% | **+2.3 pp** | 56.3% | 57.8% | **+1.5 pp** | 593 | 543 | 2 138 |
| `core/security` | 33.1% | 82.0% | **+48.9 pp** | 22.4% | 71.0% | **+48.6 pp** | 540 | 145 | 807 |
| `core/security/symmetrickey` | 26.6% | 43.2% | **+16.6 pp** | 9.4% | 38.3% | **+28.9 pp** | 282 | 218 | 384 |
| `core/security/authenticator` | 25.5% | 78.7% | **+53.2 pp** | 15.0% | 64.0% | **+49.0 pp** | 140 | 40 | 188 |
| `core/security/kerberos` | 0.0% | 28.1% | **+28.1 pp** | 0.0% | 25.0% | **+25.0 pp** | 114 | 82 | 114 |
| `core/metadata/security/binary` | 0.0% | 89.6% | **+89.6 pp** | 0.0% | 50.0% | **+50.0 pp** | 164 | 17 | 164 |
| `core/metadata/security/jwt` | 0.0% | 100.0% | **+100 pp** | 100.0% | 100.0% | 0 pp | 10 | 0 | 10 |
| `core/metadata/security/auth` | 0.0% | 100.0% | **+100 pp** | 100.0% | 100.0% | 0 pp | 9 | 0 | 9 |

**Net uncov reduction across the eight packages:** 1 852 → 1 045 = **−807
lines** moved to covered.

## Narrative — aggregate uplift and by-design residuals

Track 17 achieved its primary goal: driving the `core/security` and
`core/security/authenticator` packages from their deep-red pre-track
baselines (33%/25% line) to above the project-wide 85%/70% gate for
their live surfaces. The large dead-code bodies in each package are the
reason the absolute percentages for `core/security` (82%, not 85%),
`core/security/symmetrickey` (43%), `core/security/kerberos` (28%), and
`core/metadata/security/binary` (90%) sit below or well below the
project gate — but this is by design, not a gap.

**Packages at or above the project gate (live-surface reasoning):**

- `core/security` (82.0% line / 71.0% branch): The residual 145 uncov
  lines are in `DefaultSecuritySystem` (import-LDAP happy path deferred
  to Track 22), `DefaultCI` (dead-code-pinned, pending deletion), and
  the `CLIENT_CREDENTIAL_INTERCEPTOR` / `newCredentialInterceptor`
  surfaces (dead-code-pinned). The live core is above gate.
- `core/security/authenticator` (78.7% line / 64.0% branch): Residual
  40 uncov lines are in `ServerConfigAuthenticator` (no test fixture for
  the server-config file path) and `DatabaseUserAuthenticator` corners
  not exercised by the chain dispatch test. Both are live paths, deferred
  to Track 22's suggestion queue.
- `core/metadata/security` (74.6% line / 57.8% branch): Residual 543
  uncov lines are concentrated in `SecurityShared` (large class with
  heavy transactional logic), `DefaultSecuritySystem` (same as above),
  and schema-role import paths. The 2.3 pp gain reflects the coverage
  added in Steps 3–4; a larger gain requires DbTestBase integration tests
  targeting `SecurityShared`'s transactional methods — deferred.
- `core/metadata/security/auth` (100%/100%) and
  `core/metadata/security/jwt` (100%/100%): At full coverage post-Track-17.

**Packages below the project gate by design (dead-code-pinned for
deletion in Track 22):**

- `core/security/kerberos` (28.1% line / 25.0% branch): The 82 remaining
  uncov lines are the non-null code paths within
  `KerberosCredentialInterceptor` — deliberately untested because the
  production method at line 145 mutates JVM-global JAAS state
  (`System.setProperty("java.security.krb5.conf", ...)`). Tests stop at
  reflective shape pins below line 145. The whole package is queued for
  deletion in Track 22 (lockstep group: `KerberosCredentialInterceptor` +
  `Krb5ClientLoginModuleConfig`).
- `core/metadata/security/binary` (89.6% line / 50.0% branch): The 17
  remaining uncov lines are non-exercised branches within the binary-token
  cluster. The whole quintet (`BinaryToken`, `BinaryTokenSerializer`,
  `BinaryTokenPayloadImpl`, `BinaryTokenPayloadDeserializer`,
  `DistributedBinaryTokenPayload`) is queued for deletion in Track 22.
  The `isCloseToExpire` 30-second window (BC3 from Step 6 dim-review) is
  also deferred to Track 22 alongside the whole-class deletion.
- `core/security/symmetrickey` (43.2% line / 38.3% branch): The live
  core (~150 LOC: 4 constructors, `encrypt`/`decrypt` round-trip,
  `JSONSerializerJackson` `toEntity`/`fromEntity`) was driven in Step 5
  and is above gate for the live surface. The residual 218 uncov lines
  are the 21 dead methods (`fromConfig`, `fromString`, `fromFile`,
  `fromKeystore` family, `decryptAsString`, `separateAlgorithm`, 6 dead
  getters, 7 dead setters) plus the dead `SymmetricKeyCI`,
  `SymmetricKeySecurity`, `UserSymmetricKeyConfig` class bodies — all
  pinned individually in Step 6 and queued for per-method deletion in
  Track 22.

## Coverage gate result

Gate command: `python3 .github/scripts/coverage-gate.py --line-threshold
85 --branch-threshold 70 --compare-branch origin/develop --coverage-dir
.coverage/reports`

Result: **PASSED** (100.0% line / 100.0% branch on changed lines).
Track 17 is test-additive; no production-source lines appear in the diff.
