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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GremlinDslProcessorTest {

  private static final String DSL_PACKAGE_PATH =
      "com/jetbrains/youtrackdb/internal/annotations/gremlin/dsl";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void shouldCompileToDefaultPackage() {
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(
            JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));
    assertCompilationSuccess(compilation);
  }

  @Test
  public void shouldCompileAndMovePackage() {
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(JavaFileObjects.forResource(
            GremlinDsl.class.getResource("SocialMoveTraversalDsl.java")));
    assertCompilationSuccess(compilation);
    assertThat(compilation)
        .generatedFile(StandardLocation.SOURCE_OUTPUT,
            "com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.social",
            "SocialMoveTraversal.java");
  }

  @Test
  public void shouldCompileTraversalAndTraversalSourceToDefaultPackage() {
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(JavaFileObjects.forResource(
            GremlinDsl.class.getResource("SocialPackageTraversalDsl.java")));
    assertCompilationSuccess(compilation);
  }

  @Test
  public void shouldCompileWithNoDefaultMethods() {
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(JavaFileObjects.forResource(
            GremlinDsl.class.getResource("SocialNoDefaultMethodsTraversalDsl.java")));
    assertCompilationSuccess(compilation);
  }

  @Test
  public void shouldCompileRemoteDslTraversal() {
    final var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .compile(
            JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")),
            JavaFileObjects.forResource(GremlinDsl.class.getResource("RemoteDslTraversal.java")));

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

    var compilation = javac()
        .withProcessors(new GremlinDslProcessor())
        .withOptions(
            "-Agremlin.dsl.generatedDir=" + generatedDir.toAbsolutePath(),
            "-Agremlin.dsl.sourceDir=" + sourceDir.toAbsolutePath())
        .compile(JavaFileObjects.forResource(GremlinDsl.class.getResource("SocialTraversalDsl.java")));

    assertThat(compilation).succeeded();
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
    assertThat(compilation)
        .generatedFile(StandardLocation.SOURCE_OUTPUT,
            "com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl",
            "SocialTraversal.java");
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
  }

  private static void assertCompilationSuccess(final Compilation compilation) {
    assertThat(compilation).succeeded();
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
