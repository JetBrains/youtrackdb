package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link MatchExecutionPlanner#estimateRootEntries} (package-private).
 *
 * <p>Validates the cap behavior added in Step 17: when a filter's estimate exceeds the actual
 * class count, the result is capped at the class count.
 */
public class EstimateRootEntriesTest {

  private DatabaseSessionEmbedded db;
  private ImmutableSchema schema;
  private CommandContext ctx;

  @Before
  public void setUp() {
    db = mock(DatabaseSessionEmbedded.class);
    schema = mock(ImmutableSchema.class);
    var metadata = mock(MetadataDefault.class);
    when(db.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);

    ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(db);
  }

  private Map<String, Long> invoke(
      Map<String, String> aliasClasses,
      Map<String, SQLRid> aliasRids,
      Map<String, SQLWhereClause> aliasFilters) {
    return MatchExecutionPlanner.estimateRootEntries(
        aliasClasses, aliasRids, aliasFilters, ctx);
  }

  private SchemaClassInternal mockClass(String name, long count) {
    var oClass = mock(SchemaClassInternal.class);
    when(schema.existsClass(name)).thenReturn(true);
    when(schema.getClassInternal(name)).thenReturn(oClass);
    when(oClass.approximateCount(any(DatabaseSessionEmbedded.class))).thenReturn(count);
    return oClass;
  }

  @Test
  public void ridAliasReturnsOne() {
    var rids = Map.of("a", mock(SQLRid.class));
    var result = invoke(Map.of(), rids, Map.of());

    assertEquals(1L, (long) result.get("a"));
  }

  @Test
  public void noFilterReturnsClassCountPlusOne() {
    // No filter → classCount + 1 bias so that filtered nodes are preferred.
    mockClass("Person", 5000L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of());

    assertEquals(5001L, (long) result.get("a"));
  }

  @Test
  public void filterEstimateBelowClassCountIsUnchanged() {
    mockClass("Person", 5000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(200L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(200L, (long) result.get("a"));
  }

  @Test
  public void filterEstimateExceedingClassCountIsCapped() {
    mockClass("Person", 1000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(5000L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(1000L, (long) result.get("a"));
  }

  @Test
  public void filterEstimateEqualToClassCountIsUnchanged() {
    mockClass("Person", 3000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(3000L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(3000L, (long) result.get("a"));
  }

  @Test
  public void multipleAliasesWithMixedScenarios() {
    mockClass("Person", 1000L);
    mockClass("City", 50L);

    var filterPerson = mock(SQLWhereClause.class);
    when(filterPerson.estimate(any(), anyLong(), any())).thenReturn(2000L);

    var aliasClasses = new LinkedHashMap<String, String>();
    aliasClasses.put("p", "Person");
    aliasClasses.put("c", "City");
    var aliasRids = new HashMap<String, SQLRid>();
    aliasRids.put("r", mock(SQLRid.class));
    var aliasFilters = new HashMap<String, SQLWhereClause>();
    aliasFilters.put("p", filterPerson);

    var result = invoke(aliasClasses, aliasRids, aliasFilters);

    assertEquals("RID alias", 1L, (long) result.get("r"));
    assertEquals("Capped filter", 1000L, (long) result.get("p"));
    // No filter → classCount + 1 bias
    assertEquals("No filter", 51L, (long) result.get("c"));
  }

  @Test
  public void aliasWithNoClassNameIsOmitted() {
    var filter = mock(SQLWhereClause.class);
    var result = invoke(Map.of(), Map.of(), Map.of("x", filter));

    assertFalse("Alias without class should be omitted", result.containsKey("x"));
  }

  @Test(expected = CommandExecutionException.class)
  public void nonExistentClassThrows() {
    when(schema.existsClass("Ghost")).thenReturn(false);

    invoke(Map.of("a", "Ghost"), Map.of(), Map.of());
  }

  @Test
  public void zeroClassCountCapsFilterEstimateToZero() {
    mockClass("Empty", 0L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(50L);

    var result = invoke(Map.of("a", "Empty"), Map.of(), Map.of("a", filter));

    assertEquals(0L, (long) result.get("a"));
  }

  @Test
  public void filterReturningZeroIsPreserved() {
    mockClass("Person", 5000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(0L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(0L, (long) result.get("a"));
  }

  @Test
  public void ridTakesPriorityOverClassForSameAlias() {
    mockClass("Person", 5000L);

    var aliasClasses = Map.of("a", "Person");
    var aliasRids = Map.of("a", mock(SQLRid.class));

    var result = invoke(aliasClasses, aliasRids, Map.of());

    assertEquals(1L, (long) result.get("a"));
  }

  @Test
  public void emptyInputsProduceEmptyResult() {
    var result = invoke(Map.of(), Map.of(), Map.of());

    assertTrue(result.isEmpty());
  }

  @Test
  public void noFilterBias_isExactlyClassCountPlusOne() {
    // Verify that the unfiltered bias is exactly +1, not +0 or +2.
    // Kills mutants that change the literal 1 in "classCount + 1".
    mockClass("Person", 0L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of());

    assertEquals("0 + 1 = 1", 1L, (long) result.get("a"));
  }

  @Test
  public void filterEstimate_usesMinNotMax() {
    // When filter estimate < classCount, result is filter estimate (not classCount).
    // Kills mutant that replaces Math.min with Math.max.
    mockClass("Person", 10_000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(50L);

    var result = invoke(
        Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(50L, (long) result.get("a"));
  }

  @Test
  public void filteredNodeAlwaysLessThanOrEqualUnfilteredBias() {
    // The min(estimate, classCount) result for a filtered alias must be
    // <= classCount, while the unfiltered alias gets classCount+1.
    // This verifies that filtered nodes are always preferred.
    mockClass("Person", 500L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(500L);

    var aliasClasses = new LinkedHashMap<String, String>();
    aliasClasses.put("filtered", "Person");
    aliasClasses.put("unfiltered", "Person");
    var filters = new HashMap<String, SQLWhereClause>();
    filters.put("filtered", filter);

    var result = invoke(aliasClasses, Map.of(), filters);

    assertTrue("Filtered should be <= unfiltered",
        result.get("filtered") <= result.get("unfiltered"));
    assertEquals(500L, (long) result.get("filtered"));
    assertEquals(501L, (long) result.get("unfiltered"));
  }

  @Test
  public void ridTakesPriorityOverClassAndFilter() {
    // When an alias has a RID, class, AND filter, the RID path (=1)
    // should take priority via continue, never reaching the filter path.
    mockClass("Person", 5000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(200L);

    var aliasClasses = Map.of("a", "Person");
    var aliasRids = Map.of("a", mock(SQLRid.class));
    var aliasFilters = new HashMap<String, SQLWhereClause>();
    aliasFilters.put("a", filter);

    var result = invoke(aliasClasses, aliasRids, aliasFilters);

    assertEquals("RID takes priority", 1L, (long) result.get("a"));
  }

  @Test
  public void nullClassName_aliasOmittedFromResult() {
    // When aliasClasses maps an alias to null, that alias should be
    // skipped (className == null → continue). Kills the null-check negation.
    var aliasClasses = new HashMap<String, String>();
    aliasClasses.put("a", null);

    var result = invoke(aliasClasses, Map.of(), Map.of());

    assertFalse("Null className alias should be omitted",
        result.containsKey("a"));
  }

  @Test
  public void filterEstimateNegative_isCappedByMin() {
    // If filter.estimate returns a negative value (e.g., -1),
    // Math.min(negative, classCount) returns the negative value.
    // This tests boundary behavior of Math.min.
    mockClass("Person", 100L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(-1L);

    var result = invoke(
        Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(-1L, (long) result.get("a"));
  }

  @Test
  public void classCountOne_unfilteredReturnTwo() {
    // Boundary: classCount = 1 → unfiltered returns 2 (1 + 1).
    mockClass("Singleton", 1L);

    var result = invoke(
        Map.of("a", "Singleton"), Map.of(), Map.of());

    assertEquals(2L, (long) result.get("a"));
  }

  @Test
  public void filterOnlyAlias_noClassMapping_isOmitted() {
    // Alias appears only in aliasFilters (not in aliasClasses or aliasRids).
    // It's still in allAliases but className lookup returns null → skipped.
    var filter = mock(SQLWhereClause.class);

    var result = invoke(Map.of(), Map.of(), Map.of("x", filter));

    assertFalse(result.containsKey("x"));
  }
}
