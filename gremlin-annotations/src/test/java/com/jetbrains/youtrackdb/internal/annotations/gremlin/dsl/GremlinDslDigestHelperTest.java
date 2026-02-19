package com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link GremlinDslDigestHelper}: digest computation and reading stored digest
 * from generated file comments. Ensures "regenerate only when DSL changed" behaves correctly.
 */
public class GremlinDslDigestHelperTest {

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void computeSourceDigest_returnsSameDigestForSameContent() throws Exception {
    var file = temp.newFile("dsl.java").toPath();
    Files.writeString(file, "public interface Foo { void bar(); }");
    var a = GremlinDslDigestHelper.computeSourceDigest(file);
    var b = GremlinDslDigestHelper.computeSourceDigest(file);
    assertThat(a).isNotEmpty().isEqualTo(b);
  }

  @Test
  public void computeSourceDigest_returnsDifferentDigestForDifferentContent() throws Exception {
    var f1 = temp.newFile("a.java").toPath();
    var f2 = temp.newFile("b.java").toPath();
    Files.writeString(f1, "interface A {}");
    Files.writeString(f2, "interface B {}");
    assertThat(GremlinDslDigestHelper.computeSourceDigest(f1))
        .isNotEqualTo(GremlinDslDigestHelper.computeSourceDigest(f2));
  }

  @Test
  public void computeSourceDigest_returnsNonEmptyHexForEmptyFile() throws Exception {
    var file = temp.newFile("empty.java").toPath();
    var digest = GremlinDslDigestHelper.computeSourceDigest(file);
    assertThat(digest).isNotEmpty().matches("[0-9a-f]+");
  }

  @Test
  public void computeSourceDigest_returnsEmptyStringForNonExistentPath() {
    var missing = temp.getRoot().toPath().resolve("missing.java");
    assertThat(GremlinDslDigestHelper.computeSourceDigest(missing)).isEmpty();
  }

  @Test
  public void getStoredDigestFromGeneratedFile_returnsDigestWhenCommentLinePresent() throws Exception {
    var generated = temp.newFile("__.java").toPath();
    var expectedDigest = "a1b2c3d4";
    Files.writeString(generated,
        "// " + GremlinDslDigestHelper.DSL_SOURCE_DIGEST_PREFIX + expectedDigest + "\n"
            + "package p;\nclass __ {}");
    assertThat(GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(generated))
        .isEqualTo(expectedDigest);
  }

  @Test
  public void getStoredDigestFromGeneratedFile_trimsWhitespaceAfterDigest() throws Exception {
    var generated = temp.newFile("__.java").toPath();
    Files.writeString(generated,
        "// " + GremlinDslDigestHelper.DSL_SOURCE_DIGEST_PREFIX + "  abc123  \npackage p;");
    assertThat(GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(generated))
        .isEqualTo("abc123");
  }

  @Test
  public void getStoredDigestFromGeneratedFile_returnsNullWhenCommentLineAbsent() throws Exception {
    var generated = temp.newFile("__.java").toPath();
    Files.writeString(generated, "package p;\nclass __ {}");
    assertThat(GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(generated)).isNull();
  }

  @Test
  public void getStoredDigestFromGeneratedFile_returnsNullForNonExistentPath() {
    var missing = temp.getRoot().toPath().resolve("__.java");
    assertThat(GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(missing)).isNull();
  }

  @Test
  public void storedDigestMatchesCurrentDigest_roundTrip() throws Exception {
    var dslSource = temp.newFile("YTDBGraphTraversalSourceDSL.java").toPath();
    Files.writeString(dslSource, "public interface YTDBGraphTraversalSourceDSL {}");
    var digest = GremlinDslDigestHelper.computeSourceDigest(dslSource);
    assertThat(digest).isNotEmpty();

    var generated = temp.newFile("__.java").toPath();
    Files.writeString(generated,
        "// " + GremlinDslDigestHelper.DSL_SOURCE_DIGEST_PREFIX + digest + "\npackage p;\nclass __ {}");
    var stored = GremlinDslDigestHelper.getStoredDigestFromGeneratedFile(generated);
    assertThat(stored).isEqualTo(digest);
  }
}
