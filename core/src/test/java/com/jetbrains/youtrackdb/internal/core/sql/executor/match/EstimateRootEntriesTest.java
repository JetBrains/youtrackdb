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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link MatchExecutionPlanner#estimateRootEntries} (private, tested via reflection).
 *
 * <p>Validates the cap behavior added in Step 17: when a filter's estimate exceeds the actual
 * class count, the result is capped at the class count.
 */
public class EstimateRootEntriesTest {

  private Method estimateRootEntries;
  private DatabaseSessionEmbedded db;
  private ImmutableSchema schema;
  private CommandContext ctx;

  @Before
  public void setUp() throws Exception {
    estimateRootEntries = findEstimateRootEntries();
    estimateRootEntries.setAccessible(true);

    db = mock(DatabaseSessionEmbedded.class);
    schema = mock(ImmutableSchema.class);
    var metadata = mock(MetadataDefault.class);
    when(db.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);

    ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(db);
  }

  /**
   * Locates {@code MatchExecutionPlanner.estimateRootEntries} via
   * reflection. Produces a clear error message if the method signature
   * changes, instead of a generic {@code NoSuchMethodException}.
   */
  private static Method findEstimateRootEntries() throws NoSuchMethodException {
    try {
      return MatchExecutionPlanner.class.getDeclaredMethod(
          "estimateRootEntries",
          Map.class, Map.class, Map.class, CommandContext.class);
    } catch (NoSuchMethodException e) {
      throw new NoSuchMethodException(
          "MatchExecutionPlanner.estimateRootEntries(Map, Map, Map,"
              + " CommandContext) not found — the method signature may"
              + " have changed. Update this test to match.");
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Long> invoke(
      Map<String, String> aliasClasses,
      Map<String, SQLRid> aliasRids,
      Map<String, SQLWhereClause> aliasFilters)
      throws Exception {
    try {
      return (Map<String, Long>) estimateRootEntries.invoke(
          null, aliasClasses, aliasRids, aliasFilters, ctx);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw e;
    }
  }

  private SchemaClassInternal mockClass(String name, long count) {
    var oClass = mock(SchemaClassInternal.class);
    when(schema.existsClass(name)).thenReturn(true);
    when(schema.getClassInternal(name)).thenReturn(oClass);
    when(oClass.approximateCount(any(DatabaseSessionEmbedded.class))).thenReturn(count);
    return oClass;
  }

  @Test
  public void ridAliasReturnsOne() throws Exception {
    var rids = Map.of("a", mock(SQLRid.class));
    var result = invoke(Map.of(), rids, Map.of());

    assertEquals(1L, (long) result.get("a"));
  }

  @Test
  public void noFilterReturnsClassCount() throws Exception {
    mockClass("Person", 5000L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of());

    assertEquals(5000L, (long) result.get("a"));
  }

  @Test
  public void filterEstimateBelowClassCountIsUnchanged() throws Exception {
    mockClass("Person", 5000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(200L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(200L, (long) result.get("a"));
  }

  @Test
  public void filterEstimateExceedingClassCountIsCapped() throws Exception {
    mockClass("Person", 1000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(5000L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(1000L, (long) result.get("a"));
  }

  @Test
  public void filterEstimateEqualToClassCountIsUnchanged() throws Exception {
    mockClass("Person", 3000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(3000L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(3000L, (long) result.get("a"));
  }

  @Test
  public void multipleAliasesWithMixedScenarios() throws Exception {
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
    assertEquals("No filter", 50L, (long) result.get("c"));
  }

  @Test
  public void aliasWithNoClassNameIsOmitted() throws Exception {
    var filter = mock(SQLWhereClause.class);
    var result = invoke(Map.of(), Map.of(), Map.of("x", filter));

    assertFalse("Alias without class should be omitted", result.containsKey("x"));
  }

  @Test(expected = CommandExecutionException.class)
  public void nonExistentClassThrows() throws Exception {
    when(schema.existsClass("Ghost")).thenReturn(false);

    invoke(Map.of("a", "Ghost"), Map.of(), Map.of());
  }

  @Test
  public void zeroClassCountCapsFilterEstimateToZero() throws Exception {
    mockClass("Empty", 0L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(50L);

    var result = invoke(Map.of("a", "Empty"), Map.of(), Map.of("a", filter));

    assertEquals(0L, (long) result.get("a"));
  }

  @Test
  public void filterReturningZeroIsPreserved() throws Exception {
    mockClass("Person", 5000L);
    var filter = mock(SQLWhereClause.class);
    when(filter.estimate(any(), anyLong(), any())).thenReturn(0L);

    var result = invoke(Map.of("a", "Person"), Map.of(), Map.of("a", filter));

    assertEquals(0L, (long) result.get("a"));
  }

  @Test
  public void ridTakesPriorityOverClassForSameAlias() throws Exception {
    mockClass("Person", 5000L);

    var aliasClasses = Map.of("a", "Person");
    var aliasRids = Map.of("a", mock(SQLRid.class));

    var result = invoke(aliasClasses, aliasRids, Map.of());

    assertEquals(1L, (long) result.get("a"));
  }

  @Test
  public void emptyInputsProduceEmptyResult() throws Exception {
    var result = invoke(Map.of(), Map.of(), Map.of());

    assertTrue(result.isEmpty());
  }
}
