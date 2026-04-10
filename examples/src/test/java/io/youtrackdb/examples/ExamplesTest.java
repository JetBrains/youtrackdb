package io.youtrackdb.examples;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that the embedded example classes ({@link EmbeddedExample} and {@link ReadmeExample})
 * run successfully and produce the expected stdout output. This catches API breakages in the
 * shaded uber-jar that would otherwise go unnoticed until a user tries the examples.
 */
public class ExamplesTest {

  private final PrintStream originalOut = System.out;
  private ByteArrayOutputStream captured;

  @Before
  public void captureStdout() {
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured));
  }

  @After
  public void restoreStdout() {
    System.setOut(originalOut);
  }

  /**
   * Runs {@link EmbeddedExample#main(String[])} and verifies the four expected output lines:
   * <ol>
   *   <li>JSON vertex containing marko with age 29</li>
   *   <li>Friend name: josh</li>
   *   <li>JSON vertex containing marko (newly created)</li>
   *   <li>Created software: lop</li>
   * </ol>
   */
  @Test
  public void embeddedExampleProducesExpectedOutput() throws Exception {
    EmbeddedExample.main(new String[0]);

    assertExampleOutput(captured.toString(UTF_8));
  }

  /**
   * Runs {@link ReadmeExample#main(String[])} and verifies the same four expected output lines.
   * ReadmeExample contains equivalent logic inlined for the README code snippet.
   */
  @Test
  public void readmeExampleProducesExpectedOutput() throws Exception {
    ReadmeExample.main(new String[0]);

    assertExampleOutput(captured.toString(UTF_8));
  }

  /**
   * Runs {@link ObjectOrientedExample#main(String[])} and verifies inheritance hierarchies,
   * polymorphic queries, edge inheritance, property types with embedded collections,
   * auto-generated IDs, and schema evolution (rename class/property, add superclass, drop).
   */
  @Test
  public void objectOrientedExampleProducesExpectedOutput() throws Exception {
    ObjectOrientedExample.main(new String[0]);

    var outputLines = captured.toString(UTF_8).lines()
        .filter(line -> line.startsWith("output:"))
        .toList();

    // 1. Polymorphic query on Vehicle — 4 subclass instances
    assertEquals("output:vehicle:Ford F-150,Truck", outputLines.get(0));
    assertEquals("output:vehicle:Honda CB500,Motorcycle", outputLines.get(1));
    assertEquals("output:vehicle:Tesla Model 3,ElectricCar", outputLines.get(2));
    assertEquals("output:vehicle:Toyota Camry,Car", outputLines.get(3));

    // 2. Mid-level query: Car returns Car + ElectricCar
    assertEquals("output:cars:Tesla Model 3,Toyota Camry", outputLines.get(4));

    // 3. Multiple inheritance: Electric returns ElectricCar
    assertEquals("output:electric:Tesla Model 3,battery=60.0", outputLines.get(5));

    // 4. Edge inheritance: Leases extends Owns
    assertEquals("output:edge class:Leases", outputLines.get(6));
    assertEquals("output:edge since:2024", outputLines.get(7));

    // 5. Property types: auto-generated uid, collections
    assertEquals("output:uid generated:true", outputLines.get(8));
    assertEquals("output:skills:[Java, Python, SQL]", outputLines.get(9));
    assertEquals("output:tags unique:true", outputLines.get(10));
    assertEquals("output:metadata team:platform", outputLines.get(11));

    // 6. Schema evolution: rename class + property, add superclass
    assertEquals("output:customer:Alice,alice@example.com", outputLines.get(12));
    assertEquals("output:customer:Bob,bob@example.com", outputLines.get(13));
    assertEquals("output:auditable count:2", outputLines.get(14));
    assertEquals("output:drop class:ok", outputLines.get(15));

    assertEquals("Expected exactly 16 output lines, got: " + outputLines,
        16, outputLines.size());
  }

  /**
   * Runs {@link SecurityExample#main(String[])} and verifies that predicate-based security
   * policies correctly filter reads, block deletes, apply per-role policies, and support
   * ALTER/REVOKE lifecycle operations.
   */
  @Test
  public void securityExampleProducesExpectedOutput() throws Exception {
    SecurityExample.main(new String[0]);

    var outputLines = captured.toString(UTF_8).lines()
        .filter(line -> line.startsWith("output:"))
        .toList();

    // 1. READ policy: admin sees all, analyst sees only public
    assertEquals("output:admin docs:4", outputLines.get(0));
    assertEquals("output:analyst docs:2", outputLines.get(1));
    assertEquals("output:analyst visible:Press Release", outputLines.get(2));
    assertEquals("output:analyst visible:Q1 Report", outputLines.get(3));

    // 2. DELETE policy: debug allowed, error denied
    assertEquals("output:delete debug:ok", outputLines.get(4));
    assertEquals("output:delete error:denied", outputLines.get(5));

    // 3. Multiple roles: intern sees engineering only, manager sees all
    assertEquals("output:intern projects:Alpha,Gamma", outputLines.get(6));
    assertEquals("output:manager projects:Alpha,Beta,Gamma", outputLines.get(7));

    // 4. ALTER and REVOKE: policy widens then is removed
    assertEquals("output:before alter:1", outputLines.get(8));
    assertEquals("output:after alter:2", outputLines.get(9));
    assertEquals("output:after revoke:3", outputLines.get(10));

    assertEquals("Expected exactly 11 output lines, got: " + outputLines,
        11, outputLines.size());
  }

  /**
   * Runs {@link GettingStartedExample#main(String[])} and verifies the expected output lines
   * covering schema creation, data insertion, queries, MATCH traversals, updates, deletes,
   * and parameterized queries.
   */
  @Test
  public void gettingStartedExampleProducesExpectedOutput() throws Exception {
    GettingStartedExample.main(new String[0]);

    var outputLines = captured.toString(UTF_8).lines()
        .filter(line -> line.startsWith("output:"))
        .toList();

    // 1. Schema + data setup (3 lines)
    assertEquals("output:schema created", outputLines.get(0));
    assertEquals("output:data inserted", outputLines.get(1));
    assertEquals("output:edges created", outputLines.get(2));

    // 2. Basic query: 3 people ordered by name
    assertEquals("output:person:Alice,1990", outputLines.get(3));
    assertEquals("output:person:Bob,1985", outputLines.get(4));
    assertEquals("output:person:Charlie,1978", outputLines.get(5));

    // 3. WHERE filter: born after 1980
    assertEquals("output:born after 1980:Alice,Bob", outputLines.get(6));

    // 4. Full record query: Sci-Fi movies from 2000s
    assertEquals("output:scifi 2000s:Inception", outputLines.get(7));

    // 5. Aggregation
    assertEquals("output:person count:3", outputLines.get(8));

    // 6. MATCH: actors and movies (4 edges, ordered)
    assertEquals("output:acted:Alice in Inception", outputLines.get(9));
    assertEquals("output:acted:Alice in The Matrix", outputLines.get(10));
    assertEquals("output:acted:Bob in The Matrix", outputLines.get(11));
    assertEquals("output:acted:Charlie in The Godfather", outputLines.get(12));

    // 7. Co-actors of Alice
    assertEquals("output:coactor:Bob in The Matrix", outputLines.get(13));

    // 8. Update
    assertEquals("output:updated:Inception,Sci-Fi/Thriller", outputLines.get(14));

    // 9. Delete
    assertEquals("output:directed edges:0", outputLines.get(15));

    // 10. Parameterized query
    assertEquals("output:param query:Bob,1985", outputLines.get(16));

    // 11. Inheritance: polymorphic query returns all Animal subclasses
    assertEquals("output:animal:Buddy,HouseDog", outputLines.get(17));
    assertEquals("output:animal:Rex,Dog", outputLines.get(18));
    assertEquals("output:animal:Whiskers,Cat", outputLines.get(19));

    // 12. Subclass query: Dog + HouseDog (not Cat)
    assertEquals("output:dogs:Buddy,Rex", outputLines.get(20));

    // 13. Multiple inheritance: Pet query returns HouseDog
    assertEquals("output:pet:Buddy,owner=Alice", outputLines.get(21));

    assertEquals("Expected exactly 22 output lines, got: " + outputLines,
        22, outputLines.size());
  }

  /**
   * Asserts that the captured stdout contains the four expected "output:" lines produced by
   * the example code. The lines are:
   * <pre>
   * output:{"id":...,"label":"person",...,"name":[..."marko"...],"age":[...29...]}
   * output:josh
   * output:{"id":...,"label":"person",...,"name":[..."marko"...]}
   * output:lop
   * </pre>
   */
  static void assertExampleOutput(String rawOutput) {
    // Extract only lines that start with "output:" (ignore logging/other output)
    var outputLines = rawOutput.lines()
        .filter(line -> line.startsWith("output:"))
        .toList();

    assertEquals("Expected exactly 4 output lines, got: " + outputLines,
        4, outputLines.size());

    // Line 1: JSON vertex for marko with age 29
    var line1 = outputLines.get(0);
    assertTrue("Line 1 should contain 'name' and 'marko': " + line1,
        line1.contains("\"name\"") && line1.contains("\"marko\""));
    assertTrue("Line 1 should contain 'age' and 29: " + line1,
        line1.contains("\"age\"") && line1.contains("29"));

    // Line 2: friend name "josh"
    assertEquals("output:josh", outputLines.get(1));

    // Line 3: JSON vertex for newly created marko
    var line3 = outputLines.get(2);
    assertTrue("Line 3 should contain 'name' and 'marko': " + line3,
        line3.contains("\"name\"") && line3.contains("\"marko\""));

    // Line 4: created software "lop"
    assertEquals("output:lop", outputLines.get(3));
  }
}
