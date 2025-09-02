package com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.Test;

public class GremlinDslProcessorTest {

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
