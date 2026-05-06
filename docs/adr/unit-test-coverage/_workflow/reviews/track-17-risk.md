# Track 17 — Risk Review (iter-1)

**Verdict:** PASS-with-fixes — 0 blocker / 5 should-fix / 4 suggestion / 0 skip.

**Tooling note:** mcp-steroid is reachable; `unit-test-coverage` project is
open and matches the working tree. All reference-accuracy claims below are
PSI-grounded (`ReferencesSearch`, `MethodReferencesSearch`, source-set bucketing
into `core` main / `core` test / external-module main / external-module test).
The dead/live calls below were verified against the all-scope index, not text
search.

---

## Part 1: Evidence Certificates

### CRITICAL PATH EXPOSURE

#### Exposure: Static singletons / process-wide state in `SecurityManager`
- **Track claim**: Cover password hashing, token sign/verify, and salt-cache
  behavior (Description, "How").
- **Critical path trace**:
  1. `SecurityManager.SALT_CACHE` @ `SecurityManager.java:58` — `static`
     field, initialized in a `static {}` block @ `SecurityManager.java:60`
     from `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE` at
     class-load time. Reset only by reflection (the field is `private static`
     with no setter).
  2. `SecurityManager.instance` @ `SecurityManager.java:56` — `private static
     final SecurityManager instance = new SecurityManager();` JVM-singleton.
  3. `getPbkdf2(...)` @ `SecurityManager.java:242` writes/reads `SALT_CACHE`;
     7 production callers via `SecurityManager.checkPassword` /
     `createHashWithSalt` (`grep -rn "SecurityManager\.checkPassword\|
     SecurityManager.createHash"` in core main: `ImmutableUser:198`,
     `SecurityUserImpl:110`, `SecurityUserImpl:276`, `DefaultSecuritySystem:295`,
     `DefaultPasswordAuthenticator:125`, `SymmetricKeySecurity:105`,
     `SQLMethodHash:58`).
  4. `TokenSignImpl.threadLocalMac` @ `TokenSignImpl.java:24` — `static`
     `ThreadLocal<Map<String, Mac>>`. Surefire workers persist across test
     methods; the per-thread `Mac` cache is never cleared.
- **Blast radius**: A SALT_CACHE that retains hash outputs from a previous
  test (e.g. `("foo", "salt-A", 1000)` -> bytes) collides with a later test
  that mutates `SECURITY_USER_PASSWORD_SALT_CACHE_SIZE` via reflection — the
  cache reflects old config. A `Mac` instance that leaks across tests is
  benign (it's reset in finally) but if a test injects a custom algorithm,
  the next test on the same worker thread sees a stale `Mac` for that name.
- **Existing safeguards**:
  - `SecurityManagerTest` already runs (4 `@Test`s) without `@Category(SequentialTest)`
    and has not flaked. The `SALT_CACHE` is read/write under
    `Collections.synchronizedMap`, so write-collisions are race-safe.
  - Class-load-time initialization of `SALT_CACHE` from the global config
    means tests that mutate `SECURITY_USER_PASSWORD_SALT_CACHE_SIZE` after
    class-load have NO observable effect on the field — the test would silently
    read stale bytes from the cache it never resized. This is a TEST-DESIGN
    trap, not a production-bug; it's load-bearing because it would invalidate
    a "different cache size produces different hit/miss behaviour" pin.
- **Residual risk**: MEDIUM. The static state is real; the pattern carry-
  forward from Track 14 (`@Category(SequentialTest)` for static-volatile
  dispatchers) and Track 12 (`@After SerializationThreadLocal.INSTANCE.remove()`
  for surefire worker reuse) directly applies. Track 17 must add the same
  hygiene for any `SALT_CACHE`-touching test (or never touch it; observe-only
  tests are fine).

#### Exposure: `SecurityShared` is reachable through `DbTestBase` — heavy live coverage path
- **Track claim**: "Carry forward Tracks 5–16 conventions"; existing live
  tests in `metadata/security` already exercise `SecurityShared`.
- **Critical path trace**:
  1. `DbTestBase.beforeTest` @ `DbTestBase.java` opens an in-memory
     `DatabaseSessionEmbedded`; `getSharedContext().getSecurity()` returns
     a `SecurityShared` instance per `SecuritySharedTest:11`.
  2. `SecurityShared` (1846 LOC) is the bulk of `core/metadata/security`
     uncov mass (593 uncov / 72.3% baseline). The class has 6 `@Test` methods
     across `SecuritySharedTest` plus indirect coverage from `SecurityEngineTest`
     (8 tests), `SecurityPolicyTest` (6), `ImmutableUserTest` (3),
     `PredicateSecurityTest` (14), `ColumnSecurityTest` (16),
     `SchemaClassSecurityTest` (1), `SecurityResourceTest` (2),
     `HashSaltTest` (1), `TestReaderDropClass` (1) → 58 existing `@Test`s on
     metadata/security.
  3. Mutation paths: `acquireSchemaWriteLock`-equivalent guards in
     `SecurityShared` use `securityPredicateCache` (a `ConcurrentHashMap` @
     line 89) cleared on role drop / policy change @ line 1204. Cache reads
     happen in `SecurityShared.getPredicate` / `isAllowed` / `canRead` etc.
- **Blast radius**: Tests that mutate roles/policies but do not drop the DB
  could leak across test methods if they share a session — but DbTestBase's
  per-method drop/recreate already isolates this. Within a single test,
  mutating a role and immediately checking its `@allow` rule sees the cache
  invalidated correctly because `securityPredicateCache.clear()` is called
  on role updates.
- **Existing safeguards**:
  - DbTestBase per-method DB lifecycle drops `SecurityShared` instance
    between tests.
  - `securityPredicateCache.clear()` on every role-update path.
- **Residual risk**: LOW. This is the same shape as Track 16's `SchemaShared`
  exposure — Track 16 closed it cleanly, the same convention applies here.

### UNKNOWNS & ASSUMPTIONS

#### Assumption: "Authenticator chain dispatch (try-each, fall-through, first-match)" is unit-testable
- **Track claim**: "How: ... authenticator chain dispatch (try-each,
  fall-through, first-match)" (Description).
- **Evidence search**: PSI all-scope `ReferencesSearch` on the three
  bootstrap authenticators (`DatabaseUserAuthenticator`,
  `ServerConfigAuthenticator`, `SystemUserAuthenticator`) plus
  `DefaultSecuritySystem` (which owns `enabledAuthenticators`).
- **Code evidence**:
  - `DefaultSecuritySystem.initDefultAuthenticators` @
    `DefaultSecuritySystem.java:180` builds the default chain (3 entries:
    server / system / database). No JSON config is required for the default
    list — `DbTestBase` -> `YouTrackDBInternalEmbedded.<init>:139` constructs
    a `DefaultSecuritySystem` and the system uses these defaults.
  - The chain extension point (`reloadAuthMethods` @ line 1018) reads
    `authentication.authenticators[].class` from `security.json` and walks
    `Class.forName` @ line 212. This path is exercised only with an
    explicit JSON config — there is none in `core/src/test/resources`.
  - The 3-entry default chain IS reachable through `DbTestBase`; an
    embedded session walks `enabledAuthenticators` in
    `DefaultSecuritySystem.authenticate(session, username, password)` @
    line 256 → 272.
- **Verdict**: VALIDATED for the default-chain (3-entry) path. The
  `reloadAuthMethods` extension path is in scope for `DefaultSecuritySystem`
  testing but Track 17's package list does NOT include
  `com.jetbrains.youtrackdb.internal.core.security` (root) for
  `DefaultSecuritySystem` itself per the Description gap analysis — only the
  `authenticator` subpackage is listed. The 1233-LOC `DefaultSecuritySystem`
  drives the chain but is itself in `core/security` (548 uncov / 32.1%
  package), so test additions in the `core/security` package will reach
  `DefaultSecuritySystem`; the `Class.forName` extension path will then
  remain uncovered unless tests stand up a fake `security.json` blob.
- **Detail**: Track 17's "How" mentions "authenticator chain dispatch"
  without distinguishing the default-chain (testable today) from the
  reflective-extension chain (requires JSON fixture). The decomposition
  should call out which is in scope. The default chain is ~85% reachable
  from `DbTestBase`; the reflective extension would require synthesizing
  a JSON entity then calling `reloadAuthMethods` via `reloadComponent` — a
  ~30-line setup that is feasible but not free.

#### Assumption: Kerberos paths can be tested "construction and rejection only"
- **Track claim**: "Kerberos tests must be limited (no Kerberos
  infrastructure in test env) — pin construction and rejection paths only"
  (Description, Constraints).
- **Evidence search**: PSI all-scope `ReferencesSearch` on
  `KerberosCredentialInterceptor` and `Krb5ClientLoginModuleConfig`; full-
  text scan for `KerberosCredentialInterceptor` across the entire repo.
- **Code evidence**:
  - `KerberosCredentialInterceptor` PSI refs = 1 (= self). External-module
    refs (server / driver / embedded / tests) = 0. Repo-wide text occurrences
    = only inside the class file itself; no `Class.forName(...)`-style
    string reference, no `META-INF/services` entry, no `security.json`
    reference. The class is **dead in this repo**.
  - `Krb5ClientLoginModuleConfig` PSI refs = 1 (only from
    `KerberosCredentialInterceptor.java:148`). Both are dead together.
  - `KerberosCredentialInterceptor.intercept` @ line 65 immediately enters
    networking territory: `System.setProperty("java.security.krb5.conf",
    config)` @ line 145, `new LoginContext("ignore", null, null, cfg)` +
    `lc.login()` @ line 150-151, then `GSSManager.getInstance()` /
    `GSSContext.initSecContext(...)` @ line 192-244. The constructor itself
    has no side effects (it's empty), but the **first public method
    (`intercept`)** does JAAS + GSS-API I/O that requires a KDC.
- **Verdict**: CONTRADICTED for "construction and rejection only" framing
  if the goal is *live* coverage. The class is reachable to instantiate
  (default constructor, no fields), but every public-method exit path on
  `intercept(...)` either throws on null inputs (testable) or hits LoginContext
  /GSSManager (requires KDC). After the null-input rejection paths
  (`principal == null`, `url == null && spn == null`, `config == null`,
  `ccname == null && ktname == null`), there's no further coverage to win
  without a KDC mock.
- **Detail**: The realistic move for Track 17 is to pin Kerberos as
  **dead-code** via `*DeadCodeTest` shape pin (Track 14/15/16 pattern) and
  forward to Track 22 deletion. That converts 114 uncov lines to "covered
  by structural pin" without any KDC plumbing. The Description's "limited
  rejection paths" framing should be reframed at Step decomposition to
  match this — otherwise we burn a step writing 4 null-rejection tests for
  a class with no production callers.

#### Assumption: `core/metadata/security/binary` (164 uncov, 0%) and `core/metadata/security/jwt` (10 uncov, 0%) hold live code
- **Track claim**: "Binary token tests follow the round-trip pattern from
  Tracks 12–13" (Description, "How").
- **Evidence search**: PSI all-scope `ReferencesSearch` on the binary-token
  + JWT classes; transitive entry-point trace via `BinaryTokenSerializer`
  / `JsonWebToken`; text scan for `parseWebToken` / `BinaryTokenSerializer`.
- **Code evidence**:
  - `BinaryToken` PSI refs = 3 (intra-package only, all from
    `BinaryTokenSerializer.java`). `BinaryTokenSerializer` PSI refs = 10
    (intra-package, all from `BinaryTokenPayloadDeserializer.java` /
    `BinaryTokenPayloadImpl.java`). `BinaryTokenPayloadImpl` PSI refs = 2
    (intra-package). `DistributedBinaryTokenPayload` PSI refs = **0**.
    `BinaryTokenPayloadDeserializer` PSI refs = 1 (intra-package).
  - `JsonWebToken` PSI refs = **0** (no callers anywhere — including text
    scan; the symbol is referenced only by its own file).
  - `JwtPayload` PSI refs = 1 (only `JsonWebToken.java`); `YouTrackDBJwtHeader`
    PSI refs = 2 (intra-package + `BinaryTokenSerializer.java`).
  - `TokenSign.parseWebToken` does NOT exist (only `signToken`,
    `verifyTokenSign`, `getAlgorithm`, `getDefaultKey`, `getKeys`); text
    scan for `parseWebToken` returns 0 hits across all source.
  - The whole `binary` + `jwt` package cluster is closed under self-
    reference. The only EXTERNAL-MODULE-MAIN refs into either package are:
    `Token.java` references `TokenHeader` (interface declared in `jwt/`),
    `TokenSign.java` / `TokenSignImpl.java` reference `TokenHeader` /
    `KeyProvider`, `Token.java` extends `TokenHeader`. The actual
    serialization classes (`BinaryToken`, `BinaryTokenSerializer`,
    `BinaryTokenPayloadImpl`, `JsonWebToken`, `JwtPayload`,
    `DistributedBinaryTokenPayload`, `YouTrackDBJwtHeader`,
    `TokenPayloadDeserializer`, `BinaryTokenPayloadDeserializer`) have **no
    callers in `core/main`, `server`, `driver`, `embedded`, or `tests`**.
- **Verdict**: CONTRADICTED. The 164+10 = 174 uncov lines in `binary` +
  `jwt` are dead — the entire token serialization machinery is unreachable
  via any production path in this repo. A round-trip test (from Track 13's
  pattern) would be a **structural pin** (instantiate, exercise getters/
  setters, round-trip via the serializer in isolation), not a live-
  coverage test.
- **Detail**: The Track 13 episode's WHEN-FIXED list already includes
  `RecordSerializerBinary.fromStream(byte[])` token-handling concerns — but
  not the binary-token *serialization stack*. Track 17 should reframe these
  packages identically to Track 14's `core/db/config` (5 dead public classes
  + 3 dead Builders → 95.4% via dead-code shape pin). Realistic outcome:
  binary+jwt land at >90% via shape pins, with a Track 22 deletion lockstep
  group covering the entire 174-line surface.

#### Assumption: `SymmetricKey*` family is partly live
- **Track claim**: "Symmetric key tests cover key creation, encrypt/decrypt
  round-trip, key rotation, and serialization shape" (Description, "How").
- **Evidence search**: PSI all-scope `ReferencesSearch` on the 5 classes in
  `core/security/symmetrickey`; method-level callers on `SymmetricKey`'s
  31 public methods.
- **Code evidence**:
  - `SymmetricKey` (622 LOC): self-refs = 20 (constructor / static factory
    chains), CORE TEST refs = 1 (`SymmetricKeyTest` exists with 3 active
    tests + 1 commented-out `shouldTestOSymmetricKeySecurity`), CORE MAIN
    refs into the class come only from `SymmetricKeyCI` and
    `SymmetricKeySecurity` — both of which are themselves dead.
  - `SymmetricKey` per-method PSI: most public getters/setters / static
    factories have **0 references** outside the class. Active method
    references: `setDefaultCipherTransform` (3, only from `SymmetricKeyCI`),
    `fromConfig` (1, only from `SymmetricKeySecurity`), `fromString` (2,
    only from `SymmetricKeyCI`), `fromFile` (2, only from `SymmetricKeyCI`),
    `fromKeystore(String,String,String,String)` (2, only from
    `SymmetricKeyCI`), `encrypt(String, String)` (2, only from
    `SymmetricKeyCI`), `decryptAsString(String)` (4, only from
    `SymmetricKeySecurity`).
  - `SymmetricKeyCI` PSI refs = **0**. `SymmetricKeySecurity` PSI refs =
    **0**. `UserSymmetricKeyConfig` PSI refs = 1 (only from
    `SymmetricKeySecurity`). `SymmetricKeyConfig` PSI refs = 2 (only from
    `SymmetricKey` and `UserSymmetricKeyConfig`).
  - `SecurityManager.newCredentialInterceptor()` PSI MethodReferencesSearch
    = **0**. The whole `CredentialInterceptor` SPI (which `SymmetricKeyCI`
    and `KerberosCredentialInterceptor` implement) has no caller — the
    only thing that creates one is `SecurityManager.newCredentialInterceptor()`
    @ line 340, and that method is uncalled.
- **Verdict**: CONTRADICTED for "live coverage" framing. The whole
  `symmetrickey` package is a closed dead loop:
  `SymmetricKey -> SymmetricKeyCI -> {nothing}` and
  `SymmetricKey -> SymmetricKeySecurity -> {nothing}`. The entry edges
  (`fromConfig`, `decryptAsString`, `setDefaultCipherTransform`,
  `fromString`, `fromFile`, `fromKeystore`, `encrypt(String, String)`)
  exist only because they're called inside the dead consumers.
- **Detail**: There IS one live entry: `SymmetricKey()` default ctor +
  `SymmetricKey(String, String, int)` + `SymmetricKey(SecretKey)` +
  `SymmetricKey(String, String)` are reachable from
  `SymmetricKeyTest.java`, plus a handful of getters / setters on the
  test path. The realistic split for Track 17:
  - `SymmetricKey` core constructors + `encrypt(byte[])` /
    `decrypt(String)` round-trip → live coverage via `SymmetricKeyTest`
    expansion (~150-200 lines reachable, currently ~30% covered).
  - `SymmetricKey.fromConfig` / `fromString` / `fromFile` / `fromKeystore`
    + 18 of 31 public methods → dead (covered by their dead consumers
    only). Pin via `*DeadCodeTest` and forward `SymmetricKey` partial-
    deletion + `SymmetricKeyCI` / `SymmetricKeySecurity` /
    `UserSymmetricKeyConfig` whole-class deletion to Track 22.

### PERFORMANCE IMPLICATIONS

#### Exposure: PBKDF2 iteration count default
- **Track claim**: "Cover password hashing (PBKDF2 round-trip, salt
  handling)..." (Description, "How").
- **Critical path trace**:
  1. `SecurityManager.createHashWithSalt(password)` @ line 187 reads
     `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_ITERATIONS`. The
     default in `GlobalConfiguration` is high (~65536 by JCE convention,
     verifiable at decomposition time) so a single call is ~10-50ms on
     modern hardware.
  2. Each `checkPasswordWithSalt(...)` call re-computes PBKDF2 — also
     ~10-50ms.
- **Blast radius**: A test that runs 10+ PBKDF2 round-trips at default
  iteration count adds 100-500ms to the suite. With surefire's 4-fork
  parallelism, this is bounded — but it's still 10x slower than typical
  unit tests.
- **Existing safeguards**: `getPbkdf2(...)` caches by
  `(hashedPassword|salt|iterations|bytes)` tuple in `SALT_CACHE` — repeated
  identical inputs are O(1) after the first.
- **Residual risk**: LOW. Track 17 should add a `BeforeClass` setting that
  drops `SECURITY_USER_PASSWORD_SALT_ITERATIONS` to a small value (e.g.
  100) for the test class duration, then restores. This is the same
  pattern as Track 16's `SCHEMA_*` config tweaks. Without this, password-
  hashing test coverage will be a noticeable slowdown.

### TESTABILITY & COVERAGE

#### Testability: 85% line / 70% branch on packages starting at 0%
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: Of the 1,860 uncov lines named in the track
  Description, PSI evidence above shows:
  - `core/security/kerberos` (114 uncov, 0%): 100% dead. Pin via
    `*DeadCodeTest` → ≥95% line coverage via shape pins (Track 14/15/16
    precedent).
  - `core/metadata/security/binary` (164 uncov, 0%): 100% dead. Pin via
    `*DeadCodeTest` → ≥95% via shape pins.
  - `core/metadata/security/jwt` (10 uncov, 0%): 100% dead (`JsonWebToken`
    + `JwtPayload`). Pin → ≥95%.
  - `core/metadata/security/auth` (9 uncov, 0%): live (`AuthenticationInfo`
    has 26 PSI refs across core+server, `TokenAuthInfo` and
    `UserPasswordAuthInfo` flow through `DatabaseUserAuthenticator`). Live
    coverage via DbTestBase + DefaultSecuritySystem path. Realistic ≥85%.
  - `core/security/symmetrickey` (282 uncov, 26.6%): **mixed**. Live
    portion ~150 lines (constructors + encrypt/decrypt round-trip), dead
    portion ~480 lines (`SymmetricKeyCI`, `SymmetricKeySecurity`,
    `UserSymmetricKeyConfig`, plus 18/31 dead methods on `SymmetricKey`).
    Pin dead, drive live. Realistic ≥80% line via combined approach.
  - `core/security/authenticator` (140 uncov, 25.5%): live.
    `DatabaseUserAuthenticator` / `ServerConfigAuthenticator` /
    `SystemUserAuthenticator` are hit by `DefaultSecuritySystem` default
    chain reachable from DbTestBase. Realistic ≥85%.
  - `core/security` (548 uncov, 32.1%): mixed. `SecurityManager` (361 LOC)
    is heavily live (51 refs); `TokenSignImpl` / `TokenSign` / `ParsedToken`
    / `DefaultKeyProvider` are live (driven by
    `DatabaseUserAuthenticator.verifyTokenSign`); `DefaultCI` dead;
    `DefaultSecuritySystem` (1233 LOC) is partially live (default
    auth path tested through DbTestBase). Realistic ≥75% line via DbTestBase
    + targeted unit tests, with deferral of `DefaultSecuritySystem`'s
    JSON-config branches to Track 22.
  - `core/metadata/security` (593 uncov, 72.3%): live (already at 72.3%
    via 58 existing `@Test`s). Push to 85%/70% via targeted SecurityShared
    coverage gaps.
- **Existing test infrastructure**:
  - `DbTestBase` (per-method DB lifecycle).
  - `SecurityManagerTest` (4 tests on `createHash` + `checkPassword` +
    `createHashWithSalt`).
  - `SymmetricKeyTest` (3 active tests on `SymmetricKey`, 1 commented-out).
  - `HashSaltTest` (1 test).
  - `SecuritySharedTest`, `SecurityEngineTest`, `SecurityPolicyTest`,
    `ImmutableUserTest`, `PredicateSecurityTest`, `ColumnSecurityTest`,
    `SchemaClassSecurityTest`, `SecurityResourceTest`, `TestReaderDropClass`
    — 58 existing `@Test`s in metadata/security.
- **Feasibility**: ACHIEVABLE for the **live subset** of every targeted
  package. Targets at 85% line / 70% branch are met when the dead surface is
  pinned via `*DeadCodeTest` and excluded from the numerator (precedent:
  Track 12 for `record/string`, Track 14 for `db/config`, Track 15 for
  `db/tool`). The naive read of "85%/70% on aggregate uncov" is
  INFEASIBLE without dead-code reframe — at least 700 of the 1,860 uncov
  lines are dead.
- **Detail**: The decomposition must include a Phase A re-measure step
  that confirms the live/dead split via PSI (re-running the queries above)
  and produces a per-package live-target before driving the step list. The
  Track 14/15/16 reframe pattern applies cleanly here.

#### Testability: Static singletons / process-wide state requiring `@Category(SequentialTest)`
- **Coverage target**: avoid surefire-fork-reuse contamination on tests
  that mutate static state.
- **Difficulty assessment**: Three sources of static state in scope:
  1. `SecurityManager.SALT_CACHE` (process-wide, class-load-time
     initialization). Non-trivial to reset.
  2. `SecurityManager.instance` (final singleton, no reset).
  3. `TokenSignImpl.threadLocalMac` (per-thread, surefire workers reuse).
  4. `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_ITERATIONS` (mutable
     global; tests that override it must restore in `@After`).
  5. `SymmetricKey.defaultCipherTransformation` (per-instance, but
     modified via `setDefaultCipherTransform`); not a static-state risk.
- **Existing test infrastructure**: Track 14/16 carry-forward (`@Category
  (SequentialTest)` for static-volatile dispatchers + `@Before
  Assume.assumeNotNull` for engine-shutdown races).
- **Feasibility**: ACHIEVABLE with the carry-forward conventions.
- **Detail**: `SALT_CACHE` is the trickiest. The cache is `private static`
  and can only be cleared via reflection. The realistic test design either
  (a) probes the cache observationally (hash twice, assert second call's
  timing is ≥X% faster — fragile under parallel JIT warm-up) or (b)
  ignores the cache, treating its presence as transparent (the `MessageDigest`
  output is deterministic regardless of whether the cache hit). Option (b)
  is the safe pin: tests assert `checkPasswordWithSalt(p, h) == true` and
  let the cache do its thing.

#### Testability: Cryptographic determinism for round-trip tests
- **Coverage target**: stable round-trip tests across CI and local.
- **Difficulty assessment**: PBKDF2 / HMAC / `SecurityManager.createHash`
  outputs are deterministic given fixed inputs (no randomness). The
  *salt* in `createHashWithSalt(...)` is generated via `SecureRandom` @
  `SecurityManager.java:196` — different on every call. This is fine for
  round-trip tests (`hash(p)` produces a self-describing string;
  `checkPasswordWithSalt(p, hash(p))` round-trips correctly without
  knowing the salt). It's NOT fine for "stable hash output" pins —
  any test that asserts a specific salt:iterations:hash literal would
  flake.
  - `TokenSignImpl.signToken(...)` is deterministic given the key.
  - `SymmetricKey.encrypt(byte[])` uses `Cipher.init(ENCRYPT_MODE, key)`
    where the IV (for CBC etc.) is **auto-generated by the JCE provider**
    (see comment @ `SymmetricKey.java:472-475`); the resulting JSON doc
    contains `"iv": "<base64>"` so round-trip is fine, but byte-shape
    pins on the Base64 are infeasible.
- **Existing test infrastructure**: `SecurityManagerTest` already follows
  the round-trip pattern (`hash(p)` then `checkPassword(p, hash(p))`).
- **Feasibility**: ACHIEVABLE. The round-trip pattern is stable; byte-
  shape pins on PBKDF2 hashes / encrypted blobs are NOT — any such
  attempt should be reframed as a round-trip + property-style assertion
  (`Base64.decoder.decode(iv).length == 16` etc.). This matches Track
  13's `*SerializerTest` pattern (round-trip, not literal-byte pin).
- **Detail**: Track 17 should flag this in the Decomposition: NO
  literal-byte pins on PBKDF2 / cipher outputs; only round-trip + shape-
  property assertions.

#### Testability: Test-only secrets (constants) constraint
- **Coverage target**: "Security-test secrets must be constants in the
  test, never read from env vars or files outside the test module"
  (Constraints).
- **Difficulty assessment**: PSI grep for `System.getenv` /
  `System.getProperty` in the existing `core/test` security tests:
  - `SecurityManagerTest`, `SymmetricKeyTest`, `SecuritySharedTest`,
    `SecurityEngineTest`, `SecurityPolicyTest`, `ImmutableUserTest`,
    `PredicateSecurityTest`, `ColumnSecurityTest`, `SchemaClassSecurityTest`,
    `SecurityResourceTest`, `HashSaltTest`, `TestReaderDropClass`,
    `ResourceDerivedTest`, `SchemaPropertyAccessTest` — bash grep for
    `System.getenv\|System.getProperty\|new File(\|Paths.get(` returns 0
    hits in any of these (verified at decomposition time recommended).
  - Production code that reads env vars: `KerberosCredentialInterceptor`
    @ lines 114, 121, 128 reads `KRB5_CONFIG`, `KRB5CCNAME`,
    `KRB5_CLIENT_KTNAME`. Since the class is dead, no test needs these.
- **Existing test infrastructure**: established constants-only convention.
- **Feasibility**: ACHIEVABLE. The constraint is naturally aligned with
  the existing test corpus; no carry-forward fix is needed unless a new
  test reaches into Kerberos territory (which the dead-code reframe
  removes).

---

## Part 2: Findings

### Finding R1 [should-fix]
**Certificate**: Assumption — Kerberos paths can be tested "construction
and rejection only".
**Location**: Track 17 Description, `core/security/kerberos` (entire
package).
**Issue**: PSI all-scope `ReferencesSearch` on
`KerberosCredentialInterceptor` returns 1 ref (= self); on
`Krb5ClientLoginModuleConfig` returns 1 ref (= the dead consumer above).
External-module main refs (server, driver, embedded, tests) = 0. Repo-
wide text scan finds no `Class.forName("...KerberosCredentialInterceptor")`,
no `META-INF/services` entry, no `security.json` reference. The whole 114
uncov lines (0% baseline) are dead. Writing 4 null-input rejection tests
is wasted effort: it covers ~25 lines and leaves the JAAS+GSS body uncov.
The realistic pattern is the Track 14/15/16 dead-code reframe — pin via
`KerberosCredentialInterceptorDeadCodeTest` (reflective method-signature
+ field-shape pin) and forward whole-package deletion to Track 22.
**Proposed fix**: Reframe `core/security/kerberos` from "construction +
rejection paths" to "dead-code shape pin + Track 22 deletion lockstep
group" at Phase A decomposition. Realistic outcome: ≥95% line coverage
via shape pins, 0 KDC plumbing, 1 Track 22 deletion item. Delta vs.
naive plan: +50 wasted LOC -> 0; coverage +15-25pp on the package; Track
22 grows by 1 lockstep group (`KerberosCredentialInterceptor` +
`Krb5ClientLoginModuleConfig` together).

### Finding R2 [should-fix]
**Certificate**: Assumption — `core/metadata/security/binary` and
`core/metadata/security/jwt` hold live code.
**Location**: Track 17 Description, `core/metadata/security/binary`
(164 uncov, 0%) + `core/metadata/security/jwt` (10 uncov, 0%).
**Issue**: PSI all-scope refs on the 8 binary-token classes
(`BinaryToken`, `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`,
`BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload`,
`BinaryTokenPayload` (jwt), plus the JWT classes `JsonWebToken`,
`JwtPayload`) show the entire cluster is closed under self-reference.
External-module-MAIN refs are zero except for `TokenHeader` (interface
referenced by the live `Token`/`TokenSign*`) and `TokenMetaInfo` (used
internally). `JsonWebToken` PSI refs = 0 — completely orphaned.
`DistributedBinaryTokenPayload` PSI refs = 0.
`TokenSign.parseWebToken` does not exist — the token-parsing entry
point implied by the track's "JWT round-trip" framing is absent. The
Track 13 round-trip pattern *will work in isolation* (instantiate, set
fields, serialize via `BinaryTokenSerializer`, deserialize, assert
equal) but it covers no production path. This is structural coverage,
not behavioural coverage.
**Proposed fix**: Reframe both packages identically to Track 14's
`core/db/config`. Three options at decomposition:
(a) **Pin-only**: `*DeadCodeTest` for all 8 classes; ≥95% line coverage
via shape pins; whole-cluster deletion in Track 22.
(b) **Round-trip + pin**: write the round-trip test as Track 13 did
for the live binary serializers, but tag it as a Track 22 deletion
contingency. The round-trip test itself becomes the structural pin —
deleting the classes deletes the test. Higher LOC, slightly more
defensive; matches the Track 13 precedent for "test the surface even
though it's dead, because the wire format is contractual".
(c) **Hybrid**: round-trip the simpler `BinaryToken` + `BinaryTokenSerializer`
path (likely once supported a real wire); pure-pin the rest.
Recommended: **(a)** — JWT and binary-token serialization are unused;
the wire format isn't contractual since nothing reads/writes it on the
production path.

### Finding R3 [should-fix]
**Certificate**: Assumption — `SymmetricKey*` family is partly live.
**Location**: `core/security/symmetrickey` (282 uncov, 26.6%).
**Issue**: PSI per-method refs show the package is split into a small
live core (~150 lines: 4 constructors + `encrypt(byte[])` /
`decrypt(String)` / a handful of getters) and a large dead surface
(~480 lines: `SymmetricKeyCI` whole class, `SymmetricKeySecurity`
whole class, `UserSymmetricKeyConfig` whole class, plus 18 dead public
methods on `SymmetricKey`). Direct evidence: `SymmetricKeyCI` PSI refs
= 0; `SymmetricKeySecurity` PSI refs = 0;
`SecurityManager.newCredentialInterceptor()` PSI MethodReferencesSearch
= 0. The whole `CredentialInterceptor` SPI is uncalled. The dead
methods on `SymmetricKey` (`getDefaultCipherTransform`,
`getIteration`, `getKeyAlgorithm`, `getKeySize`, `getSaltLength`,
`getSeedAlgorithm`, `getSeedPhrase`, 7 setters mirroring those, plus
`fromConfig` / `fromString` / `fromFile` / `fromKeystore` /
`fromStream` / `fromKeystore(InputStream,...)` and 2 unused encrypt
overloads) are reachable only from the dead consumers. Treating them
as "live for test purposes" is technically possible (instantiate
`SymmetricKey`, call them) but produces structural coverage of dead
code, identical pattern to Findings R1/R2.
**Proposed fix**: At decomposition, split the package into a `live`
target (live constructors, encrypt/decrypt round-trip → expand
`SymmetricKeyTest`) and a `dead` lockstep group
(`SymmetricKeyCI`, `SymmetricKeySecurity`, `UserSymmetricKeyConfig`,
plus 18 per-method dead pins on `SymmetricKey`). Use Track 15's
per-method dead-code pinning pattern so partial deletion stays valid
(`EntityHelper` precedent — 12 dead methods pinned individually).
Realistic outcome: live subset ≥85% line coverage; dead subset pinned
shape-only; package aggregate ≥80%. Track 22 absorbs:
`SymmetricKeyCI` whole-class + `SymmetricKeySecurity` whole-class +
`UserSymmetricKeyConfig` whole-class + 18 per-method `SymmetricKey`
deletions.

### Finding R4 [should-fix]
**Certificate**: Exposure — Static singletons / process-wide state in
`SecurityManager`.
**Location**: `SecurityManager.java:56` (`instance` singleton),
`SecurityManager.java:58-66` (`SALT_CACHE` static map, class-load-time
initialization), `TokenSignImpl.java:24` (`threadLocalMac` static
ThreadLocal).
**Issue**: Tests that mutate `SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`
expect a corresponding `SALT_CACHE` resize, but the cache is initialized
in a `static {}` block exactly once per classloader at first reference
to `SecurityManager`. Surefire workers cache class metadata across
test methods, so a test that expects "config change -> cache size
change" will silently fail (the cache is the original size). Any test
that asserts on cache hit/miss must be tagged `@Category(SequentialTest)`
to avoid concurrent-mutation drift across other tests on the same
worker. Additionally, `TokenSignImpl.threadLocalMac` retains a `Mac`
instance per algorithm per thread across tests; the next test on the
same worker thread sees a pre-populated map.
**Proposed fix**: Two carry-forward conventions from Track 14/12:
(a) `@Category(SequentialTest)` on any test that depends on
`SALT_CACHE`'s exact size or hit/miss state, AND a `@Before
Assume.assumeNotNull(SecurityManager.instance())` defensive guard.
(b) `@After` cleanup that resets the algorithm config to default if
the test mutated `NETWORK_TOKEN_ENCRYPTION_ALGORITHM`. The
`SerializationThreadLocal.INSTANCE.remove()` precedent from Track 12
applies. Document this in the Decomposition Constraints section.

### Finding R5 [should-fix]
**Certificate**: Testability — `core/security` (root, 548 uncov, 32.1%)
includes `DefaultSecuritySystem` (1233 LOC).
**Location**: Track 17 Description's `core/security` package mention.
**Issue**: `DefaultSecuritySystem` is ~one-third of the `core/security`
package by line count and is the entry point for the authenticator
chain (Constraint: "How: ... authenticator chain dispatch"). It is
**partially** live via `DbTestBase` (the default 3-entry chain is
exercised), but the `reloadAuthMethods` / `reloadComponent` /
`Class.forName` reflective extension paths require synthesizing a
JSON config blob and feeding it into `reloadComponent("authentication",
jsonConfig)`. Without that fixture, ~400-500 LOC remain uncov in the
class. With the fixture, all 1233 LOC are reachable but the test setup
is non-trivial (JSON building + per-section reload). The 32.1%
baseline implies ~840 LOC are already live; the gap to 75-85% is
~250-400 LOC concentrated in the JSON-extension path.
**Proposed fix**: Decomposition decision at Phase A — either (a) target
the default-chain + a single `reloadAuthMethods` JSON fixture test
(~50 lines of fixture code, drives the reflective `Class.forName`
path once) and accept the JSON-LDAP-import branches as out-of-scope
(forward to Track 22 like Track 14's `DatabaseSessionEmbedded`
residual), OR (b) accept ~75% on `core/security` aggregate with
`DefaultSecuritySystem`'s JSON branches forwarded to Track 22.
Recommended: (a). The single JSON-fixture test is a 1-step item that
buys ~150 LOC of coverage; the LDAP import path can stay deferred.

### Finding R6 [suggestion]
**Certificate**: Performance Implications — PBKDF2 iteration count
default.
**Location**: `SecurityManager.java:152, 158` (reads
`SECURITY_USER_PASSWORD_SALT_ITERATIONS`).
**Issue**: Default iteration count is 65536 by JCE convention; each
PBKDF2 call is ~10-50ms on modern hardware. A test class with 10+
hash-round-trip tests adds 100-500ms to the suite. With the SALT_CACHE
this is bounded after the first call, but cold-cache slowness is real.
**Proposed fix**: Add a `@BeforeClass` hook to security-test classes
that overrides `SECURITY_USER_PASSWORD_SALT_ITERATIONS` to a small
value (e.g. 100) for the test class duration, restored in `@AfterClass`.
Pattern: same as Track 12's `@Before` config save/restore for
`streamableClassLoader`. Document this in the Decomposition
"How" section.

### Finding R7 [suggestion]
**Certificate**: Testability — `*DeadCodeTest` shape-pin convention.
**Location**: Track 17 Description's "Carry forward Tracks 5–16
conventions".
**Issue**: The Description names "Carry forward" but doesn't list the
specific Track 14-16 patterns that apply. Findings R1-R3 collectively
imply ~3 dead-code lockstep groups with 8+ classes — the
`*DeadCodeTest` reflective method/field-signature pin convention from
Track 14/15/16 is load-bearing for this track's success.
**Proposed fix**: Decomposition's Constraints section should explicitly
name: (a) `*DeadCodeTest` shape pins (reflective `Method`,
`Constructor`, `Field` signature assertions); (b) per-method dead
pinning so partial deletion stays valid (Track 15 `EntityHelper` +
Track 16 `CollectionSelectionFactory` precedent); (c)
`@Category(SequentialTest)` for static-state mutations (Track 14
`SystemDatabase`, Track 16 `SchemaShared`); (d) `@Before
Assume.assumeNotNull` for engine-shutdown-race statics (Track 14
precedent); (e) name-keyed assertions instead of size-only on
authenticator chains (Track 15 `RIDMapper` precedent — adapted for
this track to "user-name-keyed", not "user-count-keyed"). Cite the
precedents explicitly in the step files.

### Finding R8 [suggestion]
**Certificate**: Assumption — JWT round-trip pattern.
**Location**: Track 17 Description "How: ... token sign/verify (HMAC,
JWT)".
**Issue**: The `JsonWebToken` class has zero PSI refs and there is no
`TokenSign.parseWebToken` method (only `signToken(TokenHeader, byte[])`
+ `verifyTokenSign(ParsedToken)`). The track's "JWT round-trip"
framing implies the existence of a JWT-specific parsing/sign API which
does not exist in this repo. The HMAC sign/verify path IS testable
via `TokenSignImpl.signToken` + `verifyTokenSign(ParsedToken)`, which
does NOT operate on JWT — it operates on a byte[] + ParsedToken pair.
**Proposed fix**: Reframe the "JWT" portion as "HMAC sign/verify
round-trip via `TokenSignImpl`" (no JWT specifically). Pin
`JsonWebToken` + `JwtPayload` as dead-code (Finding R2 already
absorbs this). The HMAC sign/verify is exercised today by
`DatabaseUserAuthenticator.authenticate(session, token)` →
`TokenSignImpl.verifyTokenSign(ParsedToken)`, but no test currently
drives this path (PSI: TokenSignImpl test refs = 0 outside its own
class). A new `TokenSignImplTest` standalone unit test is the right
shape: instantiate with a synthetic key, sign a known byte[] payload,
construct a `ParsedToken` via reflection or a test-only constructor,
assert verify returns true; mutate the signature, assert verify
returns false. This covers the live HMAC sign/verify slice cleanly.

### Finding R9 [suggestion]
**Certificate**: Cross-track coupling with Track 22.
**Location**: Track 22's deferred-cleanup queue absorption block.
**Issue**: Findings R1-R3 collectively forward ~3 lockstep deletion
groups (`KerberosCredentialInterceptor` + `Krb5ClientLoginModuleConfig`;
8 binary-token + jwt classes; `SymmetricKeyCI` +
`SymmetricKeySecurity` + `UserSymmetricKeyConfig` + 18 per-method
`SymmetricKey` pins; `DefaultCI`; `JsonWebToken` + `JwtPayload`).
This is a substantial growth of Track 22's queue — comparable to
Track 14's 10 dead-code-pin groups + Track 15's 8 lockstep groups.
The cross-track impact deserves explicit recording in Track 17's
strategy refresh (per the precedent set by Tracks 12-16).
**Proposed fix**: At Phase C strategy-refresh time, record the Track
22 queue growth in the same shape as Tracks 14/15: per-package
deletion lockstep group + WHEN-FIXED markers for any latent
production issue uncovered (especially the `SecurityManager.SALT_CACHE`
class-load-time staleness behaviour described in R4 — that's a latent
production-shape pin, not a deletion). Anticipate adding to Track 22
backlog: ~3-5 lockstep deletion groups + 1-2 WHEN-FIXED markers + 5-10
suggestion-tier residual items.

---

## Summary

- **0 blockers** — all risks are mitigatable via the Track 14/15/16
  reframe-and-carry-forward pattern, no Decision Record changes needed.
- **5 should-fix** — R1-R5: dead-code reframes for Kerberos /
  binary-token+jwt / `symmetrickey` (mixed live+dead) /
  static-state hygiene / `DefaultSecuritySystem` JSON-extension
  scoping. Each is a Phase A decomposition decision, not a
  blocking blocker.
- **4 suggestions** — R6-R9: PBKDF2 iteration count override,
  carry-forward convention enumeration, JWT->HMAC reframe of the
  sign/verify wording, Track 22 queue absorption shape.

The track is feasible at the package-aggregate level provided the dead-
code reframes (R1-R3) are absorbed at Phase A. Without them, the naive
"drive uncov lines via tests" reading would burn ~600-800 LOC of test
effort on unreachable production paths. The same pattern that closed
Tracks 14, 15, and 16 closes this one.
