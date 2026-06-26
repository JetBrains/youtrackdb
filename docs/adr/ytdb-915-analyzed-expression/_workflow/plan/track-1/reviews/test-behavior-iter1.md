<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: TB1, sev: suggestion, loc: AnalyzedExprTest.java:633, anchor: "### TB1 ", cert: C1, basis: "exception-message test asserts substring contains(getName()) not the full produced message; a dropped prefix or getName->getSimpleName swap partly survives"}
  - {id: TB2, sev: suggestion, loc: AnalyzedExprTest.java:643, anchor: "### TB2 ", cert: C2, basis: "copy-constructor test pins message equality only for the no-dbName path; the getMessage() suffix-decoration interaction is untested"}
evidence_base: {section: "## Evidence base", certs: 2, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
flags: [CONTRACT_OK]
-->

## Findings

### TB1 [suggestion] Exception-message test asserts a substring, not the full produced message

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`, method `unsupportedNodeExceptionCarriesClassNameInMessage` (line 633)

**Issue:** Shallow assertion on structured string data. The production constructor builds a fully determined message — `"unsupported analyzed node: " + astNodeClass.getName()` (`UnsupportedAnalyzedNodeException.java:303`). The test asserts only `ex.getMessage().contains(String.class.getName())`, which checks one substring of a string whose entire content is known and deterministic.

**Evidence (ASSERTION PRECISION CHECK):**
- PRODUCTION VALUE: `getMessage()` returns exactly `"unsupported analyzed node: java.lang.String"` for the `String.class` input used by the test (no dbName/componentName/errorCode is set, so `CoreException.getMessage()` appends no suffix — confirmed by reading `CoreException.java:64-79`).
- ASSERTION at line 633: `assertTrue(ex.getMessage().contains(String.class.getName()))` — WEAK: it passes for multiple different incorrect messages.
- FALSIFIABILITY CHECK: a mutation that drops the `"unsupported analyzed node: "` prefix (e.g. `super(astNodeClass.getName())`) still leaves the FQN in the message, so `contains(getName())` still passes — the test gives false confidence on the human-readable prefix that makes the diagnostic actionable. A mutation `getName()` → `getSimpleName()` IS caught (`"String"` vs `"java.lang.String"`), so the FQN-vs-simple-name contract the Javadoc calls out (`UnsupportedAnalyzedNodeException.java` "names the unsupported shape (e.g. `SQLJson`)") is partly pinned — but the prefix is not.

**Missing behavior:** the prefix text that distinguishes this diagnostic from a bare class name.

**Suggested fix:**
```java
@Test
public void unsupportedNodeExceptionCarriesClassNameInMessage() {
  UnsupportedAnalyzedNodeException ex = new UnsupportedAnalyzedNodeException(String.class);
  // The full message is deterministic: prefix + fully-qualified class name.
  assertEquals("unsupported analyzed node: " + String.class.getName(), ex.getMessage());
}
```

### TB2 [suggestion] Copy-constructor test pins message equality only for the suffix-free path

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`, method `unsupportedNodeExceptionCopyConstructorPreservesMessage` (line 643)

**Issue:** The copy-constructor behavior the test pins is sound for the case it constructs, but the `CoreException.getMessage()` override (`CoreException.java:64-79`) makes the copy round-trip non-trivial in the general case, and the test exercises only the trivial slice. `BaseException(BaseException)` (`BaseException.java:89-92`) seeds the copy's raw `RuntimeException` message from `exception.getMessage()` — the *decorated* string — while also copying `dbName`. For an exception with a non-null `dbName`, the copy's `getMessage()` would re-append the `DB Name="..."` suffix on top of the already-decorated raw message, so `copy.getMessage()` would NOT equal `original.getMessage()`. The test happens to pass only because the `(Class)` constructor leaves `dbName` null.

**Evidence (FALSIFIABILITY CHECK):**
- The test builds the original via `new UnsupportedAnalyzedNodeException(Integer.class)`, which reaches `super(String)` → `CoreException(String)` → `dbName == null`. With `dbName` null, `CoreException.getMessage()` appends no suffix, so original and copy render identically.
- MUTATION: the assertion would still pass if the copy constructor were broken to copy `dbName` incorrectly, or if `BaseException(BaseException)` stored a raw vs decorated message differently — because none of those paths is reached when every suffix field is null. The test cannot distinguish a faithful copy from one that mishandles the suffix machinery.

**Missing behavior:** none required for this slice's contract — `UnsupportedAnalyzedNodeException` has no constructor that sets `dbName`, so the suffix-double-append path is unreachable from this type today. This is a precision note, not a correctness gap: the test verifies exactly the behavior the slice ships. Flagging it so a later slice that adds a dbName-carrying constructor knows the copy round-trip is currently untested.

**Suggested fix (optional, defer to a slice that adds a dbName-carrying constructor):** none for S0. If a future constructor sets `dbName`, add a sibling test that constructs an original with a dbName and asserts the copy's `getMessage()` does not double-append the suffix.

## Evidence base

#### C1 CONFIRMED — exception message is a fully-determined string; substring assertion underspecifies it
`UnsupportedAnalyzedNodeException(Class)` builds `super("unsupported analyzed node: " + astNodeClass.getName())` (read in full at `UnsupportedAnalyzedNodeException.java:302-304`). `CoreException.getMessage()` (`CoreException.java:64-79`) appends DB/component/error-code suffixes only when those fields are non-null; the `(Class)` path leaves all null, so the message for `String.class` is exactly `"unsupported analyzed node: java.lang.String"`. The test's `contains(getName())` survives a prefix-dropping mutation. Refutation attempt — "maybe the prefix varies and a substring is the only stable assertion": rejected, the prefix is a compile-time string literal with no interpolation beyond the class name, so the full message is deterministic and `assertEquals` is achievable. Verdict stands as a suggestion (the FQN contract the design cares about IS partly pinned; only the prefix is unguarded).

#### C2 CONFIRMED — copy round-trip exercises only the null-suffix slice
Chain read in full: `UnsupportedAnalyzedNodeException(UnsupportedAnalyzedNodeException)` → `CommandExecutionException(CommandExecutionException)` (`CommandExecutionException.java:26-28`) → `CoreException(CoreException)` (`CoreException.java:16-18`) → `BaseException(BaseException)` (`BaseException.java:89-92`), which calls `super(exception.getMessage(), exception.getCause())` and copies `dbName`. Because `exception.getMessage()` is the *overridden* `CoreException.getMessage()`, a non-null `dbName` would make the copy double-append its suffix. The `(Class)` constructor never sets `dbName`, so the test's assertion is exercised only on the suffix-free path. Refutation attempt — "this is a real bug the test masks": rejected, no S0 constructor sets dbName so the double-append path is unreachable from this type; the test correctly verifies the shipped contract. Downgraded to a forward-looking suggestion, not a defect.
