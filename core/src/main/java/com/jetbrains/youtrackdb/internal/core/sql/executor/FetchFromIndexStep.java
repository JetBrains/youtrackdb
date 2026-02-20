package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.ExecutionThreadLocal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBetweenCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCollection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsAnyCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsKeyOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsValueCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsValueOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLValueExpression;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Source step that fetches records by performing an index lookup described by an
 * {@link IndexSearchDescriptor}.
 *
 * <p>This is one of the most complex steps because it must handle many different
 * WHERE condition types and translate each into the appropriate index API call:
 *
 * <pre>
 *  Condition type          | Index API used
 *  ------------------------|---------------------------------------------
 *  field = value           | index.get(key) -- point lookup
 *  field &gt; value           | index.iterateEntriesMajor(key, exclusive)
 *  field &lt; value           | index.iterateEntriesMinor(key, exclusive)
 *  field BETWEEN a AND b   | index.iterateEntriesBetween(a, b, ...)
 *  field IN [a, b, c]      | multiple point lookups, merged
 *  CONTAINS / CONTAINSKEY  | index.get(key)
 *  CONTAINSTEXT            | FULLTEXT index lookup
 * </pre>
 *
 * <p>For composite indexes, the step builds a {@link CompositeKey} from multiple
 * conditions and may append a range condition on the last key field:
 * <pre>
 *  Index on [city, age]
 *  WHERE city = 'NYC' AND age &gt; 20
 *    key  = CompositeKey('NYC')
 *    range = iterateEntriesMajor(CompositeKey('NYC', 20), exclusive=true)
 * </pre>
 *
 * <h2>Composite key construction example</h2>
 * <pre>
 *  Index on [city, age, name]
 *  WHERE city = 'NYC' AND age &gt; 20
 *
 *  keyCondition = AND[city = 'NYC', age &gt; 20]
 *
 *  indexKeyFrom():     [NYC, 20]    (lower bound)
 *  indexKeyTo():       [NYC, MAX]   (upper bound)
 *  fromKeyIncluded:    false        (age &gt; 20, exclusive)
 *  toKeyIncluded:      true
 *
 *  Index API call:
 *    index.streamEntriesBetween(
 *        CompositeKey(NYC, 20), exclusive,
 *        CompositeKey(NYC, MAX), inclusive, ASC)
 * </pre>
 *
 * <p>Results are returned as key-RID pairs (not full records). A downstream
 * {@link GetValueFromIndexEntryStep} loads the actual records from the RIDs.
 *
 * @see IndexSearchDescriptor
 * @see GetValueFromIndexEntryStep
 * @see SelectExecutionPlanner#handleClassAsTargetWithIndex
 */
public class FetchFromIndexStep extends AbstractExecutionStep {

  /** Describes which index to use and what key/range conditions to apply. */
  protected IndexSearchDescriptor desc;

  /** Sort direction for the index scan (true = ascending, false = descending). */
  private boolean orderAsc;

  /** Running count of results produced (reported to {@link QueryStats} on close). */
  private long count = 0;

  public FetchFromIndexStep(
      IndexSearchDescriptor desc, boolean orderAsc, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.desc = desc;
    this.orderAsc = orderAsc;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var prev = this.prev;
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var session = ctx.getDatabaseSession();
    var tx = session.getTransactionInternal();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var streams = init(desc, orderAsc, ctx);
    var res =
        new ExecutionStreamProducer() {
          private final Iterator<Stream<RawPair<Object, RID>>> iter = streams.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            var s = iter.next();
            return ExecutionStream.resultIterator(
                s.map((nextEntry) -> {
                  tx.preProcessRecordsAndExecuteCallCallbacks();
                  return readResult(ctx, nextEntry);
                }).iterator());
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            tx.preProcessRecordsAndExecuteCallCallbacks();
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
            while (iter.hasNext()) {
              iter.next().close();
            }
          }
        };
    return new MultipleExecutionStream(res).onClose(this::close);
  }

  private void close(CommandContext context) {
    updateIndexStats();
  }

  private Result readResult(CommandContext ctx, RawPair<Object, RID> nextEntry) {
    if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
      throw new CommandInterruptedException(ctx.getDatabaseSession(),
          "The command has been interrupted");
    }
    count++;
    var key = nextEntry.first();
    Identifiable value = nextEntry.second();

    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("key", convertKey(key));
    result.setProperty("rid", value);
    ctx.setVariable("$current", result);
    return result;
  }

  private static Object convertKey(Object key) {
    if (key instanceof CompositeKey ck) {
      return new ArrayList<>(ck.getKeys());
    }
    return key;
  }

  private void updateIndexStats() {
    // stats
    var stats = QueryStats.get(ctx.getDatabaseSession());
    var index = desc.getIndex();
    var condition = desc.getKeyCondition();
    var additionalRangeCondition = desc.getAdditionalRangeCondition();
    if (index == null) {
      return; // this could happen, if not inited yet
    }
    var indexName = index.getName();
    var range = false;
    var size = 0;

    if (condition != null) {
      var andBlock = (SQLAndBlock) condition;
      size = andBlock.getSubBlocks().size();
      var lastOp = andBlock.getSubBlocks().getLast();
      if (lastOp instanceof SQLBinaryCondition binCond) {
        var op = binCond.getOperator();
        range = op.isRangeOperator();
      }
    }

    stats.pushIndexStats(indexName, size, range, additionalRangeCondition != null, count);
  }

  /**
   * Initializes the index lookup streams based on the descriptor's key condition type.
   *
   * <pre>
   *  condition == null     -> full index scan (processFlatIteration)
   *  condition == AndBlock -> range/equality lookup (processAndBlock)
   * </pre>
   *
   * @return a list of streams, each producing key-RID pairs from the index
   */
  private static List<Stream<RawPair<Object, RID>>> init(
      IndexSearchDescriptor desc, boolean isOrderAsc, CommandContext ctx) {

    var index = desc.getIndex();
    var condition = desc.getKeyCondition();
    var additionalRangeCondition = desc.getAdditionalRangeCondition();

    if (index.getDefinition() == null) {
      return Collections.emptyList();
    }
    return switch (condition) {
      case null -> processFlatIteration(ctx.getDatabaseSession(), index, isOrderAsc);
      case SQLAndBlock ignored ->
          processAndBlock(index, condition, additionalRangeCondition, isOrderAsc, ctx);
      default -> throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    };
  }

  /**
   * Handles {@code key IN [a, b, c]} conditions by performing one point lookup per value
   * and collecting the resulting streams.
   */
  private static List<Stream<RawPair<Object, RID>>> processInCondition(
      Index index, SQLBooleanExpression condition, CommandContext ctx, boolean orderAsc) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    // Track streams by identity (not equals()) to avoid adding the same stream object
    // twice, which would cause double-close or duplicate results.
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());
    var definition = index.getDefinition();
    var inCondition = (SQLInCondition) condition;

    var left = inCondition.getLeft();
    if (!left.toString().equalsIgnoreCase("key")) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    }
    var transaction = ctx.getDatabaseSession().getActiveTransaction();
    var rightValue = inCondition.evaluateRight((Result) null, ctx);
    var equals = new SQLEqualsOperator(-1);
    if (MultiValue.isMultiValue(rightValue)) {
      for (var item : MultiValue.getMultiValueIterable(rightValue)) {
        if (item instanceof Result) {
          if (((Result) item).isEntity()) {
            item = ((Result) item).asEntity();
          } else if (((Result) item).getPropertyNames().size() == 1) {
            item =
                ((Result) item).getProperty(
                    ((Result) item).getPropertyNames().getFirst());
          }
        }

        var localCursor =
            createCursor(transaction, index, equals, definition, item, orderAsc,
                condition);

        if (acquiredStreams.add(localCursor)) {
          streams.add(localCursor);
        }
      }
    } else {
      var stream =
          createCursor(
              transaction, index, equals, definition, rightValue, orderAsc, condition);
      if (acquiredStreams.add(stream)) {
        streams.add(stream);
      }
    }

    return streams;
  }

  /**
   * Processes an AND block of conditions against a (potentially composite) index.
   * Computes the from-key and to-key bounds from the conditions, then delegates
   * to {@link #multipleRange} for the actual index API calls.
   *
   * <p>Field names in the conditions have already been matched to index key positions
   * by the planner, so they are ignored here -- only the values and operators matter.
   */
  private static List<Stream<RawPair<Object, RID>>> processAndBlock(
      Index index,
      SQLBooleanExpression condition,
      SQLBinaryCondition additionalRangeCondition,
      boolean isOrderAsc,
      CommandContext ctx) {
    var fromKey = indexKeyFrom((SQLAndBlock) condition, additionalRangeCondition);
    var toKey = indexKeyTo((SQLAndBlock) condition, additionalRangeCondition);
    var fromKeyIncluded = indexKeyFromIncluded((SQLAndBlock) condition,
        additionalRangeCondition);
    var toKeyIncluded = indexKeyToIncluded((SQLAndBlock) condition, additionalRangeCondition);
    return multipleRange(
        index,
        fromKey,
        fromKeyIncluded,
        toKey,
        toKeyIncluded,
        condition,
        isOrderAsc,
        ctx);
  }

  /**
   * Full index scan (no key condition). First fetches null-key entries (if the index
   * stores them), then streams all entries in ASC or DESC order.
   */
  private static List<Stream<RawPair<Object, RID>>> processFlatIteration(
      DatabaseSessionEmbedded session, Index index, boolean isOrderAsc) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());

    var stream = fetchNullKeys(session, index);
    if (stream != null) {
      acquiredStreams.add(stream);
      streams.add(stream);
    }

    stream = isOrderAsc ? index.stream(session) : index.descStream(session);
    if (acquiredStreams.add(stream)) {
      streams.add(stream);
    }
    return streams;
  }

  /**
   * Returns a stream of null-key index entries, or {@code null} if the index ignores nulls.
   *
   * <p>Indexes that do not ignore null values store entries under a {@code null} key for
   * records where the indexed field is null. These must be included in a full index scan
   * to return complete results.
   */
  @Nullable
  private static Stream<RawPair<Object, RID>> fetchNullKeys(DatabaseSessionEmbedded session,
      Index index) {
    if (index.getDefinition().isNullValuesIgnored()) {
      return null;
    }

    return getStreamForNullKey(session, index);
  }

  /**
   * Performs one or more range lookups on the index. Each (fromKey, toKey) pair defines
   * a range to scan. For IN conditions or subqueries that return collections, the
   * Cartesian product of possible key values is computed, producing multiple ranges.
   */
  private static List<Stream<RawPair<Object, RID>>> multipleRange(
      Index index,
      SQLCollection fromKey,
      boolean fromKeyIncluded,
      SQLCollection toKey,
      boolean toKeyIncluded,
      SQLBooleanExpression condition,
      boolean isOrderAsc,
      CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());
    // Expand multi-valued key expressions (from IN or subqueries) into all combinations.
    // secondValueCombinations[i] = lower bound key for range i
    // thirdValueCombinations[i]  = upper bound key for range i
    var secondValueCombinations = cartesianProduct(fromKey, ctx);
    var thirdValueCombinations = cartesianProduct(toKey, ctx);

    var indexDef = index.getDefinition();

    var transaction = session.getActiveTransaction();
    for (var i = 0; i < secondValueCombinations.size(); i++) {

      // Evaluate the i-th from-key and to-key expressions to concrete values.
      // For single-field indexes with a single-element list, unwrap the list.
      var secondValue = secondValueCombinations.get(i).execute((Result) null, ctx);
      if (secondValue instanceof List
          && ((List<?>) secondValue).size() == 1
          && indexDef.getProperties().size() == 1
          && !(indexDef instanceof IndexDefinitionMultiValue)) {
        secondValue = ((List<?>) secondValue).getFirst();
      }
      secondValue = unboxResult(secondValue);
      var thirdValue = thirdValueCombinations.get(i).execute((Result) null, ctx);
      if (thirdValue instanceof List
          && ((List<?>) thirdValue).size() == 1
          && indexDef.getProperties().size() == 1
          && !(indexDef instanceof IndexDefinitionMultiValue)) {
        thirdValue = ((List<?>) thirdValue).getFirst();
      }
      thirdValue = unboxResult(thirdValue);

      try {
        // Convert user-supplied values to the types expected by the index definition.
        secondValue = convertToIndexDefinitionTypes(session, condition, secondValue,
            indexDef.getTypes());
        thirdValue = convertToIndexDefinitionTypes(session, condition, thirdValue,
            indexDef.getTypes());
      } catch (Exception e) {
        // Type conversion failed. This can happen when a subquery returns a raw
        // collection (e.g. [1,2,3]) instead of individual values. If both bounds
        // are the same collection, iterate its elements as individual point lookups.
        if (secondValue instanceof Collection && secondValue.equals(thirdValue)) {
          //noinspection rawtypes,unchecked
          ((Collection) secondValue)
              .forEach(
                  item -> {
                    Stream<RawPair<Object, RID>> stream;
                    var itemVal =
                        convertToIndexDefinitionTypes(session, condition, item,
                            indexDef.getTypes());

                    var from = toBetweenIndexKey(transaction, indexDef, itemVal);
                    var to = toBetweenIndexKey(transaction, indexDef, itemVal);
                    if (from == null && to == null) {
                      // manage null value explicitly, as the index API does not seem to work
                      // correctly in this
                      // case
                      stream = getStreamForNullKey(session, index);
                      if (acquiredStreams.add(stream)) {
                        streams.add(stream);
                      }
                    } else {
                      stream =
                          index.streamEntriesBetween(session,
                              from, fromKeyIncluded, to, toKeyIncluded, isOrderAsc);
                      if (acquiredStreams.add(stream)) {
                        streams.add(stream);
                      }
                    }
                  });
        }

        // Key conversion failed and could not be recovered -- skip this combination.
        continue;
      }

      // --- Dispatch to the appropriate index API based on index capabilities ---
      //
      //  Decision tree:
      //    supportsOrderedIterations?
      //      YES -> streamEntriesBetween(from, to)      [range scan]
      //      NO  -> allEqualities?
      //               YES -> streamEntries(key)          [point lookup]
      //               NO  -> isFullTextIndex?
      //                        YES -> streamEntries(key)  [full-text lookup]
      //                        NO  -> UnsupportedOperationException
      Stream<RawPair<Object, RID>> stream;
      var from = toBetweenIndexKey(transaction, indexDef, secondValue);
      var to = toBetweenIndexKey(transaction, indexDef, thirdValue);

      if (from == null && to == null) {
        // manage null value explicitly, as the index API does not seem to work correctly in this
        // case
        stream = getStreamForNullKey(session, index);
        if (acquiredStreams.add(stream)) {
          streams.add(stream);
        }
      } else {
        if (from instanceof Collection<?> fromColl) {
          var toColl = (Collection<?>) to;

          if (fromColl.size() != toColl.size()) {
            throw new DatabaseException(session, "Size of from and to collections for "
                + "index range search do not match: " + fromColl.size() + " != " + toColl.size());
          }

          var fromIter = fromColl.iterator();
          var toIter = toColl.iterator();

          while (fromIter.hasNext()) {
            var fromVal = fromIter.next();
            var toVal = toIter.next();

            stream = index.streamEntriesBetween(session, fromVal, fromKeyIncluded, toVal,
                toKeyIncluded,
                isOrderAsc);
            if (acquiredStreams.add(stream)) {
              streams.add(stream);
            }
          }
        } else {
          stream = index.streamEntriesBetween(session, from, fromKeyIncluded, to, toKeyIncluded,
              isOrderAsc);
          if (acquiredStreams.add(stream)) {
            streams.add(stream);
          }
        }
      }
    }
    return streams;
  }

  /**
   * Returns a stream of key-RID pairs for entries stored under a {@code null} index key.
   * The returned pairs have {@code null} as the key component and the stored RID as the value.
   */
  private static Stream<RawPair<Object, RID>> getStreamForNullKey(
      DatabaseSessionEmbedded session, Index index) {
    final var stream = index.getRids(session, null);
    return stream.map((rid) -> new RawPair<>(null, rid));
  }

  /**
   * Unwraps {@link Result} and {@link List} values that come from subqueries so that
   * index key conversion receives plain scalar values rather than Result wrappers.
   *
   * <pre>
   *  Input type         | Output
   *  -------------------|-------------------------------
   *  List               | recursively unboxed elements
   *  Result (entity)    | the entity's RID
   *  Result (1 column)  | the single property value
   *  other              | returned as-is
   * </pre>
   */
  private static Object unboxResult(Object value) {
    if (value instanceof List<?> list) {
      try (var stream = list.stream()) {
        return stream.map(FetchFromIndexStep::unboxResult).collect(Collectors.toList());
      }
    }
    if (value instanceof Result res) {
      if (res.isEntity()) {
        return res.getIdentity();
      }

      var props = res.getPropertyNames();
      if (props.size() == 1) {
        return res.getProperty(props.getFirst());
      }
    }
    return value;
  }

  /**
   * Computes the Cartesian product of all collection-valued expressions in an index key.
   *
   * <p>When an IN condition or subquery produces multiple values for one position
   * in a composite index key, the planner must expand those into all possible key
   * combinations. For example:
   * <pre>
   *  Index on [city, status]
   *  WHERE city IN ['NYC', 'LA'] AND status = 'active'
   *
   *  key = ['NYC','LA'], ['active']
   *  cartesianProduct = [('NYC','active'), ('LA','active')]
   * </pre>
   *
   * @param key the SQLCollection whose expressions may contain multi-valued items
   * @param ctx command context for evaluating expressions
   * @return a list of SQLCollections, each representing one key combination
   */
  private static List<SQLCollection> cartesianProduct(SQLCollection key, CommandContext ctx) {
    return cartesianProduct(new SQLCollection(-1), key, ctx);
  }

  /**
   * Recursive helper: builds key combinations by consuming one expression at a time from
   * {@code key}, expanding collection values into separate branches, and appending each
   * to the running {@code head} prefix.
   */
  private static List<SQLCollection> cartesianProduct(
      SQLCollection head, SQLCollection key, CommandContext ctx) {
    if (key.getExpressions().isEmpty()) {
      return Collections.singletonList(head);
    }

    var db = ctx.getDatabaseSession();
    var nextElementInKey = key.getExpressions().getFirst();
    var value = nextElementInKey.execute(new ResultInternal(db), ctx);
    if (value instanceof Iterable && !(value instanceof Identifiable)) {
      List<SQLCollection> result = new ArrayList<>();
      for (var elemInKey : (Collection<?>) value) {
        var newHead = new SQLCollection(-1);
        for (var exp : head.getExpressions()) {
          newHead.add(exp.copy());
        }
        newHead.add(toExpression(elemInKey));
        var tail = key.copy();
        tail.getExpressions().removeFirst();
        result.addAll(cartesianProduct(newHead, tail, ctx));
      }
      return Collections.unmodifiableList(result);
    } else {
      var newHead = new SQLCollection(-1);
      for (var exp : head.getExpressions()) {
        newHead.add(exp.copy());
      }
      newHead.add(nextElementInKey);
      var tail = key.copy();
      tail.getExpressions().removeFirst();
      return cartesianProduct(newHead, tail, ctx);
    }
  }

  private static SQLExpression toExpression(Object value) {
    return new SQLValueExpression(value);
  }

  /**
   * Converts a user-supplied value (or list of values for composite keys) to the types
   * expected by the index definition, using {@link PropertyTypeInternal#convert}.
   *
   * <p>Special handling for CONTAINSKEY and CONTAINSVALUE operators: the converted value
   * is wrapped in a single-entry {@link Map} because the index stores map entries as
   * composite keys where the key or value occupies a specific position.
   *
   * @param session   the database session (for type conversion)
   * @param condition the WHERE condition (inspected for CONTAINSKEY/CONTAINSVALUE operators)
   * @param val       the raw value(s) to convert
   * @param types     the index field types, one per key position
   * @return the converted value, or {@code null} if the input was null
   */
  @Nullable
  private static Object convertToIndexDefinitionTypes(
      DatabaseSessionEmbedded session, SQLBooleanExpression condition, Object val,
      PropertyTypeInternal[] types) {
    if (val == null) {
      return null;
    }

    if (MultiValue.isMultiValue(val)) {
      List<Object> result = new ArrayList<>();

      var i = 0;
      for (var o : MultiValue.getMultiValueIterable(val)) {
        result.add(types[i++].convert(o, null, null, session));
      }

      if (condition instanceof SQLAndBlock andBlock) {
        for (var j = 0; j < andBlock.getSubBlocks().size(); j++) {
          var subExp = andBlock.getSubBlocks().get(j);
          if (subExp instanceof SQLBinaryCondition binCond) {
            if (binCond.getOperator() instanceof SQLContainsKeyOperator) {
              Map<Object, Object> newValue = new HashMap<>();
              newValue.put(result.get(j), "");
              result.set(j, newValue);
            } else if (binCond.getOperator()
                instanceof SQLContainsValueOperator) {
              Map<Object, Object> newValue = new HashMap<>();
              newValue.put("", result.get(j));
              result.set(j, newValue);
            }
          } else if (subExp instanceof SQLContainsValueCondition) {
            Map<Object, Object> newValue = new HashMap<>();
            newValue.put("", result.get(j));
            result.set(j, newValue);
          }
        }
      }
      return result;
    }
    return types[0].convert(val, null, null, session);
  }

  /** Handles {@code key BETWEEN a AND b} conditions with an inclusive range scan. */
  private static List<Stream<RawPair<Object, RID>>> processBetweenCondition(
      Index index, SQLBooleanExpression condition, boolean isOrderAsc,
      CommandContext ctx) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();

    var definition = index.getDefinition();
    var key = ((SQLBetweenCondition) condition).getFirst();
    if (!key.toString().equalsIgnoreCase("key")) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    }
    var second = ((SQLBetweenCondition) condition).getSecond();
    var third = ((SQLBetweenCondition) condition).getThird();

    var secondValue = second.execute((Result) null, ctx);
    secondValue = unboxResult(secondValue);
    var thirdValue = third.execute((Result) null, ctx);
    thirdValue = unboxResult(thirdValue);
    var session = ctx.getDatabaseSession();
    var transaction = session.getActiveTransaction();
    var stream =
        index.streamEntriesBetween(session,
            toBetweenIndexKey(transaction, definition, secondValue),
            true,
            toBetweenIndexKey(transaction, definition, thirdValue),
            true, isOrderAsc);
    streams.add(stream);
    return streams;
  }

  /** Handles a single binary condition (=, >, <, >=, <=) against the index key. */
  private static List<Stream<RawPair<Object, RID>>> processBinaryCondition(
      FrontendTransaction transaction,
      Index index,
      SQLBooleanExpression condition,
      boolean isOrderAsc,
      CommandContext ctx) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());

    var definition = index.getDefinition();
    var operator = ((SQLBinaryCondition) condition).getOperator();
    var left = ((SQLBinaryCondition) condition).getLeft();
    if (!left.toString().equalsIgnoreCase("key")) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    }
    var rightValue = ((SQLBinaryCondition) condition).getRight().execute((Result) null, ctx);
    var stream =
        createCursor(transaction, index, operator, definition, rightValue, isOrderAsc, condition);
    if (acquiredStreams.add(stream)) {
      streams.add(stream);
    }

    return streams;
  }

  /** Converts a user-supplied value into an index key suitable for point lookups. */
  private static Collection<?> toIndexKey(
      FrontendTransaction transaction, IndexDefinition definition, Object rightValue) {
    if (definition.getProperties().size() == 1 && rightValue instanceof Collection) {
      rightValue = ((Collection<?>) rightValue).iterator().next();
    }
    if (rightValue instanceof List) {
      rightValue = definition.createValue(transaction, (List<?>) rightValue);
    } else if (!(rightValue instanceof CompositeKey)) {
      rightValue = definition.createValue(transaction, rightValue);
    }
    if (!(rightValue instanceof Collection)) {
      rightValue = Collections.singleton(rightValue);
    }
    return (Collection<?>) rightValue;
  }

  /** Converts a user-supplied value into an index key suitable for range (BETWEEN) lookups. */
  private static Object toBetweenIndexKey(
      FrontendTransaction transaction, IndexDefinition definition, Object rightValue) {
    if (definition.getProperties().size() == 1 && rightValue instanceof Collection) {
      if (!((Collection<?>) rightValue).isEmpty()) {
        rightValue = ((Collection<?>) rightValue).iterator().next();
      } else {
        rightValue = null;
      }
    }

    if (rightValue instanceof Collection) {
      rightValue = definition.createValue(transaction, ((Collection<?>) rightValue).toArray());
    } else {
      rightValue = definition.createValue(transaction, rightValue);
    }

    return rightValue;
  }

  /**
   * Creates an index cursor stream for the given operator and value. Dispatches to
   * the appropriate index API (point lookup, major, minor) based on operator type.
   */
  private static Stream<RawPair<Object, RID>> createCursor(
      FrontendTransaction transaction,
      Index index,
      SQLBinaryCompareOperator operator,
      IndexDefinition definition,
      Object value,
      boolean orderAsc,
      SQLBooleanExpression condition) {
    var session = transaction.getDatabaseSession();
    if (operator instanceof SQLEqualsOperator
        || operator instanceof SQLContainsKeyOperator
        || operator instanceof SQLContainsValueOperator) {
      return index.streamEntries(session, toIndexKey(transaction, definition, value), orderAsc);
    } else if (operator instanceof SQLGeOperator) {
      return index.streamEntriesMajor(session, value, true, orderAsc);
    } else if (operator instanceof SQLGtOperator) {
      return index.streamEntriesMajor(session, value, false, orderAsc);
    } else if (operator instanceof SQLLeOperator) {
      return index.streamEntriesMinor(session, value, true, orderAsc);
    } else if (operator instanceof SQLLtOperator) {
      return index.streamEntriesMinor(session, value, false, orderAsc);
    } else {
      throw new CommandExecutionException(session,
          "search for index for " + condition + " is not supported yet");
    }
  }

  protected boolean isOrderAsc() {
    return orderAsc;
  }

  /** Extracts the lower bound key values from the AND block conditions. */
  private static SQLCollection indexKeyFrom(SQLAndBlock keyCondition,
      SQLBinaryCondition additional) {
    var result = new SQLCollection(-1);
    for (var exp : keyCondition.getSubBlocks()) {
      var res = exp.resolveKeyFrom(additional);
      if (res != null) {
        result.add(res);
      }
    }
    return result;
  }

  /** Extracts the upper bound key values from the AND block conditions. */
  private static SQLCollection indexKeyTo(SQLAndBlock keyCondition, SQLBinaryCondition additional) {
    var result = new SQLCollection(-1);
    for (var exp : keyCondition.getSubBlocks()) {
      var res = exp.resolveKeyTo(additional);
      if (res != null) {
        result.add(res);
      }
    }
    return result;
  }

  /** Returns true if the lower bound is inclusive (>=) rather than exclusive (>). */
  private static boolean indexKeyFromIncluded(
      SQLAndBlock keyCondition, SQLBinaryCondition additional) {
    var exp =
        keyCondition.getSubBlocks().getLast();
    var additionalOperator =
        Optional.ofNullable(additional).map(SQLBinaryCondition::getOperator).orElse(null);
    if (exp instanceof SQLBinaryCondition binCond) {
      var operator = binCond.getOperator();
      if (isGreaterOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isGreaterOperator(additionalOperator));
      }
    } else if (exp instanceof SQLInCondition || exp instanceof SQLContainsAnyCondition
        || exp instanceof SQLContainsCondition) {
      return additional == null
          || (isIncludeOperator(additionalOperator) && isGreaterOperator(additionalOperator));
    } else if (exp instanceof SQLContainsTextCondition) {
      return true;
    } else if (exp instanceof SQLContainsValueCondition cvCond) {
      SQLBinaryCompareOperator operator = cvCond.getOperator();
      if (isGreaterOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isGreaterOperator(additionalOperator));
      }
    } else {
      throw new UnsupportedOperationException("Cannot execute index query with " + exp);
    }
  }

  /** Returns {@code true} for {@code >=} and {@code >} operators. */
  private static boolean isGreaterOperator(SQLBinaryCompareOperator operator) {
    if (operator == null) {
      return false;
    }
    return operator instanceof SQLGeOperator || operator instanceof SQLGtOperator;
  }

  /** Returns {@code true} for {@code <=} and {@code <} operators. */
  private static boolean isLessOperator(SQLBinaryCompareOperator operator) {
    if (operator == null) {
      return false;
    }
    return operator instanceof SQLLeOperator || operator instanceof SQLLtOperator;
  }

  /** Returns {@code true} for inclusive operators ({@code >=} and {@code <=}). */
  private static boolean isIncludeOperator(SQLBinaryCompareOperator operator) {
    if (operator == null) {
      return false;
    }
    return operator instanceof SQLGeOperator || operator instanceof SQLLeOperator;
  }

  /** Returns true if the upper bound is inclusive (<=) rather than exclusive (<). */
  private static boolean indexKeyToIncluded(SQLAndBlock keyCondition,
      SQLBinaryCondition additional) {
    var exp =
        keyCondition.getSubBlocks().getLast();
    var additionalOperator =
        Optional.ofNullable(additional).map(SQLBinaryCondition::getOperator).orElse(null);
    if (exp instanceof SQLBinaryCondition binCond) {
      var operator = binCond.getOperator();
      if (isLessOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isLessOperator(additionalOperator));
      }
    } else if (exp instanceof SQLInCondition || exp instanceof SQLContainsAnyCondition
        || exp instanceof SQLContainsCondition) {
      return additionalOperator == null
          || (isIncludeOperator(additionalOperator) && isLessOperator(additionalOperator));
    } else if (exp instanceof SQLContainsTextCondition) {
      return true;
    } else if (exp instanceof SQLContainsValueCondition cvCond) {
      SQLBinaryCompareOperator operator = cvCond.getOperator();
      if (isLessOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isLessOperator(additionalOperator));
      }
    } else {
      throw new UnsupportedOperationException("Cannot execute index query with " + exp);
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result =
        ExecutionStepInternal.getIndent(depth, indent)
            + "+ FETCH FROM INDEX "
            + desc.getIndex().getName();
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    if (desc.getKeyCondition() != null) {
      var additional =
          Optional.ofNullable(desc.getAdditionalRangeCondition())
              .map(rangeCondition -> " and " + rangeCondition)
              .orElse("");
      result +=
          ("\n"
              + ExecutionStepInternal.getIndent(depth, indent)
              + "  "
              + desc.getKeyCondition()
              + additional);
    }

    return result;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("indexName", desc.getIndex().getName());
    if (desc.getKeyCondition() != null) {
      result.setProperty("condition", desc.getKeyCondition().serialize(session));
    }
    if (desc.getAdditionalRangeCondition() != null) {
      result.setProperty(
          "additionalRangeCondition", desc.getAdditionalRangeCondition().serialize(session));
    }
    result.setProperty("orderAsc", orderAsc);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      String indexName = fromResult.getProperty("indexName");
      SQLBooleanExpression condition = null;
      if (fromResult.getProperty("condition") != null) {
        condition = SQLBooleanExpression.deserializeFromOResult(
            fromResult.getProperty("condition"));
      }
      SQLBinaryCondition additionalRangeCondition = null;
      if (fromResult.getProperty("additionalRangeCondition") != null) {
        additionalRangeCondition = new SQLBinaryCondition(-1);
        additionalRangeCondition.deserialize(fromResult.getProperty("additionalRangeCondition"));
      }
      var index = session.getSharedContext().getIndexManager().getIndex(indexName);
      desc = new IndexSearchDescriptor(index, condition, additionalRangeCondition, null);
      orderAsc = fromResult.getProperty("orderAsc");
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /**
   * Resets this step for re-initialization (typically via deserialization).
   *
   * <p><b>Warning:</b> after calling this method, {@link #desc} is {@code null}.
   * Calling {@link #internalStart} before re-initializing the descriptor (e.g. via
   * {@link #deserialize}) will result in a {@link NullPointerException}. This method
   * should only be called as part of a deserialization lifecycle.
   */
  @Override
  public void reset() {
    desc = null;
    count = 0;
  }

  /** Cacheable: the index descriptor captures the lookup conditions structurally. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromIndexStep(desc, this.orderAsc, ctx, this.profilingEnabled);
  }

  @Override
  public void close() {
    super.close();
  }

  public String getIndexName() {
    return desc.getIndex().getName();
  }

  public IndexSearchDescriptor getDesc() {
    return desc;
  }
}
