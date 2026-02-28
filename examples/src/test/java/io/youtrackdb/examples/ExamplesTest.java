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
