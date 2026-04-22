package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Direct unit tests for the non-{@code normalize} branches of the {@code IndexCandidate}
 * hierarchy. {@code normalize()} requires a live database session (for {@code
 * SharedContext.getIndexManager()}) and is exercised by {@link IndexFinderTest}; here we pin the
 * pure logic — {@code invert()}, {@code getName()}, {@code getOperation()}, {@code properties()},
 * the {@link Operation#isRange()} enum method, and the {@link IndexMetadataPath} helper.
 *
 * <p>Standalone — no session required.
 */
public class IndexCandidatesTest {

  private SchemaProperty propA;
  private SchemaProperty propB;

  @Before
  public void setUp() {
    propA = mock(SchemaProperty.class);
    when(propA.getName()).thenReturn("a");
    propB = mock(SchemaProperty.class);
    when(propB.getName()).thenReturn("b");
  }

  // ------------------------------------------------------------------------- Operation enum

  @Test
  public void operationIsRangeIsTrueOnlyForRelationalOperators() {
    assertThat(Operation.Gt.isRange()).isTrue();
    assertThat(Operation.Lt.isRange()).isTrue();
    assertThat(Operation.Ge.isRange()).isTrue();
    assertThat(Operation.Le.isRange()).isTrue();
    // Non-range operations
    assertThat(Operation.Eq.isRange()).isFalse();
    assertThat(Operation.FuzzyEq.isRange()).isFalse();
    assertThat(Operation.Range.isRange()).isFalse(); // Range itself is a compound marker
  }

  // ------------------------------------------------------------------------- IndexCandidateImpl.invert

  @Test
  public void indexCandidateImplInvertFlipsGeToLt() {
    var c = new IndexCandidateImpl("idx", Operation.Ge, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.Lt);
  }

  @Test
  public void indexCandidateImplInvertFlipsGtToLe() {
    var c = new IndexCandidateImpl("idx", Operation.Gt, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.Le);
  }

  @Test
  public void indexCandidateImplInvertFlipsLeToGt() {
    var c = new IndexCandidateImpl("idx", Operation.Le, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.Gt);
  }

  @Test
  public void indexCandidateImplInvertFlipsLtToGe() {
    var c = new IndexCandidateImpl("idx", Operation.Lt, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.Ge);
  }

  /** Non-range operations are left unchanged by {@code invert()} — Eq is a no-op fallthrough. */
  @Test
  public void indexCandidateImplInvertIsIdentityForEq() {
    var c = new IndexCandidateImpl("idx", Operation.Eq, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.Eq);
  }

  @Test
  public void indexCandidateImplInvertIsIdentityForFuzzyEq() {
    var c = new IndexCandidateImpl("idx", Operation.FuzzyEq, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.FuzzyEq);
  }

  @Test
  public void indexCandidateImplInvertIsIdentityForRange() {
    var c = new IndexCandidateImpl("idx", Operation.Range, propA);
    assertThat(c.invert().getOperation()).isEqualTo(Operation.Range);
  }

  @Test
  public void indexCandidateImplInvertReturnsSameInstance() {
    var c = new IndexCandidateImpl("idx", Operation.Ge, propA);
    // invert is in-place — returns `this`, not a new instance
    assertThat(c.invert()).isSameAs(c);
  }

  @Test
  public void indexCandidateImplExposesNameAndProperties() {
    var c = new IndexCandidateImpl("idx.a", Operation.Eq, propA);
    assertThat(c.getName()).isEqualTo("idx.a");
    assertThat(c.properties()).containsExactly(propA);
  }

  // ------------------------------------------------------------------------- IndexCandidateChain

  /**
   * Pins the current trailing-arrow concatenation. If a future refactor (candidate for Track 22)
   * joins with {@code ->} as a proper separator (no trailing arrow, e.g., {@code
   * first->second->third}), this assertion flips.
   */
  @Test
  public void chainNameIsArrowJoinedWithTrailingArrow() {
    var chain = new IndexCandidateChain("first");
    chain.add("second");
    chain.add("third");
    assertThat(chain.getName()).isEqualTo("first->second->third->");
  }

  @Test
  public void chainWithSingleIndexHasTrailingArrow() {
    var chain = new IndexCandidateChain("only");
    assertThat(chain.getName()).isEqualTo("only->");
  }

  /** Edge case — empty-string name still renders with the trailing arrow separator. */
  @Test
  public void chainNameWithEmptyStringInputStillRenders() {
    var chain = new IndexCandidateChain("");
    assertThat(chain.getName()).isEqualTo("->");
  }

  @Test
  public void chainInvertFlipsGtToLe() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Gt);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Le);
  }

  @Test
  public void chainInvertFlipsGeToLt() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Ge);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Lt);
  }

  @Test
  public void chainInvertFlipsLtToGe() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Lt);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Ge);
  }

  @Test
  public void chainInvertFlipsLeToGt() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Le);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Gt);
  }

  @Test
  public void chainInvertIsIdentityForEq() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Eq);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Eq);
  }

  @Test
  public void chainInvertIsIdentityForFuzzyEq() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.FuzzyEq);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.FuzzyEq);
  }

  @Test
  public void chainInvertIsIdentityForRange() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Range);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Range);
  }

  @Test
  public void chainInvertReturnsSameInstanceEvenWhenOperationIsNull() {
    // setOperation was never called; invert must not NPE on the null comparison branch AND
    // must return `this` (in-place mutation, not a new chain).
    var chain = new IndexCandidateChain("i");
    var ret = chain.invert();
    assertThat(ret).isSameAs(chain);
    assertThat(ret.getOperation()).isNull();
  }

  @Test
  public void chainPropertiesIsAlwaysEmpty() {
    var chain = new IndexCandidateChain("i");
    chain.add("j");
    assertThat(chain.properties()).isEmpty();
  }

  @Test
  public void chainNormalizeReturnsNull() {
    // Chain normalization is intentionally a no-op (never composed by the planner).
    var chain = new IndexCandidateChain("i");
    assertThat(chain.normalize(null)).isNull();
  }

  // ------------------------------------------------------------------------- IndexCandidateComposite

  @Test
  public void compositeInvertReturnsNull() {
    // Composites never participate in invert — planner skips them when NOT is pushed down.
    var comp = new IndexCandidateComposite("idx", Operation.Eq, List.of(propA, propB));
    assertThat(comp.invert()).isNull();
  }

  @Test
  public void compositeNormalizeReturnsSameInstance() {
    var comp = new IndexCandidateComposite("idx", Operation.Eq, List.of(propA));
    assertThat(comp.normalize(null)).isSameAs(comp);
  }

  @Test
  public void compositeExposesNameAndOperationAndProperties() {
    var comp = new IndexCandidateComposite("idx.a_b", Operation.Eq, List.of(propA, propB));
    assertThat(comp.getName()).isEqualTo("idx.a_b");
    assertThat(comp.getOperation()).isEqualTo(Operation.Eq);
    assertThat(comp.properties()).containsExactly(propA, propB);
  }

  // ------------------------------------------------------------------------- MultipleIndexCanditate

  @Test
  public void multipleGetOperationThrowsUnsupported() {
    var m = new MultipleIndexCanditate();
    assertThatThrownBy(m::getOperation).isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * {@code invert()} is a TODO no-op that returns {@code this}. Pin so that if a future fix adds
   * real invert semantics, this test flags it.
   */
  @Test
  public void multipleInvertReturnsSameInstance() {
    var m = new MultipleIndexCanditate();
    assertThat(m.invert()).isSameAs(m);
  }

  @Test
  public void multipleAddAndGetCandidates() {
    var m = new MultipleIndexCanditate();
    var inner = new IndexCandidateImpl("idx", Operation.Eq, propA);
    m.addCanditate(inner);
    assertThat(m.getCanditates()).containsExactly(inner);
    // Pin: getCanditates() returns the live field (no defensive copy).
    assertThat(m.getCanditates()).isSameAs(m.canditates);
  }

  @Test
  public void multipleGetNameConcatenatesLastCandidateWithPipe() {
    // Pinning the current behavior: only the LAST candidate's name is used because the loop
    // overwrites `name` on every iteration. WHEN-FIXED: Track 22 may replace the loop with a
    // proper join and strip the trailing "|". Flipping the assertion to `.equals("idx|idx2|")`
    // would be the fix-target contract.
    var m = new MultipleIndexCanditate();
    m.addCanditate(new IndexCandidateImpl("idx", Operation.Eq, propA));
    m.addCanditate(new IndexCandidateImpl("idx2", Operation.Eq, propB));
    assertThat(m.getName()).isEqualTo("idx2|");
  }

  @Test
  public void multipleGetNameIsEmptyWhenNoCandidates() {
    var m = new MultipleIndexCanditate();
    assertThat(m.getName()).isEmpty();
  }

  @Test
  public void multiplePropertiesFlattenAllCandidateProperties() {
    var m = new MultipleIndexCanditate();
    m.addCanditate(new IndexCandidateImpl("idxA", Operation.Eq, propA));
    m.addCanditate(new IndexCandidateImpl("idxB", Operation.Eq, propB));
    assertThat(m.properties()).containsExactly(propA, propB);
  }

  @Test
  public void multiplePropertiesIsEmptyWhenNoCandidates() {
    assertThat(new MultipleIndexCanditate().properties()).isEmpty();
  }

  @Test
  public void multiplePropertiesSkipsCandidatesWithEmptyProperties() {
    // IndexCandidateChain.properties() is always empty — pin that its addAll(empty) contributes
    // nothing but does not throw.
    var m = new MultipleIndexCanditate();
    m.addCanditate(new IndexCandidateChain("chain"));
    m.addCanditate(new IndexCandidateImpl("idx", Operation.Eq, propA));
    assertThat(m.properties()).containsExactly(propA);
  }

  // ------------------------------------------------------------------------- RangeIndexCanditate

  @Test
  public void rangeInvertReturnsSameInstance() {
    var range = new RangeIndexCanditate("idx", propA);
    assertThat(range.invert()).isSameAs(range);
  }

  @Test
  public void rangeOperationIsAlwaysRange() {
    assertThat(new RangeIndexCanditate("idx", propA).getOperation()).isEqualTo(Operation.Range);
  }

  @Test
  public void rangeNormalizeReturnsSameInstance() {
    var range = new RangeIndexCanditate("idx", propA);
    assertThat(range.normalize(null)).isSameAs(range);
  }

  @Test
  public void rangeExposesSingleProperty() {
    var range = new RangeIndexCanditate("idx", propA);
    assertThat(range.properties()).containsExactly(propA);
    assertThat(range.getName()).isEqualTo("idx");
  }

  // ------------------------------------------------------------------------- RequiredIndexCanditate

  @Test
  public void requiredGetOperationThrowsUnsupported() {
    var r = new RequiredIndexCanditate();
    assertThatThrownBy(r::getOperation).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void requiredInvertReturnsSameInstance() {
    var r = new RequiredIndexCanditate();
    assertThat(r.invert()).isSameAs(r);
  }

  @Test
  public void requiredAddAndGetCandidates() {
    var r = new RequiredIndexCanditate();
    var inner = new IndexCandidateImpl("idx", Operation.Eq, propA);
    r.addCanditate(inner);
    assertThat(r.getCanditates()).containsExactly(inner);
    assertThat(r.getCanditates()).isSameAs(r.canditates);
  }

  @Test
  public void requiredGetNameLoopOverwritesSameAsMultiple() {
    // Same behavior as MultipleIndexCanditate.getName (single-assign loop — last candidate wins).
    var r = new RequiredIndexCanditate();
    r.addCanditate(new IndexCandidateImpl("a", Operation.Eq, propA));
    r.addCanditate(new IndexCandidateImpl("b", Operation.Eq, propB));
    assertThat(r.getName()).isEqualTo("b|");
  }

  @Test
  public void requiredPropertiesFlattenAllCandidates() {
    var r = new RequiredIndexCanditate();
    r.addCanditate(new IndexCandidateImpl("idxA", Operation.Eq, propA));
    r.addCanditate(new IndexCandidateImpl("idxB", Operation.Eq, propB));
    assertThat(r.properties()).containsExactly(propA, propB);
  }

  /**
   * RequiredIndexCanditate.normalize is pure logic over the child list — no CommandContext use.
   * When every child normalizes to non-null, a new RequiredIndexCanditate is built with the
   * normalized children.
   */
  @Test
  public void requiredNormalizeAllChildrenSurviveReturnsNewInstance() {
    var child1 = mock(IndexCandidate.class);
    var child2 = mock(IndexCandidate.class);
    var normalized1 = mock(IndexCandidate.class);
    var normalized2 = mock(IndexCandidate.class);
    when(child1.normalize(null)).thenReturn(normalized1);
    when(child2.normalize(null)).thenReturn(normalized2);
    var r = new RequiredIndexCanditate();
    r.addCanditate(child1);
    r.addCanditate(child2);

    var result = (RequiredIndexCanditate) r.normalize(null);
    assertThat(result).isNotSameAs(r);
    assertThat(result.getCanditates()).containsExactly(normalized1, normalized2);
  }

  /** Any child normalizing to null short-circuits the whole normalize to null. */
  @Test
  public void requiredNormalizeReturnsNullIfAnyChildNormalizesToNull() {
    var child1 = mock(IndexCandidate.class);
    var child2 = mock(IndexCandidate.class);
    when(child1.normalize(null)).thenReturn(mock(IndexCandidate.class));
    when(child2.normalize(null)).thenReturn(null);
    var r = new RequiredIndexCanditate();
    r.addCanditate(child1);
    r.addCanditate(child2);
    assertThat(r.normalize(null)).isNull();
  }

  /** Empty child list → new empty RequiredIndexCanditate (for-loop body never entered). */
  @Test
  public void requiredNormalizeWithNoChildrenReturnsEmptyRequired() {
    var r = new RequiredIndexCanditate();
    var result = (RequiredIndexCanditate) r.normalize(null);
    assertThat(result).isNotSameAs(r);
    assertThat(result.getCanditates()).isEmpty();
  }

  /** Mocked CommandContext is accepted but never consumed by Required.normalize. */
  @Test
  public void requiredNormalizeDoesNotTouchContext() {
    var ctx = mock(CommandContext.class);
    var child = mock(IndexCandidate.class);
    when(child.normalize(ctx)).thenReturn(child);
    var r = new RequiredIndexCanditate();
    r.addCanditate(child);
    var result = (RequiredIndexCanditate) r.normalize(ctx);
    assertThat(result.getCanditates()).containsExactly(child);
    // No interactions on the context — pins the "pure logic" guarantee.
    org.mockito.Mockito.verifyNoInteractions(ctx);
  }

  // ------------------------------------------------------------------------- IndexMetadataPath

  @Test
  public void pathConstructorSeedsSingleEntry() {
    var path = new IndexMetadataPath("first");
    assertThat(path.getPath()).containsExactly("first");
  }

  @Test
  public void pathAddPrePrependsValue() {
    var path = new IndexMetadataPath("tail");
    path.addPre("mid");
    path.addPre("head");
    assertThat(path.getPath()).containsExactly("head", "mid", "tail");
  }

  @Test
  public void pathGetPathIsLiveReference() {
    // getPath returns the underlying ArrayList reference — mutating it affects the holder.
    // Pinning behavior rather than the ideal immutable view.
    var path = new IndexMetadataPath("x");
    path.getPath().add("y");
    assertThat(path.getPath()).containsExactly("x", "y");
  }
}
