package com.jetbrains.youtrackdb.shade;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
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
   * TinkerPop modules and their shaded Kryo dependency retain their original package paths.
   * No TinkerPop class may appear under the YouTrackDB relocation prefix.
   */
  @Test
  public void tinkerPopClassesKeepOriginalPackages() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      for (String classPath : List.of(
          "org/apache/tinkerpop/gremlin/structure/Graph.class",
          "org/apache/tinkerpop/gremlin/language/grammar/GremlinLexer.class",
          "org/apache/tinkerpop/gremlin/groovy/jsr223/GremlinGroovyScriptEngine.class",
          "org/apache/tinkerpop/gremlin/util/message/RequestMessage.class",
          "org/apache/tinkerpop/shaded/kryo/Kryo.class")) {
        assertNotNull("Original TinkerPop class must be bundled: " + classPath,
            jar.getEntry(classPath));
      }
      assertFalse("TinkerPop must not be relocated", jar.stream().anyMatch(entry -> entry.getName()
          .startsWith("com/jetbrains/youtrackdb/shade/org/apache/tinkerpop/")));
    }
  }

  /**
   * Both Gremlin engines and all required plugins remain discoverable from the jar's services.
   * Each required provider also has its implementation class in the jar.
   */
  @Test
  public void gremlinServiceProvidersAreBundled() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertServiceProviders(jar, "org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngineFactory",
          List.of("org.apache.tinkerpop.gremlin.jsr223.GremlinLangScriptEngineFactory",
              "org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngineFactory"));
      assertServiceProviders(jar, "org.apache.tinkerpop.gremlin.jsr223.GremlinPlugin",
          List.of("org.apache.tinkerpop.gremlin.groovy.jsr223.SugarGremlinPlugin",
              "org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialGraphGremlinPlugin",
              "com.jetbrains.youtrackdb.internal.core.gremlin.jsr223.YTDBGremlinPlugin"));
    }
  }

  /**
   * The jar carries the full Sun BSD copyright, conditions and disclaimer for Groovy JSR-223.
   */
  @Test
  public void groovyJsr223SunBsdLicenseIsComplete() throws IOException {
    // Groovy 4.0.32 licenses/jsr223-license.txt is the authoritative text.
    String expected = """
        Copyright (c) 2006, Sun Microsystems, Inc.
        All rights reserved.

        Redistribution and use in source and binary forms, with or without
        modification, are permitted provided that the following conditions are met:

         - Redistributions of source code must retain the above copyright notice, this
           list of conditions and the following disclaimer.

         - Redistributions in binary form must reproduce the above copyright notice,
           this list of conditions and the following disclaimer in the documentation
           and/or other materials provided with the distribution.

         - Neither the name of the Sun Microsystems, Inc. nor the names of
           contributors may be used to endorse or promote products derived from this
           software without specific prior written permission.

        THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
        CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
        WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
        WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
        PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
        COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY
        DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
        CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
        PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
        USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
        CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
        CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
        OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
        SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
        DAMAGE.
        """;
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertEquals("Full Sun BSD text must be bundled", expected.strip(),
          readResource(jar, "META-INF/licenses/jsr223-license.txt").replace("\r\n", "\n").strip());
    }
  }

  /**
   * Bundled Apache-licensed code has the complete repository Apache License 2.0 text.
   * A truncated or replaced license must fail even if it retains the title and final marker.
   */
  @Test
  public void apacheLicenseIsComplete() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertBundledLicense(jar, "META-INF/LICENSE", "license.txt");
    }
  }

  /**
   * The jar retains non-empty merged notices independently of its Apache license text.
   */
  @Test
  public void mergedNoticeIsNotEmpty() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertFalse("Merged NOTICE must not be empty",
          readResource(jar, "META-INF/NOTICE").isBlank());
    }
  }

  /**
   * Bundled jBCrypt, JLine 2 and TreeLayout classes have their complete ISC or BSD license texts.
   * Compare each jar resource with its repository source to reject omissions and truncation.
   */
  @Test
  public void bundledIscAndBsdLicensesAreComplete() throws IOException {
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      for (String license : List.of("jbcrypt", "jline2", "treelayout")) {
        assertBundledLicense(jar, "META-INF/licenses/" + license,
            "gremlin-console/src/main/static/licenses/" + license);
      }
    }
  }

  /**
   * Bundled JNR POSIX and both x86asm package variants retain the exact upstream license files.
   * Byte equality rejects truncation, replacement and changes to the copied legal text.
   */
  @Test
  public void bundledJnrLicensesAreByteIdenticalToSources() throws IOException {
    Path repositoryRoot = Paths.get(System.getProperty("repository.root", ".."));
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      for (String classPath : List.of("jnr/posix/POSIX.class", "jnr/x86asm/Assembler.class",
          "com/kenai/jnr/x86asm/Assembler.class")) {
        assertNotNull("JNR class must be bundled: " + classPath, jar.getEntry(classPath));
      }
      for (String license : List.of("jnr-posix", "jnr-x86asm")) {
        String resource = "META-INF/licenses/" + license;
        var entry = jar.getJarEntry(resource);
        assertNotNull("JNR license must be bundled: " + resource, entry);
        try (var input = jar.getInputStream(entry)) {
          assertArrayEquals("Exact upstream license must be bundled: " + resource,
              Files.readAllBytes(repositoryRoot.resolve("embedded/src/main/static/licenses/"
                  + license)),
              input.readAllBytes());
        }
      }
    }
  }

  /**
   * The jar bundles a JFFI native library and the exact upstream MIT license for its libffi code.
   * Byte equality rejects missing, truncated, replaced or reformatted license text.
   */
  @Test
  public void bundledLibffiLicenseIsByteIdenticalToSource() throws IOException {
    Path repositoryRoot = Paths.get(System.getProperty("repository.root", ".."));
    try (JarFile jar = new JarFile(findShadedJar().toFile())) {
      assertNotNull("JFFI native library must be bundled",
          jar.getEntry("jni/x86_64-Linux/libjffi-1.2.so"));
      String resource = "META-INF/licenses/libffi";
      var entry = jar.getJarEntry(resource);
      assertNotNull("libffi MIT license must be bundled", entry);
      try (var input = jar.getInputStream(entry)) {
        assertArrayEquals("Exact upstream libffi license must be bundled",
            Files.readAllBytes(repositoryRoot.resolve("embedded/src/main/static/licenses/libffi")),
            input.readAllBytes());
      }
    }
  }

  private static void assertBundledLicense(JarFile jar, String resource, String source)
      throws IOException {
    Path repositoryRoot = Paths.get(System.getProperty("repository.root", ".."));
    String expected = Files.readString(repositoryRoot.resolve(source), StandardCharsets.UTF_8);
    assertEquals("Full license text must be bundled: " + resource,
        normalizeLicense(expected), normalizeLicense(readResource(jar, resource)));
  }

  private static String normalizeLicense(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n').strip();
  }

  private static void assertServiceProviders(JarFile jar, String service, List<String> required)
      throws IOException {
    // ServiceLoader allows blank lines and comments, including comments after a provider name.
    Set<String> providers = readResource(jar, "META-INF/services/" + service).lines()
        .map(line -> line.split("#", 2)[0].strip())
        .filter(line -> !line.isEmpty())
        .collect(Collectors.toSet());
    for (String provider : required) {
      assertTrue("Service provider must be registered: " + provider, providers.contains(provider));
      assertNotNull("Service provider class must be bundled: " + provider,
          jar.getEntry(provider.replace('.', '/') + ".class"));
    }
  }

  private static String readResource(JarFile jar, String path) throws IOException {
    var entry = jar.getJarEntry(path);
    assertNotNull("Jar resource must be present: " + path, entry);
    try (var input = jar.getInputStream(entry)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
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
