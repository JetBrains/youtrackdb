package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Stored-key tests pin acquisition counts, pairwise metadata selection and lazy effects. */
public class SQLOrderByStoredKeyTest extends DbTestBase {

  private CommandContext context() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private ResultInternal row(Object value) {
    var row = new ResultInternal(session);
    row.setProperty("v", value);
    return row;
  }

  private SQLOrderByItem item(SQLModifier modifier) {
    var item = new SQLOrderByItem();
    item.setAlias("v");
    item.setModifier(modifier);
    return item;
  }

  private List<Result> sort(List<Result> rows, List<SQLOrderByItem> items, Integer capacity,
      CommandContext ctx, SQLOrderByItem hint, Boolean sorted, AtomicInteger pulled,
      AtomicBoolean closed) {
    var order = new SQLOrderBy(-1);
    order.setItems(items);
    var step = new OrderByStep(order, capacity, hint, sorted != null, ctx, -1, false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext context) {
        if (sorted != null) {
          context.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, sorted);
        }
        var iterator = rows.iterator();
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext context) {
            return iterator.hasNext();
          }

          @Override
          public Result next(CommandContext context) {
            pulled.incrementAndGet();
            return iterator.next();
          }

          @Override
          public void close(CommandContext context) {
            closed.set(true);
          }
        };
      }
    });
    var stream = step.start(ctx);
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }

  private List<Result> sort(List<Result> rows, List<SQLOrderByItem> items, Integer capacity) {
    return sort(rows, items, capacity, context(), null, null,
        new AtomicInteger(), new AtomicBoolean());
  }

  /** Unbounded sorting acquires each required RID suffix once and keeps the full ordered result. */
  @Test
  public void unboundedRidSuffixLoadsOncePerRowAndNeededItem() {
    assertRidLoads(null);
  }

  /** Heap replacements and draining reuse each RID suffix without changing the selected rows. */
  @Test
  public void boundedRidSuffixLoadsOncePerRowAndNeededItem() {
    assertRidLoads(17);
  }

  private void assertRidLoads(Integer capacity) {
    session.begin();
    var rows = new ArrayList<Result>();
    for (int i = 127; i >= 0; i--) {
      var entity = session.newEntity();
      entity.setProperty("first", i / 4);
      entity.setProperty("second", i);
      rows.add(row(entity.getIdentity()));
    }
    var actual = session.getActiveTransaction();
    var loads = new HashMap<Object, Integer>();
    var transaction = mock(FrontendTransactionImpl.class);
    when(transaction.loadEntity(any(Identifiable.class))).thenAnswer(call -> {
      Identifiable id = call.getArgument(0);
      loads.merge(id, 1, Integer::sum);
      return actual.loadEntity(id);
    });
    var db = spy(session);
    doReturn(transaction).when(db).getActiveTransaction();
    var ctx = context();
    ctx.setDatabaseSession(db);
    var items = new ArrayList<SQLOrderByItem>();
    for (String property : List.of("first", "second")) {
      var modifier = new SQLModifier(-1);
      modifier.suffix = new SQLSuffixIdentifier(new SQLIdentifier(property));
      items.add(item(modifier));
    }
    var result = sort(rows, items, capacity, ctx, null, null,
        new AtomicInteger(), new AtomicBoolean());
    assertEquals(capacity == null ? 128 : capacity.intValue(), result.size());
    for (int i = 0; i < result.size(); i++) {
      assertSame(rows.get(127 - i), result.get(i));
    }
    // Every group ties on the primary key, so every row needs both suffixes.
    assertEquals(128, loads.size());
    loads.values().forEach(count -> assertEquals(2, count.intValue()));
    session.rollback();
  }

  /** Both algorithms leave single-row and lexicographically unused modifiers untouched. */
  @Test
  public void singleRowsAndUnusedSecondaryItemsNeverRunModifiers() {
    var fail = new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext ctx) {
        throw new IllegalStateException("unused");
      }
    };
    for (Integer capacity : java.util.Arrays.asList(null, 1, 3)) {
      assertEquals(1, sort(List.of(row(1)), List.of(item(fail)), capacity).size());
      var rows = List.<Result>of(row(3), row(2), row(1));
      assertSame(rows.getLast(), sort(rows, List.of(item(null), item(fail)), capacity).getFirst());
    }
  }

  /** Raw-null rows choose metadata only against another raw-null row, not by coalescing. */
  @Test
  public void metadataVariantsMatchDirectPairSignsAndRunOncePerVariant() {
    var ctx = context();
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    var rows = List.of(row(null), row(null), row(5), row(null));
    rows.get(0).setMetadata("v", 1);
    rows.get(1).setMetadata("v", 9);
    var calls = new IdentityHashMap<Result, Map<Object, Integer>>();
    var modifier = new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext context) {
        calls.computeIfAbsent(row, ignored -> new HashMap<>()).merge(value, 1, Integer::sum);
        return value == null ? 5 : value;
      }
    };
    var stored = item(modifier);
    var direct = item(new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext context) {
        return value == null ? 5 : value;
      }
    });
    var keys = rows.stream().map(ignored -> new SQLOrderByItem.SortKey()).toList();
    for (int repeat = 0; repeat < 3; repeat++) {
      for (int a = 0; a < rows.size(); a++) {
        for (int b = 0; b < rows.size(); b++) {
          assertEquals(Integer.signum(direct.compare(rows.get(a), rows.get(b), ctx, placements)),
              Integer.signum(stored.compareKeys(rows.get(a), keys.get(a), rows.get(b), keys.get(b),
                  ctx, placements)));
        }
      }
    }
    assertEquals(Map.of(1, 1), withoutNull(calls.get(rows.get(0))));
    assertEquals(1, calls.get(rows.get(0)).get(null).intValue());
    assertEquals(1, calls.get(rows.get(1)).get(null).intValue());
    assertEquals(1, calls.get(rows.get(1)).get(9).intValue());
    assertEquals(1, calls.get(rows.get(2)).get(5).intValue());
    // Absent metadata and the raw-null variant both evaluate null, once each.
    assertEquals(2, calls.get(rows.get(3)).get(null).intValue());
    assertTrue(stored.compareKeys(rows.get(0), keys.get(0), rows.get(1), keys.get(1),
        ctx, placements) < 0);
    assertEquals(0, stored.compareKeys(rows.get(0), keys.get(0), rows.get(2), keys.get(2),
        ctx, placements));
    assertEquals(0, stored.compareKeys(rows.get(2), keys.get(2), rows.get(1), keys.get(1),
        ctx, placements));
  }

  private Map<Object, Integer> withoutNull(Map<Object, Integer> values) {
    var copy = new HashMap<>(values);
    copy.remove(null);
    return copy;
  }

  /** First demand controls effects and context reads, with no later reevaluation. */
  @Test
  public void mutableContextAndEffectsFollowFirstComparisonDemand() {
    var ctx = context();
    var order = new ArrayList<Result>();
    var counter = new AtomicInteger();
    var modifier = new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext context) {
        order.add(row);
        context.setVariable("$current", row);
        return counter.incrementAndGet();
      }
    };
    var item = item(modifier);
    var a = row(10);
    var b = row(20);
    var aKey = new SQLOrderByItem.SortKey();
    var bKey = new SQLOrderByItem.SortKey();
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    assertTrue(item.compareKeys(b, bKey, a, aKey, ctx, placements) < 0);
    assertEquals(List.of(b, a), order);
    assertSame(a, ctx.getVariable("$current"));
    ctx.setVariable("$current", b);
    assertTrue(item.compareKeys(a, aKey, b, bKey, ctx, placements) > 0);
    assertEquals(2, counter.get());
    assertSame(b, ctx.getVariable("$current"));
  }

  /** Key failures start at comparison demand and always close upstream on either algorithm. */
  @Test
  public void modifierFailuresAreLazyAndCloseUpstream() {
    for (Integer capacity : java.util.Arrays.asList(null, 2)) {
      var pulled = new AtomicInteger();
      var closed = new AtomicBoolean();
      var modifier = new SQLModifier(-1) {
        @Override
        public Object execute(Result row, Object value, CommandContext ctx) {
          assertEquals(capacity == null ? 3 : 2, pulled.get());
          throw new IllegalStateException("key failure");
        }
      };
      assertThrows(IllegalStateException.class, () -> sort(List.of(row(3), row(2), row(1)),
          List.of(item(modifier)), capacity, context(), null, null, pulled, closed));
      assertTrue(closed.get());
    }
  }

  /** Stored and direct signs agree on direction, placement, collation and incompatible ties. */
  @Test
  public void stableComparisonPolicyIsSharedAcrossAllValuePairs() {
    var ctx = context();
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    var rows = java.util.Arrays.asList(row(null), row("A"), row("a"), row("Z"), row(1));
    for (String direction : List.of(SQLOrderByItem.ASC, SQLOrderByItem.DESC)) {
      for (String nulls : List.of(SQLOrderByItem.NULLS_FIRST, SQLOrderByItem.NULLS_LAST)) {
        for (boolean ci : List.of(false, true)) {
          var item = item(null);
          item.setType(direction);
          item.setNullOrdering(nulls);
          if (ci) {
            item.setDeclaredCollate(Collate.caseInsensitiveCollate());
          }
          for (var a : rows) {
            for (var b : rows) {
              assertEquals(Integer.signum(item.compare(a, b, ctx, placements)),
                  Integer.signum(item.compareKeys(a, new SQLOrderByItem.SortKey(),
                      b, new SQLOrderByItem.SortKey(), ctx, placements)));
            }
          }
        }
      }
    }
  }

  /** An equal but non-identical primary hint reuses the first key through cutoff and heap work. */
  @Test
  public void primaryCutoffReusesKeysAndRuntimeFallbackConsumesEverything() {
    for (boolean sorted : List.of(false, true)) {
      var calls = new IdentityHashMap<Result, Integer>();
      var primary = item(new SQLModifier(-1) {
        @Override
        public SQLModifier copy() {
          return this;
        }

        @Override
        public Object execute(Result row, Object value, CommandContext ctx) {
          calls.merge(row, 1, Integer::sum);
          return value;
        }
      });
      var secondary = new SQLOrderByItem();
      secondary.setAlias("tag");
      var rows = new ArrayList<Result>();
      for (int i = 0; i < 10; i++) {
        var row = row(i / 3);
        row.setProperty("tag", 10 - i);
        rows.add(row);
      }
      var hint = primary.copy();
      var pulled = new AtomicInteger();
      var result = sort(rows, List.of(primary, secondary), 2, context(), hint, sorted,
          pulled, new AtomicBoolean());
      assertEquals(sorted ? 4 : 10, pulled.get());
      assertSame(rows.get(2), result.getFirst());
      calls.values().forEach(count -> assertEquals(1, count.intValue()));
    }
  }

  /** The real sequence method advances once per demanded row, not once per comparison. */
  @Test
  public void sequenceNextRunsOncePerRowInBothAlgorithms() {
    session.execute("CREATE SEQUENCE SortSequence TYPE ORDERED").close();
    for (Integer capacity : java.util.Arrays.asList(null, 5)) {
      var sequence = session.getMetadata().getSequenceLibrary().getSequence("SortSequence");
      session.begin();
      long before = sequence.current(session);
      var rows = new ArrayList<Result>();
      for (int i = 0; i < 32; i++) {
        rows.add(row(null));
      }
      var ctx = context();
      ctx.setVariable("$sortSequence", sequence);
      var calls = new IdentityHashMap<Result, Integer>();
      var modifier = new SQLModifier(-1) {
        @Override
        public Object execute(Result row, Object value, CommandContext context) {
          calls.merge(row, 1, Integer::sum);
          return super.execute(row, value, context);
        }
      };
      modifier.suffix = new SQLSuffixIdentifier(new SQLIdentifier("$sortSequence"));
      modifier.next = new SQLModifier(-1);
      modifier.next.methodCall = new SQLMethodCall(-1);
      modifier.next.methodCall.methodName = new SQLIdentifier("next");
      assertEquals(capacity == null ? 32 : capacity.intValue(),
          sort(rows, List.of(item(modifier)), capacity, ctx, null, null,
              new AtomicInteger(), new AtomicBoolean()).size());
      assertEquals(before + 32, sequence.current(session));
      assertEquals(32, calls.size());
      calls.values().forEach(count -> assertEquals(1, count.intValue()));
      session.commit();
    }
  }

  /** A real context suffix is sampled on demand and retains its key after a context update. */
  @Test
  public void currentSuffixKeepsOriginalModifierContextBehaviour() {
    var ctx = context();
    var first = row(1);
    var second = row(2);
    var observed = new ArrayList<Object>();
    var modifier = new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext context) {
        var result = super.execute(row, value, context);
        observed.add(result);
        return result;
      }
    };
    modifier.suffix = new SQLSuffixIdentifier(new SQLIdentifier("$current"));
    var item = item(modifier);
    item.setAlias("missing");
    var firstKey = new SQLOrderByItem.SortKey();
    var secondKey = new SQLOrderByItem.SortKey();
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    item.compareKeys(first, firstKey, second, secondKey, ctx, placements);
    assertSame(first, ctx.getSystemVariable(CommandContext.VAR_CURRENT));
    assertEquals(List.of(first, first), observed);
    ctx.setVariable("$current", row(42));
    assertEquals(0, item.compareKeys(first, firstKey, second, secondKey, ctx, placements));
    assertEquals(2, observed.size());
  }

  /** Edge keys preserve direct signs under default, declared and explicit collation policies. */
  @Test
  public void edgeKeysKeepComparisonSignsAndModifiersReceiveTheNeighbour() {
    session.begin();
    var a = session.newVertex("V");
    var b = session.newVertex("V");
    var targetA = session.newVertex("V");
    var targetB = session.newVertex("V");
    session.newRegularEdge("E", a, targetA);
    session.newRegularEdge("E", b, targetB);
    var first = new ResultInternal(session, a);
    var second = new ResultInternal(session, b);
    var ctx = context();
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (int policy = 0; policy < 4; policy++) {
      var item = item(null);
      item.setAlias("E");
      item.setEdge(true);
      if (policy == 1) {
        item.setDeclaredCollate(Collate.caseInsensitiveCollate());
      } else if (policy == 2) {
        item.setGremlinToMatchTranslatorProduced(true);
      } else if (policy == 3) {
        item.setDeclaredCollate(Collate.caseInsensitiveCollate());
        item.collate = new SQLExpression(-1) {
          @Override
          public Object execute(Result row, CommandContext context) {
            return "default";
          }
        };
      }
      var aKey = new SQLOrderByItem.SortKey();
      var bKey = new SQLOrderByItem.SortKey();
      for (int repeat = 0; repeat < 3; repeat++) {
        assertEquals(Integer.signum(item.compare(first, second, ctx, placements)),
            Integer.signum(item.compareKeys(first, aKey, second, bKey, ctx, placements)));
      }
      item.setModifier(new SQLModifier(-1) {
        @Override
        public Object execute(Result row, Object value, CommandContext context) {
          assertTrue(value instanceof Vertex);
          return ((Vertex) value).getIdentity();
        }
      });
      item.compareKeys(first, new SQLOrderByItem.SortKey(), second,
          new SQLOrderByItem.SortKey(), ctx, placements);
    }
    session.rollback();
  }

  /** Record attributes and missing aliases retain metadata fallback, including computed nulls. */
  @Test
  public void recordAttributeAndAbsentAliasVariantsAgreeWithDirectComparison() {
    var ctx = context();
    var a = row(1);
    var b = row(2);
    var missing = new ResultInternal(session);
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (boolean attribute : List.of(false, true)) {
      var item = item(null);
      if (attribute) {
        item.setRecordAttr("v");
      }
      for (var first : List.of(a, b, missing, row(null))) {
        for (var second : List.of(a, b, missing, row(null))) {
          assertEquals(item.compare(first, second, ctx, placements),
              item.compareKeys(first, new SQLOrderByItem.SortKey(), second,
                  new SQLOrderByItem.SortKey(), ctx, placements));
        }
      }
    }
  }

  /** Raw extraction failures keep left-before-right acquisition and first-comparison timing. */
  @Test
  public void rawExtractionFailureWaitsForComparisonAndKeepsAcquisitionOrder() {
    var failing = new ResultInternal(session) {
      @Override
      public boolean hasProperty(String name) {
        return true;
      }

      @Override
      public <T> T getProperty(String name) {
        throw new IllegalArgumentException("raw extraction");
      }
    };
    for (Integer capacity : java.util.Arrays.asList(null, 2)) {
      assertEquals(1, sort(List.of(failing), List.of(item(null)), capacity).size());
      var pulled = new AtomicInteger();
      var closed = new AtomicBoolean();
      var failure = assertThrows(IllegalArgumentException.class,
          () -> sort(List.of(row(1), failing, row(2)), List.of(item(null)), capacity,
              context(), null, null, pulled, closed));
      assertEquals("raw extraction", failure.getMessage());
      assertEquals(capacity == null ? 3 : 2, pulled.get());
      assertTrue(closed.get());
    }
  }

  /** Successful reads, metadata fallback and modifiers keep left/right effect order. */
  @Test
  public void successfulAcquisitionPinsEveryEffectInOrder() {
    var events = new ArrayList<String>();
    var rows = new ArrayList<Result>();
    for (String label : List.of("a", "b")) {
      rows.add(new ResultInternal(session) {
        @Override
        public boolean hasProperty(String name) {
          return true;
        }

        @Override
        public <T> T getProperty(String name) {
          events.add("read " + label);
          return null;
        }

        @Override
        public Object getMetadata(String name) {
          events.add("metadata " + label);
          return label;
        }
      });
    }
    var item = item(new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext ctx) {
        events.add("modifier " + value);
        return value;
      }
    });
    var ctx = context();
    var placement = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (boolean stored : List.of(false, true)) {
      events.clear();
      int result = stored
          ? item.compareKeys(rows.get(0), new SQLOrderByItem.SortKey(), rows.get(1),
              new SQLOrderByItem.SortKey(), ctx, placement)
          : item.compare(rows.get(0), rows.get(1), ctx, placement);
      assertTrue(result < 0);
      assertEquals(List.of("read a", "read b", "metadata a", "metadata b",
          "modifier a", "modifier b"), events);
    }
  }

  /** A null expression uses its clause text and caches a resolved collation on both paths. */
  @Test
  public void nullCollateExpressionResolvesClauseTextOnce() {
    var ctx = context();
    var placement = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (boolean stored : List.of(false, true)) {
      var calls = new AtomicInteger();
      var item = item(null);
      item.collate = new SQLExpression(-1) {
        @Override
        public Object execute(Result row, CommandContext context) {
          calls.incrementAndGet();
          return null;
        }

        @Override
        public void toString(Map<Object, Object> params, StringBuilder builder) {
          builder.append("ci");
        }
      };
      for (int repeat = 0; repeat < 2; repeat++) {
        var a = row("a");
        var b = row("A");
        assertEquals(0, stored
            ? item.compareKeys(a, new SQLOrderByItem.SortKey(), b,
                new SQLOrderByItem.SortKey(), ctx, placement)
            : item.compare(a, b, ctx, placement));
      }
      assertEquals(1, calls.get());
    }
  }

  /** Literal null selects no explicit collation and retries the expression at each comparison. */
  @Test
  public void literalNullCollateRetriesAndKeepsDefaultOrdering() {
    var ctx = context();
    var placement = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (boolean stored : List.of(false, true)) {
      var calls = new AtomicInteger();
      var item = item(null);
      item.collate = new SQLExpression(-1) {
        @Override
        public Object execute(Result row, CommandContext context) {
          calls.incrementAndGet();
          return null;
        }

        @Override
        public void toString(Map<Object, Object> params, StringBuilder builder) {
          builder.append("null");
        }
      };
      var a = row("a");
      var b = row("A");
      var aKey = new SQLOrderByItem.SortKey();
      var bKey = new SQLOrderByItem.SortKey();
      for (int repeat = 0; repeat < 2; repeat++) {
        assertTrue((stored ? item.compareKeys(a, aKey, b, bKey, ctx, placement)
            : item.compare(a, b, ctx, placement)) > 0);
      }
      assertEquals(2, calls.get());
    }
  }

  /** Mixed clauses allocate dense work slots and read cheap items again on every comparison. */
  @Test
  public void mixedSortStoresOnlyWorkItemsAndKeepsCheapReadsDirect() {
    var reads = new AtomicInteger();
    var calls = new AtomicInteger();
    var a = new ResultInternal(session) {
      @Override
      public <T> T getProperty(String name) {
        if (name.equals("v")) {
          reads.incrementAndGet();
        }
        return super.getProperty(name);
      }
    };
    a.setProperty("v", 1);
    a.setProperty("work", 4);
    var b = row(1);
    b.setProperty("work", 9);
    var work = item(new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext ctx) {
        calls.incrementAndGet();
        return value;
      }
    });
    work.setAlias("work");
    var order = new SQLOrderBy(-1);
    order.setItems(List.of(item(null), work, item(null)));
    assertEquals(1, order.storedKeyCount());
    var aKeys = new SQLOrderByItem.SortKey[1];
    var bKeys = new SQLOrderByItem.SortKey[1];
    var ctx = context();
    var placement = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (int repeat = 0; repeat < 4; repeat++) {
      assertEquals(-1, order.compareKeys(a, aKeys, b, bKeys, ctx, placement));
    }
    assertEquals(4, reads.get());
    assertEquals(2, calls.get());
    assertEquals(-1, order.compareKeys(a, null, b, bKeys, ctx, placement));
    assertEquals(-1, order.compareKeys(a, aKeys, b, null, ctx, placement));
    assertEquals(6, calls.get());
    for (Integer capacity : java.util.Arrays.asList(null, 1, 2)) {
      assertSame(a, sort(List.of(b, a), order.getItems(), capacity).getFirst());
    }
    order.setItems(List.of(item(null), item(null)));
    assertEquals(0, order.storedKeyCount());
  }

  /** Cheap primary cutoff reads directly even when a secondary item stores keys. */
  @Test
  public void mixedCheapPrimaryCutoffStopsAfterStrictlyWorseInput() {
    var primary = item(null);
    var secondary = item(new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext ctx) {
        return value;
      }
    });
    var rows = List.<Result>of(row(0), row(0), row(1), row(2));
    var pulled = new AtomicInteger();
    assertEquals(2, sort(rows, List.of(primary, secondary), 2, context(), primary.copy(),
        true, pulled, new AtomicBoolean()).size());
    assertEquals(3, pulled.get());
  }

  /** The left raw error wins over the right raw error and both modifiers on either path. */
  @Test
  public void competingAcquisitionFailuresKeepTheLeftErrorFirst() {
    var reads = new ArrayList<String>();
    var rows = new ArrayList<Result>();
    for (String label : List.of("left", "right")) {
      rows.add(new ResultInternal(session) {
        @Override
        public boolean hasProperty(String name) {
          reads.add(label);
          throw new IllegalArgumentException(label);
        }
      });
    }
    var item = item(new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext ctx) {
        throw new AssertionError("modifier must not run before both raw reads");
      }
    });
    var ctx = context();
    var placement = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    assertEquals("left", assertThrows(IllegalArgumentException.class,
        () -> item.compare(rows.get(0), rows.get(1), ctx, placement)).getMessage());
    assertEquals("left", assertThrows(IllegalArgumentException.class,
        () -> item.compareKeys(rows.get(0), new SQLOrderByItem.SortKey(), rows.get(1),
            new SQLOrderByItem.SortKey(), ctx, placement)).getMessage());
    assertEquals(List.of("left", "left"), reads);
  }

  /** Alias and record-attribute reads happen once per demanded row even across repeated pairs. */
  @Test
  public void rawAcquisitionCountsAndIndependentDescendingNullOrder() {
    for (boolean attribute : List.of(false, true)) {
      var reads = new AtomicInteger();
      var a = new ResultInternal(session) {
        @Override
        public <T> T getProperty(String name) {
          reads.incrementAndGet();
          return super.getProperty(name);
        }
      };
      a.setProperty("v", 4);
      var item = item(null);
      if (attribute) {
        item.setRecordAttr("v");
      }
      var ctx = context();
      var placement = OrderByNullsUtil.resolvePlacementsForSort(ctx);
      var key = new SQLOrderByItem.SortKey();
      var b = row(9);
      var other = new SQLOrderByItem.SortKey();
      for (int i = 0; i < 4; i++) {
        assertTrue(item.compareKeys(a, key, b, other, ctx, placement) < 0);
      }
      assertEquals(1, reads.get());
      item.setType(SQLOrderByItem.DESC);
      item.setNullOrdering(SQLOrderByItem.NULLS_LAST);
      var missing = row(null);
      assertEquals(List.of(b, a, missing), sort(List.of(a, missing, b), List.of(item), null));
      assertEquals(List.of(b, a), sort(List.of(a, missing, b), List.of(item), 2));
    }
  }

  /** A cutoff hint must identify item zero, including when a planner supplies an equal copy. */
  @Test
  public void primaryHintRejectsASecondaryItem() {
    var first = item(null);
    var second = new SQLOrderByItem();
    second.setAlias("tag");
    var order = new SQLOrderBy(-1);
    order.setItems(List.of(first, second));
    assertThrows(AssertionError.class,
        () -> new OrderByStep(order, 2, second, true, context(), -1, false));
  }

  /** Explicit collation overrides a conflicting declaration on both comparison paths. */
  @Test
  public void explicitDefaultCollationWinsOverDeclaredCaseInsensitiveOrder() {
    var ctx = context();
    var item = item(null);
    item.setDeclaredCollate(Collate.caseInsensitiveCollate());
    item.collate = new SQLExpression(-1) {
      @Override
      public Object execute(Result row, CommandContext context) {
        return "default";
      }
    };
    var a = row("a");
    var b = row("Z");
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    assertTrue(item.compare(a, b, ctx, placements) > 0);
    assertTrue(item.compareKeys(a, new SQLOrderByItem.SortKey(), b,
        new SQLOrderByItem.SortKey(), ctx, placements) > 0);
  }

  /** A computed null is cached, while fully sorted pass-through evaluates no keys. */
  @Test
  public void computedNullIsNotReevaluatedAndPresortedPassThroughDoesNotExtractKeys() {
    var count = new AtomicInteger();
    var modifier = new SQLModifier(-1) {
      @Override
      public Object execute(Result row, Object value, CommandContext ctx) {
        count.incrementAndGet();
        return null;
      }
    };
    var ctx = context();
    var item = item(modifier);
    var a = row(1);
    var b = row(2);
    var aKey = new SQLOrderByItem.SortKey();
    var bKey = new SQLOrderByItem.SortKey();
    var placements = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    for (int repeat = 0; repeat < 3; repeat++) {
      assertEquals(0, item.compareKeys(a, aKey, b, bKey, ctx, placements));
    }
    assertEquals(2, count.get());
    assertEquals(List.of(a, b), sort(List.of(a, b), List.of(item), 1, ctx, null,
        true, new AtomicInteger(), new AtomicBoolean()));
    assertEquals(2, count.get());
  }

  /** Rebinding a cached SELECT in another session reads current values, not earlier row keys. */
  @Test
  public void cachedSortKeysDoNotCrossBindingsOrSessions() {
    session.createClass("ReusableSort");
    session.begin();
    for (int i = 3; i >= 0; i--) {
      var entity = session.newEntity("ReusableSort");
      entity.setProperty("v", i);
    }
    session.commit();
    var query = "SELECT v FROM ReusableSort ORDER BY v LIMIT :n";
    session.begin();
    try (var result = session.query(query, Map.of("n", 2))) {
      assertEquals(List.of(0, 1), result.toList().stream()
          .map(row -> row.<Integer>getProperty("v")).toList());
    }
    session.commit();
    var cache = YqlExecutionPlanCache.instance(session);
    assertTrue(cache.contains(query));
    long hits = cache.getHits();
    session.begin();
    try (var result = session.query(query, Map.of("n", 1))) {
      assertEquals(0, result.next().<Integer>getProperty("v").intValue());
    }
    session.commit();
    assertTrue(cache.getHits() > hits);
    try (var other = youTrackDB.open(databaseName, adminUser, adminPassword)) {
      other.begin();
      other.execute("UPDATE ReusableSort SET v = 100 - v").close();
      other.commit();
      try (var result = other.query(query, Map.of("n", 3))) {
        assertEquals(List.of(97, 98, 99), result.toList().stream()
            .map(row -> row.<Integer>getProperty("v")).toList());
      }
    } finally {
      session.activateOnCurrentThread();
    }
    session.begin();
    try (var result = session.query(query, Map.of("n", 1))) {
      assertEquals(97, result.next().<Integer>getProperty("v").intValue());
    }
    session.commit();
  }

  /** Invalid collation and unsupported RID errors propagate instead of becoming comparison ties. */
  @Test
  public void invalidCollationAndRidRemainLazyErrors() {
    var invalid = item(null);
    invalid.collate = new SQLExpression(-1) {
      @Override
      public Object execute(Result row, CommandContext ctx) {
        return "no_such_collation";
      }
    };
    var rid = item(null);
    rid.setRid(new SQLRid(-1) {
      @Override
      public void toString(Map<Object, Object> params, StringBuilder builder) {
        builder.append("#0:0");
      }
    });
    for (var item : List.of(invalid, rid)) {
      for (Integer capacity : java.util.Arrays.asList(null, 2)) {
        assertEquals(1, sort(List.of(row(1)), List.of(item), capacity).size());
        Class<? extends RuntimeException> expected = item == invalid
            ? CommandExecutionException.class : UnsupportedOperationException.class;
        var pulled = new AtomicInteger();
        var closed = new AtomicBoolean();
        assertThrows(expected, () -> sort(List.of(row(1), row(2), row(3)), List.of(item),
            capacity, context(), null, null, pulled, closed));
        assertEquals(capacity == null ? 3 : 2, pulled.get());
        assertTrue(closed.get());
      }
    }
  }
}
