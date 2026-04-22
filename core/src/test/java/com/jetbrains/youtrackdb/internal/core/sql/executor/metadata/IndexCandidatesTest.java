package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

  @Test
  public void chainInvertFlipsRangeOperations() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Gt);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Le);

    var chain2 = new IndexCandidateChain("i");
    chain2.setOperation(Operation.Ge);
    assertThat(chain2.invert().getOperation()).isEqualTo(Operation.Lt);

    var chain3 = new IndexCandidateChain("i");
    chain3.setOperation(Operation.Lt);
    assertThat(chain3.invert().getOperation()).isEqualTo(Operation.Ge);

    var chain4 = new IndexCandidateChain("i");
    chain4.setOperation(Operation.Le);
    assertThat(chain4.invert().getOperation()).isEqualTo(Operation.Gt);
  }

  @Test
  public void chainInvertDoesNothingForEq() {
    var chain = new IndexCandidateChain("i");
    chain.setOperation(Operation.Eq);
    assertThat(chain.invert().getOperation()).isEqualTo(Operation.Eq);
  }

  @Test
  public void chainInvertDoesNothingWhenOperationIsNull() {
    // setOperation was never called; invert must not NPE on the null comparison branch.
    var chain = new IndexCandidateChain("i");
    assertThat(chain.invert().getOperation()).isNull();
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
    assertThat(m.canditates).containsExactly(inner);
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
    assertThat(r.canditates).containsExactly(inner);
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
