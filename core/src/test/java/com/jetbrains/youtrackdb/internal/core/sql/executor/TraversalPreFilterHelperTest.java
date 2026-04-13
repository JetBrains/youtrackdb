package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for the adaptive abort guards in {@link TraversalPreFilterHelper}.
 *
 * <p>The guard methods ({@code shouldAbort}, {@code passesRatioCheck}) are
 * pure functions that depend only on integer/double arithmetic, so they can
 * be tested without a database context.
 */
public class TraversalPreFilterHelperTest {

  @After
  public void restoreDefaults() {
    // resetToDefault() restores the auto-scaled value and the "never
    // explicitly set" state so isChanged() returns false.
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.resetToDefault();
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.resetToDefault();
    GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.resetToDefault();
    // Reset new split config entries fully — resetToDefault() restores the
    // "never explicitly set" state so isChanged() returns false and the
    // fallback path in edgeLookupMaxRatio() is testable.
    GlobalConfiguration.QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO.resetToDefault();
    GlobalConfiguration.QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY
        .resetToDefault();
  }

  // =========================================================================
  // shouldAbort — absolute maxRidSetSize cap
  // =========================================================================

  /**
   * When the accumulated count exceeds {@link TraversalPreFilterHelper#maxRidSetSize()},
   * the build must abort.
   */
  @Test
  public void shouldAbort_exceedsAbsoluteCap_aborts() {
    int count = TraversalPreFilterHelper.maxRidSetSize() + 1;
    assertThat(TraversalPreFilterHelper.shouldAbort(count)).isTrue();
  }

  /**
   * When the accumulated count is below the absolute cap, the build
   * should continue.
   */
  @Test
  public void shouldAbort_belowAbsoluteCap_continues() {
    assertThat(TraversalPreFilterHelper.shouldAbort(50_000)).isFalse();
  }

  /**
   * At the exact maxRidSetSize boundary, the count is not yet exceeded
   * (guard uses strict {@code >}).
   */
  @Test
  public void shouldAbort_exactlyAtAbsoluteCap_doesNotAbort() {
    assertThat(TraversalPreFilterHelper.shouldAbort(
        TraversalPreFilterHelper.maxRidSetSize())).isFalse();
  }

  // =========================================================================
  // passesRatioCheck — selectivity ratio
  // =========================================================================

  /**
   * A RidSet that is small relative to the link bag passes the ratio
   * check (the filter is highly selective).
   */
  @Test
  public void passesRatioCheck_smallRidSetVsLargeLinkBag_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(100, 10_000)).isTrue();
  }

  /**
   * A RidSet that covers more than {@link TraversalPreFilterHelper#maxSelectivityRatio()}
   * of the link bag fails — the filter rejects too few elements.
   */
  @Test
  public void passesRatioCheck_ridSetTooLargeRelativeToLinkBag_fails() {
    int linkBag = 1000;
    int ridSetSize =
        (int) (linkBag * TraversalPreFilterHelper.maxSelectivityRatio()) + 1;
    assertThat(TraversalPreFilterHelper.passesRatioCheck(ridSetSize, linkBag))
        .isFalse();
  }

  /**
   * When the link bag size is zero, the ratio check passes (avoids
   * division by zero; zero-size link bag means no records anyway).
   */
  @Test
  public void passesRatioCheck_zeroLinkBag_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(0, 0)).isTrue();
  }

  /**
   * A RidSet exactly at the ratio boundary passes (guard uses {@code <=}).
   */
  @Test
  public void passesRatioCheck_exactlyAtBoundary_passes() {
    int linkBag = 1000;
    int ridSetSize =
        (int) (linkBag * TraversalPreFilterHelper.maxSelectivityRatio());
    assertThat(TraversalPreFilterHelper.passesRatioCheck(ridSetSize, linkBag))
        .isTrue();
  }

  /**
   * An empty RidSet always passes the ratio check.
   */
  @Test
  public void passesRatioCheck_emptyRidSet_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(0, 10_000)).isTrue();
  }

  // =========================================================================
  // Defaults sanity
  // =========================================================================

  /**
   * The three adaptive-abort defaults must have their documented values.
   * maxRidSetSize is auto-scaled from heap: 0.5% of maxMemory, clamped
   * to [100K, 10M].
   */
  @Test
  public void defaults_haveExpectedValues() {
    int expectedCap = (int) Math.min(10_000_000L,
        Math.max(100_000L, Runtime.getRuntime().maxMemory() / 200));
    assertThat(TraversalPreFilterHelper.maxRidSetSize())
        .isEqualTo(expectedCap)
        .isGreaterThanOrEqualTo(100_000)
        .isLessThanOrEqualTo(10_000_000);
    assertThat(TraversalPreFilterHelper.maxSelectivityRatio()).isEqualTo(0.8);
    assertThat(TraversalPreFilterHelper.minLinkBagSize()).isEqualTo(50);
  }

  // =========================================================================
  // Runtime override via GlobalConfiguration
  // =========================================================================

  /**
   * Verifies that changing configuration at runtime is immediately
   * reflected by the getter methods and affects guard behaviour.
   */
  @Test
  public void runtimeOverride_affectsGuardBehaviour() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(500);
    assertThat(TraversalPreFilterHelper.maxRidSetSize()).isEqualTo(500);
    assertThat(TraversalPreFilterHelper.shouldAbort(501)).isTrue();
    assertThat(TraversalPreFilterHelper.shouldAbort(499)).isFalse();

    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.5);
    assertThat(TraversalPreFilterHelper.maxSelectivityRatio()).isEqualTo(0.5);
    assertThat(TraversalPreFilterHelper.passesRatioCheck(600, 1000)).isFalse();
    assertThat(TraversalPreFilterHelper.passesRatioCheck(500, 1000)).isTrue();

    GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.setValue(200);
    assertThat(TraversalPreFilterHelper.minLinkBagSize()).isEqualTo(200);
  }

  // =========================================================================
  // toRid — type-based RID extraction
  // =========================================================================

  /** Null input returns null. */
  @Test
  public void toRid_nullInput_returnsNull() {
    assertThat(TraversalPreFilterHelper.toRid(null)).isNull();
  }

  /** A RecordId (which implements RID) is returned directly. */
  @Test
  public void toRid_ridInput_returnsSameInstance() {
    RID rid = new RecordId(10, 5);
    assertThat(TraversalPreFilterHelper.toRid(rid)).isSameAs(rid);
  }

  /** An Identifiable returns its identity. */
  @Test
  public void toRid_identifiableInput_returnsIdentity() {
    var rid = new RecordId(7, 3);
    var identifiable = mock(Identifiable.class);
    when(identifiable.getIdentity()).thenReturn(rid);

    assertThat(TraversalPreFilterHelper.toRid(identifiable)).isSameAs(rid);
  }

  /** A non-RID, non-Identifiable object returns null. */
  @Test
  public void toRid_stringInput_returnsNull() {
    assertThat(TraversalPreFilterHelper.toRid("not-a-rid")).isNull();
  }

  /** An integer returns null. */
  @Test
  public void toRid_integerInput_returnsNull() {
    assertThat(TraversalPreFilterHelper.toRid(42)).isNull();
  }

  // =========================================================================
  // intersect — delegation to RidSet.intersect
  // =========================================================================

  /** Both null returns null. */
  @Test
  public void intersect_bothNull_returnsNull() {
    assertThat(TraversalPreFilterHelper.intersect(null, null)).isNull();
  }

  /** One null returns the other. */
  @Test
  public void intersect_oneNull_returnsOther() {
    var set = new RidSet();
    set.add(new RecordId(1, 1));
    assertThat(TraversalPreFilterHelper.intersect(set, null)).isSameAs(set);
    assertThat(TraversalPreFilterHelper.intersect(null, set)).isSameAs(set);
  }

  /** Two overlapping sets return the intersection. */
  @Test
  public void intersect_overlappingSets_returnsIntersection() {
    var a = new RidSet();
    a.add(new RecordId(10, 1));
    a.add(new RecordId(10, 2));

    var b = new RidSet();
    b.add(new RecordId(10, 2));
    b.add(new RecordId(10, 3));

    var result = TraversalPreFilterHelper.intersect(a, b);
    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.contains(new RecordId(10, 2))).isTrue();
  }

  // =========================================================================
  // findIndexForFilter — null guards
  // =========================================================================

  @Test
  public void findIndexForFilter_nullContext_returnsNull() {
    var where = new SQLWhereClause(-1);
    assertThat(TraversalPreFilterHelper.findIndexForFilter(where, "SomeClass", null))
        .isNull();
  }

  /** findIndexForFilter returns null when the context has no database session. */
  @Test
  public void findIndexForFilter_nullSession_returnsNull() {
    var ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(null);
    var where = new SQLWhereClause(-1);
    assertThat(TraversalPreFilterHelper.findIndexForFilter(where, "SomeClass", ctx))
        .isNull();
  }

  /** findIndexForFilter returns null when the class does not exist in schema. */
  @Test
  public void findIndexForFilter_nonExistentClass_returnsNull() {
    var session =
        mock(DatabaseSessionEmbedded.class);
    var metadata =
        mock(MetadataDefault.class);
    var schema =
        mock(ImmutableSchema.class);
    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
    when(schema.getClassInternal("NoSuchClass")).thenReturn(null);

    var ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(session);

    var where = new SQLWhereClause(-1);
    assertThat(TraversalPreFilterHelper.findIndexForFilter(
        where, "NoSuchClass", ctx)).isNull();
  }

  // =========================================================================
  // collectionIdsForClass — polymorphic collection ID extraction
  // =========================================================================

  /** Converts a class with multiple collection IDs into an IntSet. */
  @Test
  public void collectionIdsForClass_multipleIds() {
    var clazz = mock(
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.class);
    when(clazz.getPolymorphicCollectionIds()).thenReturn(new int[] {10, 20, 30});

    var result = TraversalPreFilterHelper.collectionIdsForClass(clazz);
    assertThat(result).hasSize(3);
    assertThat(result.contains(10)).isTrue();
    assertThat(result.contains(20)).isTrue();
    assertThat(result.contains(30)).isTrue();
  }

  /** An empty array produces an empty IntSet. */
  @Test
  public void collectionIdsForClass_emptyArray() {
    var clazz = mock(
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.class);
    when(clazz.getPolymorphicCollectionIds()).thenReturn(new int[0]);

    var result = TraversalPreFilterHelper.collectionIdsForClass(clazz);
    assertThat(result).isEmpty();
  }

  /** A single collection ID produces a singleton IntSet. */
  @Test
  public void collectionIdsForClass_singleId() {
    var clazz = mock(
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.class);
    when(clazz.getPolymorphicCollectionIds()).thenReturn(new int[] {42});

    var result = TraversalPreFilterHelper.collectionIdsForClass(clazz);
    assertThat(result).hasSize(1);
    assertThat(result.contains(42)).isTrue();
  }

  /** findIndexForFilter returns null when the class has no indexes. */
  @Test
  public void findIndexForFilter_noIndexes_returnsNull() {
    var session =
        mock(DatabaseSessionEmbedded.class);
    var metadata =
        mock(MetadataDefault.class);
    var schema =
        mock(ImmutableSchema.class);
    var schemaClass =
        mock(SchemaClassInternal.class);
    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
    when(schema.getClassInternal("EmptyClass")).thenReturn(schemaClass);
    when(schemaClass.getIndexesInternal())
        .thenReturn(java.util.Collections.emptySet());

    var ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(session);

    var where = new SQLWhereClause(-1);
    assertThat(TraversalPreFilterHelper.findIndexForFilter(
        where, "EmptyClass", ctx)).isNull();
  }

  // =========================================================================
  // Split config — edgeLookupMaxRatio and indexLookupMaxSelectivity
  // =========================================================================

  /**
   * When the new edge lookup config is explicitly set, it takes precedence
   * over the old config.
   */
  @Test
  public void edgeLookupMaxRatio_newConfigTakesPrecedence() {
    GlobalConfiguration.QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO.setValue(0.6);
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.3);
    assertThat(TraversalPreFilterHelper.edgeLookupMaxRatio()).isEqualTo(0.6);
  }

  /**
   * When the new index lookup config is explicitly set, it is used.
   */
  @Test
  public void indexLookupMaxSelectivity_newConfigOverride() {
    GlobalConfiguration.QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY
        .setValue(0.99);
    assertThat(TraversalPreFilterHelper.indexLookupMaxSelectivity())
        .isEqualTo(0.99);
  }

  /**
   * indexLookupMaxSelectivity reads from its own config entry, not the
   * old selectivity ratio. Even when the old property changes, the index
   * lookup threshold is independent. Uses a non-default value (0.88) to
   * ensure the test would fail if the implementation returned a hardcoded
   * default.
   */
  @Test
  public void indexLookupMaxSelectivity_independentOfOldConfig() {
    GlobalConfiguration.QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY
        .setValue(0.88);
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.5);
    // Should read from its own entry (0.88), not old (0.5)
    assertThat(TraversalPreFilterHelper.indexLookupMaxSelectivity())
        .isEqualTo(0.88);
  }

  /**
   * When the new edge lookup config is NOT explicitly set, edgeLookupMaxRatio
   * falls back to the old maxSelectivityRatio property. This is the critical
   * backward-compatibility path for existing deployments.
   */
  @Test
  public void edgeLookupMaxRatio_fallsBackToOldConfig() {
    // Ensure new config is in "never set" state (resetToDefault in @After)
    GlobalConfiguration.QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO.resetToDefault();
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.55);
    assertThat(TraversalPreFilterHelper.edgeLookupMaxRatio()).isEqualTo(0.55);
  }

  // =========================================================================
  // Auto-scaled maxRidSetSize — boundary and override tests
  // =========================================================================

  /**
   * Explicit setValue() override beats the auto-scaled default, and
   * isChanged() returns true to reflect an operator-configured value.
   */
  @Test
  public void maxRidSetSize_explicitOverrideBeatsAutoScale() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(42);
    assertThat(TraversalPreFilterHelper.maxRidSetSize()).isEqualTo(42);
    assertThat(
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.isChanged())
        .isTrue();
  }

  /**
   * resetToDefault() restores the auto-scaled value and clears the
   * "explicitly set" flag so isChanged() returns false.
   */
  @Test
  public void maxRidSetSize_resetToDefaultRestoresAutoScale() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(42);
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.resetToDefault();

    int expectedCap = (int) Math.min(10_000_000L,
        Math.max(100_000L, Runtime.getRuntime().maxMemory() / 200));
    assertThat(TraversalPreFilterHelper.maxRidSetSize())
        .isEqualTo(expectedCap);
    assertThat(
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.isChanged())
        .isFalse();
  }

  /**
   * Verifies the shouldAbort guard works correctly at the auto-scale
   * formula's floor and ceiling boundary values by exercising production
   * code via setValue(). Each boundary is tested at the exact threshold
   * and one above.
   */
  @Test
  public void maxRidSetSize_shouldAbortAtBoundaryValues() {
    // Floor value: the minimum the auto-scale formula can produce
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(100_000);
    assertThat(TraversalPreFilterHelper.maxRidSetSize()).isEqualTo(100_000);
    assertThat(TraversalPreFilterHelper.shouldAbort(100_000)).isFalse();
    assertThat(TraversalPreFilterHelper.shouldAbort(100_001)).isTrue();

    // Ceiling value: the maximum the auto-scale formula can produce
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(10_000_000);
    assertThat(TraversalPreFilterHelper.maxRidSetSize())
        .isEqualTo(10_000_000);
    assertThat(TraversalPreFilterHelper.shouldAbort(10_000_000)).isFalse();
    assertThat(TraversalPreFilterHelper.shouldAbort(10_000_001)).isTrue();

    // Verify the actual auto-scaled default is within the documented bounds
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.resetToDefault();
    assertThat(TraversalPreFilterHelper.maxRidSetSize())
        .isGreaterThanOrEqualTo(100_000)
        .isLessThanOrEqualTo(10_000_000);
  }
}
