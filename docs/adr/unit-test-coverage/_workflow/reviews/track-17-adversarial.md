# Track 17: Security — Adversarial Review (Phase A, iter-1)

Reviewer: devil's-advocate sub-agent. Goal: stress-test the track's
scope, decisions, invariants, and assumptions before Phase B begins.

**Tooling note.** PSI is reachable (mcp-steroid →
`unit-test-coverage` open). All "callers / inheritors" claims below
come from `JavaPsiFacade.findClass(...)` →
`ReferencesSearch.search(...)` / `ClassInheritorsSearch.search(...)`
in all-scope (project) — i.e. every module on disk including
`youtrackdb-server`, `youtrackdb-driver`, `youtrackdb-embedded`,
`youtrackdb-tests`, `youtrackdb-distributed*`, `youtrackdb-console`,
`youtrackdb-jmh-ldbc`, `youtrackdb-test-commons`,
`youtrackdb-docker-tests`. References inside the **same file** are
counted; references that *only* exist inside the class's own file
mean **zero external consumers**.

---

## Part 1 — Challenge Certificates

### Decision-Record Challenges

#### Challenge: Decision D1 — Test-first ordering by testability tier
- **Chosen approach**: Order tracks by testability tier — high (SQL
  utilities) first, hard (storage internals) last. Track 17 is
  positioned as "medium" in the second-to-last band.
- **Best rejected alternative**: D1(a) — order by package size /
  largest gap first.
- **Counterargument trace**:
  1. Track 17's largest single block is `core/metadata/security`
     (593 uncov / 2 138 total — the *measured* size, see
     `.coverage/reports/youtrackdb-core/jacoco.xml`).
  2. The other seven packages combined are 1 273 uncov, but the
     mass is concentrated in three classes:
     `DefaultSecuritySystem` (1 233 LOC) and `SecurityShared`
     (1 846 LOC) at `core/main/java/.../security/DefaultSecuritySystem.java`
     and `.../metadata/security/SecurityShared.java`, plus
     `SymmetricKey` (622 LOC).
  3. Under D1(a) the largest-gap-first heuristic, Track 17 would
     ride alongside Tracks 14–16 (which already exercised
     `DbTestBase`, schema, and metadata layers). The classes share
     the same lifecycle baggage (in-memory DB, role bootstrap, user
     role), so D1(a) would have **smaller carry-forward debt** than
     deferring to position 17.
  4. The rationale in D1 invokes "quick wins build momentum" —
     valid for Tracks 2–7, but Track 17 is no longer in the
     momentum-building band. The original justification for D1(c)
     does not apply to mid-stream tracks.
- **Codebase evidence**: `core/.../security/DefaultSecuritySystem.java:106`
  default constructor + `:109` `activate(YouTrackDBInternalEmbedded,
  SecurityConfig)` shows it requires a full embedded context; this is
  not "medium testability" — it is "needs Track 14's `YouTrackDBInternal`
  fixtures already in place," which D1's tier label hides.
- **Survival test**: WEAK — D1's rationale is generic and doesn't
  acknowledge that Track 17 specifically benefits from *prior*
  tracks (14, 15, 16) having paved the embedded-context fixtures.
  The decision survives because it isn't actively wrong, but the
  rationale doesn't carry Track 17 specifically. Track 17's step
  file should explicitly enumerate which Track 14–16 fixtures it
  depends on (currently the description only mentions "benefits
  from Track 16").

#### Challenge: Decision D2 — Standalone tests over DbTestBase where possible
- **Chosen approach**: Standalone for utility code; DbTestBase only
  when DB is genuinely needed.
- **Best rejected alternative**: D2(a) — uniform DbTestBase use.
- **Counterargument trace**:
  1. `SecurityManager.SALT_CACHE` is a static
     `Collections.synchronizedMap(LRUCache)` populated at class-init
     from `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`
     (`SecurityManager.java:56–66`).
  2. `SecurityResource.cache` is a static `ConcurrentHashMap`
     (`SecurityResource.java:11`) holding interned resource keys.
  3. `PropertyAccess.NO_FILTER` (`PropertyAccess.java:8`) and
     `SecurityResourceAll.INSTANCE` / `SecurityResourceServerOp.{SERVER,
     STATUS, REMOVE, ADMIN}` / `SecurityResourceDatabaseOp.{DB, COPY,
     DROP}` are static singletons whose interning behaviour leaks
     across tests.
  4. Under D2 (standalone preferred), tests for these classes are
     **encouraged** to be standalone — but the static caches are
     mutated as a side-effect of the first test run that constructs
     a `SecurityResource(...)` (interns into the cache). A second
     standalone test that asserts `cache` shape will see polluted
     state.
  5. Under D2(a) (everyone uses DbTestBase), the
     `@Before`/`@After` hooks at least force a "fresh DB" intent
     and the test author is reminded that shared state is a
     concern.
- **Codebase evidence**:
  - `core/.../security/SecurityManager.java:60–66` — static-init
    block reads `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`
    at first class load. This is identical in shape to the
    static-volatile-dispatcher pattern Track 14 documented in its
    iter-1 review notes.
  - `core/.../metadata/security/SecurityResource.java:11` —
    `private static final Map<String, SecurityResource> cache = new ConcurrentHashMap<>();`
- **Survival test**: WEAK — D2 is correct *as a default* but the
  Track 17 step file currently doesn't flag the static-state hazard,
  so a step author following D2 verbatim will write standalone tests
  that contaminate the JVM and only fail on disk-storage CI runs.

#### Challenge: Decision D4 — Accept lower coverage for storage internals
- **Chosen approach**: D4 lowers the bar to ~65–70% line for storage
  packages (Tracks 19–21) and compensates with higher coverage in
  testable areas (SQL, common, serialization). Track 17 is **not
  named** in D4 — implicit assumption: security can hit 85%/70%.
- **Best rejected alternative**: extend D4 to security packages with
  inherent testability obstacles — Kerberos (JAAS / JDK-internal
  `Krb5LoginModule`), binary token serializer (currently 0% / no
  callers), JsonWebToken (interface only, 0 callers).
- **Counterargument trace**:
  1. `core/security/kerberos`: 114/114 line uncov, 48/48 branch
     uncov. The intercept path
     (`KerberosCredentialInterceptor.intercept`) instantiates
     `LoginContext("ignore", null, null, cfg)` at line 150 and
     calls `lc.login()` at 151 — both require JAAS infrastructure
     and `com.sun.security.auth.module.Krb5LoginModule` (JDK-internal
     module), which is JDK-version- and JDK-vendor-dependent
     (Temurin 21 ships it; some hardened JDKs and GraalVM native
     image do not). "Pin construction and rejection paths" only
     gets to line 142 (the parameter-null guards), leaving 80+
     LOC in `intercept` (lines 142–180) and the entire 70-line
     `getServiceTicket` (lines 182–259) untestable without a real
     KDC.
  2. `core/metadata/security/binary`: 164/164 line uncov, 32/32
     branch uncov. PSI confirms **zero callers anywhere in the
     project** (only intra-package and `jwt`-back-edges). All five
     classes are entirely orphan.
  3. `core/metadata/security/jwt/JsonWebToken`: 0 callers.
     `YouTrackDBJwtHeader`: 2 references, both inside
     `BinaryTokenSerializer` (which is itself orphan). The JWT
     "feature" is **a header-only abstract surface** with no live
     producer.
  4. Under the implicit "security can hit 85%/70%" assumption, the
     plan budgets ~6 steps for ~1 860 uncov lines, expecting most
     to be covered. But ≥420 of those lines are dead-code orphans
     (Kerberos 114 + binary 164 + DefaultCI 6 + SymmetricKeyCI 54 +
     SymmetricKeySecurity 72 + YouTrackDBJwtHeader 10 = 420). That's
     **22.6% of the budget that cannot be covered live without
     Track-22-style WHEN-FIXED + DeadCodeTest reframing** (the
     pattern Tracks 14, 15, 16 all converged on).
- **Codebase evidence**:
  - PSI all-scope ReferencesSearch.search:
    `KerberosCredentialInterceptor` total=1 (self),
    `Krb5ClientLoginModuleConfig` total=1 (self),
    `BinaryTokenSerializer` total=10 (all intra-package),
    `BinaryToken` total=3 (all intra-package),
    `BinaryTokenPayloadImpl` total=2 (intra-package),
    `BinaryTokenPayloadDeserializer` total=1 (intra-package),
    `DistributedBinaryTokenPayload` total=0,
    `JsonWebToken` total=0, `DefaultCI` total=0,
    `SymmetricKeyCI` total=0, `SymmetricKeySecurity` total=0.
- **Survival test**: NO — D4 should be either (a) explicitly
  extended to mention "security packages with infrastructure
  dependencies (Kerberos, binary token JDK-version-sensitive)" or
  (b) Track 17's scope rewritten to plan for dead-code reframing
  upfront. The current text leaves a 22%-of-budget gap that the
  step author will hit only at Phase B.

#### Challenge: Decision D5 — One PR per track
- **Chosen approach**: One PR per track (5–7 commits per PR).
- **Best rejected alternative**: D5(b) — one PR per step.
- **Counterargument trace**:
  1. Track 16 produced 9 commits (7 step + 2 iter review). Track
     17's planned scope (~6 steps + carry-forward conventions
     including reflective shape pins, `*DeadCodeTest` pins,
     `@Category(SequentialTest)` for static-volatile, tracked
     `spawn()` discipline) is comparable.
  2. Per-track PRs of ~9 commits each are *already* at the upper
     edge of reviewer load. Tracks 17, 18, 19, 20, 21 each will
     bring this load with little time to soak. D5(b) (per-step PR)
     would amortize.
  3. Counter-counter: per-step PRs would multiply CI cost (full
     coverage run per step) and break the `[no-test-number-check]`
     batching. D5 survives because the operational cost of (b) is
     real.
- **Survival test**: YES — D5 holds. The challenge does not
  outweigh CI / `[no-test-number-check]` batching cost.

### Scope Challenges

#### Challenge: 6 steps for 8 packages spanning ~1 860 uncov lines
- **Chosen approach**: Track 17 description says "~6 steps covering
  password/token, authenticators, roles/permissions, symmetric key,
  binary tokens/JWT, and verification."
- **Best rejected alternative**: split into Track 17a (live security
  surface — `core/security`, `core/security/authenticator`,
  `core/metadata/security`, `core/metadata/security/auth`) and
  Track 17b (dead-code-pin sweep — `core/security/kerberos`,
  `core/security/symmetrickey` orphan members,
  `core/metadata/security/binary`, `core/metadata/security/jwt`).
- **Counterargument trace**:
  1. Track 14–16 episodes show a consistent pattern: when ≥20% of
     uncov budget turns out to be dead code, the track gets a
     dedicated `*DeadCodeTest` step that runs in parallel to the
     live-coverage steps. Track 14: 7 steps + dead-code reframe;
     Track 15: explicit dead-code reframe in Step 4 (the
     `git clean -fd` incident step); Track 16: cluster-selection
     trio + IndexConfigProperty deletion lockstep.
  2. Track 17's 6-step scope makes no allowance for this. The
     step descriptions ("password/token", "authenticators",
     "roles/permissions", "symmetric key", "binary tokens/JWT",
     "verification") all describe **live coverage targets**.
  3. The 420 LOC of orphan code (Kerberos 114, binary 164,
     DefaultCI 6, SymmetricKeyCI 54, SymmetricKeySecurity 72,
     YouTrackDBJwtHeader 10) need either (a) reflective shape
     pins via `*DeadCodeTest` to satisfy coverage without driving
     impossible code paths, or (b) explicit deferral to Track 22's
     queue.
  4. The "binary tokens/JWT" step name hints the author may have
     intended dead-code reframing for those two — but the
     description's wording ("binary token tests follow the
     round-trip pattern from Tracks 12–13") implies live
     round-trip testing, which is **not possible** since the
     binary token system has no producer or consumer in production.
- **Codebase evidence**: PSI all-scope as enumerated in the D4
  challenge above.
- **Survival test**: NO — the 6-step scope is structurally
  unrealistic without a dead-code reframe step. Track 17 should
  add a Step 0 / Step "audit" that enumerates dead-code candidates
  via PSI before any live coverage step is written. This matches
  the Track 16 carry-forward "all-scope PSI `ReferencesSearch`
  before driving live coverage" line — but Track 17 currently
  doesn't reserve a step slot for it.

#### Challenge: Step boundary — JWT split from binary tokens
- **Chosen approach**: Step "binary tokens/JWT" treats them as
  paired.
- **Best rejected alternative**: keep `core/metadata/security/jwt`
  out of Track 17 entirely (its 10 uncov LOC + 9 in
  `core/metadata/security/auth` are header-only abstract surfaces
  whose semantics are pinned only via the binary impl).
- **Counterargument trace**:
  1. PSI: `JsonWebToken` 0 refs, `JwtPayload` 1 self-ref,
     `BinaryTokenPayload` (in jwt pkg) 9 refs (all intra-binary
     and intra-jwt). The jwt package is the abstract layer the
     binary package implements.
  2. The JWT package has **no live JWT impl** — `JsonWebToken`
     interface is unimplemented. The 10 uncov lines are all in
     `YouTrackDBJwtHeader` (which is referenced only from the
     orphan `BinaryTokenSerializer`).
  3. Therefore the chosen "binary tokens/JWT" step name *is* the
     coherent grouping — the JWT package is just header for the
     binary impl. The challenge doesn't outweigh the pairing.
- **Survival test**: YES — the pairing is correct. (But the step
  must reframe both as dead-code pins, not live round-trip
  tests.)

#### Challenge: SymmetricKey serialization shape — does it belong in Track 13?
- **Chosen approach**: SymmetricKey serialization (key creation,
  encrypt/decrypt round-trip, key rotation, **serialization shape**)
  is in Track 17.
- **Best rejected alternative**: hand SymmetricKey JSON shape to
  Track 13 (binary serializer track).
- **Counterargument trace**:
  1. Track 13 owned `core/serialization/serializer/record/binary`.
     Track 12 owned the string serializer.
  2. `SymmetricKey` uses
     `com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson`
     (`SymmetricKey.java:26`) — i.e. the **string** serializer,
     not the binary serializer.
  3. So Track 13 is not the natural home. The natural home would
     be Track 12, but Track 12 is closed (track-episode at line
     897 in the slim plan).
  4. The challenge fails — SymmetricKey JSON shape is correctly
     in Track 17.
- **Survival test**: YES — this challenge does not hold; the
  scope assignment is correct.

#### Challenge: cheap additions
- `core/.../metadata/security/SecuritySystemUserImpl` (2 refs
  total, both in `DefaultSecuritySystem`) — could be folded into
  the password-validator step at minimal cost. Worth flagging as
  a fold-in opportunity rather than a separate step.
- `core/security/AuditingService` (5 refs, 4 in
  `DefaultSecuritySystem`) — not currently in scope text but
  *is* in the `core/security` package which has 540 uncov lines.
  The step decomposition should call it out explicitly so the
  step author doesn't miss the surface.

### Invariant Challenges

The track description does not enumerate explicit invariants —
it lists "What/How/Constraints/Interactions." I'll construct
violation scenarios for the implied invariants behind the
constraints.

#### Violation scenario: "Security-test secrets must be constants in the test, never read from env vars"
- **Invariant claim**: secrets are baked into the test sources.
- **Violation construction**:
  1. Step 5 author writes a Kerberos rejection-path test.
  2. To get past `KerberosCredentialInterceptor:114`
     (`var config = System.getenv("KRB5_CONFIG");`), they must
     **either** unset the env var (`assumeNull(System.getenv("KRB5_CONFIG"))`)
     **or** override the
     `GlobalConfiguration.CLIENT_KRB5_CONFIG` system property at
     test setup.
  3. If the author chooses the second path (more common in
     YouTrackDB tests — the suite already uses `System.setProperty`
     for `youtrackdb.*` keys), they must `System.setProperty(
     "youtrackdb.client.krb5.config", ...)` and unset it in
     `@After`.
  4. **Violation point**: `KerberosCredentialInterceptor:145`
     also calls `System.setProperty("java.security.krb5.conf",
     config)` *during the test* (real production code path). This
     mutates **JVM-global JAAS config** for every concurrent test
     in the surefire fork.
  5. **Observable consequence**: every later test in the same
     surefire fork that touches JAAS / GSSAPI / SSL sees the
     mutated `java.security.krb5.conf` system property. Surefire
     parallel-fork (4 threads default per `core` `pom.xml`) means
     this corrupts **three concurrent test files**.
- **Feasibility**: CONSTRUCTIBLE — exact code path
  `KerberosCredentialInterceptor.java:145` already mutates
  global state inside the production method.
- **Mitigation required**: Track 17 step 5 must use
  `@Category(SequentialTest)` *or* avoid calling `intercept(...)`
  past the line-142 guards entirely (test only the parameter
  rejection paths). The track description's "pin construction
  and rejection paths only" wording is consistent with the
  latter, but the step author needs an **explicit reminder** in
  the step file that calling `.intercept(url, principal, spn)`
  with valid args mutates JVM-global state.

#### Violation scenario: "Tests must pass in both memory and disk modes" (project-wide constraint)
- **Invariant claim**: Track 17's tests pass under both
  `youtrackdb.test.env=ci` (disk) and the default (memory).
- **Violation construction**:
  1. Step 1 author tests `SecurityManager` PBKDF2 round-trip,
     written as standalone (per D2).
  2. The first test in the surefire fork that touches
     `SecurityManager` triggers static init → reads
     `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`
     → populates `SALT_CACHE` (LRUCache instance).
  3. Step 2 author tests `DefaultPasswordAuthenticator` and
     happens to also exercise the salt code path.
  4. Surefire parallel fork: tests in step 1 and step 2 share
     `SALT_CACHE` state.
  5. **Violation point**: under disk mode, the
     `youtrackdb.security.createDefaultUsers=false` system
     property is set in `<argLine>`, but the salt cache is global.
     A test that asserts a specific salt for input "admin" / "admin"
     will see a stale salt from the LRUCache populated by an
     earlier test.
  6. **Observable consequence**: assertion failure only on disk
     CI, only when test ordering interleaves two specific tests.
- **Feasibility**: CONSTRUCTIBLE — `SecurityManager.java:64`
  initializes the salt cache with default size (`64` per
  `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`
  default). Two tests at different surefire-fork seeds will
  collide.
- **Mitigation required**: any salt-related test must either
  (a) use a unique salt input per test (`UUID.randomUUID()` as
  password), (b) clear `SALT_CACHE` via reflection in
  `@Before`, or (c) be `@Category(SequentialTest)`. Track 17's
  description does not enumerate this, but Track 14's
  carry-forward conventions imply it. The step file should
  spell it out.

#### Violation scenario: "Do NOT introduce real network or external Kerberos KDC dependencies"
- **Invariant claim**: no network / no real KDC.
- **Violation construction**:
  1. Step 5 author writes "construction and rejection paths"
     test for `KerberosCredentialInterceptor`.
  2. To make `intercept(...)` reach the
     `LoginContext("ignore", null, null, cfg)` line and trigger
     a rejection (the path the plan asks for), the author must
     supply non-null `principal`, non-null `config` (KRB5 config),
     and non-null `ccname` or `ktname`.
  3. `LoginContext` constructor itself is fine, but `lc.login()`
     at line 151 invokes
     `com.sun.security.auth.module.Krb5LoginModule.initialize(...)`
     which on most JDKs **opens `/etc/krb5.conf` or the supplied
     config file**, parses it, and (for keytab path) opens the
     keytab.
  4. **Violation point**: even in the rejection path, the test
     must point `config` at *some* file — either a temp file
     or a string that fails parsing. The latter is the safer
     choice (parser-failure rejection rather than keytab-not-
     found rejection), but the test now asserts "krb5.conf
     parser raises ParseException with our garbage input" —
     which is **JDK-implementation-dependent** behaviour, not
     a YouTrackDB property under test.
  5. **Observable consequence**: test fails on a JDK whose
     krb5.conf parser is more lenient (e.g. a future JDK that
     accepts blank files), without any YouTrackDB code change.
- **Feasibility**: CONSTRUCTIBLE — `KerberosCredentialInterceptor.java:142–158`
  shows the LoginContext-and-login flow is unconditional once
  the line-142 guards pass.
- **Mitigation required**: Track 17 Step 5 should test only the
  parameter-null rejection paths (lines 70–84, 134–139) and stop
  at the LoginContext boundary. Anything past that is JDK-
  implementation-dependent. The step file must explicitly
  say so — a step author who reads "pin rejection paths" may
  reasonably interpret it as "any throw site," including
  LoginException.

### Assumption Challenges

#### Assumption test: "Kerberos can be tested with construction + rejection pinning only"
- **Claim**: Track 17 can hit acceptable coverage on
  `core/security/kerberos` without standing up a JAAS / KDC.
- **Stress scenario**: a step author writes:
  ```java
  @Test(expected = SecurityException.class)
  public void interceptRejectsNullPrincipal() {
    new KerberosCredentialInterceptor()
      .intercept("remote://h", null, "spn");
  }
  ```
  This works — line 70–72 throws on null principal. **Coverage
  delta**: 2 lines of 84 in `intercept` and 0 of 70 in
  `getServiceTicket`. The remaining 80+ lines stay uncovered.
- **Code evidence**:
  `core/.../security/kerberos/KerberosCredentialInterceptor.java`
  lines 70–84 (parameter guards: ~14 LOC), 87–110 (URL parsing:
  ~24 LOC reachable with a malformed URL), 134–139 (config-null
  guards: ~6 LOC), 142–180 (LoginContext path: ~38 LOC requires
  JAAS), 182–259 (`getServiceTicket`: ~77 LOC requires GSSAPI
  ticket cache).
- **Verdict**: BREAKS — construction + rejection pinning covers
  ~44 of 114 uncov lines (38%). Remaining 70 lines are dead-code
  and should be reframed as `KerberosCredentialInterceptorDeadCodeTest`
  via reflective method-shape pins (per Track 14/15/16
  carry-forward). Track 17's step file currently writes
  "limited (no Kerberos infrastructure in test env) — pin
  construction and rejection paths only" without acknowledging
  this leaves >60% of the package uncov; this needs an explicit
  dead-code reframe + Track 22 absorption.

#### Assumption test: "Carry forward Tracks 5–16 conventions"
- **Claim**: the conventions accumulated in Tracks 5–16 apply
  cleanly to Track 17.
- **Stress scenario**: which conventions actually transfer?
  - **DOES apply**: rebase on develop; Spotless apply;
    `coverage-gate.py` for line/branch thresholds;
    `@Category(SequentialTest)` for static-volatile dispatchers
    (matches `SecurityManager.SALT_CACHE`,
    `SecurityResource.cache`, `PropertyAccess.NO_FILTER`, the
    `SecurityResource*` static singletons); `@After
    rollbackIfLeftOpen`; tracked `spawn()` + `@After` join.
  - **PARTIALLY applies**: `*DeadCodeTest` reflective shape pins
    — Track 17 has *more* dead-code-eligible classes than any
    prior track (≥6 fully orphan classes spanning ≥420 uncov
    lines), so the discipline is heavier here.
  - **Does NOT cleanly apply**: "round-trip pattern from Tracks
    12–13" for binary tokens — Tracks 12 and 13 tested a live
    serializer with live producers/consumers. The binary token
    serializer in Track 17 has no producer or consumer; "round-
    trip" tests would be *self-roundtrip-only*, which is a
    materially weaker pin (it cannot detect a producer-consumer
    drift if no producer-consumer pair exists). The step file
    should call this out.
- **Code evidence**: see the static-state inventory under D2.
- **Verdict**: FRAGILE — most conventions apply, but the "round-
  trip from Tracks 12–13" carry-forward is misleading. The step
  file should re-cast that as "construction-shape pins on the
  serializer (no live producer-consumer pair to round-trip
  against)."

#### Assumption test: "Prior tracks reveal nothing that weakens Track 17 assumptions"
- **Claim**: Track 16 episode says "no upcoming-track assumption
  is invalidated."
- **Stress scenario**: Track 14 discovered the static-volatile-
  dispatcher pattern. Track 15 reframed substantial scope as
  `*DeadCodeTest`. Track 16 split cluster-selection scope into
  method-level dead-code lockstep. **Each of these patterns
  applies to Track 17 with equal or greater severity**:
  - Static-volatile: more severe — `SecurityManager` is the
    canonical singleton with `SALT_CACHE`, plus
    `SecurityResource.cache` ConcurrentHashMap, plus 6 static
    `SecurityResource*` singletons.
  - Dead-code reframe: more severe — three full packages and
    six standalone classes are dead-code candidates (vs. Track
    16's two-class lockstep and Track 15's smaller trio).
  - Method-level lockstep: applicable — `DefaultSecuritySystem`
    has many methods, some referenced only from itself or via
    1-hop into the orphan `SymmetricKeySecurity` /
    `SymmetricKeyCI`.
- **Code evidence**: PSI all-scope; see D2 and D4 challenges
  above.
- **Verdict**: FRAGILE — Track 16's "no upcoming-track
  assumption invalidated" is technically correct (the schema
  carry-forward conventions still apply) but **understates** the
  severity. Track 17 needs *more* dead-code reframe budget than
  any prior track, not less.

### Simplification Challenges

#### Challenge: Reframe Kerberos / Binary Token / orphan SymmetricKey as `*DeadCodeTest` shape pins
- **Argument**: 420 of Track 17's ~1860 uncov LOC (22.6%) sit
  in classes with **zero non-self callers**. Per Track 14/15/16
  carry-forward, the right move is a `*DeadCodeTest` per orphan
  class with reflective method/field-signature pins, plus a
  `// WHEN-FIXED: Track 22 — delete <class>` marker.
- **Mechanism**:
  - `KerberosCredentialInterceptorDeadCodeTest` — reflective
    shape pin on the constructor, `intercept(String, String,
    String)`, `getUsername`, `getPassword` signatures + the
    `principal` / `serviceTicket` field shape; coverage credit
    via JaCoCo on the synthetic `<init>` and reflection
    machinery; ≤5 LOC live-coverage hit but lockstep delete
    listed in Track 22.
  - `Krb5ClientLoginModuleConfigDeadCodeTest` — reflective
    constructor + `getAppConfigurationEntry` shape; the
    constructor *can* run safely (it just builds a HashMap +
    AppConfigurationEntry, no JAAS init).
  - `BinaryTokenSerializerDeadCodeTest` +
    `BinaryTokenPayload{Impl,Deserializer}DeadCodeTest` +
    `BinaryTokenDeadCodeTest` +
    `DistributedBinaryTokenPayloadDeadCodeTest` — five
    test classes, each reflective.
  - `JsonWebTokenDeadCodeTest` (interface shape pin),
    `YouTrackDBJwtHeaderDeadCodeTest` (header shape).
  - `DefaultCIDeadCodeTest`, `SymmetricKeyCIDeadCodeTest`,
    `SymmetricKeySecurityDeadCodeTest`.
- **Cost**: ≈12 small `*DeadCodeTest` classes, ≈30 LOC each =
  ~360 LOC. Compare to writing live coverage for these (which
  requires JAAS / mocks / stand-up infra) — **5–10× cheaper**.
- **Step impact**: this becomes its own step (call it Step
  "dead-code reframe"), making Track 17 a 7-step (not 6-step)
  track. Adding Track 22 absorption queue load: ~12 lockstep
  delete groups.
- **Survival test of the chosen "live coverage" approach**:
  WEAK — the chosen approach implicitly requires live infra
  for code that has no live caller. The simplification (dead-
  code reframe) is strictly cheaper per Tracks 14–16
  precedent.

#### Challenge: Reuse existing security test infrastructure
- **Argument**: there are **13 existing test files** under
  `core/.../security/*` and `core/.../metadata/security/*`
  totalling 2 653 LOC. New Track 17 tests should extend these
  where the scope fits (per the existing-classes-preferred
  constraint #6 in the slim plan).
- **Existing files** (sizes):
  - `core/security/SecurityManagerTest.java` (56 LOC) —
    extend for full hash/PBKDF2/salt round-trip
  - `core/security/SchemaPropertyAccessTest.java` (172 LOC) —
    extend for `PropertyAccess` shape pins
  - `core/security/ResourceDerivedTest.java` (220 LOC) — extend
    for resource-class polymorphism
  - `core/security/symmetrickey/SymmetricKeyTest.java` (92 LOC)
    — extend for encrypt/decrypt round-trip + key rotation
  - `core/metadata/security/SecurityResourceTest.java` (124 LOC)
    — extend for full resource matrix
  - `core/metadata/security/SecurityEngineTest.java` (306 LOC) —
    extend for permission allow/deny matrix
  - `core/metadata/security/SecurityPolicyTest.java` (188 LOC)
    — already large, extend for boundary cases
  - `core/metadata/security/SecuritySharedTest.java` (144 LOC)
    — extend for SecurityShared internals
  - `core/metadata/security/ImmutableUserTest.java` (68 LOC) —
    extend for immutable-shape pins
  - `core/metadata/security/HashSaltTest.java` (21 LOC) —
    extend for hash dispatch
  - Existing tests **not** to extend (load-bearing existing
    behaviour tests):
    `ColumnSecurityTest.java` (587 LOC),
    `PredicateSecurityTest.java` (658 LOC) — these are
    integration-ish and shouldn't be conflated with Track 17's
    unit-coverage goal.
- **Survival test of "create new test classes" implicit
  default**: WEAK — the project constraint (#6, "existing
  test classes preferred") is in the slim plan but not echoed
  in Track 17's step file. Should be made explicit.

---

## Part 2 — Findings

### Finding A1 [blocker]
**Certificate**: D4 challenge, "Reframe Kerberos / Binary Token
/ orphan SymmetricKey as `*DeadCodeTest` shape pins"
simplification challenge.
**Target**: Decision D4, Track 17 step decomposition.
**Challenge**: 420 of Track 17's ~1 860 uncov LOC (22.6%) live
in classes with **zero non-self callers**. The chosen approach
("write live coverage tests for password hashing, token sign /
verify, role/permission matrix, authenticator chain dispatch,
symmetric key, binary tokens/JWT, Kerberos") implicitly assumes
all these surfaces have live producers/consumers. They don't:
- `KerberosCredentialInterceptor` (84 uncov), `Krb5ClientLoginModuleConfig` (21 uncov), `KerberosCredentialInterceptor$1` (9 uncov) — all 0 external refs
- `BinaryTokenSerializer` (53), `BinaryToken` (38), `BinaryTokenPayloadImpl` (52), `BinaryTokenPayloadDeserializer` (19), `DistributedBinaryTokenPayload` (2) — all intra-package only
- `JsonWebToken` (interface) — 0 refs; `YouTrackDBJwtHeader` (10) — referenced only from orphan `BinaryTokenSerializer`
- `DefaultCI` (6), `SymmetricKeyCI` (54), `SymmetricKeySecurity` (72) — 0 refs
**Evidence**: PSI all-scope `ReferencesSearch.search` totals
shown in the D4 challenge above; `.coverage/reports/youtrackdb-core/jacoco.xml`
per-class line-uncov counts.
**Proposed fix**: add an explicit Step 0 / Step "audit + dead-code
reframe" to Track 17 that (a) runs PSI all-scope on all 8
in-scope packages before any live coverage step, (b) produces a
fixed dead-code roster, (c) writes ~12 `*DeadCodeTest` classes
with reflective shape pins + `// WHEN-FIXED: Track 22 — delete
<class>` markers, (d) delegates the actual deletion to Track 22's
absorption queue. This makes Track 17 a 7-step track (not 6),
and reduces the live-coverage burden from ~1 860 LOC to ~1 440
LOC (a 22.6% scope reduction). This is a **blocker** because
without this reframe the live-coverage budget per step is
unrealistic and a step author will hit JAAS-init pain in Step 5.

### Finding A2 [blocker]
**Certificate**: Violation scenario "Kerberos rejection path mutates JVM-global state."
**Target**: Track 17 Step 5 (Kerberos), Constraint "Do NOT
introduce real network or external Kerberos KDC dependencies."
**Challenge**: `KerberosCredentialInterceptor.intercept(...)`
calls `System.setProperty("java.security.krb5.conf", config)` at
line 145 — *during* the production-method execution, **before**
any rejection point that doesn't require LoginContext. Any
test that calls `intercept(...)` past the line-142 guards
mutates JVM-global JAAS state for the entire surefire fork
(4 parallel threads). Furthermore, `LoginContext.login()` at
line 151 may attempt to load a JAAS Configuration that
references `com.sun.security.auth.module.Krb5LoginModule`,
which is JDK-version- and JDK-vendor-dependent.
**Evidence**: `core/.../security/kerberos/KerberosCredentialInterceptor.java:145`
and 151. `core/pom.xml` surefire fork count: 4 (carried forward
from the project default).
**Proposed fix**: Track 17 Step 5 must restrict its tests to
the *parameter-null* rejection paths only (lines 70–72, 82–84,
99–101, 134–139). Calling `intercept(...)` with a *valid*
principal-and-config combination — even just to provoke a
LoginException — is forbidden. The step file should add an
explicit "DO NOT call intercept past parameter-null guards"
rule, plus pair the live tests with the
`KerberosCredentialInterceptorDeadCodeTest` from finding A1.

### Finding A3 [should-fix]
**Certificate**: D2 challenge, Violation scenario "Tests must
pass in both memory and disk modes."
**Target**: Decision D2, Track 17 step decomposition.
**Challenge**: Multiple `core/security` and
`core/metadata/security` classes hold static state that
contaminates JVM across tests:
- `SecurityManager.SALT_CACHE` (LRUCache, populated at static-init
  from `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`)
- `SecurityResource.cache` (ConcurrentHashMap, populated lazily
  by interning constructor calls)
- `PropertyAccess.NO_FILTER`, `SecurityResourceAll.INSTANCE`,
  `SecurityResourceServerOp.{SERVER, STATUS, REMOVE, ADMIN}`,
  `SecurityResourceDatabaseOp.{DB, COPY, DROP}`,
  `SecurityResourceSchema.INSTANCE`,
  `SecurityResourceFunction` (file/class-init), and
  `PropertyEncryptionNone.inst` — all static singletons
**Evidence**:
- `core/.../security/SecurityManager.java:60–66`
- `core/.../metadata/security/SecurityResource.java:11`
- `core/.../metadata/security/PropertyAccess.java:8`
- `core/.../metadata/security/SecurityResource{All,DatabaseOp,ServerOp,Schema}.java`
- `core/.../metadata/security/PropertyEncryptionNone.java:5`
**Proposed fix**: Track 17's "Carry forward Tracks 5–16
conventions" must enumerate static-state offenders explicitly
in the step file, with the `@Category(SequentialTest)`
discipline for any test that asserts cache shape (vs. using
unique inputs). Track 14's pattern was specific to a single
`OSchemaProxy`-style dispatcher; Track 17 has **at least seven
distinct static singletons**, so the discipline is more
load-bearing. This is should-fix (not blocker) because the
mitigation is a step-file note, not a structural plan change.

### Finding A4 [should-fix]
**Certificate**: Scope challenge "6 steps for 8 packages
spanning ~1 860 uncov lines."
**Target**: Track 17 step count.
**Challenge**: Track 16 produced 9 commits (7 step + 2 review)
for ~1 390 uncov LOC across 4 packages. Track 17 budgets 6
steps for ~1 860 uncov LOC across 8 packages — i.e. 33% more
uncov in 14% fewer steps, while *also* needing more dead-code
reframe (per A1) and more static-state discipline (per A3).
The implied per-step density (~310 uncov LOC) is unrealistic
when the step also includes a `*DeadCodeTest` reframe.
**Evidence**: Track 16 episode at line 1356–1456 of the slim
plan; this finding's A1 and A3 above.
**Proposed fix**: budget 7 steps explicitly:
1. `core/security` core (SecurityManager, PasswordValidator, TokenSign/TokenSignImpl, ParsedToken, GlobalUserImpl)
2. `core/security/authenticator` chain dispatch
3. `core/metadata/security` roles + policies + Identity + Rule
4. `core/metadata/security/auth` + DefaultSecuritySystem (in-scope live paths)
5. `core/security/symmetrickey` SymmetricKey + SymmetricKeyConfig + UserSymmetricKeyConfig (live encrypt/decrypt)
6. **Dead-code reframe** (`*DeadCodeTest` × 12 — Kerberos, binary tokens/JWT, orphan symmetrickey)
7. Verification + coverage gate
This makes the per-step density ~265 uncov LOC, in line with
Track 16's actual density.

### Finding A5 [should-fix]
**Certificate**: Assumption test "Carry forward Tracks 5–16
conventions"; Scope challenge "JWT split from binary tokens."
**Target**: "How" section bullet "Binary token tests follow the
round-trip pattern from Tracks 12–13."
**Challenge**: Tracks 12 and 13 tested live serializers with
live producers/consumers. The binary token surface in Track
17 has **no production producer or consumer** (PSI confirms
0 external refs). A "round-trip" test that produces a token
in the test and consumes it in the same test is materially
weaker — it cannot detect producer-consumer drift because no
producer or consumer exists. Calling this a "Track 12/13
carry-forward" misframes the test's evidentiary value.
**Evidence**: PSI all-scope counts in D4 challenge.
**Proposed fix**: rewrite the "How" bullet from "Binary token
tests follow the round-trip pattern from Tracks 12–13" to
"Binary token classes are reframed as `*DeadCodeTest` shape
pins (per Track 14/15/16 carry-forward) — no production
producer/consumer exists, so live round-trip tests would be
self-tautological." Pair with finding A1.

### Finding A6 [suggestion]
**Certificate**: Decision D1 challenge.
**Target**: D1 rationale text.
**Challenge**: D1's rationale ("quick wins build momentum") is
a Phase-A-vintage justification that doesn't carry to mid-stream
tracks. Track 17 doesn't need a momentum-building justification;
it needs a "Track 17 leans on Track 14's `YouTrackDBInternal`
fixtures, Track 15's record-impl scaffolding, and Track 16's
schema-class fixtures" justification.
**Evidence**: `core/.../security/DefaultSecuritySystem.java:106–121`
(default ctor + `activate(YouTrackDBInternalEmbedded, SecurityConfig)`)
shows Track 17 needs the embedded context.
**Proposed fix**: not a plan change — but Track 17's step file
should explicitly enumerate which Track 14 fixtures it depends
on (`YouTrackDBInternalEmbedded` instantiation pattern,
`SystemDatabase` lifecycle pattern, `SharedContext` setup).
This is a documentation / step-file fix, not a Decision
Record fix.

### Finding A7 [suggestion]
**Certificate**: Simplification challenge "Reuse existing
security test infrastructure."
**Target**: Step decomposition.
**Challenge**: 13 existing test files (2 653 LOC) cover parts
of the Track 17 surface. Constraint #6 in the slim plan
("existing test classes preferred") means new Track 17 tests
should extend rather than create-new where the scope fits.
The step file does not currently enumerate which existing files
to extend.
**Evidence**: `find core/src/test/java -path "*security*" -name "*.java"`
listing (13 files).
**Proposed fix**: in each step's plan, list candidate existing
test classes to extend (e.g. Step 1 → `SecurityManagerTest.java`,
`HashSaltTest.java`; Step 3 → `SecurityResourceTest.java`,
`SecurityEngineTest.java`, `SecurityPolicyTest.java`,
`SecuritySharedTest.java`; Step 5 → `SymmetricKeyTest.java`).

### Finding A8 [suggestion]
**Certificate**: Cheap-additions scope challenge.
**Target**: Step 1 / Step 4 scope.
**Challenge**: Two small classes are easy fold-ins:
- `SecuritySystemUserImpl` (2 refs, both in
  `DefaultSecuritySystem`) — pin shape in the
  password/token step
- `AuditingService` interface + `AuditingOperation` enum
  (referenced only from `DefaultSecuritySystem` and
  `SecuritySystem`) — pin in the verification step
**Evidence**: PSI all-scope refs.
**Proposed fix**: add explicit shape-pin tests for these in
the step file (~30 LOC each).

### Finding A9 [suggestion]
**Certificate**: Track 22 absorption challenge (track-level).
**Target**: Track 22 scope.
**Challenge**: Track 17 will forward at least 12 dead-code
lockstep delete groups to Track 22 (per A1). Combined with
the existing Track 22 queue (~30+ items from Tracks 7–16) and
Tracks 18, 19, 20, 21 yet to add their absorptions, the Track
22 queue is heading for ≥60 deferred items. The scope
indicator currently says "~6 steps + ~2-3 absorption steps,"
which is unlikely to be enough.
**Evidence**: Track 16 episode forwarded ~17 suggestion-tier
items + 8 dead-code groups; Track 17 will forward at least
that many. Tracks 18–21 (storage) per D4 are expected to fall
short of 85%/70%, meaning more deferral.
**Proposed fix**: not Track 17's responsibility to fix, but
Track 17's Phase A should record an explicit count of items
forwarded so Track 22's Phase A can re-budget its step count.

### Finding A10 [suggestion]
**Certificate**: D5 challenge (PR cadence), survives but worth flagging.
**Target**: Track 17 PR shape.
**Challenge**: Track 17's PR will likely have 9+ commits (7
step + ≥2 review). With the carry-forward conventions Track
16 surfaced (3-iter review loop, max 3 iterations), Track 17
should plan for the iter-3 cap from the start.
**Proposed fix**: Track 17's Phase A pre-computes the review
matrix (CQ/BC/TB/TC/TX/TS dimensions) and surfaces it in the
step file so iter-1 reviews don't drift.

---

## Summary

- **Blocker:** 2 (A1 dead-code reframe missing; A2 Kerberos JVM-global mutation)
- **Should-fix:** 3 (A3 static-state discipline, A4 step count, A5 binary-token roundtrip framing)
- **Suggestion:** 5 (A6 D1 rationale, A7 existing-test reuse, A8 cheap fold-ins, A9 Track 22 budget, A10 review cadence)
- **Skip:** 0

### Top 3 highest-leverage findings
1. **A1 (blocker)** — 22.6% of Track 17's uncov budget (~420 LOC across Kerberos, binary tokens/JWT, orphan symmetrickey members) is in classes with zero non-self callers. The chosen "live coverage" approach is structurally unworkable for this slice; a `*DeadCodeTest` reframe step is required (per Track 14/15/16 carry-forward).
2. **A2 (blocker)** — `KerberosCredentialInterceptor.intercept(...)` mutates JVM-global state (`System.setProperty("java.security.krb5.conf", ...)`) inside the production method *before* the LoginContext call. Any rejection-path test that exercises the method past the line-142 guards corrupts the surefire-fork JVM. Step 5 must restrict to parameter-null guards only.
3. **A4 (should-fix)** — 6 steps for 8 packages × 1 860 uncov LOC + carry-forward dead-code reframe is unrealistic. 7 steps (with an explicit dead-code reframe step) matches Track 16's actual per-step density.
