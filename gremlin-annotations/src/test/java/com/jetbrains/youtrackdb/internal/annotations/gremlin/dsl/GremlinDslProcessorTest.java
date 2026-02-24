package com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.JavaFileObject;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GremlinDslProcessorTest {

  private static final String DSL_PACKAGE_PATH =
      "com/jetbrains/youtrackdb/internal/annotations/gremlin/dsl";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void getSupportedOptions_returnsExpectedOptionNames() {
    var processor = new GremlinDslProcessor();
    var options = processor.getSupportedOptions();
    Assert.assertEquals(2, options.size());
    Assert.assertTrue(options.contains(GremlinDslProcessor.OPTION_GENERATED_DIR));
    Assert.assertTrue(options.contains(GremlinDslProcessor.OPTION_SOURCE_DIR));
  }

  @Test
  public void whenDSLIsClassNotInterface_printsErrorAndCompilationFails() {
    var notAnInterface = JavaFileObjects.forSourceString("bad.NotADsl",
        """
            package bad;
            import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl;
            @GremlinDsl
            public class NotADsl { }
            """);
    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(notAnInterface);
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("Only interfaces can be annotated");
  }

  @Test
  public void whenGeneratedDirNotSet_failsWithError() {
    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(
            JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));
    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining(GremlinDslProcessor.OPTION_GENERATED_DIR);
  }

  @Test
  public void shouldCompileToDefaultPackage() throws IOException {
    var generatedDir = temp.newFolder("gen-default").toPath();
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions("-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath())
        .compile(
            JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));
    assertCompilationSuccess(compilation);

    var anonymousSrc = readGeneratedFile(generatedDir, "__.java");
    Assert.assertTrue("__.java should contain person() from @AnonymousMethod",
        anonymousSrc.contains("person("));
    Assert.assertTrue("__.java should contain knowsOverride() from @AnonymousMethod",
        anonymousSrc.contains("knowsOverride("));
    Assert.assertTrue("__.java should contain meanAgeOfFriendsOverride() from @AnonymousMethod",
        anonymousSrc.contains("meanAgeOfFriendsOverride("));
    Assert.assertFalse("__.java should NOT contain from() marked @SkipAsAnonymousMethod",
        anonymousSrc.contains("from(Object"));
    Assert.assertTrue("__.java should contain start() method",
        anonymousSrc.contains("start()"));

    var traversalSourceSrc = readGeneratedFile(generatedDir, "SocialTraversalSource.java");
    Assert.assertTrue("TraversalSource should contain addV (default methods enabled)",
        traversalSourceSrc.contains("addV"));
    Assert.assertTrue("TraversalSource should contain addE (default methods enabled)",
        traversalSourceSrc.contains("addE"));
  }

  @Test
  public void shouldCompileAndMovePackage() throws IOException {
    var generatedDir = temp.newFolder("gen-move").toPath();
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions("-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(
            GremlinDsl.class.getResource("SocialMoveTraversalDsl.java")));
    assertCompilationSuccess(compilation);
    Assert.assertTrue("SocialMoveTraversal.java should be generated",
        Files.isRegularFile(generatedDir.resolve(
            "com/jetbrains/youtrackdb/internal/annotations/gremlin/dsl/social")
            .resolve("SocialMoveTraversal.java")));
  }

  /**
   * SocialPackageTraversalDsl specifies a custom traversalSource - verify generation of all 4
   * artifacts (Traversal interface, DefaultTraversal, TraversalSource, __) and that the Traversal
   * interface contains DSL methods.
   */
  @Test
  public void shouldCompileTraversalAndTraversalSourceToDefaultPackage() throws IOException {
    var generatedDir = temp.newFolder("gen-pkg").toPath();
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions("-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(
            GremlinDsl.class.getResource("SocialPackageTraversalDsl.java")));
    assertCompilationSuccess(compilation);

    var traversalSrc = readGeneratedFile(generatedDir, "SocialPackageTraversal.java");
    Assert.assertTrue("Traversal interface should contain knows() override",
        traversalSrc.contains("knows("));
    Assert.assertTrue("Traversal interface should contain meanAgeOfFriends() override",
        traversalSrc.contains("meanAgeOfFriends("));

    Assert.assertTrue("SocialPackageTraversalSource.java should be generated",
        Files.isRegularFile(generatedDir.resolve(DSL_PACKAGE_PATH)
            .resolve("SocialPackageTraversalSource.java")));
  }

  /**
   * generateDefaultMethods=false disables addV/addE/V/E generation in TraversalSource.
   */
  @Test
  public void shouldCompileWithNoDefaultMethods() throws IOException {
    var generatedDir = temp.newFolder("gen-nodefault").toPath();
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions("-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(
            GremlinDsl.class.getResource("SocialNoDefaultMethodsTraversalDsl.java")));
    assertCompilationSuccess(compilation);

    var traversalSourceSrc = readGeneratedFile(generatedDir,
        "SocialNoDefaultMethodsTraversalSource.java");
    Assert.assertFalse(
        "TraversalSource with generateDefaultMethods=false should NOT contain addV",
        traversalSourceSrc.contains("addV"));
    Assert.assertFalse(
        "TraversalSource with generateDefaultMethods=false should NOT contain addE",
        traversalSourceSrc.contains("addE"));
  }

  @Test
  @SuppressWarnings("resource")
  public void shouldCompileRemoteDslTraversal() throws IOException {
    var generatedDir = temp.newFolder("gen-remote").toPath();

    var dslCompilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions("-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath())
        .compile(
            JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));
    assertCompilationSuccess(dslCompilation);

    var genPkgDir = generatedDir.resolve(DSL_PACKAGE_PATH);
    var generatedSources = Files.list(genPkgDir)
        .filter(p -> p.toString().endsWith(".java"))
        .map(p -> {
          try {
            var className = DSL_PACKAGE_PATH.replace('/', '.')
                + "." + p.getFileName().toString().replace(".java", "");
            return JavaFileObjects.forSourceString(className, Files.readString(p));
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        })
        .toList();

    var allSources = new java.util.ArrayList<JavaFileObject>();
    allSources.add(
        JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));
    allSources.add(
        JavaFileObjects.forResource(GremlinDsl.class.getResource("RemoteDslTraversal.java")));
    allSources.addAll(generatedSources);

    var compilation = javac().compile(allSources.toArray(new JavaFileObject[0]));
    assertCompilationSuccess(compilation);

    try {
      final var cl = new JavaFileObjectClassLoader(compilation.generatedFiles());
      final var cls = cl.loadClass(
          "com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.RemoteDslTraversal");
      cls.getConstructor().newInstance();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * With -A options and a pre-existing __.java whose digest matches the DSL source, the processor
   * skips generation (canSkipByDigest returns true). Covers getDslSourcePath non-null and
   * digest-equals branches.
   */
  @Test
  public void withOptions_skipsGenerationWhenDigestMatches() throws IOException {
    var sourceDir = temp.newFolder("src").toPath();
    var generatedDir = temp.newFolder("gen").toPath();
    var dslSource = sourceDir.resolve(DSL_PACKAGE_PATH).resolve("SocialTraversalDsl.java");
    Files.createDirectories(dslSource.getParent());
    try (var in = GremlinDsl.class.getResourceAsStream("SocialTraversalDsl.java")) {
      Files.write(dslSource, in.readAllBytes());
    }
    var digest = GremlinDslDigestHelper.computeSourceDigest(dslSource);
    var sentinel = generatedDir.resolve(DSL_PACKAGE_PATH).resolve("__.java");
    Files.createDirectories(sentinel.getParent());
    Files.writeString(sentinel,
        "// " + GremlinDslDigestHelper.DSL_SOURCE_DIGEST_PREFIX + digest + "\npackage p;\nclass __ {}");
    var sentinelModifiedBefore = Files.getLastModifiedTime(sentinel);

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
            "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).succeeded();
    var genPkgDir = generatedDir.resolve(DSL_PACKAGE_PATH);
    Assert.assertFalse("SocialTraversal.java should NOT be generated when digest matches",
        Files.isRegularFile(genPkgDir.resolve("SocialTraversal.java")));
    Assert.assertFalse("SocialTraversalSource.java should NOT be generated when digest matches",
        Files.isRegularFile(genPkgDir.resolve("SocialTraversalSource.java")));
    Assert.assertFalse("DefaultSocialTraversal.java should NOT be generated when digest matches",
        Files.isRegularFile(genPkgDir.resolve("DefaultSocialTraversal.java")));
    Assert.assertEquals("Sentinel __.java should NOT be modified",
        sentinelModifiedBefore, Files.getLastModifiedTime(sentinel));
  }

  /**
   * With -A options but no sentinel __.java, canSkipByDigest returns false (storedDigest null).
   * Processor runs full generation. Covers dslPath non-null, currentDigest non-empty, storedDigest null.
   */
  @Test
  public void withOptions_generatesWhenNoSentinelFile() throws IOException {
    var sourceDir = temp.newFolder("src").toPath();
    var generatedDir = temp.newFolder("gen").toPath();
    var dslSource = sourceDir.resolve(DSL_PACKAGE_PATH).resolve("SocialTraversalDsl.java");
    Files.createDirectories(dslSource.getParent());
    try (var in = GremlinDsl.class.getResourceAsStream("SocialTraversalDsl.java")) {
      Files.write(dslSource, in.readAllBytes());
    }

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
            "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).succeeded();
    var genPkgDir = generatedDir.resolve(DSL_PACKAGE_PATH);
    assertAllFourArtifactsGenerated(genPkgDir, "Social");
    var digest = GremlinDslDigestHelper.computeSourceDigest(dslSource);
    var storedDigest =
        GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(genPkgDir.resolve("__.java"));
    Assert.assertEquals("Generated __.java should contain the DSL source digest",
        digest, storedDigest);
  }

  /**
   * With -A options and __.java present but digest mismatch, canSkipByDigest returns false.
   * Covers storedDigest non-null but !currentDigest.equals(storedDigest).
   */
  @Test
  public void withOptions_generatesWhenDigestMismatch() throws IOException {
    var sourceDir = temp.newFolder("src").toPath();
    var generatedDir = temp.newFolder("gen").toPath();
    var dslSource = sourceDir.resolve(DSL_PACKAGE_PATH).resolve("SocialTraversalDsl.java");
    Files.createDirectories(dslSource.getParent());
    try (var in = GremlinDsl.class.getResourceAsStream("SocialTraversalDsl.java")) {
      Files.write(dslSource, in.readAllBytes());
    }
    var sentinel = generatedDir.resolve(DSL_PACKAGE_PATH).resolve("__.java");
    Files.createDirectories(sentinel.getParent());
    Files.writeString(sentinel,
        "// " + GremlinDslDigestHelper.DSL_SOURCE_DIGEST_PREFIX + "wrongDigest\npackage p;\nclass __ {}");

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
            "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).succeeded();
    var genPkgDir = generatedDir.resolve(DSL_PACKAGE_PATH);
    assertAllFourArtifactsGenerated(genPkgDir, "Social");
    var currentDigest = GremlinDslDigestHelper.computeSourceDigest(dslSource);
    var storedDigest =
        GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(genPkgDir.resolve("__.java"));
    Assert.assertEquals("Regenerated __.java should contain the updated DSL source digest",
        currentDigest, storedDigest);
  }

  /**
   * Empty generatedDir option triggers a compilation error because the option is required.
   */
  @Test
  public void withOptions_emptyGeneratedDir_failsWithError() throws IOException {
    var sourceDir = temp.newFolder("src").toPath();
    var dslSource = sourceDir.resolve(DSL_PACKAGE_PATH).resolve("SocialTraversalDsl.java");
    Files.createDirectories(dslSource.getParent());
    try (var in = GremlinDsl.class.getResourceAsStream("SocialTraversalDsl.java")) {
      Files.write(dslSource, in.readAllBytes());
    }

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=",
            "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining(GremlinDslProcessor.OPTION_GENERATED_DIR);
  }

  /**
   * sourceDir empty but generatedDir set: getDslSourcePath returns null because sourceDir is
   * empty, so canSkipByDigest returns false and generation proceeds with an empty digest.
   */
  @Test
  public void withOptions_emptySourceDir_generatesBecauseGetDslSourcePathReturnsNull() throws IOException {
    var generatedDir = temp.newFolder("gen").toPath();

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
            "-Agremlin.dsl.sourceDir=")
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).succeeded();
    var genPkgDir = generatedDir.resolve(DSL_PACKAGE_PATH);
    assertAllFourArtifactsGenerated(genPkgDir, "Social");
    var storedDigest =
        GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(genPkgDir.resolve("__.java"));
    Assert.assertTrue("Digest should be empty when sourceDir is empty",
        storedDigest == null || storedDigest.isEmpty());
  }

  /**
   * With -A options set but DSL source file absent at expected path, getDslSourcePath returns null
   * (line 114: Files.isRegularFile(path) is false). Processor runs full generation with empty
   * digest (because source path is null, so no digest can be computed).
   */
  @Test
  public void withOptions_dslSourceFileAbsent_generatesBecausePathNotRegularFile() throws IOException {
    var sourceDir = temp.newFolder("src").toPath();
    var generatedDir = temp.newFolder("gen").toPath();
    Files.createDirectories(sourceDir.resolve(DSL_PACKAGE_PATH));

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
            "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).succeeded();
    var genPkgDir = generatedDir.resolve(DSL_PACKAGE_PATH);
    assertAllFourArtifactsGenerated(genPkgDir, "Social");
    var storedDigest =
        GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(genPkgDir.resolve("__.java"));
    Assert.assertTrue("Digest should be empty when DSL source file is absent",
        storedDigest == null || storedDigest.isEmpty());
  }

  /**
   * When DSL source file is unreadable (e.g. chmod 000 on Unix), computeSourceDigest returns ""
   * and canSkipByDigest returns false (line 127). Processor runs generation.
   * Skipped on Windows where setReadable(false) does not prevent owner read.
   */
  @Test
  @SuppressWarnings("ResultOfMethodCallIgnored")
  public void withOptions_dslSourceUnreadable_generatesBecauseCurrentDigestEmpty() throws IOException {
    var sourceDir = temp.newFolder("src").toPath();
    var generatedDir = temp.newFolder("gen").toPath();
    var dslSource = sourceDir.resolve(DSL_PACKAGE_PATH).resolve("SocialTraversalDsl.java");
    Files.createDirectories(dslSource.getParent());

    try (var in = GremlinDsl.class.getResourceAsStream("SocialTraversalDsl.java")) {
      Objects.requireNonNull(in, "Resource not found");
      Files.write(dslSource, in.readAllBytes());
    }

    var sentinel = generatedDir.resolve(DSL_PACKAGE_PATH).resolve("__.java");
    Files.createDirectories(sentinel.getParent());

    var digest = GremlinDslDigestHelper.computeSourceDigest(dslSource);
    Files.writeString(sentinel,
        "// " + GremlinDslDigestHelper.DSL_SOURCE_DIGEST_PREFIX + digest + "\npackage p;\nclass __ {}");

    var wasSetSuccessful = dslSource.toFile().setReadable(false, false);
    var canRead = dslSource.toFile().canRead();

    dslSource.toFile().setReadable(true, false);
    Assume.assumeTrue("Platform does not support making files unreadable", wasSetSuccessful && !canRead);

    Assert.assertTrue("Failed to make file unreadable", dslSource.toFile().setReadable(false, false));
    try {
      var compilation = javac()
          .withProcessors(new GremlinDslProcessor())
          .withOptions(
              "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
              "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
          .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

      assertThat(compilation).succeeded();
      Assert.assertTrue("SocialTraversal.java should be generated in generatedDir",
          Files.isRegularFile(
              generatedDir.resolve(DSL_PACKAGE_PATH).resolve("SocialTraversal.java")));
    } finally {
      dslSource.toFile().setReadable(true, false);
      dslSource.toFile().setWritable(true, false);
    }
  }

  private static void assertCompilationSuccess(final Compilation compilation) {
    assertThat(compilation).succeeded();
  }

  private static void assertAllFourArtifactsGenerated(java.nio.file.Path genPkgDir,
      String dslPrefix) {
    Assert.assertTrue(dslPrefix + "Traversal.java should be generated",
        Files.isRegularFile(genPkgDir.resolve(dslPrefix + "Traversal.java")));
    Assert.assertTrue("Default" + dslPrefix + "Traversal.java should be generated",
        Files.isRegularFile(genPkgDir.resolve("Default" + dslPrefix + "Traversal.java")));
    Assert.assertTrue(dslPrefix + "TraversalSource.java should be generated",
        Files.isRegularFile(genPkgDir.resolve(dslPrefix + "TraversalSource.java")));
    Assert.assertTrue("__.java should be generated",
        Files.isRegularFile(genPkgDir.resolve("__.java")));
  }

  private static String readGeneratedFile(java.nio.file.Path generatedDir, String fileName)
      throws IOException {
    var filePath = generatedDir.resolve(DSL_PACKAGE_PATH).resolve(fileName);
    Assert.assertTrue("Expected generated file " + filePath + " not found",
        Files.isRegularFile(filePath));
    return Files.readString(filePath);
  }


  static class JavaFileObjectClassLoader extends ClassLoader {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
        ".*(?=com/jetbrains/youtrackdb)");
    private static final Pattern PATH_PATTERN = Pattern.compile("\\.");
    Map<String, JavaFileObject> classFileMap;

    JavaFileObjectClassLoader(List<JavaFileObject> classFiles) {
      classFileMap = classFiles.stream().collect(Collectors.toMap(
          f -> PACKAGE_PATTERN.matcher(f.toUri().toString()).replaceFirst(""),
          Function.identity()));
    }

    @Override
    public Class<?> findClass(String name) {
      try {
        var b = loadClassData(name);
        return defineClass(name, b, 0, b.length);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    private byte[] loadClassData(String name) throws IOException {
      final var out = new ByteArrayOutputStream();
      final var classFilename = PATH_PATTERN.matcher(name).replaceAll("/") + ".class";
      try (var in = classFileMap.get(classFilename).openInputStream()) {
        final var buf = new byte[1024];
        var len = in.read(buf);
        while (len != -1) {
          out.write(buf, 0, len);
          len = in.read(buf);
        }
      }
      return out.toByteArray();
    }
  }
}
