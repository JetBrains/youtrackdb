Review documentation files for grammar, factual accuracy, and query correctness.

The user will provide a path to a single document or a folder containing documents.
Use `$ARGUMENTS` as the target path. If no argument is provided, ask the user for the path.

## Scope

This command reviews Markdown documentation files (`.md`) covering:
1. **Grammar and style** — punctuation, spelling, word choice, hyphenation, sentence structure
2. **Factual claims** — verify statements about database behavior against the actual codebase
3. **Query correctness** — validate all YQL/SQL code examples by executing them on a real in-memory database
4. **Internal consistency** — cross-reference links, terminology, and naming conventions within and across documents
5. **Code example formatting** — verify code blocks have correct language tags, consistent style

## Workflow

### Step 1: Discover target files

1. If `$ARGUMENTS` points to a single `.md` file, review that file.
2. If `$ARGUMENTS` points to a directory, discover all `.md` files in it recursively using Glob.
3. Read each file and build a review queue.

### Step 2: Grammar and style review (per file)

For each document, check for:
- **Punctuation**: missing commas after introductory phrases ("For this reason,"), before conjunctions in compound sentences, around parenthetical clauses.
- **Spelling**: typos, common misspellings (e.g., "straight forward" → "straightforward").
- **Hyphenation**: compound adjectives before nouns need hyphens (e.g., "case-insensitive", "not-indexed").
- **Word choice**: awkward phrasing (e.g., "suggest to use" → "suggest using", "look to" → "refer to").
- **Consistency**: same terms spelled/capitalized the same way throughout. Check `YouTrackDB`, `YQL`, class names, etc.
- **Formatting**: extra spaces before colons/punctuation, double spaces, inconsistent heading levels.
- **Code block language tags**: ensure ` ```sql `, ` ```java `, etc. are present and correct.

Produce a table of findings per file: line number, issue, suggested fix.

### Step 3: Link and reference validation (per file)

1. Extract all Markdown links `[text](target)` and anchor links `[text](#anchor)`.
2. For file links (e.g., `YQL-Where.md`), check if the referenced file exists relative to the document using Glob. Report missing files but **do not block the review** — flag them as warnings.
3. For anchor links, check if the referenced heading exists in the target file.
4. Check for inconsistent link targets (e.g., a file linked as both `SQL-Syntax.md` and `YQL-Syntax.md` when only one exists).

### Step 4: Factual claim extraction

Read through each document and extract all **verifiable factual claims** about YouTrackDB / YQL behavior. Examples:
- "Keywords and class names are case-insensitive"
- "Field names and values are case-sensitive"
- "YouTrackDB does not support the HAVING keyword"
- "YouTrackDB allows only one class as the target"
- "The YQL engine automatically recognizes if any indexes can be used"

For each claim, note the file, line number, and the exact claim text.

### Step 5: Query extraction

Extract all YQL/SQL code examples from fenced code blocks (` ```sql ` sections). For each query, note:
- The file and line number
- Whether it is a YouTrackDB YQL query or a standard SQL example (for comparison only)
- Whether it should succeed or fail (some examples demonstrate unsupported syntax)

### Step 6: Build validation test

Add new test methods to the **persistent** `DocValidationTest` class (`core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/DocValidationTest.java`) that validate extracted queries and factual claims against a real in-memory YouTrackDB instance using the **Gremlin API with `yql()` methods**. If the test class does not exist yet, create it with the following pattern:

```java
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.nio.file.Path;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class DocValidationTest {
  private static YouTrackDB youTrackDB;
  private static Path dbPath;
  private YTDBGraphTraversalSource g;

  @BeforeClass
  public static void setUpClass() throws Exception {
    dbPath = Path.of(System.getProperty("java.io.tmpdir"), "doc-validation-test");
    youTrackDB = YourTracks.instance(dbPath.toString());
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");
  }

  @AfterClass
  public static void tearDownClass() {
    youTrackDB.close();
  }

  @Before
  public void setUp() {
    g = youTrackDB.openTraversal("test", "admin", "admin");
  }

  @After
  public void tearDown() {
    g.close();
  }
}
```

**CRITICAL: Only use the public Gremlin API.** Never use internal classes like `DatabaseSessionEmbedded`, `YouTrackDBImpl`, `DbTestBase`, or anything under `com.jetbrains.youtrackdb.internal`. The test class must only import from `com.jetbrains.youtrackdb.api.*` and standard TinkerPop packages (`org.apache.tinkerpop.gremlin.*`).

Key patterns for writing tests:
- **Schema commands** (CREATE CLASS, CREATE PROPERTY, CREATE INDEX) run outside transactions using `g.command()`: `g.command("CREATE CLASS Foo EXTENDS V")` — always use `EXTENDS V` (vertex) or `EXTENDS E` (edge) so results are compatible with the Gremlin result mapper.
- **Creating data** — use `CREATE VERTEX` (not `INSERT INTO`) inside transactions:
  ```java
  g.executeInTx(tx -> {
    tx.yql("CREATE VERTEX Foo SET name = 'bar'").iterate();
  });
  ```
- **Updating data** — use `UPDATE` inside transactions via `yql().iterate()`:
  ```java
  g.executeInTx(tx -> {
    tx.yql("UPDATE Foo SET name = 'baz' WHERE name = 'bar'").iterate();
  });
  ```
- **Querying data** — use `SELECT` inside transactions via `g.computeInTx()`:
  ```java
  var results = g.computeInTx(tx -> tx.yql("SELECT FROM Foo").toList());
  assertThat(results).isNotEmpty();
  ```
  Results from `SELECT` on vertex classes are `Vertex` objects — cast and use `.value("prop")`:
  ```java
  Vertex v = (Vertex) results.get(0);
  assertThat((String) v.value("name")).isEqualTo("baz");
  ```
- **Queries that should fail** (unsupported syntax): wrap in `assertThatThrownBy`:
  ```java
  assertThatThrownBy(() -> g.executeInTx(tx -> {
    tx.yql("BAD QUERY").iterate();
  }));
  ```
- **The `yql()` method** returns a lazy `YTDBGraphTraversal` — always call `.iterate()` or `.toList()` to execute it.
- **The `command()` method** executes eagerly — no need to call `.iterate()`. However, `command()` internally iterates results through the Gremlin result mapper, which only supports vertices and stateful edges. Therefore, **only use `command()` for DDL** (CREATE CLASS, CREATE PROPERTY, CREATE INDEX) — never for INSERT, UPDATE, or DELETE.
- **Parameterized queries**: use alternating key/value pairs: `tx.yql("SELECT FROM Foo WHERE name = :name", "name", "bar")`

### Gremlin API limitations to be aware of

These limitations affect what can be validated through the public API:
1. **All classes must extend V or E** — the Gremlin result mapper (`GremlinResultMapper`) only supports vertices and stateful edges. SELECT on a plain class (not extending V/E) will throw `IllegalStateException: Only vertices and stateful edges are supported in Gremlin results`. If a document example uses a plain class, create it with `EXTENDS V` for testing purposes.
2. **Use `yql().iterate()` for UPDATE/INSERT** — never use `command()` for data mutation commands, as it will fail when the result mapper tries to process the non-vertex result.
4. **`RETURN AFTER` on UPDATE** — use `tx.yql("UPDATE ... RETURN AFTER @this").toList()` to collect results. The results are vertex objects when the target class extends V.

Group tests by document section. Each test method should have a comment referencing the document claim it validates.

### Step 7: Run validation

1. Run the test class:
   ```bash
   ./mvnw -pl core clean test -Dtest=DocValidationTest
   ```
2. If Spotless formatting fails, fix it first:
   ```bash
   ./mvnw -pl core spotless:apply
   ```
3. If tests fail, analyze each failure:
   - **Parse error on a query the doc says should work** → the document has a query bug. Flag it.
   - **Query succeeds but the doc says it should fail** → the document claim is wrong. Flag it.
   - **Wrong result count or values** → the document example may be misleading. Flag it.
   - **Compilation error** → fix the test code and re-run.

### Step 8: Produce review report

After all checks complete, present a consolidated report to the user:

```
## Documentation Review: <path>

### Grammar & Style
| File | Line | Issue | Suggested Fix |
|------|------|-------|---------------|
| ... | ... | ... | ... |

### Link Validation
| File | Line | Link Target | Status |
|------|------|-------------|--------|
| ... | ... | ... | Missing / OK |

### Factual Claims
| File | Line | Claim | Verified | Notes |
|------|------|-------|----------|-------|
| ... | ... | ... | Yes/No | ... |

### Query Validation
| File | Line | Query | Expected | Result | Notes |
|------|------|-------|----------|--------|-------|
| ... | ... | ... | Success/Fail | Pass/Fail | ... |

### Summary
- Total files reviewed: N
- Grammar issues found: N
- Broken links: N
- Factual claims verified: N/N
- Queries validated: N/N
```

### Step 9: Apply fixes

Ask the user whether to:
1. **Apply grammar fixes** — edit the documents directly using the Edit tool.
2. **Flag query/factual issues only** — report without changing files (the user may want to rewrite sections).
3. **Apply all fixes** — grammar fixes + rewrite incorrect queries/claims.

### Step 10: Verify test class

After the review is complete:
1. **Keep the `DocValidationTest` class** — it serves as a living validation of documentation examples.
2. Ensure all new test methods were added and pass: `./mvnw -pl core clean test -Dtest=DocValidationTest`
3. Run Spotless to ensure formatting: `./mvnw -pl core spotless:apply`

## Important notes

- Never modify the test infrastructure — only add methods to `DocValidationTest`.
- The `core` module uses JUnit 4 (not JUnit 5). Use `@Test`, `@Before`, `@After`, `@BeforeClass`, `@AfterClass` from `org.junit`.
- Always run `spotless:apply` before running tests if the test class is new.
- When validating queries, create unique class names per test to avoid collisions (e.g., `CityDistinct`, `EmpSalary`).
- Standard SQL examples shown for comparison (e.g., JOIN syntax) should NOT be executed — they are expected to be invalid in YouTrackDB.
- If a document references files that don't exist yet, flag them as warnings but don't fail the review.
- **Use `yql()` for YQL queries** — the `yql()` method on `YTDBGraphTraversalSourceDSL` returns a lazy traversal; always terminate with `.iterate()` or `.toList()`.
- **Use `command()` for schema DDL** — `command()` executes eagerly and is appropriate for CREATE CLASS, CREATE PROPERTY, CREATE INDEX, etc.
- **Transaction helpers**: Use `g.executeInTx()` for side-effecting operations, `g.computeInTx()` when you need a return value, and `g.autoExecuteInTx()` when returning a traversal that should be auto-iterated.
