# Track 17: Security

## Description

Write tests for the security subsystem — authentication, authorization,
token management, and encryption.

> **What** (per-package live targets — Phase A reviews refined the
> backlog's class list; the legacy `O*` FQNs no longer exist after the
> OrientDB → YouTrackDB rebrand and three of the originally-named
> classes were phantoms):
> - `core/metadata/security` (593 uncov, 72.3% — ALREADY-LIVE) — close
>   the residual `SecurityShared`, `Role`, `Rule`, `Identity`,
>   `SecurityPolicyImpl`, `SecurityResource*` JSON / role-resource /
>   `permissionToString` / `SystemRole`-via-`SecuritySystemUserImpl` gaps;
>   extend `SecurityResourceTest`, `SecurityEngineTest`,
>   `SecurityPolicyTest`, `SecuritySharedTest`, `ImmutableUserTest`,
>   `HashSaltTest`, `TestReaderDropClass` rather than create new classes
>   (existing-classes-preferred).
> - `core/security` (548 uncov, 32.1%) — `SecurityManager` (live core
>   ~360 LOC: hash/PBKDF2/salt-cache/token-encryption-config),
>   `PasswordValidator`, `TokenSign` + `TokenSignImpl` (HMAC sign/verify
>   round-trip), `ParsedToken`, `GlobalUserImpl`, `Syslog`,
>   `AuditingOperation` enum, `AuditingService` interface, `Security`
>   facade, `SecurityComponent`, `DefaultKeyProvider`,
>   `DefaultSecurityConfig`. Plus `DefaultSecuritySystem` (1 233 LOC,
>   ~one-third of the package) — **target the JSON-config-driven
>   reflective `reload` / `reloadComponent` / `reloadAuthMethods` /
>   `getClass` / `loadAuthenticators` / `loadPasswordValidator` /
>   `registerSecurityClass` paths in a dedicated step (highest-yield
>   single test class for this package per Phase A T7)**. Extend
>   `SecurityManagerTest` / `SchemaPropertyAccessTest` /
>   `ResourceDerivedTest` rather than recreate.
> - `core/security/authenticator` (140 uncov, 25.5%) — chain dispatch
>   via `DefaultSecuritySystem.initDefultAuthenticators` (default 3-entry
>   chain reachable through `DbTestBase`): `SecurityAuthenticatorAbstract`,
>   `DefaultPasswordAuthenticator`, `DatabaseUserAuthenticator`,
>   `ServerConfigAuthenticator`, `SystemUserAuthenticator`,
>   `TemporaryGlobalUser`. The reflective extension chain
>   (`reloadAuthMethods` reading JSON `authentication.authenticators[].class`
>   via `Class.forName`) is covered in the `DefaultSecuritySystem`-reload
>   step (above).
> - `core/security/symmetrickey` (282 uncov, 26.6%) — **mixed live/dead
>   split**: live core ~150 LOC (`SymmetricKey` 4 reachable constructors +
>   `encrypt(byte[])` / `decrypt(String)` round-trip + a handful of
>   getters, plus `JSONSerializerJackson` `toEntity`/`fromEntity` shape).
>   Extend `SymmetricKeyTest`. Dead surface (~480 LOC) goes through the
>   dead-code reframe step.
> - `core/metadata/security/auth` (9 uncov, 0%) — three thin record-
>   style value classes (`AuthenticationInfo`, `TokenAuthInfo`,
>   `UserPasswordAuthInfo`); trivial constructor-and-getter tests. Live
>   wiring exists (26 PSI refs on `AuthenticationInfo` across core+
>   server) but no test currently constructs them.
>
> **What** (per-package dead-code-pin targets — applied per Track 14/15/16
> precedent after Phase A PSI all-scope `ReferencesSearch` confirmed the
> following classes have **zero non-self callers** across all 5+ modules
> — `core` main, `server`, `driver`, `embedded`, `tests`,
> `gremlin-annotations`):
> - `core/security/kerberos` whole package (114 uncov, 0%):
>   `KerberosCredentialInterceptor`, `Krb5ClientLoginModuleConfig`. PSI
>   refs = 1 (= self) for both. The `CredentialInterceptor` SPI is
>   uncalled (`SecurityManager.newCredentialInterceptor()` itself has 0
>   callers). Pin via `*DeadCodeTest` reflective method-/field-signature
>   shape pins (Track 14/15/16 precedent).
> - `core/metadata/security/binary` whole package (164 uncov, 0%):
>   `BinaryToken`, `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`,
>   `BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload`
>   — all closed under self-reference; `DistributedBinaryTokenPayload`
>   has 0 refs anywhere. Header comment of
>   `DistributedBinaryTokenPayload` says *"may be removed if we do not
>   support runtime compatibility with 3.1 or less"* — this is the
>   intended outcome.
> - `core/metadata/security/jwt` JWT-specific abstractions (10 uncov, 0%):
>   `JsonWebToken` (interface, 0 implementers, 0 refs), `JwtPayload`
>   (interface, 0 implementers, 1 self-ref). The generic token
>   plumbing in the same `jwt/` package (`TokenHeader`, `TokenPayload`,
>   `TokenMetaInfo`, `KeyProvider`, `BinaryTokenPayload` interface,
>   `TokenPayloadDeserializer`) is **live** through `Token` /
>   `TokenSignImpl` and is not in the dead-code list. Also dead:
>   `YouTrackDBJwtHeader` (10 uncov; only referenced from the orphan
>   `BinaryTokenSerializer`).
> - `core/security` orphan SPI surface: `DefaultCI`,
>   `SecurityManager.newCredentialInterceptor()`,
>   `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` (whole interceptor
>   plug-in chain).
> - `core/security/symmetrickey` orphan members: `SymmetricKeyCI` whole
>   class, `SymmetricKeySecurity` whole class, `UserSymmetricKeyConfig`
>   whole class, plus 18 of 31 public methods on `SymmetricKey` reachable
>   only from those dead consumers (`fromConfig`, `fromString`,
>   `fromFile`, `fromKeystore` family, `setDefaultCipherTransform`,
>   `decryptAsString`, plus 12 dead getters/setters mirroring config
>   accessors). Per-method pinning so partial deletion stays valid
>   (Track 15 `EntityHelper` precedent — 12 methods pinned individually).
>
> Each dead-code class/method gets a `// WHEN-FIXED: Track 22 — delete
> <class-or-method>` marker. Track 22's deferred-cleanup queue absorbs
> ~12 lockstep deletion groups + 18 per-method `SymmetricKey` deletions
> at Phase C strategy refresh.
>
> **How**:
> - Cover password hashing (PBKDF2 round-trip with fixed inputs, salt
>   handling, `SecurityManager.createHashWithSalt` / `checkPasswordWithSalt`
>   self-describing-string round-trip — never assert a specific salt:
>   iterations:hash byte literal); HMAC token sign/verify via
>   `TokenSignImpl.signToken(byte[])` + `verifyTokenSign(ParsedToken)`
>   round-trip (NOT a JWT-specific API — `JsonWebToken` and
>   `TokenSign.parseWebToken` do not exist in this codebase);
>   role/permission allow/deny matrix via `SecurityShared` / `SecurityEngine`
>   driven by `DbTestBase`; authenticator chain dispatch through
>   `DefaultSecuritySystem`'s default 3-entry chain.
> - Symmetric key tests cover live constructors, `encrypt(byte[])` /
>   `decrypt(String)` round-trip, JSON `toEntity`/`fromEntity` shape,
>   and JCE-IV-auto-generation contract (assert `Base64.decode(iv).length
>   == cipher block size` — DO NOT pin literal Base64 bytes; the IV is
>   provider-generated and non-deterministic). Keystore loader
>   (`fromKeystore(file, password, alias)`) tested with a fixture
>   `.jks`/`.p12` file checked in under `core/src/test/resources/`.
> - Kerberos tests are **dead-code shape pins only** — DO NOT call
>   `KerberosCredentialInterceptor.intercept(...)` with valid args.
>   Phase A established that `intercept` at line 145 calls
>   `System.setProperty("java.security.krb5.conf", config)` *during the
>   production method, before the LoginContext call* — this mutates
>   JVM-global JAAS state for every concurrent test in the surefire
>   fork (4 parallel threads default). Reflective shape pins on
>   constructor + method signatures stay below the line-142 boundary;
>   they exercise reflection machinery and the empty constructor only,
>   never invoking `intercept`, `getServiceTicket`, or
>   `Krb5ClientLoginModuleConfig.getAppConfigurationEntry` past their
>   parameter-null guards.
> - Dead-code reframe covers Kerberos / binary-token / JWT-abstractions /
>   `CredentialInterceptor` SPI / orphan symmetric-key surface (~420
>   uncov LOC). Reflective-method/field-signature shape pins per Track
>   14/15/16 carry-forward; per-method pinning where partial deletion
>   must stay valid (Track 15 `EntityHelper` precedent).
> - **Existing-classes-preferred**: 13 existing test files (~2 653 LOC)
>   already cover parts of the surface. Each step's design must list the
>   candidate existing classes to extend before creating new ones.
>   Specifically: `SecurityManagerTest`, `HashSaltTest`,
>   `SchemaPropertyAccessTest`, `ResourceDerivedTest`,
>   `SymmetricKeyTest`, `SecurityResourceTest`, `SecurityEngineTest`,
>   `SecurityPolicyTest`, `SecuritySharedTest`, `ImmutableUserTest`,
>   `TestReaderDropClass`. **Do NOT** extend `ColumnSecurityTest` (587
>   LOC) or `PredicateSecurityTest` (658 LOC) — they are
>   integration-style, conflating with their scope dilutes the unit-
>   coverage goal.
>
> **Constraints**:
> - In-scope: only the listed `core/security*` and `core/metadata/security*`
>   packages.
> - Do NOT introduce real network or external Kerberos KDC dependencies
>   — and do NOT call `KerberosCredentialInterceptor.intercept(...)` at
>   all, because the production method mutates JVM-global state before
>   any rejection point. Tests stop at reflective shape pins.
> - Security-test secrets must be constants in the test, never read from
>   env vars or files outside the test module.
> - PBKDF2 / HMAC / cipher tests assert **round-trip** properties, never
>   literal byte/Base64 outputs (PBKDF2 salt is `SecureRandom`-generated;
>   cipher IV is JCE-provider-generated; both are non-deterministic
>   across runs).
>
> **Carry-forward conventions** (the load-bearing subset of Tracks 5–16
> precedents that this track depends on):
> - `*DeadCodeTest` reflective method-/field-signature shape pins for
>   orphan classes (Tracks 9, 10, 12, 13, 14, 15, 16).
> - Per-method dead pinning so partial deletion stays valid (Track 15
>   `EntityHelper`, Track 16 `CollectionSelectionFactory`).
> - `// WHEN-FIXED: Track 22 — delete <class-or-method>` markers on every
>   dead-pinned surface, plus falsifiable-regression markers for any
>   latent production issue we can pin observationally.
> - `@After rollbackIfLeftOpen` safety net for any DbTestBase test
>   (Tracks 8–16).
> - Corrected-baseline rule (Track 12, codified Track 14): Step 1 must
>   re-measure live coverage of the eight in-scope packages on the
>   actual track-17 commit; a post-Track-16 patch may have shifted
>   numbers by ~1–2 percentage points and the baseline numbers in this
>   description are pre-Track-17 estimates.
> - `@Category(SequentialTest)` is applied **selectively** — only on
>   tests that mutate a JVM-global mutable static (e.g.,
>   `SECURITY_USER_PASSWORD_SALT_CACHE_SIZE` reflection, JAAS
>   `java.security.krb5.conf` system property) — not as a blanket
>   discipline. Per Phase A T4: `SecurityManager.SALT_CACHE` is a
>   `Collections.synchronizedMap`-wrapped LRU memo; pure
>   `hash(p)` / `checkPassword(p, hash(p))` round-trip tests are race-
>   safe and need no sequential discipline.
> - `@BeforeClass` PBKDF2 iteration override (lower
>   `SECURITY_USER_PASSWORD_SALT_ITERATIONS` to ~100 for the test class,
>   restored in `@AfterClass`) to keep the suite fast; same shape as
>   Track 12's stream-classloader save/restore (Phase A R6).
> - Static-state inventory to handle (Phase A R4 / A3):
>   `SecurityManager.SALT_CACHE` (class-load-time init from
>   `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`),
>   `SecurityManager.instance` (final singleton — harmless),
>   `TokenSignImpl.threadLocalMac` (per-thread `Mac` cache; surefire
>   workers reuse — clear only if the test injects a custom algorithm),
>   `SecurityResource.cache` (interning ConcurrentHashMap),
>   `PropertyAccess.NO_FILTER` + `SecurityResourceAll.INSTANCE` +
>   `SecurityResourceServerOp.{SERVER, STATUS, REMOVE, ADMIN}` +
>   `SecurityResourceDatabaseOp.{DB, COPY, DROP}` +
>   `SecurityResourceSchema.INSTANCE` + `PropertyEncryptionNone.inst`
>   (static singletons — harmless if tests use unique inputs).
>
> **Interactions**:
> - Depends on Track 1 (coverage analyzer).
> - Leans on Track 14's `YouTrackDBInternalEmbedded` instantiation
>   pattern (`DefaultSecuritySystem.activate(YouTrackDBInternalEmbedded,
>   SecurityConfig)` requires the embedded context that Track 14
>   established) and Track 15's `DbTestBase` precedent.
> - Forwards to Track 22 (deferred-cleanup queue): ~12 dead-code
>   lockstep deletion groups + 18 per-method `SymmetricKey` pins + any
>   latent production issues uncovered (e.g., `SecurityManager.SALT_CACHE`
>   class-load-time staleness — pinned via observable behaviour, no
>   `WHEN-FIXED` marker needed).

## Progress
- [x] Review + decomposition
- [ ] Step implementation (6/7 complete)
- [ ] Track-level code review

## Base commit
`aadb522a9adb485faddd90bd524a90a5b5a238d2`

## Reviews completed
- [x] Technical (`reviews/track-17-technical.md`) — 2 blockers, 4 should-fix, 3 suggestions
- [x] Risk (`reviews/track-17-risk.md`) — 0 blockers, 5 should-fix, 4 suggestions
- [x] Adversarial (`reviews/track-17-adversarial.md`) — 2 blockers, 3 should-fix, 5 suggestions
- [x] Iter-1 fix application (Description rewrite + step decomposition) — applied inline below; **iteration-2 gate verification by sub-agents was deferred due to context window pressure (Phase A session ended at 27%, warning level)**. Inline self-audit of each blocker:
  - **T1 (phantom class names)**: Description's `**What:**` block now lists actual current FQNs (no `O*` prefix), drops `OJwtPayloadImpl` / `OServerUserAuthenticator` / `OKerberosAuthenticator` (none exist), and explicitly notes the rebrand-rename context.
  - **T2 + R1 + R2 + R3 + A1 (dead-code reframe)**: Description split into "live targets" (~1 440 LOC) and "dead-code-pin targets" (~420 LOC); Step 6 dedicates a full step to 12 `*DeadCodeTest` classes + per-method `SymmetricKey` pins per Track 14/15/16 carry-forward.
  - **A2 (Kerberos JVM-global hazard)**: Description's `**Constraints:**` and `**How:**` sections both prohibit invoking `KerberosCredentialInterceptor.intercept(...)` outright — tests stop at reflective shape pins, never reach line 145's `System.setProperty("java.security.krb5.conf", ...)`. Step 6's per-test-class line spells the same prohibition.
  - **Should-fix items addressed in Description**: T3 (existing-classes-preferred enumeration in `**How:**`), T4 (selective `@Category(SequentialTest)` rule in Carry-forward), T5 (Kerberos prohibition above), T6 (dead-code reframe formally closes the coverage gap), T9 / R7 (carry-forward conventions enumerated), R4 / A3 (static-state inventory in Carry-forward), R5 (`DefaultSecuritySystem` reload paths placed in Step 4), R6 (`@BeforeClass` PBKDF2 iteration override in Carry-forward), R8 (HMAC reframe in `**How:**` — JWT API does not exist), R9 (Track 22 absorption in Step 7), A4 (7 steps not 6), A5 (binary-token framing rewritten), A6 (Track 14 fixture dependency in `**Interactions:**`), A7 (per-step existing-class enumeration), A8 (`AuditingService` / `SecuritySystemUserImpl` fold-in in Step 1).
  - **Recommended next-session action**: optional iter-2 gate-verification sub-agent run (one per review type or one consolidated) to formally verify the fixes against PSI before Phase B starts. If the user accepts this self-audit, Phase A is complete and Phase B can begin in the next session.

## Steps

- [x] Step 1: `core/security` core helpers (SecurityManager + PasswordValidator + TokenSign/TokenSignImpl + ParsedToken + small surface)
  - [x] Context: safe
  > **Risk:** medium — multi-file logic in core (no HIGH triggers — tests-only,
  > capped at medium per risk-tagging Tests-only rule)
  >
  > **What was done:** Re-measured the post-Track-16 baseline for the
  > eight in-scope security packages from the existing `jacoco.xml` on
  > `aadb522a` (corrected-baseline rule). Extended
  > `SecurityManagerTest` with a `@BeforeClass` PBKDF2 iteration
  > override (lowered to 100, restored in `@AfterClass`) plus 17 new
  > tests covering hash/PBKDF2/salt round-trips for both PBKDF2-SHA1
  > and PBKDF2-SHA256, prefix-driven dispatch in `checkPassword`,
  > null/empty/unsupported-algorithm error paths, byte-to-hex edge
  > cases, deterministic SHA-256 helpers, and the singleton-instance
  > contract. Extended `HashSaltTest` with explicit-algorithm
  > overloads and the `<hexHash>:<hexSalt>:<iterations>` shape pin.
  > Added six new test classes: `TokenSignImplTest` (HMAC
  > sign/verify round-trip via `signToken(byte[])` +
  > `verifyTokenSign(ParsedToken)` with anonymous `Token` /
  > `TokenHeader` stubs because `BinaryToken` and `JsonWebToken` are
  > dead-pinned in Step 6 — covers signature mutation, payload
  > mutation, `ContextConfiguration` constructor, and
  > unknown-algorithm rejection); `PasswordValidatorTest`
  > (interface-shape pin + checked-exception declaration);
  > `ParsedTokenTest` (3-tuple value-class with reference-identity
  > contract); `GlobalUserImplTest` (POJO getters/setters +
  > `GlobalUser` interface contract); `DefaultKeyProviderTest`
  > (HmacSHA256 `SecretKeySpec` wrapping + same-key-for-any-header
  > invariant); `SecuritySurfaceShapeTest` (cheap fold-in shape pins
  > for `Security`, `SecurityComponent`, `DefaultSecurityConfig`,
  > `Syslog`, `AuditingOperation` enum, `AuditingService`). All 55
  > tests pass; Spotless clean.
  >
  > **What was discovered:** Latent issue in
  > `SecurityManager.SALT_CACHE` — the cache key is
  > `hashedPassword | salt | iterations | bytes` and **omits the
  > algorithm**. As a result, a verify call under one algorithm can
  > short-circuit on a cached PBKDF2 result computed under a
  > different algorithm (e.g., `createHashWithSalt` under
  > PBKDF2-SHA1 populates the cache, and a
  > `checkPasswordWithSalt` under `"FAKE-ALG-DOES-NOT-EXIST"` with
  > the same `password+salt+iters+bytes` returns true via cache hit
  > instead of throwing). Pinned via observable behaviour by routing
  > `shouldWrapUnknownAlgorithmInSecurityException` through
  > `createHashWithSalt` (fresh password forces a cache miss) rather
  > than `checkPasswordWithSalt`. **Forward to Track 22
  > deferred-cleanup queue** — no `WHEN-FIXED` marker (observable
  > shape is the pin).
  >
  > Corrected-baseline measurements (post-Track-16, pre-Track-17):
  > `core.metadata.security` 72.3%/56.3% (593 uncov / 2 138 total),
  > `core.security` **33.1%/22.4% (540 uncov / 807 total — drift from
  > Phase A's 32.1%/548 estimate)**, `core.security.symmetrickey`
  > 26.6%/9.4% (282/384), `core.security.authenticator` 25.5%/15.0%
  > (140/188), `core.security.kerberos` 0%/0% (114/114),
  > `core.metadata.security.binary` 0%/0% (164/164),
  > `core.metadata.security.jwt` 0%/100% (10/10),
  > `core.metadata.security.auth` 0%/100% (9/9). Aggregate target
  > ~1 860 LOC across the 8 packages.
  >
  > `InvalidPasswordException` extends `BaseException`
  > (`RuntimeException`) but is declared in `throws` on
  > `PasswordValidator.validatePassword` — pinned the declaration via
  > reflection in `PasswordValidatorTest`.
  >
  > `DefaultSecurityConfig.getSyslog()` throws
  > `UnsupportedOperationException` — pinned as observable behaviour
  > because callers use that as the "no syslog configured" signal.
  >
  > **Cross-track forward:** the latent SALT_CACHE
  > algorithm-omitted finding goes to Track 22's deferred-cleanup
  > queue; recorded for Step 7's absorption block. Step 6's
  > `*DeadCodeTest` pattern can reuse the anonymous `Token` /
  > `TokenHeader` stub idiom from `TokenSignImplTest` whenever it
  > needs a `Token` instance without `DatabaseSession` plumbing.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/SecurityManagerTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/HashSaltTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/TokenSignImplTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/PasswordValidatorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/ParsedTokenTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/GlobalUserImplTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/DefaultKeyProviderTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/SecuritySurfaceShapeTest.java` (new)
  >
  > **Commit:** `6f0c668eaa`

- [x] Step 2: `core/security/authenticator` chain dispatch
  - [x] Context: safe
  > **Risk:** low — default (extends DbTestBase-driven authentication
  > tests, no new shared infrastructure)
  >
  > **What was done:** Added four new test classes in
  > `core/security/authenticator`. `SecurityAuthenticatorAbstractTest`
  > uses a minimal concrete subclass to exercise every concrete
  > method in the abstract base class: config map keys (`name`,
  > `enabled`, `debug`, `caseSensitive`), `getAuthenticationHeader`
  > with and without a db name, `getClientSubject`,
  > `isSingleSignOnSupported`, `getUser`/`isAuthorized`/`authenticate`
  > fall-throughs, and the `SecurityAuthenticator` +
  > `SecurityComponent` interface shape pin.
  > `DefaultPasswordAuthenticatorTest` drives the in-memory user-map
  > path: config registration, null/unknown/case-insensitive
  > `getUser`, `dispose`, and the `isPasswordValid` observable
  > contract. `TemporaryGlobalUserTest` pins the POJO triple
  > (constructor, setters, null fields, `GlobalUser` interface).
  > `AuthenticatorChainDispatchTest` is a `DbTestBase` integration
  > test that drives the default 3-entry chain through
  > `DefaultSecuritySystem`: admin user authentication, unknown
  > user, wrong-password null return, chain size/order assertion,
  > `AuthenticationInfo` dispatch, `DatabaseUserAuthenticator` token
  > sign/verify/tampered/invalid-`isValid` paths, and
  > `SystemUserAuthenticator` null-args shape pins. All 1 809 core
  > tests pass; Spotless clean.
  >
  > **What was discovered:**
  > `DefaultPasswordAuthenticator.createServerUser()` reads the JSON
  > `"password"` field but passes only `(session, name, userType)`
  > to `ImmutableUser`, storing `""` as the password. The
  > `isPasswordValid()` guard in `authenticate()` returns false for
  > any user with an empty stored password, so `authenticate()`
  > **always returns null for server users registered via the
  > in-memory config map**. Pinned as observable behaviour in
  > `DefaultPasswordAuthenticatorTest#authenticateShouldReturnNullForUserWithEmptyStoredPassword`.
  > **Forward to Track 22 deferred-cleanup queue.**
  >
  > `DatabaseUserAuthenticator` test method names beginning with
  > `"server"` caused `DbTestBase` database creation to fail
  > (`YouTrackDBInternalEmbedded.checkDatabaseName` rejects names
  > starting with `"server"`); renamed to `configAuthenticator*`.
  >
  > `DefaultSecuritySystem.getPrimaryAuthenticator()` is guarded by
  > the `enabled` flag, which defaults to false in the embedded
  > context (no `security.json`). The primary authenticator is null
  > unless a full security config is loaded — pinned the null return
  > in the test.
  >
  > **Cross-track forward:** Step 4 (`DefaultSecuritySystem`
  > reflective reload paths) must synthesize a JSON config map and
  > call `loadComponents()` (setting `enabled=true`) before calling
  > `getPrimaryAuthenticator()` — bare instantiation always returns
  > null. The empty-password `createServerUser` bug joins Step 1's
  > SALT_CACHE finding in the Track 22 absorption block.
  >
  > **Critical context:** `DefaultPasswordAuthenticatorTest` names
  > that map to db names must not begin with `"server"`, `"system"`,
  > or other reserved words rejected by `checkDatabaseName`. Future
  > `DbTestBase` subclasses in this package should follow the same
  > caution.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/authenticator/SecurityAuthenticatorAbstractTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/authenticator/DefaultPasswordAuthenticatorTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/authenticator/TemporaryGlobalUserTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/authenticator/AuthenticatorChainDispatchTest.java` (new)
  >
  > **Commit:** `7ab8540020`

- [x] Step 3: `core/metadata/security` live coverage gap (Roles + Policies + Identity + Resources + Auth-info)
  - [x] Context: safe
  > **Risk:** low — default (extends existing tests, no new shared
  > infrastructure)
  >
  > **What was done:** Added 4 new test classes and extended 3
  > existing ones in `core/metadata/security` targeting the 593-uncov
  > baseline gap. New classes: `RuleAndResourceGenericTest` (`Rule`
  > bitmask logic, `ResourceGeneric` static lookup,
  > `Role.permissionToString` corners), `ImmutableSecurityPolicyTest`
  > (both constructors, immutability contract),
  > `SecurityRoleAndIdentityShapeTest` (`SecurityRole` / `Identity` /
  > `SystemRole` interface/class shape pins), `AuthInfoTest` in
  > `auth/` sub-package (all three auth value classes:
  > `AuthenticationInfo`, `TokenAuthInfo`, `UserPasswordAuthInfo`).
  > Extended `SecuritySharedTest` with `getAllUsers`/`getAllRoles`,
  > `dropRole`, `createRole` with parent, `getRoleRID`,
  > `SecurityProxy` delegation, `getVersion` increment. Extended
  > `SecurityResourceTest` with wildcard / schema / systemcollections
  > paths, `equals`/`hashCode`, and introspection getters. Extended
  > `ImmutableUserTest` with `SecuritySystemUserImpl` constructor
  > tests using the null-safe empty-dbName branch. All 1 798 core
  > tests pass; Spotless clean.
  >
  > **What was discovered:**
  > `SecuritySystemUserImpl.populateSystemRoles` has a latent NPE in
  > the `databaseName`-non-empty branch:
  > `List<String> dbNames = entity.getProperty(SystemRole.DB_FILTER);
  > for (var dbName : dbNames)` — **no null check before iteration**.
  > Regular-database roles have no `dbFilter` property, so
  > `getProperty` returns null and iteration NPEs. The null-safe
  > else-branch (empty/null `dbName`) is guarded correctly. This is
  > a production bug; `SecuritySystemUserImpl` is only safe on the
  > system database where roles have `dbFilter` set. **Forward to
  > Track 22 deferred-cleanup queue** (no `WHEN-FIXED` marker —
  > observable shape is the pin).
  >
  > **Cross-track forward:** Track 22 deferred-cleanup queue now has
  > three items pinned via observable behaviour: (a) SALT_CACHE
  > algorithm-omission bug (Step 1), (b)
  > `DefaultPasswordAuthenticator` empty-password
  > `createServerUser` bug (Step 2), (c) `SecuritySystemUserImpl.
  > populateSystemRoles` NPE in `databaseName`-non-empty branch
  > (this step).
  >
  > **Critical context:** `SecuritySystemUserImpl` tests must use
  > empty-string `dbName` (not a specific db name) when running
  > against a regular database — the `databaseName`-non-empty code
  > path NPEs because regular roles have no `dbFilter` property.
  > Future tests requiring the filtered path need to construct role
  > entities with the `dbFilter` property explicitly set.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/RuleAndResourceGenericTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/ImmutableSecurityPolicyTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/SecurityRoleAndIdentityShapeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/auth/AuthInfoTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/SecurityResourceTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/SecuritySharedTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/ImmutableUserTest.java` (modified)
  >
  > **Commit:** `00b37fff7c`

- [x] Step 4: `DefaultSecuritySystem` JSON-config-driven reflective reload paths
  - [x] Context: safe
  > **Risk:** medium — multi-file logic (introduces a synthesized-
  > JSON-config test fixture pattern that may be reused across the
  > track)
  >
  > **What was done:** Added `DefaultSecuritySystemReloadTest`, a
  > single high-yield JUnit-4 + `DbTestBase` test class targeting the
  > JSON-config-driven reflective reload paths in
  > `DefaultSecuritySystem` (1 233 LOC, the largest class in
  > `core/security`). 39 tests synthesise `Map<String, Object>`
  > security-config maps and drive them through the public
  > `reload(...)` and `reloadComponent(...)` entry points to exercise:
  > (a) `getClass` SPI lookup hit (via `registerSecurityClass`) plus
  > `Class.forName` fallback path (and its silent miss branch);
  > (b) `loadAuthenticators` chain registration with custom plugin,
  > `allowDefault` append, `enabled=false` skip, missing-name log,
  > `isAssignableFrom` reject, and the no-authenticators-key fallback
  > to `initDefultAuthenticators` that installs the canonical 3-entry
  > chain; (c) `reloadPasswordValidator` happy path plus
  > dispose-and-replace, missing-class, non-validator-class, and
  > disabled-section branches; (d) `reloadAuditingService` happy +
  > reject + disabled branches; (e) `reloadImportLDAP` error branches
  > (happy path deferred to Track 22 forward per the step
  > description); (f) `reloadComponent` null-name and null-`jsonConfig`
  > guards; (g) `register`/`unregisterSecurityClass` round-trip;
  > (h) `getConfig` / `getComponentConfig` section read-back;
  > (i) `getAuthenticationHeader` aggregation when enabled vs default
  > Basic when disabled; (j) `close()` clearing the authenticator
  > list. Test plugin classes (`TestAuthenticator`,
  > `TestPasswordValidator`, `LenientPasswordValidator`,
  > `TestAuditingService`, `TestImportLDAP`, `NotAnAuthenticator`)
  > are public static nested types resolvable by `Class.forName` via
  > their JVM names. A `TemporaryFolder` rule redirects `setSection`'s
  > persistence path to a per-test scratch file so `reloadComponent`
  > does not write to `${YOUTRACKDB_HOME}/config/security.json`. All
  > 39 tests pass; Spotless clean.
  >
  > **What was discovered:** The `setSection` helper at line 901 of
  > `DefaultSecuritySystem` writes the entire `configEntity` to disk
  > on every `reloadComponent` call. Without an override of
  > `GlobalConfiguration.SERVER_SECURITY_FILE`, the path resolves to
  > the literal string `"null/config/security.json"` (the
  > `SystemVariableResolver` returns null for an unset
  > `YOUTRACKDB_HOME`) — production code swallows the resulting
  > `IOException`. Tests therefore must redirect
  > `SERVER_SECURITY_FILE` to a writable temp file so they exercise
  > both the in-memory section update and its persistence side
  > without polluting the build tree. **No latent bug**; observable
  > behaviour is the redirect requirement, not a defect to pin.
  >
  > Inline confirmation of the Step 2 carry-forward note: the
  > embedded `DefaultSecuritySystem` starts with `enabled=false`
  > (no `security.json` loaded), so `reloadComponent`'s enabled-only
  > branches of `reloadPasswordValidator` / `reloadImportLDAP` /
  > `reloadAuditingService` are unreachable until
  > `reload(session, {"enabled": true})` flips the flag. The new
  > helper `enableAndPrepareSystem()` codifies this two-step setup
  > for future tests in this package.
  >
  > **Cross-track forward:** The `enableAndPrepareSystem()` helper,
  > the `buildAuthenticationConfig()` helper, and the `TemporaryFolder`
  > + `SERVER_SECURITY_FILE` redirect pattern are reusable for any
  > subsequent step that needs a JSON-driven security configuration
  > in test code. Track 22 deferred-cleanup queue acquires no new
  > entries from this step.
  >
  > **Critical context:** Future tests in this package that call
  > `reloadComponent` must redirect
  > `GlobalConfiguration.SERVER_SECURITY_FILE` to a temp path in
  > `@Before` and restore it in `@After` (the
  > `DefaultSecuritySystemReloadTest` pattern). Otherwise
  > `setSection` writes a phantom `security.json` relative to the
  > resolved (or null) `YOUTRACKDB_HOME`.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/DefaultSecuritySystemReloadTest.java` (new)
  >
  > **Commit:** `059812cb05`

- [x] Step 5: `core/security/symmetrickey` live core (SymmetricKey + JSON ser-de + keystore loader)
  - [x] Context: info
  > **Risk:** low — default (extends single test class with fixture
  > files)
  >
  > **What was done:** Extended `SymmetricKeyTest` with 9 new tests
  > covering the 4 live reachable constructors (no-arg,
  > `(String,String,int)`, `(SecretKey)`, `(String,String)`),
  > `encrypt(byte[])` / `decrypt(String)` round-trip, the
  > Base64-encoded JSON-document encoding shape (all 4 required
  > fields: `algorithm`, `transform`, `payload`, `iv`), the IV-length
  > invariant (16 bytes = AES block size for AES/CBC), and `Object`
  > identity semantics for `equals`/`hashCode`/`toString`. Applied
  > Spotless. All 12 `SymmetricKeyTest` tests pass; 187 total
  > security tests pass on the cumulative track diff.
  >
  > **What was discovered:** PSI `find-usages` confirmed that **all**
  > `fromKeystore` overloads (`String` path variant and `InputStream`
  > variant) are reachable only from `SymmetricKeyCI`, which is
  > itself a dead consumer. Similarly, `fromConfig`, `fromString`,
  > and `fromFile` are reachable only from `SymmetricKeyCI` and
  > `SymmetricKeySecurity` (both dead). **No live consumer exists
  > for any of these static factory methods.** Therefore, keystore
  > fixture and tests move entirely to Step 6's dead-code reframe
  > per the step description's explicit reconciliation rule. The
  > Step 5 description's "fixture `.jks`/`.p12` under
  > `core/src/test/resources/security/`" is not added — Step 6's
  > shape pins exercise reflective signature alone, no JCE
  > invocation.
  >
  > Tested-and-removed: `AES/CBC/PKCS5Padding` wrong-key decryption
  > does **not** reliably throw — the PKCS5 padding check can pass
  > with garbage bytes, so a "different key must throw" test is
  > non-deterministic. Removed the
  > `encryptedPayloadShouldNotBeDecryptableWithDifferentKey` test;
  > the round-trip test is the canonical property pin.
  >
  > **What changed from the plan:** Keystore fixture and
  > `fromKeystore` tests move to **Step 6** (not Step 5) because
  > PSI confirmed all `fromKeystore` overloads are dead-consumer-only.
  > Step 6 will include these as dead-code shape pins (reflective
  > signatures, no JCE invocation, no fixture file required) rather
  > than live-path tests. This is explicitly anticipated in the step
  > description's reconciliation rule.
  >
  > **Cross-track forward:** Step 6 must include:
  > `fromKeystore(String, String, String, String)` shape pin
  > (SymmetricKeyCI caller confirmed dead),
  > `fromKeystore(InputStream, String, String, String)` shape pin
  > (self-only ref), `fromConfig` / `fromString` / `fromFile` /
  > `fromStream` shape pins (`SymmetricKeyCI` / `SymmetricKeySecurity`
  > callers confirmed dead). The keystore fixture file
  > (`.jks`/`.p12`) originally described for Step 5 is **not needed**
  > because the keystore loader goes into dead-code pins.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/symmetrickey/SymmetricKeyTest.java` (modified)
  >
  > **Commit:** `f281c2fec8`

- [x] Step 6: Dead-code reframe — `*DeadCodeTest` shape pins for orphan classes/methods
  - [x] Context: warning
  > **Risk:** high — security (Phase A discovered Kerberos JVM-global
  > mutation hazard at line 145 of `KerberosCredentialInterceptor`;
  > tests must stop at reflective shape pins, never invoke `intercept`).
  > Plus introduces the `*DeadCodeTest` pattern across 12+ classes —
  > shared test pattern that future cleanup tracks rely on. When in
  > doubt, mark high (per risk-tagging.md).
  >
  > **What was done:** Added 16 `*DeadCodeTest` classes (109 tests
  > total post-fix) reflectively pinning the orphan classes and
  > methods across `core/security/kerberos`,
  > `core/metadata/security/binary`, `core/metadata/security/jwt`
  > (JWT-specific abstractions only), `core/security` (`DefaultCI` +
  > `SecurityManager.newCredentialInterceptor` /
  > `CLIENT_CREDENTIAL_INTERCEPTOR`), and `core/security/symmetrickey`
  > (`SymmetricKeyCI`, `SymmetricKeySecurity`, `UserSymmetricKeyConfig`,
  > plus per-method pins for the 20 dead `SymmetricKey` methods —
  > 6 dead getters, 7 dead setters, 6 dead static factories,
  > `decryptAsString`, plus `separateAlgorithm` per per-method-pinning
  > precedent). Each test class carries a `// WHEN-FIXED: Track 22 —
  > delete <class-or-method>` marker. The Kerberos pin strictly
  > stays below the line-145 `System.setProperty` boundary by
  > exercising only parameter-null guards and reflective signatures,
  > with `@Before`/`@After` save-restore of `java.security.krb5.conf`
  > as a defensive net. PSI all-scope `ReferencesSearch` verified
  > every target's dead classification before pinning.
  >
  > **Dimensional review (3 iterations, blocker resolved):**
  > Iter-1 (7 dimensions: CQ/BC/TB/TC/SE/TS/TX): 1 blocker (5-reviewer
  > convergence on missing `@Category(SequentialTest)` for
  > `SecurityManagerNewCredentialInterceptorDeadCodeTest`) + 5
  > should-fix + ~10 suggestions. Applied in commit `3ccd8a65fb` —
  > added the `SequentialTest` category + Javadoc rationale + a new
  > behavioural pin (`factoryReturnsNullForClassThatDoesNotImplementCredentialInterceptor`
  > pinning the `isAssignableFrom` guard); fixed the JWT "12
  > methods" → 13 comment; corrected the
  > `SymmetricKeyDeadMethodsDeadCodeTest` Javadoc count (6 dead
  > getters, 7 dead setters with `setDefaultCipherTransform` listed);
  > added the `separateAlgorithm` per-method shape pin; rewrote 9
  > `msg == null || msg.contains(...)` sites across 5 files into
  > `assertNotNull` + `assertTrue(msg.contains(...))`; added
  > `@Category(SequentialTest)` + `@Before`/`@After` save-restore on
  > `KerberosCredentialInterceptorDeadCodeTest`; added a new
  > `keyFile`-without-`keyAlgorithm` error pin in
  > `UserSymmetricKeyConfigDeadCodeTest`; replaced `s3cr3t` literal
  > and `/tmp/...` paths with unambiguous fictional values.
  > Iter-2 (7-dim gate check): 6/7 PASS, only CQ remained with 3
  > suggestion-tier cosmetic items (CQ4 inline `java.util.Arrays`
  > in 2 files, CQ5 inline `YouTrackDBJwtHeader` FQN in 2 files,
  > CQ7 107-char import). Iter-3 (final cleanup, applied in commit
  > `224d6d9a73`): added declared imports for `Arrays` and
  > `YouTrackDBJwtHeader`; dropped the cross-reference `{@link …}`
  > from `SymmetricKeyCIDeadCodeTest`'s Javadoc to eliminate the
  > overlong import. All targeted tests pass (33/33 on the touched
  > files), Spotless clean. **Review loop closed; only BC3 (a
  > 30-second TOCTOU race in `BinaryTokenDeadCodeTest`'s
  > `isCloseToExpire` test) is deferred to Step 7 absorption — not
  > applied here by design.**
  >
  > **What was discovered:** The plan's "18 dead `SymmetricKey`
  > methods" was off; PSI enumeration found **20** dead public
  > methods (6 getters + 7 setters + 6 static factories +
  > `decryptAsString`) plus the protected-static
  > `separateAlgorithm(String)` (only caller is dead `SymmetricKeyCI`).
  > All 21 are pinned individually so partial deletion stays valid
  > (Track 15 `EntityHelper` precedent). The `SecurityManager`
  > `newCredentialInterceptor()` factory has a third behavioural
  > branch (`Class.forName` succeeds but `isAssignableFrom` returns
  > false → null instead of `ClassCastException`) that the iter-1
  > implementer missed and was added in iter-2.
  >
  > **What changed from the plan:** Plan said "twelve `*DeadCodeTest`
  > classes" but the explicit class list enumerates 16. Created all
  > 16 explicitly named classes. `SymmetricKey` dead-method count
  > grew from 18 (plan) → 20 (PSI) → 21 (with `separateAlgorithm`).
  > Step 7 absorption block must reflect the corrected count.
  >
  > **Critical context:**
  > - Kerberos pin discipline: `KerberosCredentialInterceptor.intercept(...)`
  >   is **never** invoked past its parameter-null guards. Two test
  >   methods snapshot `java.security.krb5.conf` before/after a
  >   guarded `intercept()` call to prove the JVM-global mutation at
  >   line 145 is gated. The `@Before`/`@After` save-restore + the
  >   `@Category(SequentialTest)` annotation are belt-and-braces
  >   protection against any future regression that drops the
  >   production-side null guards.
  > - `SecurityManagerNewCredentialInterceptorDeadCodeTest` mutates
  >   `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` (a
  >   JVM-global volatile static); `@Category(SequentialTest)` is
  >   load-bearing for the surefire-fork (4 parallel threads default)
  >   isolation guarantee — do not remove.
  >
  > **Cross-track forward (Track 22 deferred-cleanup queue):**
  > - 12-cluster dead-code group (whole-class deletions): Kerberos
  >   pair (`KerberosCredentialInterceptor` + `Krb5ClientLoginModuleConfig`),
  >   binary-token quintet (`BinaryToken` + `BinaryTokenSerializer` +
  >   `BinaryTokenPayloadImpl` + `BinaryTokenPayloadDeserializer` +
  >   `DistributedBinaryTokenPayload`), JWT trio (`JsonWebToken` +
  >   `JwtPayload` + `YouTrackDBJwtHeader`), CI plug-in chain
  >   (`DefaultCI` + `SecurityManager.newCredentialInterceptor()` +
  >   `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` slot),
  >   symmetric-key trio (`SymmetricKeyCI` + `SymmetricKeySecurity` +
  >   `UserSymmetricKeyConfig`).
  > - 21 per-method `SymmetricKey` deletion entries (corrected from
  >   the plan's 18). PSI-confirmed safe-deletion order: drop
  >   `SymmetricKeyCI` first → enables `setDefaultCipherTransform`,
  >   `fromString`, `fromFile`, `fromKeystore` family, `fromStream`,
  >   `separateAlgorithm` deletions. Drop `SymmetricKeySecurity` →
  >   enables `fromConfig` + `decryptAsString` deletions. Drop
  >   `UserSymmetricKeyConfig` with `SymmetricKeySecurity` (same
  >   lockstep group). The 13 setter/getter pairs with zero callers
  >   delete independently.
  > - Deferred to Step 7 / Track 22: BC3 — relax the 30-second
  >   `isCloseToExpire` window in `BinaryTokenDeadCodeTest` (low
  >   risk on a heavily-loaded CI host) — defer alongside the
  >   binary-token cluster's whole-class deletion.
  > - Latent production issues (no `WHEN-FIXED` markers — observable
  >   shape is the pin): `UserSymmetricKeyConfig` line 133 NPE in
  >   the no-recognized-keys branch (dead-code, only triggered if
  >   `props` is non-null but contains none of `key`/`keyFile`/
  >   `keyStore`).
  >
  > **Key files (cumulative across 3 commits):**
  > - 16 new test classes under `core/src/test/java/.../security/...`
  >   (binary, jwt, kerberos, symmetrickey, plus core/security
  >   `DefaultCIDeadCodeTest` and
  >   `SecurityManagerNewCredentialInterceptorDeadCodeTest`).
  >
  > **Commits:** `7ae307140b` (initial), `3ccd8a65fb` (Review fix:
  > tighten), `224d6d9a73` (Review fix: clean up FQN imports).

- [ ] Step 7: Verification + Track 22 absorption
  > **Risk:** low — default (verification only; no test additions)
  >
  > Re-run the coverage analyzer on the eight in-scope packages,
  > confirm aggregate uplift, write the post-track baseline. Append
  > Track 22 absorption block to `implementation-backlog.md` (or
  > directly to the plan's Track 22 entry) with: ~12 dead-code
  > lockstep deletion groups, 18 per-method `SymmetricKey` deletions,
  > ~5 production issues pinned by observable behaviour (no
  > WHEN-FIXED markers needed for those — observable shape is the
  > pin), plus suggestion-tier deferred items from Phase A reviews
  > (R6 PBKDF2 micro-config, R7 carry-forward enumeration, A6 fixture
  > deps, A7 existing-test reuse, A8 cheap fold-ins, A9 Track 22
  > queue count).
