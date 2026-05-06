# Track 17 (Security) — Technical Review (iter-1)

Scope: validate Track 17's package layout, class FQNs, dead-code shape,
test isolation needs, Kerberos test-environment risk, existing test
overlap, and cross-module callers — all with PSI-backed evidence.

Codebase root: `/home/andrii0lomakin/Projects/ytdb/unit-test-coverage`
Track description: `docs/adr/unit-test-coverage/_workflow/tracks/track-17.md`

---

## Part 1: Evidence Certificates

### Component-presence + FQN audit (track's `**What:**` list vs reality)

#### Premise: `OSecurityManager`, `OTokenSign`, `OPasswordValidator`, `OSymmetricKey`, `BinaryToken`, `OJwtPayloadImpl`, `ODatabaseUserAuthenticator`, `OServerUserAuthenticator`, `OKerberosAuthenticator` exist where the backlog implies
- **Track claim**: backlog uses legacy `O*` FQN-style names from the OrientDB era.
- **Search performed**: `find` + PSI find-class for each FQN in `core` module.
- **Code location**: see verdict below.
- **Actual behavior**: every "O-prefixed" class name in the track description was renamed at the OrientDB → YouTrackDB rebrand. Current FQNs:
  - `core/security/SecurityManager.java` (was `OSecurityManager`)
  - `core/security/TokenSign.java` + `TokenSignImpl.java` (was `OTokenSign`/`OTokenSignImpl`)
  - `core/security/PasswordValidator.java` (was `OPasswordValidator`)
  - `core/security/symmetrickey/SymmetricKey.java` (was `OSymmetricKey`)
  - `core/metadata/security/binary/BinaryToken.java` (this one already had no `O` prefix)
  - `core/security/authenticator/DatabaseUserAuthenticator.java` (was `ODatabaseUserAuthenticator`)
- **Verdict**: PARTIAL — the conceptual targets exist, but several specific class names do **not**:
  - **`OJwtPayloadImpl` — DOES NOT EXIST.** Only the interface `core/metadata/security/jwt/JwtPayload.java` is present, and PSI `ClassInheritorsSearch` confirms **0 implementers** anywhere in the project.
  - **`OServerUserAuthenticator` — DOES NOT EXIST in `core/security/authenticator`.** What exists is `SystemUserAuthenticator`. There *is* a `ServerConfigAuthenticator` in the same package, plus duplicate-named copies under `server/security/authenticator/` (different module — out of scope per the track's package list).
  - **`OKerberosAuthenticator` — DOES NOT EXIST.** The Kerberos package contains only `KerberosCredentialInterceptor` (a `CredentialInterceptor`, not a `SecurityAuthenticator`) and `Krb5ClientLoginModuleConfig`.
- **Detail**: The track plan was written against an outdated/inferred class roster. The actual Kerberos surface (114 LOC, 0% coverage) is *not* an authenticator chain — it's a JAAS LoginContext-based credential interceptor that runs only when the user explicitly sets `youtrackdb.client.credentialinterceptor` to its FQN.

#### Premise: Listed packages exist
- **Track claim**: in-scope packages are `core/metadata/security/**` and `core/security/**`.
- **Search performed**: `ls core/src/main/java/com/jetbrains/youtrackdb/internal/core/{security,metadata/security}/{,binary,jwt,auth,kerberos,symmetrickey,authenticator}`
- **Code location**: all eight directories present.
- **Actual behavior**: package layout matches the backlog's bullet list exactly. Sub-package contents verified.
- **Verdict**: CONFIRMED.

---

### Dead-code reframe audit (the central finding for this track)

#### Premise: `core/metadata/security/binary` (164 uncov, 0%) is testable through a live caller
- **Track claim**: "Binary token tests follow the round-trip pattern from Tracks 12–13."
- **Search performed**: PSI `ReferencesSearch` on `BinaryToken`, `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`, `BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload`. Then `MethodReferencesSearch` on every public method of `BinaryTokenSerializer` (`serialize`, `deserialize`, `createMap`).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/security/binary/**`
- **Actual behavior**:
  - `BinaryToken` — 3 references, all internal to `BinaryTokenSerializer`.
  - `BinaryTokenSerializer` — 10 references, all internal to the same package.
  - `BinaryTokenPayloadImpl` — 2 references (its deserializer + `DistributedBinaryTokenPayload`).
  - `DistributedBinaryTokenPayload` — **0 references and 0 inheritors anywhere**.
  - Public methods `serialize`/`deserialize`/`createMap`/`getDbType`/`getDbTypeID` of `BinaryTokenSerializer` — **NO external callers** anywhere in `core`, `server`, `driver`, `embedded`, `tests`, `gremlin-annotations`, `lucene`. Only the class's own `writeString` helper has cross-class callers (and they're inside the same package).
  - The header comment of `DistributedBinaryTokenPayload` explicitly says *"may be removed if we do not support runtime compatibility with 3.1 or less"*.
- **Verdict**: WRONG — the entire `metadata/security/binary` package is **chain-dead**. Driving live coverage requires a caller that does not exist; the round-trip-test pattern from Tracks 12–13 cannot create one without inventing a fake consumer. This is the dead-code-reframe shape from Tracks 9, 10, 14, 15, 16.

#### Premise: `core/metadata/security/jwt` (10 uncov, 0%) is testable
- **Track claim**: implied by the package's inclusion in `**What:**`.
- **Search performed**: PSI `ClassInheritorsSearch` on `JsonWebToken` and `JwtPayload`; `ReferencesSearch` on each.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/security/jwt/`
- **Actual behavior**:
  - `JsonWebToken` interface — **0 inheritors and 0 references** anywhere.
  - `JwtPayload` interface — **0 inheritors**, 1 reference (in `JsonWebToken` itself, also dead).
  - `BinaryTokenPayload` (in this same `jwt` package) — 9 internal references; consumed by the dead `BinaryToken` chain above.
  - `TokenHeader`, `TokenPayload`, `TokenMetaInfo`, `KeyProvider` — referenced inside `core/security/TokenSignImpl` and similar live token-signing code (LIVE).
- **Verdict**: PARTIAL — the JWT-specific abstractions (`JsonWebToken`, `JwtPayload`) are dead; the generic token plumbing in the same package (`TokenHeader`, `TokenPayload`, `TokenMetaInfo`, `KeyProvider`, `YouTrackDBJwtHeader`) is live and exercised through `TokenSignImpl`. The 10-line "uncov" gap likely is the dead JWT interface surface.

#### Premise: `core/security/symmetrickey` is reachable via `SymmetricKeySecurity` / `SymmetricKeyCI`
- **Track claim**: "Symmetric key tests cover key creation, encrypt/decrypt round-trip, key rotation, and serialization shape."
- **Search performed**: PSI `ReferencesSearch` on `SymmetricKey`, `SymmetricKeyCI`, `SymmetricKeySecurity`, `SymmetricKeyConfig`, `UserSymmetricKeyConfig`. Cross-checked the existing `SymmetricKeyTest`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/security/symmetrickey/`
- **Actual behavior**:
  - `SymmetricKey` — only consumers are `SymmetricKeyCI`, `SymmetricKeySecurity`, and the existing `SymmetricKeyTest`. (Plus a Javadoc-only mention in `SymmetricKey` itself.)
  - `SymmetricKeyCI` — **0 references project-wide** (CredentialInterceptor reflectively loaded).
  - `SymmetricKeySecurity` — **0 references** (only mentioned in a *commented-out* test block in `SymmetricKeyTest`, marked "Fails under develop"). No inheritors of its parent `SecurityInternal` use this. Production never instantiates it.
  - `SymmetricKeyConfig` / `UserSymmetricKeyConfig` — only used inside the same package.
  - The single live entry — `SymmetricKey` itself — already has 3 round-trip tests.
- **Verdict**: WRONG — three of the five classes (`SymmetricKeyCI`, `SymmetricKeySecurity`, the dead config glue) are chain-dead. Driving live coverage on them is impossible without inventing fake users; pinning their shape via `*DeadCodeTest` + WHEN-FIXED markers is the only sound approach. `SymmetricKey` itself can be extended (key rotation, ser/de shape, base64 round-trip with explicit IVs).

#### Premise: `core/security/kerberos` is testable via construction + rejection paths
- **Track claim**: "Kerberos tests must be limited (no Kerberos infrastructure in test env) — pin construction and rejection paths only."
- **Search performed**: PSI `ReferencesSearch` on `KerberosCredentialInterceptor` and `Krb5ClientLoginModuleConfig`. Read both class bodies fully.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/security/kerberos/{KerberosCredentialInterceptor,Krb5ClientLoginModuleConfig}.java`
- **Actual behavior**:
  - `KerberosCredentialInterceptor` — **0 cross-file references**. Implements `CredentialInterceptor`, but the only method that loads a `CredentialInterceptor` (`SecurityManager.newCredentialInterceptor()`) has **0 callers project-wide**. The whole `CredentialInterceptor` plug-in surface (`DefaultCI`, `SymmetricKeyCI`, `KerberosCredentialInterceptor`) is chain-dead.
  - `KerberosCredentialInterceptor.intercept()`: does **not** bind to a KDC at construction (constructor is no-op). It binds inside `intercept()` via `LoginContext("ignore", null, null, cfg).login()` — only when called. Reading `KRB5_CONFIG` / `KRB5CCNAME` / `KRB5_CLIENT_KTNAME` env vars and `GlobalConfiguration.CLIENT_KRB5_*`. If config is null → throws `SecurityException("KRB5 Config cannot be null")`. If credential cache and keytab are both null → throws.
  - `Krb5ClientLoginModuleConfig` — 1 reference (constructed by `KerberosCredentialInterceptor`).
- **Verdict**: PARTIAL — the track's "pin construction and rejection paths only" guidance is technically achievable (no KDC bind at construction; rejection branches can be exercised by passing null/blank URLs), but the live-coverage value is near zero because no production code calls these classes. The dead-code reframe + `*DeadCodeTest` shape pin is the natural fit.

#### Premise: `core/security/authenticator` (140 uncov, 25.5%) reaches its targets through `DefaultSecuritySystem`
- **Track claim**: "authenticator chain dispatch (try-each, fall-through, first-match)."
- **Search performed**: PSI `ReferencesSearch` on each authenticator class; verified `DefaultSecuritySystem.initDefultAuthenticators()`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/security/authenticator/**` and `core/security/DefaultSecuritySystem.java:181-194`.
- **Actual behavior**: `DefaultSecuritySystem.initDefultAuthenticators` directly instantiates `ServerConfigAuthenticator`, `DatabaseUserAuthenticator`, `SystemUserAuthenticator` and chains them in that order via `setAuthenticatorList`. PSI confirms these classes are **live** with 1–4 cross-file callers each. Note: `server/security/authenticator/` has duplicate-named classes — but they live in a different package (out of scope per the track's `**Constraints:**` list). The lucene `security.json` fixture hardcodes the **server-package** FQNs, not the core ones, so do not be confused — the `core` authenticators are the production default; the server ones are the JSON-config-driven extension surface.
- **Verdict**: CONFIRMED — driving live coverage through `DefaultSecuritySystem.initDefultAuthenticators` is feasible. The 25.5% baseline matches the limited touch existing tests give the authenticator chain (mainly via `ImmutableUserTest`, `SecurityEngineTest`, `PredicateSecurityTest`).

#### Premise: `core/metadata/security/auth` (9 uncov, 0%) reachable
- **Search performed**: PSI on `AuthenticationInfo`, `TokenAuthInfo`, `UserPasswordAuthInfo`.
- **Actual behavior**: `AuthenticationInfo` is heavily referenced (26 refs across 26 files including `server/YouTrackDBServer.java`, `core/db/DatabaseSessionEmbedded`, `DefaultSecuritySystem`). `TokenAuthInfo` and `UserPasswordAuthInfo` are referenced inside `DatabaseUserAuthenticator` — they are constructed via `new …()` inside the live token-auth and password-auth paths. The `9 uncov / 0%` baseline reflects two trivial value-class records whose constructors and getters happen not to be hit by any current test even though they are wired into live flows.
- **Verdict**: CONFIRMED — these are testable via simple construction-and-getter unit tests; the live wiring exists.

---

### Static-state and isolation audit

#### Premise: `SecurityManager` has static singleton state requiring `@Category(SequentialTest)`
- **Search performed**: read `core/security/SecurityManager.java`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/security/SecurityManager.java:43-67`
- **Actual behavior**:
  - `private static final SecurityManager instance = new SecurityManager();` (line 56) — JVM-lifetime singleton, no setter.
  - `private static Map<String, byte[]> SALT_CACHE` (line 58), populated from `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE` in a `static { … }` initializer.
  - All hashing / password-check entry points are `public static` methods → no per-test reset hook.
- **Verdict**: PARTIAL — singleton itself is harmless (no observable mutation between tests). The static `SALT_CACHE` is mutated by `createHashWithSalt` / `checkPasswordWithSalt`. Two parallel tests hashing the same `(user, plaintext)` pair will race on the LRU cache, but only the cache contents — not correctness — are at risk. **`@Category(SequentialTest)` is *not* required for behavioural tests** because the cache is functionally a memo: every bucket maps `(plaintext|hash) → salt-bytes`, and the helper recomputes on miss. Track 14's `Assume.assumeNotNull` static-volatile-dispatcher pattern does **not** apply here (no swappable global). Add a `@After` that clears `SALT_CACHE` only if a test explicitly reads/mutates `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`.

#### Premise: Existing tests in the security packages overlap the planned scope
- **Search performed**: `ls` plus per-file LOC + read of headline tests.
- **Code location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/{security,metadata/security}/**`
- **Actual behavior**: substantial existing coverage:
  - `SecurityManagerTest` (56 LOC) — covers SHA-256 + PBKDF2 + salted hash round-trips.
  - `ColumnSecurityTest` (587 LOC), `PredicateSecurityTest` (658 LOC), `SchemaClassSecurityTest` (85 LOC), `SecurityEngineTest` (306 LOC), `SecurityPolicyTest` (188 LOC), `SecurityResourceTest` (124 LOC), `SecuritySharedTest` (144 LOC) — heavy DbTestBase-driven coverage of metadata/security/* (matching the 72.3% line baseline).
  - `HashSaltTest` (21 LOC), `ImmutableUserTest` (68 LOC), `TestReaderDropClass` (24 LOC).
  - `SymmetricKeyTest` (92 LOC) — 3 round-trip tests + 1 commented-out `OSymmetricKeySecurity` test.
- **Verdict**: PARTIAL — the existing suite already drives `metadata/security` to 72.3%. **Track 17's marginal-coverage opportunity in `metadata/security` is small** — most of the 593 uncovered lines are in `SecurityShared`, `Role`, `Rule`, and JSON serialization/round-trip paths. The biggest unique-to-Track-17 ROI is in `core/security` (32.1%) and `core/security/authenticator` (25.5%) — through additional `DefaultSecuritySystem`-style integration tests targeting `TokenSignImpl`, password-validator-loading, default-authenticator chain dispatch, and `DefaultSecuritySystem.reload`/`reloadComponent`/`getClass` reflective loading.

---

### Cross-module / SPI integration audit

#### Integration: `CredentialInterceptor` reflective plug-in chain
- **Plan claim**: implicit — `KerberosCredentialInterceptor` test-pinning suggests the class is reachable via configuration.
- **Actual entry point**: `core/security/SecurityManager.java:340-360` (`newCredentialInterceptor`) reads `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` and `Class.forName`s the configured class.
- **Caller analysis**: PSI `ReferencesSearch` on `SecurityManager.newCredentialInterceptor()` → **0 callers**. PSI `ReferencesSearch` on `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` → 1 reference (the loader itself).
- **Breaking change risk**: none — there is no live caller chain.
- **Verdict**: CALLERS AT RISK (of the dead-code variety) — the entire `CredentialInterceptor` SPI is dead. Removing `DefaultCI`, `SymmetricKeyCI`, `KerberosCredentialInterceptor`, `Krb5ClientLoginModuleConfig`, **and** `SecurityManager.newCredentialInterceptor()` + the unused `CLIENT_CREDENTIAL_INTERCEPTOR` config would cleanly delete ~250 LOC of dead code. Pinning via `*DeadCodeTest` is the recommended Track-17 action; deletion is a Track 22 cleanup-queue item.

#### Integration: `SecurityAuthenticator` SPI plug-in chain (JSON-config-driven)
- **Plan claim**: implicit.
- **Actual entry point**: `DefaultSecuritySystem.getClass(jsonConfig)` (line 202-220) reads `class` from JSON, looks it up in `securityClassMap` first, then falls back to `Class.forName`.
- **Caller analysis**: 4 callers — `loadAuthenticators` (line 776), `loadPasswordValidator` (line 1038), `loadLdapImporter` (line 1077), `loadAuditing` (line 1116). The default authenticator list is created without going through this loader (`initDefultAuthenticators` directly instantiates the three core authenticators).
- **Breaking change risk**: none — this is read-only reflection.
- **Verdict**: MATCHES — testing the JSON-driven authenticator/password-validator loader requires synthesizing a `Map<String,Object>` config and calling `DefaultSecuritySystem.reload(...)`. This reaches `getClass`, the security-class registration map, and the `Class.forName` fallback. It is exactly the angle Track 17 needs to lift `core/security` from 32.1%.

#### Integration: `core/security/authenticator/SecurityAuthenticatorAbstract` and overrides
- **Plan claim**: implicit by listing all four authenticator classes.
- **Actual entry point**: `DefaultSecuritySystem.initDefultAuthenticators` (line 180-195).
- **Caller analysis**: PSI on each:
  - `DefaultPasswordAuthenticator` — 2 refs total, 1 in `youtrackdb-server` (`server/security/authenticator/DefaultPasswordAuthenticator` re-implements the same class but extends the core one — verify that the server class is *actually* derived from the core one, not a parallel implementation).
  - `DatabaseUserAuthenticator` — 3 refs, all in `core` (inside `DefaultSecuritySystem`).
  - `SystemUserAuthenticator` — 4 refs, 1 in `youtrackdb-server`.
  - `ServerConfigAuthenticator` — 4 refs, 1 in `youtrackdb-server`.
- **Breaking change risk**: low — adding tests against the core authenticators won't disturb the server module's parallel hierarchy.
- **Verdict**: MATCHES.

---

### Coverage-arithmetic plausibility

#### Premise: 85%/70% targets are reachable on this track's package set
- **Search performed**: read coverage-baseline for the eight in-scope packages; cross-referenced PSI dead-code findings.
- **Code location**: `docs/adr/unit-test-coverage/_workflow/coverage-baseline.md:38-91`
- **Actual behavior** (totals from baseline):
  - `metadata/security` 593 uncov / 2138 total — already 72.3% line, branch 56.3%. Closing the 13-pt gap is feasible.
  - `core/security` 548 uncov / 807 total — 32.1%. Reaching 85% needs +427 more covered lines. Doable, but **only if the dead `CredentialInterceptor` plumbing in `SecurityManager.newCredentialInterceptor` is excluded or pinned**.
  - `core/security/authenticator` 140 uncov / 188 total — 25.5%. To 85%: +112 more covered lines. Achievable through `DefaultSecuritySystem`-driven tests.
  - `core/security/symmetrickey` 282 uncov / 384 total — 26.6%. To 85%: +225. **Three of the five files (~200 LOC) are dead.** Reaching 85% on the live `SymmetricKey` only requires another ~80 LOC of round-trip + serialization-shape tests.
  - `core/metadata/security/binary` 164 uncov / 164 total — 0%. **All 164 LOC are chain-dead.** Live-coverage target is unreachable.
  - `core/metadata/security/jwt` 10 uncov / 10 total — 0%. **All 10 LOC are dead JWT interfaces.**
  - `core/metadata/security/auth` 9 uncov / 9 total — 0%. Three thin record-style classes; trivially testable.
  - `core/security/kerberos` 114 uncov / 114 total — 0%. **Chain-dead.** Pin-only.
- **Verdict**: PARTIAL — without dead-code exclusion, three packages (`binary`, `jwt`, `kerberos`) **cannot** reach 85% via live coverage; one (`symmetrickey`) needs ~50% of its lines excluded. Per D4 and the dead-code reframe pattern, these require `*DeadCodeTest` shape pins + WHEN-FIXED markers, with deletion deferred to Track 22's cleanup queue. Track 17's effective coverage gain therefore concentrates on `metadata/security` (live), `core/security` (live core + dead `newCredentialInterceptor`), and `core/security/authenticator` (live).

---

## Part 2: Findings

### Finding T1 [blocker]
**Certificate**: "Component-presence + FQN audit (track's `**What:**` list vs reality)"
**Location**: `track-17.md` `**What:**` bullet list and supporting prose
**Issue**: Three classes named in the backlog **do not exist** under any current FQN:
- `OJwtPayloadImpl` — only the empty `JwtPayload` interface is present, with **0 implementers**.
- `OServerUserAuthenticator` — closest match is `ServerConfigAuthenticator` (an entirely different role) or `SystemUserAuthenticator` (also different).
- `OKerberosAuthenticator` — there is no Kerberos *authenticator*; only `KerberosCredentialInterceptor` (a `CredentialInterceptor`, not a `SecurityAuthenticator`).

In addition, all "O-prefixed" names should be rewritten without the `O` since the OrientDB rebrand. A track plan that instantiates non-existent classes will fail at compile time.
**Proposed fix**: replace the targeted-class list in the track description with the actual FQNs (no `O` prefix) and drop the three phantom classes. Suggested replacement (Phase A decomposition input):
- Token sign/verify: `TokenSign` interface + `TokenSignImpl` (only implementer); `ParsedToken` value class.
- Authenticators: `SecurityAuthenticatorAbstract`, `DefaultPasswordAuthenticator`, `DatabaseUserAuthenticator`, `ServerConfigAuthenticator`, `SystemUserAuthenticator`.
- Symmetric key: `SymmetricKey` (round-trip, key rotation, base64 ser/de). Pin `SymmetricKeyCI`/`SymmetricKeySecurity`/`SymmetricKeyConfig`/`UserSymmetricKeyConfig` as dead.
- Binary tokens: pin entire `metadata/security/binary` package as dead.
- JWT: pin `JsonWebToken` + `JwtPayload` as dead; live tokens via `TokenHeader`/`TokenPayload`/`TokenMetaInfo`/`KeyProvider`/`YouTrackDBJwtHeader` exercised through `TokenSignImpl`.
- Kerberos: pin `KerberosCredentialInterceptor` + `Krb5ClientLoginModuleConfig` as dead.
- Auth-info value classes: trivial constructor/getter tests for `AuthenticationInfo`, `TokenAuthInfo`, `UserPasswordAuthInfo`.

### Finding T2 [blocker]
**Certificate**: dead-code audit on `core/metadata/security/binary`, `core/metadata/security/jwt`, `core/security/kerberos`, plus the `CredentialInterceptor` SPI in `core/security`.
**Location**: track-17.md `**How:**` bullet — "Binary token tests follow the round-trip pattern from Tracks 12–13" and "Kerberos tests must be limited (no Kerberos infrastructure in test env) — pin construction and rejection paths only."
**Issue**: PSI `ReferencesSearch` shows that the entire `binary` package, the JWT-specific abstractions in the `jwt` package, the entire Kerberos package, and the entire `CredentialInterceptor` SPI (`DefaultCI`, `SymmetricKeyCI`, `KerberosCredentialInterceptor`, plus `SecurityManager.newCredentialInterceptor()`) are **chain-dead** — no production caller reaches them. `BinaryTokenSerializer.serialize` / `deserialize` / `createMap` have **zero** external callers across all 5 modules. The "round-trip pattern from Tracks 12–13" presupposes a live caller; instructing this track to drive live coverage on dead code will produce fragile, scaffold-only tests that lock in dead behaviour.

This is the **dead-code reframe** pattern from Tracks 9, 10, 14, 15, 16 — the same shape that decided the `LiveQueryDeadCodeTest`, `FetchHelperDeadCodeTest`, `SQLFunctionFormatMiscDeadTest` outcomes.
**Proposed fix**: split Track 17 explicitly into a *live* slab and a *dead* slab. For the dead slab, drop the round-trip tests and use `*DeadCodeTest` shape pins:
- `BinaryTokenDeadCodeTest` — pin `BinaryToken`, `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`, `BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload` shapes (constructors, methods, modifiers) with `// WHEN-FIXED: Track 22 — delete metadata/security/binary` markers.
- `JwtInterfacesDeadCodeTest` — pin `JsonWebToken`, `JwtPayload` shape with the same marker.
- `KerberosDeadCodeTest` — pin `KerberosCredentialInterceptor`, `Krb5ClientLoginModuleConfig` shape.
- `CredentialInterceptorDeadCodeTest` — pin `DefaultCI`, `SymmetricKeyCI`, `CredentialInterceptor` interface, plus `SecurityManager.newCredentialInterceptor()` and `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` (whole interceptor SPI).
- `SymmetricKeySecurityDeadCodeTest` + `SymmetricKeyConfigDeadCodeTest` — same shape pin.

Live slab keeps the round-trip / chain-dispatch / encryption tests for `SymmetricKey`, the four authenticators (via `DefaultSecuritySystem`), `TokenSignImpl`, `SecurityManager` (hash + salt-cache + PBKDF2), `ImmutableUser`/`ImmutableRole`, and the auth-info value classes.

### Finding T3 [should-fix]
**Certificate**: Existing-test-overlap audit (above).
**Location**: `track-17.md` `**Constraints:**` — "In-scope: only the listed `core/security*` and `core/metadata/security*` packages."
**Issue**: `core/metadata/security` already sits at 72.3% line / 56.3% branch — the highest baseline in the track's package set — because of seven existing tests totaling ~1,800 LOC (`ColumnSecurityTest`, `PredicateSecurityTest`, `SchemaClassSecurityTest`, `SecurityEngineTest`, `SecurityPolicyTest`, `SecurityResourceTest`, `SecuritySharedTest`). The existing scope is largely DbTestBase-driven and exercises the live security flow end-to-end. Track 17's marginal ROI in this package is much lower than the 593 uncov / 2138 total numbers suggest at first glance — the residual gap is concentrated in JSON serialization, role-resource introspection, `Rule.permissionToString` corners, and `SystemRole`'s `SecuritySystemUserImpl` integration. Naively writing new, unscoped tests against `metadata/security` risks duplicating coverage already achieved.
**Proposed fix**: during decomposition, run a per-class JaCoCo gap analysis against the existing tests' touch surface and target only the residual uncovered methods. Avoid writing duplicate `assertNotNull(securityResource.parseResource("database.class.X"))` smoke tests — the existing `SecurityResourceTest` already covers parser dispatch.

### Finding T4 [should-fix]
**Certificate**: Static-state audit on `SecurityManager`.
**Location**: `track-17.md` carry-forward bullet for "`@Category(SequentialTest)` + `@Before Assume.assumeNotNull` for static volatile dispatchers (relevant to `OSecurity*` singletons)" (in slim plan, Track 16 episode).
**Issue**: `SecurityManager` does **not** have a swappable static-volatile dispatcher. It has:
1. A `private static final SecurityManager instance = new SecurityManager()` — read-only, harmless.
2. A `private static Map<String, byte[]> SALT_CACHE` initialized once from `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE` and mutated by `createHashWithSalt`/`checkPasswordWithSalt`.

Mechanical application of the Track 14 `Assume.assumeNotNull` pattern is unwarranted because there is nothing to swap out. The `SequentialTest` category is also unjustified for behavioural password-hashing tests — the salt cache is a memo, races affect cache contents, not test outcomes. Mis-applying `SequentialTest` here would slow the suite down with no upside.
**Proposed fix**: in the decomposition, only apply `@Category(SequentialTest)` to tests that *intentionally* mutate `GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_CACHE_SIZE`. For the rest, rely on the fact that hashing entry points are pure functions; a per-test salt-cache reset is unnecessary.

### Finding T5 [should-fix]
**Certificate**: Kerberos-test-environment-risk audit.
**Location**: `track-17.md` `**How:**` — "Kerberos tests must be limited (no Kerberos infrastructure in test env) — pin construction and rejection paths only."
**Issue**: The constraint is real but understated: `KerberosCredentialInterceptor.intercept()` reads three OS env vars (`KRB5_CONFIG`, `KRB5CCNAME`, `KRB5_CLIENT_KTNAME`) before deciding whether to throw or proceed. On a CI agent that *happens* to have these set (e.g., a developer workstation, an LDAP-joined runner), the rejection-path test for "config null → SecurityException" can flip to a `LoginException` from a real KDC interaction.

Additionally, `intercept()` calls `System.setProperty("java.security.krb5.conf", config)` — a JVM-wide side effect.
**Proposed fix**: regardless of whether this becomes a `*DeadCodeTest` shape pin (per Finding T2) or a runtime test, scope every Kerberos test with: (a) `@Before` clears the three env vars *if* a child-process boundary is used (not possible inside the JVM — env vars are read-only), so instead (b) directly set both `GlobalConfiguration.CLIENT_KRB5_CONFIG` and `GlobalConfiguration.CLIENT_KRB5_CCNAME` to non-null garbage before calling `intercept` to short-circuit on the JAAS `LoginException` branch deterministically; (c) `@After` restore `java.security.krb5.conf` system property and `GlobalConfiguration.CLIENT_KRB5_*` mutations; (d) mark `@Category(SequentialTest)` because of the JVM-wide `System.setProperty` mutation. The cleaner alternative (per T2) is the dead-code reframe — no runtime tests at all, just shape pins.

### Finding T6 [should-fix]
**Certificate**: Coverage-arithmetic plausibility audit.
**Location**: `track-17.md` is silent on D4's "lower targets for hard-to-test" allowance.
**Issue**: Three of the eight in-scope packages (`binary`, `jwt`, `kerberos`) are 0% by virtue of being chain-dead, not by virtue of being hard-to-test. Without explicit dead-code exclusion via `*DeadCodeTest` shape pins (which JaCoCo treats as covered branches), the post-Track-17 numbers will *appear* to underperform the 85%/70% targets — but the gap is structural, not a track failure. Track 17 should explicitly state, in its post-mortem track-episode, that the binary/jwt/kerberos shortfall is dead-code overhead absorbed by Track 22's cleanup queue, and exclude those LOC from the Track 17 success metric.
**Proposed fix**: during decomposition, add a Step at the start of the track that runs the dead-code PSI audit (using the same script in this review), records the dead LOC by package, and writes the result into the track's success criteria so the verification step doesn't fail spuriously. The shape pin tests — counted as "coverage" by the gate (since they execute every method) — naturally close the formal gap; this is the same pattern used in Tracks 14/15/16.

### Finding T7 [suggestion]
**Certificate**: `core/security` 32.1% coverage gap composition + `DefaultSecuritySystem` reflective-loader entry points.
**Location**: track decomposition input.
**Issue**: The biggest unique-to-Track-17 ROI is `core/security/DefaultSecuritySystem` (declared in `core/security`, not `core/security/authenticator`). PSI shows it has the central reflective-loader logic for authenticators / password validators / auditing — all live entry points whose live-coverage delta is the largest in the track's footprint. The track description doesn't call this class out explicitly.
**Proposed fix**: add a step `DefaultSecuritySystemReloadTest` that drives `reload(session, configEntity)` with synthesized JSON config maps to exercise: (1) `getClass` SPI lookup hit (registered class), (2) `getClass` `Class.forName` fallback miss, (3) `loadAuthenticators` chain registration, (4) `loadPasswordValidator` happy + invalid-class paths, (5) `reloadComponent` for an authenticator, (6) `registerSecurityClass` / `unregisterSecurityClass` round-trip. This is the highest-yield single test class for closing the `core/security` gap.

### Finding T8 [suggestion]
**Certificate**: `SymmetricKey` orphan analysis + existing `SymmetricKeyTest` shape.
**Location**: `track-17.md` `**How:**` — "Symmetric key tests cover key creation, encrypt/decrypt round-trip, key rotation, and serialization shape."
**Issue**: The existing `SymmetricKeyTest` already covers the three obvious round-trip shapes (default constructor, AES-key-from-string, generated AES-128). The remaining ~282 uncov in `core/security/symmetrickey` is dominated by `SymmetricKeyCI` and `SymmetricKeySecurity` (both dead) plus `SymmetricKey`'s less-common branches (keystore loading, key-from-`SecretKey`, equals/hashCode/toString, JSON ser/de via `toEntity`/`fromEntity`).
**Proposed fix**: extend `SymmetricKeyTest` — don't create a new test class — to cover the keystore-loader path (`SymmetricKey.fromKeystore(file, password, alias)` with a test-fixture `.jks`/`.p12` file), the `toEntity`/`fromEntity` round-trip, equality/`toString`, and an explicit-IV encrypt branch. Pin the dead `SymmetricKeyCI` / `SymmetricKeySecurity` separately per Finding T2.

### Finding T9 [suggestion]
**Certificate**: Track ordering + cross-track precedent.
**Location**: track-17.md is silent on which prior-track conventions apply.
**Issue**: The slim plan's Track 16 episode cites carry-forward conventions for Track 17, but Track 17's own `**How:**` only says "Carry forward Tracks 5–16 conventions" without enumerating which ones bind. Three are load-bearing for this track:
- `*DeadCodeTest` + `// WHEN-FIXED: Track 22 — delete <class>` markers (Tracks 9, 10, 12, 13, 14, 15, 16).
- `@After rollbackIfLeftOpen` safety net for any test using `DbTestBase` (Tracks 8–16).
- corrected-baseline rule (Track 16) — re-measure the eight in-scope packages on the actual track-17 commit, since post-Track-16 patches may have shifted coverage by ~1–2 percentage points.

**Proposed fix**: enumerate the three above explicitly in the track description so decomposition phases don't re-derive them. Also add the explicit dead-code-PSI-audit step as Step 1 (per T6).

---

## Summary

- **Blocker**: 2 (T1 phantom classes; T2 dead-code reframe needed for binary/jwt/kerberos/CredentialInterceptor SPI)
- **Should-fix**: 4 (T3 existing-test overlap; T4 unwarranted SequentialTest pattern application; T5 Kerberos env-var contamination + JVM-wide setProperty; T6 coverage-arithmetic vs dead-code accounting)
- **Suggestion**: 3 (T7 DefaultSecuritySystem high-ROI path; T8 extend SymmetricKeyTest in place; T9 enumerate carry-forward conventions)

**Top-3 highest-leverage findings**:
1. **T2** — without the dead-code reframe, ~440 LOC of dead code (binary 164 + jwt 10 + kerberos 114 + CredentialInterceptor SPI ~150) will either drag the track below targets or invite scaffold-only tests that pin dead behaviour. Apply the Tracks 9/10/14/15/16 `*DeadCodeTest` + WHEN-FIXED-marker pattern.
2. **T1** — three named classes don't exist; the track will fail at compile time on the first decomposition step that tries to import `OJwtPayloadImpl` / `OServerUserAuthenticator` / `OKerberosAuthenticator`. Rewrite the class list with current FQNs.
3. **T7** — the highest live-coverage win is `DefaultSecuritySystem.reload`/`getClass`/`registerSecurityClass`, currently absent from the track description. Adding one targeted step here moves `core/security` from 32% toward 70%+ in a single shot.
