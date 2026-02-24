package com.jetbrains.youtrackdb.shade;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Integration tests that inspect the shaded uber-jar to verify relocations.
 * These run during the {@code integration-test} phase (via failsafe), after
 * the {@code package} phase has produced the shaded jar.
 */
public class ShadedJarIT {

  /**
   * Verifies that Guava classes have been relocated into the shaded package
   * inside the uber-jar.
   */
  @Test
  public void shadedGuavaClassIsAccessible() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNotNull(
          "Relocated Guava ImmutableList should be present",
          jar.getEntry(
              "com/jetbrains/youtrackdb/shade/com/google/common"
                  + "/collect/ImmutableList.class"));
    }
  }

  /**
   * Verifies that the original (unshaded) Guava class is NOT present in the
   * uber-jar. Users' own Guava version should be the only one visible.
   */
  @Test
  public void originalGuavaClassIsNotBundled() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNull(
          "Original Guava ImmutableList should not be in the shaded jar",
          jar.getEntry(
              "com/google/common/collect/ImmutableList.class"));
    }
  }

  /**
   * Verifies that Jackson classes have been relocated into the shaded
   * package inside the uber-jar.
   */
  @Test
  public void shadedJacksonClassIsAccessible() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNotNull(
          "Relocated Jackson JsonFactory should be present",
          jar.getEntry(
              "com/jetbrains/youtrackdb/shade/com/fasterxml/jackson"
                  + "/core/JsonFactory.class"));
    }
  }

  /**
   * Verifies that fastutil classes have been relocated into the shaded
   * package inside the uber-jar.
   */
  @Test
  public void shadedFastutilClassIsAccessible() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNotNull(
          "Relocated fastutil IntArrayList should be present",
          jar.getEntry(
              "com/jetbrains/youtrackdb/shade/it/unimi/dsi"
                  + "/fastutil/ints/IntArrayList.class"));
    }
  }

  /**
   * Verifies that SLF4J is excluded from the uber-jar (users provide their
   * own binding).
   */
  @Test
  public void slf4jIsExcluded() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNull(
          "SLF4J Logger should not be bundled in the shaded jar",
          jar.getEntry("org/slf4j/Logger.class"));
    }
  }

  /**
   * Verifies that Netty is excluded from the uber-jar (native transport
   * breaks under relocation, so Netty must be provided externally).
   */
  @Test
  public void nettyIsExcluded() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNull(
          "Netty Bootstrap should not be bundled in the shaded jar",
          jar.getEntry("io/netty/bootstrap/Bootstrap.class"));
    }
  }

  /**
   * Verifies that YouTrackDB's own API classes are present (not relocated).
   */
  @Test
  public void youTrackDbApiClassIsPresent() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNotNull(
          "YouTrackDB API class should be present in the shaded jar",
          jar.getEntry(
              "com/jetbrains/youtrackdb/api/YouTrackDB.class"));
    }
  }

  /**
   * Locates the shaded uber-jar in the target directory. The build directory
   * is passed via the {@code project.build.directory} system property set by
   * failsafe.
   */
  private static Path findShadedJar() throws IOException {
    String buildDir = System.getProperty("project.build.directory",
        "target");
    Path targetDir = Paths.get(buildDir);
    // The "original-youtrackdb-embedded-*" jar from maven-shade-plugin is
    // naturally excluded because it does not start with "youtrackdb-embedded-".
    try (Stream<Path> entries = Files.list(targetDir)) {
      return entries
          .filter(p -> {
            String name = p.getFileName().toString();
            return name.startsWith("youtrackdb-embedded-")
                && name.endsWith(".jar")
                && !name.contains("-sources")
                && !name.contains("-javadoc");
          })
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "Shaded jar not found in " + targetDir.toAbsolutePath()));
    }
  }
}
