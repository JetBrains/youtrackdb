/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.collection.SortedMultiIterator;
import com.jetbrains.youtrack.db.internal.common.concur.resource.SharedResource;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.stream.BreakingForEach;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.common.util.PatternConst;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.ExecutionThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexAbstract;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.IndexEngineException;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClusters;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.FilterOptimizer;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemVariable;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import com.jetbrains.youtrack.db.internal.core.sql.functions.coll.SQLFunctionDistinct;
import com.jetbrains.youtrack.db.internal.core.sql.functions.misc.SQLFunctionCount;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorAnd;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorBetween;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorIn;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMajor;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMajorEquals;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMinor;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMinorEquals;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * Executes the SQL SELECT statement. the parse() method compiles the query and builds the meta
 * information needed by the execute(). If the query contains the ORDER BY clause, the results are
 * temporary collected internally, then ordered and finally returned all together to the listener.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLSelect extends CommandExecutorSQLResultsetAbstract
    implements TemporaryRidGenerator {

  public static final String KEYWORD_SELECT = "SELECT";
  public static final String KEYWORD_ASC = "ASC";
  public static final String KEYWORD_DESC = "DESC";
  public static final String KEYWORD_ORDER = "ORDER";
  public static final String KEYWORD_BY = "BY";
  public static final String KEYWORD_GROUP = "GROUP";
  public static final String KEYWORD_UNWIND = "UNWIND";
  public static final String KEYWORD_FETCHPLAN = "FETCHPLAN";
  public static final String KEYWORD_NOCACHE = "NOCACHE";
  public static final String KEYWORD_FOREACH = "FOREACH";
  private static final String KEYWORD_AS = "AS";
  private static final String KEYWORD_PARALLEL = "PARALLEL";
  private static final int PARTIAL_SORT_BUFFER_THRESHOLD = 10000;
  private static final String NULL_VALUE = "null";

  private static class AsyncResult {

    private final Identifiable record;
    private final CommandContext context;

    public AsyncResult(final DBRecord iRecord, final CommandContext iContext) {
      record = iRecord;
      context = iContext;
    }
  }

  private static final AsyncResult PARALLEL_END_EXECUTION_THREAD = new AsyncResult(null, null);

  private final OrderByOptimizer orderByOptimizer = new OrderByOptimizer();
  private final MetricRecorder metricRecorder = new MetricRecorder();
  private final FilterOptimizer filterOptimizer = new FilterOptimizer();
  private final FilterAnalyzer filterAnalyzer = new FilterAnalyzer();
  private Map<String, String> projectionDefinition = null;
  // THIS HAS BEEN KEPT FOR COMPATIBILITY; BUT IT'S USED THE PROJECTIONS IN GROUPED-RESULTS
  private Map<String, Object> projections = null;
  private List<Pair<String, String>> orderedFields = new ArrayList<Pair<String, String>>();
  private List<String> groupByFields;
  private final ConcurrentHashMap<Object, RuntimeResult> groupedResult =
      new ConcurrentHashMap<Object, RuntimeResult>();
  private boolean aggregate = false;
  private List<String> unwindFields;
  private Object expandTarget;
  private int fetchLimit = -1;
  private Identifiable lastRecord;
  private String fetchPlan;
  private boolean fullySortedByIndex = false;

  private Boolean isAnyFunctionAggregates = null;
  private volatile boolean parallel = false;
  private volatile boolean parallelRunning;
  private final ArrayBlockingQueue<AsyncResult> resultQueue;

  private ConcurrentHashMap<RID, RID> uniqueResult;
  private boolean noCache = false;
  private int tipLimitThreshold;

  private final AtomicLong tmpQueueOffer = new AtomicLong();
  private final Object resultLock = new Object();

  public CommandExecutorSQLSelect(DatabaseSessionInternal session) {
    var conf = session.getConfiguration();
    resultQueue =
        new ArrayBlockingQueue<AsyncResult>(
            conf.getValueAsInteger(GlobalConfiguration.QUERY_PARALLEL_RESULT_QUEUE_SIZE));
    tipLimitThreshold = conf.getValueAsInteger(GlobalConfiguration.QUERY_LIMIT_THRESHOLD_TIP);
  }

  private static final class IndexUsageLog {

    private final Index index;
    private final List<Object> keyParams;
    private final IndexDefinition indexDefinition;

    IndexUsageLog(Index index, List<Object> keyParams, IndexDefinition indexDefinition) {
      this.index = index;
      this.keyParams = keyParams;
      this.indexDefinition = indexDefinition;
    }
  }

  private final class IndexComparator implements Comparator<Index> {

    public int compare(final Index indexOne, final Index indexTwo) {
      final var definitionOne = indexOne.getDefinition();
      final var definitionTwo = indexTwo.getDefinition();

      final var firstParamCount = definitionOne.getParamCount();
      final var secondParamCount = definitionTwo.getParamCount();

      final var result = firstParamCount - secondParamCount;

      if (result == 0 && !orderedFields.isEmpty()) {
        if (!(indexOne instanceof ChainedIndexProxy)
            && orderByOptimizer.canBeUsedByOrderBy(
            indexOne, CommandExecutorSQLSelect.this.orderedFields)) {
          return 1;
        }

        if (!(indexTwo instanceof ChainedIndexProxy)
            && orderByOptimizer.canBeUsedByOrderBy(
            indexTwo, CommandExecutorSQLSelect.this.orderedFields)) {
          return -1;
        }
      }

      return result;
    }
  }

  private static Object getIndexKey(
      DatabaseSessionInternal session,
      final IndexDefinition indexDefinition,
      Object value,
      CommandContext context) {
    if (indexDefinition instanceof CompositeIndexDefinition
        || indexDefinition.getParamCount() > 1) {
      if (value instanceof List<?> values) {
        List<Object> keyParams = new ArrayList<Object>(values.size());

        for (var o : values) {
          keyParams.add(SQLHelper.getValue(o, null, context));
        }
        return indexDefinition.createValue(session, keyParams);
      } else {
        value = SQLHelper.getValue(value);
        if (value instanceof CompositeKey) {
          return value;
        } else {
          return indexDefinition.createValue(session, value);
        }
      }
    } else {
      if (indexDefinition instanceof IndexDefinitionMultiValue) {
        return ((IndexDefinitionMultiValue) indexDefinition)
            .createSingleValue(session, SQLHelper.getValue(value));
      } else {
        return indexDefinition.createValue(session, SQLHelper.getValue(value, null, context));
      }
    }
  }

  public boolean hasGroupBy() {
    return groupByFields != null && groupByFields.size() > 0;
  }

  @Override
  protected boolean isUseCache() {
    return !noCache && request.isUseCache();
  }

  private static EntityImpl createIndexEntryAsEntity(
      DatabaseSessionInternal db, final Object iKey, final Identifiable iValue) {
    final var entity = new EntityImpl(db);
    entity.setProperty("key", iKey);
    entity.setProperty("rid", iValue);
    RecordInternal.unsetDirty(entity);
    return entity;
  }

  /**
   * Compile the filter conditions only the first time.
   */
  public CommandExecutorSQLSelect parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    this.context = iRequest.getContext();

    final var textRequest = (CommandRequestText) iRequest;
    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      super.parse(session, iRequest);

      initContext(iRequest.getContext());

      final var pos = parseProjections();
      if (pos == -1) {
        return this;
      }

      final var endPosition = parserText.length();

      parserNextWord(true);
      if (parserGetLastWord().equalsIgnoreCase(KEYWORD_FROM)) {
        // FROM
        parsedTarget =
            SQLEngine
                .parseTarget(
                    parserText.substring(parserGetCurrentPosition(), endPosition), getContext());
        parserSetCurrentPosition(
            parsedTarget.parserIsEnded()
                ? endPosition
                : parsedTarget.parserGetCurrentPosition() + parserGetCurrentPosition());
      } else {
        parserGoBack();
      }

      if (!parserIsEnded()) {
        parserSkipWhiteSpaces();

        while (!parserIsEnded()) {
          final var w = parserNextWord(true);

          if (!w.isEmpty()) {
            if (w.equals(KEYWORD_WHERE)) {
              compiledFilter =
                  SQLEngine
                      .parseCondition(
                          parserText.substring(parserGetCurrentPosition(), endPosition),
                          getContext(),
                          KEYWORD_WHERE);
              optimize(session);
              if (compiledFilter.parserIsEnded()) {
                parserSetCurrentPosition(endPosition);
              } else {
                parserSetCurrentPosition(
                    compiledFilter.parserGetCurrentPosition() + parserGetCurrentPosition());
              }
            } else if (w.equals(KEYWORD_LET)) {
              parseLet(session);
            } else if (w.equals(KEYWORD_GROUP)) {
              parseGroupBy();
            } else if (w.equals(KEYWORD_ORDER)) {
              parseOrderBy();
            } else if (w.equals(KEYWORD_UNWIND)) {
              parseUnwind();
            } else if (w.equals(KEYWORD_LIMIT)) {
              parseLimit(w);
            } else if (w.equals(KEYWORD_SKIP) || w.equals(KEYWORD_OFFSET)) {
              parseSkip(w);
            } else if (w.equals(KEYWORD_FETCHPLAN)) {
              parseFetchplan(w);
            } else if (w.equals(KEYWORD_NOCACHE)) {
              parseNoCache(w);
            } else if (w.equals(KEYWORD_TIMEOUT)) {
              parseTimeout(w);
            } else if (w.equals(KEYWORD_PARALLEL)) {
              parallel = parseParallel(w);
            } else {
              if (preParsedStatement == null) {
                throwParsingException(session.getDatabaseName(), "Invalid keyword '" + w + "'");
              } // if the pre-parsed statement is OK, then you can go on with the rest, the SQL is
              // valid and this is probably a space in a backtick
            }
          }
        }
      }
      if (limit == 0 || limit < -1) {
        throw new IllegalArgumentException("Limit must be > 0 or = -1 (no limit)");
      }
      validateQuery();
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  private void validateQuery() {
    if (this.let != null) {
      for (var letValue : let.values()) {
        if (letValue instanceof SQLFunctionRuntime f) {
          if (f.getFunction().aggregateResults()
              && this.groupByFields != null
              && this.groupByFields.size() > 0) {
            throwParsingException(null,
                "Aggregate function cannot be used in LET clause together with GROUP BY");
          }
        }
      }
    }
  }

  /**
   * Determine clusters that are used in select operation
   *
   * @return set of involved cluster names
   */
  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {

    final Set<String> clusters = new HashSet<String>();

    if (parsedTarget != null) {
      if (parsedTarget.getTargetQuery() != null
          && parsedTarget.getTargetRecords() instanceof CommandExecutorSQLResultsetDelegate) {
        // SUB-QUERY: EXECUTE IT LOCALLY
        // SUB QUERY, PROPAGATE THE CALL
        final var clIds =
            ((CommandExecutorSQLResultsetDelegate) parsedTarget.getTargetRecords())
                .getInvolvedClusters(session);
        for (var c : clIds) {
          // FILTER THE CLUSTER WHERE THE USER HAS THE RIGHT ACCESS
          if (checkClusterAccess(session, c)) {
            clusters.add(c);
          }
        }

      } else if (parsedTarget.getTargetRecords() != null) {
        // SINGLE RECORDS: BROWSE ALL (COULD BE EXPENSIVE).
        for (var identifiable : parsedTarget.getTargetRecords()) {
          final var c =
              session.getClusterNameById(identifiable.getIdentity().getClusterId())
                  .toLowerCase(Locale.ENGLISH);
          // FILTER THE CLUSTER WHERE THE USER HAS THE RIGHT ACCESS
          if (checkClusterAccess(session, c)) {
            clusters.add(c);
          }
        }
      }

      if (parsedTarget.getTargetClasses() != null) {
        return getInvolvedClustersOfClasses(parsedTarget.getTargetClasses().values(), session);
      }

      if (parsedTarget.getTargetClusters() != null) {
        return getInvolvedClustersOfClusters(session, parsedTarget.getTargetClusters().keySet());
      }

      if (parsedTarget.getTargetIndex() != null) {
        // EXTRACT THE CLASS NAME -> CLUSTERS FROM THE INDEX DEFINITION
        return getInvolvedClustersOfIndex(session, parsedTarget.getTargetIndex());
      }
    }
    return clusters;
  }

  /**
   * @return {@code ture} if any of the sql functions perform aggregation, {@code false} otherwise
   */
  public boolean isAnyFunctionAggregates() {
    if (isAnyFunctionAggregates == null) {
      if (projections != null) {
        for (var p : projections.entrySet()) {
          if (p.getValue() instanceof SQLFunctionRuntime
              && ((SQLFunctionRuntime) p.getValue()).aggregateResults()) {
            isAnyFunctionAggregates = true;
            break;
          }
        }
      }

      if (isAnyFunctionAggregates == null) {
        isAnyFunctionAggregates = false;
      }
    }
    return isAnyFunctionAggregates;
  }


  public Iterator<Identifiable> iterator(DatabaseSessionInternal session) {
    return iterator(session, null);
  }

  public Iterator<Identifiable> iterator(DatabaseSessionInternal session,
      final Map<Object, Object> iArgs) {
    final Iterator<Identifiable> subIterator;
    if (target == null) {
      // GET THE RESULT
      executeSearch(session, iArgs);
      applyExpand(session);
      handleNoTarget();
      handleGroupBy(context);
      applyOrderBy(true);

      subIterator = new ArrayList<Identifiable>(
          (List<Identifiable>) getResult(session)).iterator();
      lastRecord = null;
      tempResult = null;
      groupedResult.clear();
      aggregate = false;
    } else {
      subIterator = (Iterator<Identifiable>) target;
    }

    return subIterator;
  }

  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    bindDefaultContextVariables(session);

    if (iArgs != null)
    // BIND ARGUMENTS INTO CONTEXT TO ACCESS FROM ANY POINT (EVEN FUNCTIONS)
    {
      for (var arg : iArgs.entrySet()) {
        context.setVariable(arg.getKey().toString(), arg.getValue());
      }
    }

    if (timeoutMs > 0) {
      getContext().beginExecution(timeoutMs, timeoutStrategy);
    }

    if (!optimizeExecution(session)) {
      fetchLimit = getQueryFetchLimit();

      executeSearch(session, iArgs);
      applyExpand(session);
      handleNoTarget();
      handleGroupBy(context);
      applyOrderBy(true);
      applyLimitAndSkip();
    }
    return getResult(session);
  }

  public Map<String, Object> getProjections() {
    return projections;
  }

  @Override
  public String getSyntax() {
    return "SELECT [<Projections>] FROM <Target> [LET <Assignment>*] [WHERE <Condition>*] [ORDER BY"
        + " <Fields>* [ASC|DESC]*] [LIMIT <MaxRecords>] [TIMEOUT <TimeoutInMs>] [LOCK"
        + " none|record] [NOCACHE]";
  }

  public String getFetchPlan() {
    return fetchPlan != null ? fetchPlan : request.getFetchPlan();
  }

  protected void executeSearch(DatabaseSessionInternal db, final Map<Object, Object> iArgs) {
    assignTarget(db, iArgs);

    if (target == null) {
      if (let != null)
      // EXECUTE ONCE TO ASSIGN THE LET
      {
        assignLetClauses(db, lastRecord != null ? lastRecord.getRecord(db) : null);
      }

      // SEARCH WITHOUT USING TARGET (USUALLY WHEN LET/INDEXES ARE INVOLVED)
      return;
    }

    fetchFromTarget(db, target);
  }

  @Override
  protected boolean assignTarget(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    if (!super.assignTarget(session, iArgs)) {
      if (parsedTarget.getTargetIndex() != null) {
        searchInIndex(session);
      } else {
        throw new QueryParsingException(session.getDatabaseName(),
            "No source found in query: specify class, cluster(s), index or single record(s). Use "
                + getSyntax());
      }
    }
    return true;
  }

  protected boolean executeSearchRecord(
      final Identifiable id, final CommandContext iContext, boolean callHooks) {
    if (id == null) {
      return false;
    }

    final var identity = (RecordId) id.getIdentity();

    if (uniqueResult != null) {
      if (uniqueResult.containsKey(identity)) {
        return true;
      }

      if (identity.isValid()) {
        uniqueResult.put(identity, identity);
      }
    }

    if (!checkInterruption()) {
      return false;
    }

    var db = iContext.getDatabaseSession();
    RecordAbstract record;
    if (!(id instanceof DBRecord)) {
      try {
        record = db.load(id.getIdentity());
      } catch (RecordNotFoundException e) {
        record = null;
      }

      if (id instanceof ContextualRecordId && ((ContextualRecordId) id).getContext() != null) {
        var ridContext = ((ContextualRecordId) id).getContext();
        for (var entry : ridContext.entrySet()) {
          context.setVariable(entry.getKey(), entry.getValue());
        }
      }
    } else {
      record = (RecordAbstract) id;
    }

    iContext.updateMetric("recordReads", +1);

    if (record == null)
    // SKIP IT
    {
      return true;
    }
    if (RecordInternal.getRecordType(iContext.getDatabaseSession(), record)
        != EntityImpl.RECORD_TYPE
        && checkSkipBlob())
    // SKIP binary records in case of projection.
    {
      return true;
    }

    iContext.updateMetric("documentReads", +1);

    iContext.setVariable("current", record);

    if (filter(record, iContext)) {
      if (callHooks) {
        db.beforeReadOperations(record);
        db.afterReadOperations(record);
      }

      if (parallel) {
        try {
          applyGroupBy(record, iContext);
          resultQueue.put(new AsyncResult(record, iContext));
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
          return false;
        }
        tmpQueueOffer.incrementAndGet();
      } else {
        applyGroupBy(record, iContext);

        // LIMIT REACHED
        return handleResult(record, iContext);
      }
    }

    return true;
  }

  private boolean checkSkipBlob() {
    if (expandTarget != null) {
      return true;
    }
    if (projections != null) {
      if (projections.size() > 1) {
        return true;
      }
      if (projections.containsKey("@rid")) {
        return false;
      }
    }
    return false;
  }

  /**
   * Handles the record in result.
   *
   * @param iRecord Record to handle
   * @return false if limit has been reached, otherwise true
   */
  @Override
  protected boolean handleResult(final Identifiable iRecord, final CommandContext iContext) {
    lastRecord = iRecord;

    if ((orderedFields.isEmpty() || fullySortedByIndex || isRidOnlySort())
        && skip > 0
        && this.unwindFields == null
        && this.expandTarget == null) {
      lastRecord = null;
      skip--;
      return true;
    }

    if (!addResult(lastRecord, iContext)) {
      return false;
    }

    return continueSearching();
  }

  private boolean continueSearching() {
    return !((orderedFields.isEmpty() || fullySortedByIndex || isRidOnlySort())
        && !isAnyFunctionAggregates()
        && (groupByFields == null || groupByFields.isEmpty())
        && fetchLimit > -1
        && resultCount >= fetchLimit
        && expandTarget == null);
  }

  /**
   * Returns the temporary RID counter assuring it's unique per query tree.
   *
   * @return Serial as integer
   */
  public int getTemporaryRIDCounter(final CommandContext iContext) {
    final var parentQuery =
        (TemporaryRidGenerator) iContext.getVariable("parentQuery");
    if (parentQuery != null && parentQuery != this) {
      return parentQuery.getTemporaryRIDCounter(iContext);
    } else {
      return serialTempRID.getAndIncrement();
    }
  }

  protected boolean addResult(Identifiable iRecord, final CommandContext iContext) {
    resultCount++;
    if (iRecord == null) {
      return true;
    }

    if (projections != null || groupByFields != null && !groupByFields.isEmpty()) {
      if (!aggregate) {
        // APPLY PROJECTIONS IN LINE
        throw new UnsupportedOperationException("Projections are not supported bu old engine");
      } else {
        // GROUP BY
        return true;
      }
    }

    if (tipLimitThreshold > 0 && resultCount > tipLimitThreshold && getLimit() == -1) {
      tipLimitThreshold = 0;
    }

    List<Identifiable> allResults = new ArrayList<Identifiable>();
    if (unwindFields != null) {
      var partial = unwind(iRecord, this.unwindFields, iContext);

      for (var item : partial) {
        allResults.add(item);
      }
    } else {
      allResults.add(iRecord);
    }
    var result = true;
    if (allowsStreamedResult()) {
      // SEND THE RESULT INLINE
      if (request.getResultListener() != null) {
        for (var iRes : allResults) {
          result = pushResult(iContext.getDatabaseSession(), iRes);
        }
      }
    } else {

      // COLLECT ALL THE RECORDS AND ORDER THEM AT THE END
      if (tempResult == null) {
        tempResult = new ArrayList<Identifiable>();
      }

      applyPartialOrderBy();

      for (var iRes : allResults) {
        ((Collection<Identifiable>) tempResult).add(iRes);
      }
    }

    return result;
  }

  private EntityImpl applyGroupBy(final Identifiable iRecord, final CommandContext iContext) {
    if (!aggregate) {
      return null;
    }

    // AGGREGATION/GROUP BY
    Object fieldValue = null;
    if (groupByFields != null && !groupByFields.isEmpty()) {
      if (groupByFields.size() > 1) {
        // MULTI-FIELD GROUP BY
        final EntityImpl entity = iRecord.getRecord(iContext.getDatabaseSession());
        final var fields = new Object[groupByFields.size()];
        for (var i = 0; i < groupByFields.size(); ++i) {
          final var field = groupByFields.get(i);
          if (field.startsWith("$")) {
            fields[i] = iContext.getVariable(field);
          } else {
            fields[i] = entity.getProperty(field);
          }
        }
        fieldValue = fields;
      } else {
        final var field = groupByFields.get(0);
        if (field != null) {
          if (field.startsWith("$")) {
            fieldValue = iContext.getVariable(field);
          } else {
            EntityImpl entity = iRecord.getRecord(iContext.getDatabaseSession());
            fieldValue = entity.getProperty(field);
          }
        }
      }
    }

    throw new UnsupportedOperationException("Group by is not supported by old engine");
  }

  private boolean allowsStreamedResult() {
    return (fullySortedByIndex || orderedFields.isEmpty())
        && expandTarget == null
        && unwindFields == null;
  }

  /**
   * in case of ORDER BY + SKIP + LIMIT, this method applies ORDER BY operation on partial result
   * and discards overflowing results (results > skip + limit)
   */
  private void applyPartialOrderBy() {
    if (expandTarget != null
        || (unwindFields != null && unwindFields.size() > 0)
        || orderedFields.isEmpty()
        || fullySortedByIndex
        || isRidOnlySort()) {
      return;
    }

    if (limit > 0) {
      var sortBufferSize = limit + 1;
      if (skip > 0) {
        sortBufferSize += skip;
      }
      if (tempResult instanceof List
          && ((List) tempResult).size() >= sortBufferSize + PARTIAL_SORT_BUFFER_THRESHOLD) {
        applyOrderBy(false);
        tempResult = new ArrayList(((List) tempResult).subList(0, sortBufferSize));
      }
    }
  }

  private Collection<Identifiable> unwind(
      final Identifiable iRecord,
      final List<String> unwindFields,
      final CommandContext iContext) {
    final List<Identifiable> result = new ArrayList<Identifiable>();
    EntityImpl entity;
    if (iRecord instanceof EntityImpl) {
      entity = (EntityImpl) iRecord;
    } else {
      entity = iRecord.getRecord(iContext.getDatabaseSession());
    }
    if (unwindFields.size() == 0) {
      RecordInternal.setIdentity(entity, new RecordId(-2, getTemporaryRIDCounter(iContext)));
      result.add(entity);
    } else {
      var firstField = unwindFields.get(0);
      final var nextFields = unwindFields.subList(1, unwindFields.size());

      var fieldValue = entity.getProperty(firstField);
      if (fieldValue == null
          || !(fieldValue instanceof Iterable)
          || fieldValue instanceof EntityImpl) {
        result.addAll(unwind(entity, nextFields, iContext));
      } else {
        throw new UnsupportedOperationException();
      }
    }
    return result;
  }

  protected RuntimeResult getProjectionGroup(
      final Object fieldValue, final CommandContext iContext) {
    final long projectionElapsed = (Long) context.getVariable("projectionElapsed", 0L);
    final var begin = System.currentTimeMillis();
    try {

      aggregate = true;

      Object key;

      if (fieldValue != null) {
        if (fieldValue.getClass().isArray()) {
          // LOOK IT BY HASH (FASTER THAN COMPARE EACH SINGLE VALUE)
          final var array = (Object[]) fieldValue;

          final var keyArray = new StringBuilder();
          for (var o : array) {
            if (keyArray.length() > 0) {
              keyArray.append(",");
            }
            if (o != null) {
              keyArray.append(
                  o instanceof Identifiable
                      ? ((Identifiable) o).getIdentity().toString()
                      : o.toString());
            } else {
              keyArray.append(NULL_VALUE);
            }
          }

          key = keyArray.toString();
        } else {
          // LOOKUP FOR THE FIELD
          key = fieldValue;
        }
      } else
      // USE NULL_VALUE THEN REPLACE WITH REAL NULL
      {
        key = NULL_VALUE;
      }

      var group = groupedResult.get(key);
      if (group == null) {
        group =
            new RuntimeResult(
                fieldValue,
                createProjectionFromDefinition(),
                getTemporaryRIDCounter(iContext),
                context);
        final var prev = groupedResult.putIfAbsent(key, group);
        if (prev != null)
        // ALREADY EXISTENT: USE THIS
        {
          group = prev;
        }
      }
      return group;

    } finally {
      context.setVariable(
          "projectionElapsed", projectionElapsed + (System.currentTimeMillis() - begin));
    }
  }

  protected void parseGroupBy() {
    parserRequiredKeyword(null, KEYWORD_BY);

    groupByFields = new ArrayList<String>();
    while (!parserIsEnded()
        && (groupByFields.size() == 0
        || parserGetLastSeparator() == ','
        || parserGetCurrentChar() == ',')) {
      final var fieldName = parserRequiredWord(false, "Field name expected", null);
      groupByFields.add(fieldName);
      parserSkipWhiteSpaces();
    }

    if (groupByFields.size() == 0) {
      throwParsingException(null, "Group by field set was missed. Example: GROUP BY name, salary");
    }

    // AGGREGATE IT
    aggregate = true;
    groupedResult.clear();
  }

  protected void parseUnwind() {
    unwindFields = new ArrayList<String>();
    while (!parserIsEnded()
        && (unwindFields.size() == 0
        || parserGetLastSeparator() == ','
        || parserGetCurrentChar() == ',')) {
      final var fieldName = parserRequiredWord(false, "Field name expected", null);
      unwindFields.add(fieldName);
      parserSkipWhiteSpaces();
    }

    if (unwindFields.size() == 0) {
      throwParsingException(null, "unwind field set was missed. Example: UNWIND name, salary");
    }
  }

  protected void parseOrderBy() {
    parserRequiredKeyword(null, KEYWORD_BY);

    String fieldOrdering = null;

    orderedFields = new ArrayList<Pair<String, String>>();
    while (!parserIsEnded()
        && (orderedFields.size() == 0
        || parserGetLastSeparator() == ','
        || parserGetCurrentChar() == ',')) {
      final var fieldName = parserRequiredWord(false, "Field name expected", null);

      parserOptionalWord(true);

      final var word = parserGetLastWord();

      if (word.length() == 0)
      // END CLAUSE: SET AS ASC BY DEFAULT
      {
        fieldOrdering = KEYWORD_ASC;
      } else if (word.equals(KEYWORD_LIMIT)
          || word.equals(KEYWORD_SKIP)
          || word.equals(KEYWORD_OFFSET)) {
        // NEXT CLAUSE: SET AS ASC BY DEFAULT
        fieldOrdering = KEYWORD_ASC;
        parserGoBack();
      } else {
        if (word.equals(KEYWORD_ASC)) {
          fieldOrdering = KEYWORD_ASC;
        } else if (word.equals(KEYWORD_DESC)) {
          fieldOrdering = KEYWORD_DESC;
        } else {
          throwParsingException(null,
              "Ordering mode '"
                  + word
                  + "' not supported. Valid is 'ASC', 'DESC' or nothing ('ASC' by default)");
        }
      }

      orderedFields.add(new Pair<String, String>(fieldName, fieldOrdering));
      parserSkipWhiteSpaces();
    }

    if (orderedFields.size() == 0) {
      throwParsingException(null,
          "Order by field set was missed. Example: ORDER BY name ASC, salary DESC");
    }
  }

  @Override
  protected void searchInClasses(DatabaseSessionInternal db) {
    final var className = parsedTarget.getTargetClasses().keySet().iterator().next();

    final var cls = db.getMetadata().getImmutableSchemaSnapshot().getClass(className);
    if (!searchForIndexes(db, (SchemaClassInternal) cls) && !searchForSubclassIndexes(
        db, cls)) {
      // CHECK FOR INVERSE ORDER
      final var browsingOrderAsc = isBrowsingAscendingOrder();
      super.searchInClasses(browsingOrderAsc, db);
    }
  }

  private boolean isBrowsingAscendingOrder() {
    return !(orderedFields.size() == 1
        && orderedFields.get(0).getKey().equalsIgnoreCase("@rid")
        && orderedFields.get(0).getValue().equalsIgnoreCase("DESC"));
  }

  protected int parseProjections() {
    if (!parserOptionalKeyword(null, KEYWORD_SELECT)) {
      return -1;
    }

    var upperBound =
        StringSerializerHelper.getLowerIndexOfKeywords(
            parserTextUpperCase, parserGetCurrentPosition(), KEYWORD_FROM, KEYWORD_LET);
    if (upperBound == -1)
    // UP TO THE END
    {
      upperBound = parserText.length();
    }

    var lastRealPositionProjection = -1;

    var currPos = parserGetCurrentPosition();
    if (currPos == -1) {
      return -1;
    }

    final var projectionString = parserText.substring(currPos, upperBound);
    if (projectionString.trim().length() > 0) {
      // EXTRACT PROJECTIONS
      projections = new LinkedHashMap<String, Object>();
      projectionDefinition = new LinkedHashMap<String, String>();

      final var items = StringSerializerHelper.smartSplit(projectionString, ',');

      int endPos;
      for (var projectionItem : items) {
        var projection = StringSerializerHelper.smartTrim(projectionItem.trim(), true, true);

        if (projectionDefinition == null) {
          throw new CommandSQLParsingException((String) null,
              "Projection not allowed with FLATTEN() and EXPAND() operators");
        }

        final var words = StringSerializerHelper.smartSplit(projection, ' ');

        String fieldName;
        if (words.size() > 1 && words.get(1).trim().equalsIgnoreCase(KEYWORD_AS)) {
          // FOUND AS, EXTRACT ALIAS
          if (words.size() < 3) {
            throw new CommandSQLParsingException((String) null, "Found 'AS' without alias");
          }

          fieldName = words.get(2).trim();

          if (projectionDefinition.containsKey(fieldName)) {
            throw new CommandSQLParsingException((String) null,
                "Field '"
                    + fieldName
                    + "' is duplicated in current SELECT, choose a different name");
          }

          projection = words.get(0).trim();

          if (words.size() > 3) {
            lastRealPositionProjection = projectionString.indexOf(words.get(3));
          } else {
            lastRealPositionProjection += projectionItem.length() + 1;
          }

        } else {
          // EXTRACT THE FIELD NAME WITHOUT FUNCTIONS AND/OR LINKS
          projection = words.get(0);
          fieldName = projection;

          lastRealPositionProjection = projectionString.indexOf(fieldName) + fieldName.length() + 1;

          if (fieldName.charAt(0) == '@') {
            fieldName = fieldName.substring(1);
          }

          endPos = extractProjectionNameSubstringEndPosition(fieldName);

          if (endPos > -1) {
            fieldName = fieldName.substring(0, endPos);
          }

          // FIND A UNIQUE NAME BY ADDING A COUNTER
          for (var fieldIndex = 2; projectionDefinition.containsKey(fieldName); ++fieldIndex) {
            fieldName += fieldIndex;
          }
        }

        final var p = SQLPredicate.upperCase(projection);
        if (p.startsWith("FLATTEN(") || p.startsWith("EXPAND(")) {
          if (p.startsWith("FLATTEN(")) {
            LogManager.instance().debug(this, "FLATTEN() operator has been replaced by EXPAND()");
          }

          var pars = StringSerializerHelper.getParameters(projection);
          if (pars.size() != 1) {
            throw new CommandSQLParsingException((String) null,
                "EXPAND/FLATTEN operators expects the field name as parameter. Example EXPAND( out"
                    + " )");
          }

          expandTarget = SQLHelper.parseValue(this, pars.get(0).trim(), context);

          // BY PASS THIS AS PROJECTION BUT TREAT IT AS SPECIAL
          projectionDefinition = null;
          projections = null;

          if (!aggregate
              && expandTarget instanceof SQLFunctionRuntime
              && ((SQLFunctionRuntime) expandTarget).aggregateResults()) {
            aggregate = true;
          }

          continue;
        }

        fieldName = IOUtils.getStringContent(fieldName);

        projectionDefinition.put(fieldName, projection);
      }

      if (projectionDefinition != null
          && (projectionDefinition.size() > 1
          || !projectionDefinition.values().iterator().next().equals("*"))) {
        projections = createProjectionFromDefinition();

        for (var p : projections.values()) {

          if (!aggregate
              && p instanceof SQLFunctionRuntime
              && ((SQLFunctionRuntime) p).aggregateResults()) {
            // AGGREGATE IT
            getProjectionGroup(null, context);
            break;
          }
        }

      } else {
        // TREATS SELECT * AS NO PROJECTION
        projectionDefinition = null;
        projections = null;
      }
    }

    if (upperBound < parserText.length() - 1) {
      parserSetCurrentPosition(upperBound);
    } else if (lastRealPositionProjection > -1) {
      parserMoveCurrentPosition(lastRealPositionProjection);
    } else {
      parserSetEndOfText();
    }

    return parserGetCurrentPosition();
  }

  protected Map<String, Object> createProjectionFromDefinition() {
    if (projectionDefinition == null) {
      return new LinkedHashMap<String, Object>();
    }

    final Map<String, Object> projections =
        new LinkedHashMap<String, Object>(projectionDefinition.size());
    for (var p : projectionDefinition.entrySet()) {
      final var projectionValue = SQLHelper.parseValue(this, p.getValue(), context);
      projections.put(p.getKey(), projectionValue);
    }
    return projections;
  }

  protected int extractProjectionNameSubstringEndPosition(final String projection) {
    int endPos;
    final var pos1 = projection.indexOf('.');
    final var pos2 = projection.indexOf('(');
    final var pos3 = projection.indexOf('[');
    if (pos1 > -1 && pos2 == -1 && pos3 == -1) {
      endPos = pos1;
    } else if (pos2 > -1 && pos1 == -1 && pos3 == -1) {
      endPos = pos2;
    } else if (pos3 > -1 && pos1 == -1 && pos2 == -1) {
      endPos = pos3;
    } else if (pos1 > -1 && pos2 > -1 && pos3 == -1) {
      endPos = Math.min(pos1, pos2);
    } else if (pos2 > -1 && pos3 > -1 && pos1 == -1) {
      endPos = Math.min(pos2, pos3);
    } else if (pos1 > -1 && pos3 > -1 && pos2 == -1) {
      endPos = Math.min(pos1, pos3);
    } else if (pos1 > -1 && pos2 > -1 && pos3 > -1) {
      endPos = Math.min(pos1, pos2);
      endPos = Math.min(endPos, pos3);
    } else {
      endPos = -1;
    }
    return endPos;
  }

  /**
   * Parses the fetchplan keyword if found.
   */
  protected boolean parseFetchplan(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_FETCHPLAN)) {
      return false;
    }

    parserSkipWhiteSpaces();
    var start = parserGetCurrentPosition();

    parserNextWord(true);
    var end = parserGetCurrentPosition();
    parserSkipWhiteSpaces();

    var position = parserGetCurrentPosition();
    while (!parserIsEnded()) {
      final var word = IOUtils.getStringContent(parserNextWord(true));
      if (!PatternConst.PATTERN_FETCH_PLAN.matcher(word).matches()) {
        break;
      }

      end = parserGetCurrentPosition();
      parserSkipWhiteSpaces();
      position = parserGetCurrentPosition();
    }

    parserSetCurrentPosition(position);

    if (end < 0) {
      fetchPlan = IOUtils.getStringContent(parserText.substring(start));
    } else {
      fetchPlan = IOUtils.getStringContent(parserText.substring(start, end));
    }

    request.setFetchPlan(fetchPlan);

    return true;
  }

  protected boolean optimizeExecution(DatabaseSessionInternal session) {
    if (compiledFilter != null) {
      mergeRangeConditionsToBetweenOperators(session, compiledFilter);
    }

    if ((compiledFilter == null || (compiledFilter.getRootCondition() == null))
        && groupByFields == null
        && projections != null
        && projections.size() == 1) {

      final var startOptimization = System.currentTimeMillis();
      try {

        final var entry = projections.entrySet().iterator().next();

        if (entry.getValue() instanceof SQLFunctionRuntime rf) {
          if (rf.function instanceof SQLFunctionCount
              && rf.configuredParameters.length == 1
              && "*".equals(rf.configuredParameters[0])) {

            final var restrictedClasses = isUsingRestrictedClasses(session);

            if (!restrictedClasses) {
              long count = 0;

              if (parsedTarget.getTargetClasses() != null) {
                final var className = parsedTarget.getTargetClasses().keySet().iterator().next();
                var cls =
                    (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
                        .getClass(className);
                count = cls.count(session);
              } else if (parsedTarget.getTargetClusters() != null) {
                for (var cluster : parsedTarget.getTargetClusters().keySet()) {
                  count += session.countClusterElements(cluster);
                }
              } else if (parsedTarget.getTargetIndex() != null) {
                count +=
                    session
                        .getMetadata()
                        .getIndexManagerInternal()
                        .getIndex(session, parsedTarget.getTargetIndex())
                        .size(session);
              } else {
                final var recs = parsedTarget.getTargetRecords();
                if (recs != null) {
                  if (recs instanceof Collection<?>) {
                    count += ((Collection<?>) recs).size();
                  } else {
                    for (Object o : recs) {
                      count++;
                    }
                  }
                }
              }

              if (tempResult == null) {
                tempResult = new ArrayList<>();
              }
              var t = session.newEmbeddedEntity();
              t.setProperty(entry.getKey(), count);

              ((Collection<Identifiable>) tempResult).add(t);
              return true;
            }
          }
        }

      } finally {
        context.setVariable(
            "optimizationElapsed", (System.currentTimeMillis() - startOptimization));
      }
    }

    return false;
  }

  private boolean isUsingRestrictedClasses(DatabaseSessionInternal db) {
    var restrictedClasses = false;
    final var user = db.getCurrentUser();

    if (parsedTarget.getTargetClasses() != null
        && user != null
        && user.checkIfAllowed(db, Rule.ResourceGeneric.BYPASS_RESTRICTED, null,
        Role.PERMISSION_READ)
        == null) {
      for (var className : parsedTarget.getTargetClasses().keySet()) {
        final var cls =
            db.getMetadata().getImmutableSchemaSnapshot().getClass(className);
        if (cls.isSubClassOf(SecurityShared.RESTRICTED_CLASSNAME)) {
          restrictedClasses = true;
          break;
        }
      }
    }
    return restrictedClasses;
  }

  /**
   * Parses the NOCACHE keyword if found.
   */
  protected boolean parseNoCache(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_NOCACHE)) {
      return false;
    }

    noCache = true;
    return true;
  }

  private void mergeRangeConditionsToBetweenOperators(DatabaseSessionInternal session,
      SQLFilter filter) {
    var condition = filter.getRootCondition();

    var newCondition = convertToBetweenClause(session, condition);
    if (newCondition != null) {
      filter.setRootCondition(newCondition);
      metricRecorder.recordRangeQueryConvertedInBetween();
      return;
    }

    mergeRangeConditionsToBetweenOperators(session, condition);
  }

  private void mergeRangeConditionsToBetweenOperators(DatabaseSessionInternal session,
      SQLFilterCondition condition) {
    if (condition == null) {
      return;
    }

    SQLFilterCondition newCondition;

    if (condition.getLeft() instanceof SQLFilterCondition leftCondition) {
      newCondition = convertToBetweenClause(session, leftCondition);

      if (newCondition != null) {
        condition.setLeft(newCondition);
        metricRecorder.recordRangeQueryConvertedInBetween();
      } else {
        mergeRangeConditionsToBetweenOperators(session, leftCondition);
      }
    }

    if (condition.getRight() instanceof SQLFilterCondition rightCondition) {

      newCondition = convertToBetweenClause(session, rightCondition);
      if (newCondition != null) {
        condition.setRight(newCondition);
        metricRecorder.recordRangeQueryConvertedInBetween();
      } else {
        mergeRangeConditionsToBetweenOperators(session, rightCondition);
      }
    }
  }

  private SQLFilterCondition convertToBetweenClause(DatabaseSessionInternal session,
      final SQLFilterCondition condition) {
    if (condition == null) {
      return null;
    }

    final var right = condition.getRight();
    final var left = condition.getLeft();

    final var operator = condition.getOperator();
    if (!(operator instanceof QueryOperatorAnd)) {
      return null;
    }

    if (!(right instanceof SQLFilterCondition rightCondition)) {
      return null;
    }

    if (!(left instanceof SQLFilterCondition leftCondition)) {
      return null;
    }

    String rightField;

    if (rightCondition.getLeft() instanceof SQLFilterItemField
        && rightCondition.getRight() instanceof SQLFilterItemField) {
      return null;
    }

    if (!(rightCondition.getLeft() instanceof SQLFilterItemField)
        && !(rightCondition.getRight() instanceof SQLFilterItemField)) {
      return null;
    }

    if (leftCondition.getLeft() instanceof SQLFilterItemField
        && leftCondition.getRight() instanceof SQLFilterItemField) {
      return null;
    }

    if (!(leftCondition.getLeft() instanceof SQLFilterItemField)
        && !(leftCondition.getRight() instanceof SQLFilterItemField)) {
      return null;
    }

    final List<Object> betweenBoundaries = new ArrayList<Object>();

    if (rightCondition.getLeft() instanceof SQLFilterItemField itemField) {
      if (!itemField.isFieldChain()) {
        return null;
      }

      if (itemField.getFieldChain().getItemCount() > 1) {
        return null;
      }

      rightField = itemField.getRoot(session);
      betweenBoundaries.add(rightCondition.getRight());
    } else if (rightCondition.getRight() instanceof SQLFilterItemField itemField) {
      if (!itemField.isFieldChain()) {
        return null;
      }

      if (itemField.getFieldChain().getItemCount() > 1) {
        return null;
      }

      rightField = itemField.getRoot(session);
      betweenBoundaries.add(rightCondition.getLeft());
    } else {
      return null;
    }

    betweenBoundaries.add("and");

    String leftField;
    if (leftCondition.getLeft() instanceof SQLFilterItemField itemField) {
      if (!itemField.isFieldChain()) {
        return null;
      }

      if (itemField.getFieldChain().getItemCount() > 1) {
        return null;
      }

      leftField = itemField.getRoot(session);
      betweenBoundaries.add(leftCondition.getRight());
    } else if (leftCondition.getRight() instanceof SQLFilterItemField itemField) {
      if (!itemField.isFieldChain()) {
        return null;
      }

      if (itemField.getFieldChain().getItemCount() > 1) {
        return null;
      }

      leftField = itemField.getRoot(session);
      betweenBoundaries.add(leftCondition.getLeft());
    } else {
      return null;
    }

    if (!leftField.equalsIgnoreCase(rightField)) {
      return null;
    }

    final var rightOperator = rightCondition.getOperator();
    final var leftOperator = leftCondition.getOperator();

    if ((rightOperator instanceof QueryOperatorMajor
        || rightOperator instanceof QueryOperatorMajorEquals)
        && (leftOperator instanceof QueryOperatorMinor
        || leftOperator instanceof QueryOperatorMinorEquals)) {

      final var between = new QueryOperatorBetween();

      if (rightOperator instanceof QueryOperatorMajor) {
        between.setLeftInclusive(false);
      }

      if (leftOperator instanceof QueryOperatorMinor) {
        between.setRightInclusive(false);
      }

      return new SQLFilterCondition(
          new SQLFilterItemField(session, this, leftField, null), between,
          betweenBoundaries.toArray());
    }

    if ((leftOperator instanceof QueryOperatorMajor
        || leftOperator instanceof QueryOperatorMajorEquals)
        && (rightOperator instanceof QueryOperatorMinor
        || rightOperator instanceof QueryOperatorMinorEquals)) {
      final var between = new QueryOperatorBetween();

      if (leftOperator instanceof QueryOperatorMajor) {
        between.setLeftInclusive(false);
      }

      if (rightOperator instanceof QueryOperatorMinor) {
        between.setRightInclusive(false);
      }

      Collections.reverse(betweenBoundaries);

      return new SQLFilterCondition(
          new SQLFilterItemField(session, this, leftField, null), between,
          betweenBoundaries.toArray());
    }

    return null;
  }

  public void initContext(@Nonnull CommandContext context) {
    metricRecorder.setContext(context);
    this.context = context;
  }

  private boolean fetchFromTarget(DatabaseSessionInternal db,
      final Iterator<? extends Identifiable> iTarget) {
    fetchLimit = getQueryFetchLimit();

    final var startFetching = System.currentTimeMillis();

    final int[] clusterIds;
    if (iTarget instanceof RecordIteratorClusters) {
      clusterIds = ((RecordIteratorClusters) iTarget).getClusterIds();
    } else {
      clusterIds = null;
    }

    parallel =
        (parallel
            || db.getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_PARALLEL_AUTO))
            && canRunParallel(db, clusterIds, iTarget);

    try {
      if (parallel) {
        return parallelExec(db, iTarget);
      }

      var prefetchPages = canScanStorageCluster(db, clusterIds);

      // WORK WITH ITERATOR
      db.setPrefetchRecords(prefetchPages);
      try {
        return serialIterator(iTarget);
      } finally {
        db.setPrefetchRecords(false);
      }

    } finally {
      context.setVariable(
          "fetchingFromTargetElapsed", (System.currentTimeMillis() - startFetching));
    }
  }

  private boolean canRunParallel(DatabaseSessionInternal db, int[] clusterIds,
      Iterator<? extends Identifiable> iTarget) {
    if (db.getTransactionInternal().isActive()) {
      return false;
    }

    if (iTarget instanceof RecordIteratorClusters) {
      if (clusterIds.length > 1) {
        final var totalRecords = db.countClusterElements(clusterIds);
        if (totalRecords > db.getConfiguration()
            .getValueAsLong(GlobalConfiguration.QUERY_PARALLEL_MINIMUM_RECORDS)) {
          // ACTIVATE PARALLEL
          LogManager.instance()
              .debug(
                  this,
                  "Activated parallel query. clusterIds=%d, totalRecords=%d",
                  clusterIds.length,
                  totalRecords);
          return true;
        }
      }
    }
    return false;
  }

  private boolean canScanStorageCluster(DatabaseSessionInternal db, final int[] clusterIds) {
    if (clusterIds != null && request.isIdempotent() && !db.getTransactionInternal().isActive()) {
      final var schema = db.getMetadata().getImmutableSchemaSnapshot();
      for (var clusterId : clusterIds) {
        final var cls = (SchemaImmutableClass) schema.getClassByClusterId(
            clusterId);
        if (cls != null) {
          if (cls.isRestricted() || cls.isUser() || cls.isRole()) {
            return false;
          }
        }
      }
      return true;
    }
    return false;
  }

  private boolean serialIterator(Iterator<? extends Identifiable> iTarget) {
    // BROWSE, UNMARSHALL AND FILTER ALL THE RECORDS ON CURRENT THREAD
    while (iTarget.hasNext()) {
      final var next = iTarget.next();
      if (!executeSearchRecord(next, context, false)) {

        return false;
      }
    }
    return true;
  }

  private boolean parseParallel(String w) {
    return w.equals(KEYWORD_PARALLEL);
  }

  private boolean parallelExec(DatabaseSessionInternal db,
      final Iterator<? extends Identifiable> iTarget) {
    final var result = (LegacyResultSet) getResultInstance();

    // BROWSE ALL THE RECORDS ON CURRENT THREAD BUT DELEGATE UNMARSHALLING AND FILTER TO A THREAD
    // POOL
    if (limit > -1) {
      if (result != null) {
        result.setLimit(limit);
      }
    }

    final var res = execParallelWithPool((RecordIteratorClusters) iTarget, db);

    if (LogManager.instance().isDebugEnabled()) {
      LogManager.instance().debug(this, "Parallel query '%s' completed", parserText);
    }

    return res;
  }

  private boolean execParallelWithPool(
      final RecordIteratorClusters iTarget, final DatabaseSessionInternal session) {
    final var clusterIds = iTarget.getClusterIds();

    // CREATE ONE THREAD PER CLUSTER
    final var jobNumbers = clusterIds.length;
    final List<Future<?>> jobs = new ArrayList<Future<?>>();

    LogManager.instance()
        .debug(
            this,
            "Executing parallel query with strategy executors. clusterIds=%d, jobs=%d",
            clusterIds.length,
            jobNumbers);

    final var results = new boolean[jobNumbers];
    final var contexts = new CommandContext[jobNumbers];

    final var exceptions = new RuntimeException[jobNumbers];

    parallelRunning = true;

    final var runningJobs = new AtomicInteger(jobNumbers);

    for (var i = 0; i < jobNumbers; ++i) {
      final var current = i;

      final Runnable job =
          () -> {
            try {
              DatabaseSessionInternal localDatabase = null;
              try {
                exceptions[current] = null;
                results[current] = true;

                final var threadContext = context.copy();
                contexts[current] = threadContext;

                localDatabase = session.copy();

                localDatabase.activateOnCurrentThread();
                threadContext.setDatabaseSession(localDatabase);

                // CREATE A SNAPSHOT TO AVOID DEADLOCKS
                session.getMetadata().getSchema().makeSnapshot();
                scanClusterWithIterator(
                    localDatabase, threadContext, clusterIds[current], current, results);
              } catch (RuntimeException t) {
                exceptions[current] = t;
              } finally {
                runningJobs.decrementAndGet();
                resultQueue.offer(PARALLEL_END_EXECUTION_THREAD);

                if (localDatabase != null) {
                  localDatabase.close();
                }
              }
            } catch (Exception e) {
              if (exceptions[current] == null) {
                exceptions[current] = new RuntimeException(e);
              }

              LogManager.instance().error(this, "Error during command execution", e);
            }
          };

      jobs.add(session.getSharedContext().getYouTrackDB().execute(job));
    }

    final var maxQueueSize =
        session.getConfiguration()
            .getValueAsInteger(GlobalConfiguration.QUERY_PARALLEL_RESULT_QUEUE_SIZE)
            - 1;

    var cancelQuery = false;
    var tipProvided = false;
    while (runningJobs.get() > 0 || !resultQueue.isEmpty()) {
      try {
        final var result = resultQueue.take();

        final var qSize = resultQueue.size();

        if (!tipProvided && qSize >= maxQueueSize) {
          LogManager.instance()
              .debug(
                  this,
                  "Parallel query '%s' has result queue full (size=%d), this could reduce"
                      + " concurrency level. Consider increasing queue size with setting:"
                      + " %s=<size>",
                  parserText,
                  maxQueueSize + 1,
                  GlobalConfiguration.QUERY_PARALLEL_RESULT_QUEUE_SIZE.getKey());
          tipProvided = true;
        }

        if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
          throw new InterruptedException("Operation has been interrupted");
        }

        if (result != PARALLEL_END_EXECUTION_THREAD) {

          if (!handleResult(result.record, result.context)) {
            // STOP EXECUTORS
            parallelRunning = false;
            break;
          }
        }

      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
        cancelQuery = true;
        break;
      }
    }

    parallelRunning = false;

    if (cancelQuery) {
      // CANCEL ALL THE RUNNING JOBS
      for (var i = 0; i < jobs.size(); ++i) {
        jobs.get(i).cancel(true);
      }
    } else {
      // JOIN ALL THE JOBS
      for (var i = 0; i < jobs.size(); ++i) {
        try {
          jobs.get(i).get();
          context.merge(contexts[i]);
        } catch (InterruptedException ignore) {
          break;
        } catch (final ExecutionException e) {
          LogManager.instance().error(this, "Error on executing parallel query", e);
          throw BaseException.wrapException(
              new CommandExecutionException(session, "Error on executing parallel query"), e,
              session);
        }
      }
    }

    // CHECK FOR ANY EXCEPTION
    for (var i = 0; i < jobNumbers; ++i) {
      if (exceptions[i] != null) {
        throw exceptions[i];
      }
    }

    for (var i = 0; i < jobNumbers; ++i) {
      if (!results[i]) {
        return false;
      }
    }
    return true;
  }

  private void scanClusterWithIterator(
      final DatabaseSessionInternal localDatabase,
      final CommandContext iContext,
      final int iClusterId,
      final int current,
      final boolean[] results) {
    final var it = new RecordIteratorCluster<>(localDatabase, iClusterId, true);

    while (it.hasNext()) {
      final var next = it.next();

      if (!executeSearchRecord(next, iContext, false)) {
        results[current] = false;
        break;
      }

      if (parallel && !parallelRunning)
      // EXECUTION ENDED
      {
        break;
      }
    }
  }

  private int getQueryFetchLimit() {
    final int sqlLimit;
    final int requestLimit;

    if (limit > -1) {
      sqlLimit = limit;
    } else {
      sqlLimit = -1;
    }

    if (request.getLimit() > -1) {
      requestLimit = request.getLimit();
    } else {
      requestLimit = -1;
    }

    if (sqlLimit == -1) {
      return requestLimit;
    }

    if (requestLimit == -1) {
      return sqlLimit;
    }

    return Math.min(sqlLimit, requestLimit);
  }

  private Stream<RawPair<Object, RID>> tryGetOptimizedSortStream(
      final SchemaClassInternal iSchemaClass,
      DatabaseSessionInternal session) {
    if (orderedFields.size() == 0) {
      return null;
    } else {
      return getOptimizedSortStream(iSchemaClass, session);
    }
  }

  private boolean tryOptimizeSort(DatabaseSessionInternal session,
      final SchemaClassInternal iSchemaClass) {
    if (orderedFields.size() == 0) {
      return false;
    } else {
      return optimizeSort(iSchemaClass, session);
    }
  }

  private boolean searchForSubclassIndexes(
      DatabaseSessionInternal db, final SchemaClass iSchemaClass) {
    var subclasses = iSchemaClass.getSubclasses();
    if (subclasses.size() == 0) {
      return false;
    }

    final var order = new SQLOrderBy();
    order.setItems(new ArrayList<>());
    if (this.orderedFields != null) {
      for (var pair : this.orderedFields) {
        var item = new SQLOrderByItem();
        item.setRecordAttr(pair.getKey());
        if (pair.getValue() == null) {
          item.setType(SQLOrderByItem.ASC);
        } else {
          item.setType(
              pair.getValue().toUpperCase(Locale.ENGLISH).equals("DESC")
                  ? SQLOrderByItem.DESC
                  : SQLOrderByItem.ASC);
        }
        order.getItems().add(item);
      }
    }
    var cursor = new SortedMultiIterator<>(db, order);
    var fullySorted = true;

    if (!iSchemaClass.isAbstract()) {
      var parentClassIterator =
          (Iterator<Identifiable>) searchInClasses(db, iSchemaClass, false, true);
      if (parentClassIterator.hasNext()) {
        cursor.add(parentClassIterator);
        fullySorted = false;
      }
    }

    if (uniqueResult != null) {
      uniqueResult.clear();
    }

    var attempted = 0;
    for (var subclass : subclasses) {
      var substreams = getIndexCursors(db,
          (SchemaClassInternal) subclass);
      fullySorted = fullySorted && fullySortedByIndex;
      if (substreams == null || substreams.isEmpty()) {
        if (attempted > 0) {
//          revertSubclassesProfiler(context, attempted);
        }
        return false;
      }
      for (var c : substreams) {
        if (!fullySortedByIndex) {
          // TODO sort every iterator
        }
        attempted++;
        cursor.add(c.map((pair) -> (Identifiable) pair.second).iterator());
      }
    }
    fullySortedByIndex = fullySorted;

    uniqueResult = new ConcurrentHashMap<RID, RID>();

    fetchFromTarget(db, cursor);

    if (uniqueResult != null) {
      uniqueResult.clear();
    }
    uniqueResult = null;

    return true;
  }

  @SuppressWarnings("rawtypes")
  private List<Stream<RawPair<Object, RID>>> getIndexCursors(
      DatabaseSessionInternal session, final SchemaClassInternal iSchemaClass) {
    // Leaving this in for reference, for the moment.
    // This should not be necessary as searchInClasses() does a security check and when the record
    // iterator
    // calls SchemaClassImpl.readableClusters(), it too filters out clusters based on the class's
    // security permissions.
    // This throws an unnecessary exception that potentially prevents using an index and prevents
    // filtering later.
    //    database.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ,
    // iSchemaClass.getName().toLowerCase(Locale.ENGLISH));

    // fetch all possible variants of subqueries that can be used in indexes.
    if (compiledFilter == null) {
      var stream = tryGetOptimizedSortStream(iSchemaClass, session);
      if (stream == null) {
        return null;
      }
      List<Stream<RawPair<Object, RID>>> result = new ArrayList<>();
      result.add(stream);
      return result;
    }

    // the main condition is a set of sub-conditions separated by OR operators
    final var conditionHierarchy =
        filterAnalyzer.analyzeMainCondition(
            compiledFilter.getRootCondition(), iSchemaClass, context);
    if (conditionHierarchy == null) {
      return null;
    }

    List<Stream<RawPair<Object, RID>>> cursors = new ArrayList<>();

    var indexIsUsedInOrderBy = false;
    List<IndexUsageLog> indexUseAttempts = new ArrayList<IndexUsageLog>();
    // try {

    IndexSearchResult lastSearchResult = null;
    for (var indexSearchResults : conditionHierarchy) {
      // go through all variants to choose which one can be used for index search.
      var indexUsed = false;
      for (final var searchResult : indexSearchResults) {
        lastSearchResult = searchResult;
        final var involvedIndexes =
            FilterAnalyzer.getInvolvedIndexes(session, iSchemaClass, searchResult);

        Collections.sort(involvedIndexes, new IndexComparator());

        // go through all possible index for given set of fields.
        for (final var index : involvedIndexes) {

          final var indexDefinition = index.getDefinition();

          if (searchResult.containsNullValues && indexDefinition.isNullValuesIgnored()) {
            continue;
          }

          final var operator = searchResult.lastOperator;

          // we need to test that last field in query subset and field in index that has the same
          // position
          // are equals.
          if (!IndexSearchResult.isIndexEqualityOperator(operator)) {
            final var lastFiled =
                searchResult.lastField.getItemName(searchResult.lastField.getItemCount() - 1);
            final var relatedIndexField =
                indexDefinition.getFields().get(searchResult.fieldValuePairs.size());
            if (!lastFiled.equals(relatedIndexField)) {
              continue;
            }
          }

          final var searchResultFieldsCount = searchResult.fields().size();
          final List<Object> keyParams = new ArrayList<Object>(searchResultFieldsCount);
          // We get only subset contained in processed sub query.
          for (final var fieldName :
              indexDefinition.getFields().subList(0, searchResultFieldsCount)) {
            final var fieldValue = searchResult.fieldValuePairs.get(fieldName);
            if (fieldValue instanceof SQLQuery<?>) {
              return null;
            }

            if (fieldValue != null) {
              keyParams.add(fieldValue);
            } else {
              if (searchResult.lastValue instanceof SQLQuery<?>) {
                return null;
              }

              keyParams.add(searchResult.lastValue);
            }
          }

          metricRecorder.recordInvolvedIndexesMetric(index);

          Stream<RawPair<Object, RID>> cursor;
          indexIsUsedInOrderBy =
              orderByOptimizer.canBeUsedByOrderBy(index, orderedFields)
                  && !(index instanceof ChainedIndexProxy);
          try {
            var ascSortOrder =
                !indexIsUsedInOrderBy || orderedFields.get(0).getValue().equals(KEYWORD_ASC);

            if (indexIsUsedInOrderBy) {
              fullySortedByIndex =
                  expandTarget == null
                      && indexDefinition.getFields().size() >= orderedFields.size()
                      && conditionHierarchy.size() == 1;
            }

            context.setVariable("$limit", limit);

            cursor = operator.executeIndexQuery(context, index, keyParams, ascSortOrder);

          } catch (IndexEngineException e) {
            throw e;
          } catch (Exception e) {
            LogManager.instance()
                .error(
                    this,
                    "Error on using index %s in query '%s'. Probably you need to rebuild indexes."
                        + " Now executing query using cluster scan",
                    e,
                    index.getName(),
                    request != null && request.getText() != null ? request.getText() : "");

            fullySortedByIndex = false;
            cursors.clear();
            return null;
          }

          if (cursor == null) {
            continue;
          }

          cursors.add(cursor);
          indexUseAttempts.add(new IndexUsageLog(index, keyParams, indexDefinition));
          indexUsed = true;
          break;
        }
        if (indexUsed) {
          break;
        }
      }
      if (!indexUsed) {
        var stream = tryGetOptimizedSortStream(iSchemaClass, session);
        if (stream == null) {
          return null;
        }
        List<Stream<RawPair<Object, RID>>> result = new ArrayList<>();
        result.add(stream);
        return result;
      }
    }

    if (cursors.size() == 0 || lastSearchResult == null) {
      return null;
    }

    metricRecorder.recordOrderByOptimizationMetric(indexIsUsedInOrderBy, this.fullySortedByIndex);

    indexUseAttempts.clear();

    return cursors;
  }

  @SuppressWarnings("rawtypes")
  private boolean searchForIndexes(DatabaseSessionInternal session,
      final SchemaClassInternal iSchemaClass) {
    if (uniqueResult != null) {
      uniqueResult.clear();
    }

    session.checkSecurity(
        Rule.ResourceGeneric.CLASS,
        Role.PERMISSION_READ,
        iSchemaClass.getName().toLowerCase(Locale.ENGLISH));

    // fetch all possible variants of subqueries that can be used in indexes.
    if (compiledFilter == null) {
      return tryOptimizeSort(session, iSchemaClass);
    }

    // try indexed functions
    var fetchedFromFunction = tryIndexedFunctions(session, iSchemaClass);
    if (fetchedFromFunction != null) {
      fetchFromTarget(session, fetchedFromFunction);
      return true;
    }

    // the main condition is a set of sub-conditions separated by OR operators
    final var conditionHierarchy =
        filterAnalyzer.analyzeMainCondition(
            compiledFilter.getRootCondition(), iSchemaClass, context);
    if (conditionHierarchy == null) {
      return false;
    }

    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();

    var indexIsUsedInOrderBy = false;
    List<IndexUsageLog> indexUseAttempts = new ArrayList<IndexUsageLog>();
    try {

      IndexSearchResult lastSearchResult = null;
      for (var indexSearchResults : conditionHierarchy) {
        // go through all variants to choose which one can be used for index search.
        var indexUsed = false;
        for (final var searchResult : indexSearchResults) {
          lastSearchResult = searchResult;
          final var involvedIndexes =
              FilterAnalyzer.getInvolvedIndexes(session, iSchemaClass, searchResult);

          Collections.sort(involvedIndexes, new IndexComparator());

          // go through all possible index for given set of fields.
          for (final var index : involvedIndexes) {
            final var indexDefinition = index.getDefinition();

            if (searchResult.containsNullValues && indexDefinition.isNullValuesIgnored()) {
              continue;
            }

            final var operator = searchResult.lastOperator;

            // we need to test that last field in query subset and field in index that has the same
            // position
            // are equals.
            if (!IndexSearchResult.isIndexEqualityOperator(operator)) {
              final var lastFiled =
                  searchResult.lastField.getItemName(searchResult.lastField.getItemCount() - 1);
              final var relatedIndexField =
                  indexDefinition.getFields().get(searchResult.fieldValuePairs.size());
              if (!lastFiled.equals(relatedIndexField)) {
                continue;
              }
            }

            final var searchResultFieldsCount = searchResult.fields().size();
            final List<Object> keyParams = new ArrayList<Object>(searchResultFieldsCount);
            // We get only subset contained in processed sub query.
            for (final var fieldName :
                indexDefinition.getFields().subList(0, searchResultFieldsCount)) {
              final var fieldValue = searchResult.fieldValuePairs.get(fieldName);
              if (fieldValue instanceof SQLQuery<?>) {
                return false;
              }

              if (fieldValue != null) {
                keyParams.add(fieldValue);
              } else {
                if (searchResult.lastValue instanceof SQLQuery<?>) {
                  return false;
                }

                keyParams.add(searchResult.lastValue);
              }
            }

            Stream<RawPair<Object, RID>> stream;
            indexIsUsedInOrderBy =
                orderByOptimizer.canBeUsedByOrderBy(index, orderedFields)
                    && !(index instanceof ChainedIndexProxy);
            try {
              var ascSortOrder =
                  !indexIsUsedInOrderBy || orderedFields.get(0).getValue().equals(KEYWORD_ASC);

              if (indexIsUsedInOrderBy) {
                fullySortedByIndex =
                    expandTarget == null
                        && indexDefinition.getFields().size() >= orderedFields.size()
                        && conditionHierarchy.size() == 1;
              }

              context.setVariable("$limit", limit);

              stream = operator.executeIndexQuery(context, index, keyParams, ascSortOrder);
              if (stream != null) {
                metricRecorder.recordInvolvedIndexesMetric(index);
              }

            } catch (IndexEngineException e) {
              throw e;
            } catch (Exception e) {
              LogManager.instance()
                  .error(
                      this,
                      "Error on using index %s in query '%s'. Probably you need to rebuild indexes."
                          + " Now executing query using cluster scan",
                      e,
                      index.getName(),
                      request != null && request.getText() != null ? request.getText() : "");

              fullySortedByIndex = false;
              streams.clear();
              return false;
            }

            if (stream == null) {
              continue;
            }

            streams.add(stream);
            indexUseAttempts.add(new IndexUsageLog(index, keyParams, indexDefinition));
            indexUsed = true;
            break;
          }
          if (indexUsed) {
            break;
          }
        }
        if (!indexUsed) {
          return tryOptimizeSort(session, iSchemaClass);
        }
      }

      if (streams.size() == 0 || lastSearchResult == null) {
        return false;
      }

      if (streams.size() == 1 && canOptimize(conditionHierarchy)) {
        filterOptimizer.optimize(compiledFilter, lastSearchResult);
      }

      uniqueResult = new ConcurrentHashMap<RID, RID>();

      if (streams.size() == 1
          && (compiledFilter == null || compiledFilter.getRootCondition() == null)
          && groupByFields == null
          && projections != null
          && projections.size() == 1) {
        // OPTIMIZATION: ONE INDEX USED WITH JUST ONE CONDITION: REMOVE THE FILTER
        final var entry = projections.entrySet().iterator().next();

        if (entry.getValue() instanceof SQLFunctionRuntime rf) {
          if (rf.function instanceof SQLFunctionCount
              && rf.configuredParameters.length == 1
              && "*".equals(rf.configuredParameters[0])) {

            final var restrictedClasses = isUsingRestrictedClasses(session);

            if (!restrictedClasses) {
              final Iterator cursor = streams.get(0).iterator();
              long count = 0;
              if (cursor instanceof Sizeable) {
                count = ((Sizeable) cursor).size();
              } else {
                while (cursor.hasNext()) {
                  cursor.next();
                  count++;
                }
              }

              if (tempResult == null) {
                tempResult = new ArrayList<>();
              }

              var t = session.newEmbeddedEntity();
              t.setProperty(entry.getKey(), count);
              ((Collection<Identifiable>) tempResult)
                  .add(t);
              return true;
            }
          }
        }
      }

      for (var stream : streams) {
        if (!fetchValuesFromIndexStream(session, stream)) {
          break;
        }
      }
      uniqueResult.clear();
      uniqueResult = null;

      metricRecorder.recordOrderByOptimizationMetric(indexIsUsedInOrderBy, this.fullySortedByIndex);

      indexUseAttempts.clear();
      return true;
    } finally {
      for (IndexUsageLog wastedIndexUsage : indexUseAttempts) {
//        revertProfiler(
//            context,
//            wastedIndexUsage.index,
//            wastedIndexUsage.keyParams,
//            wastedIndexUsage.indexDefinition);
      }
    }
  }

  private Iterator<Identifiable> tryIndexedFunctions(DatabaseSessionInternal db,
      SchemaClass iSchemaClass) {
    // TODO profiler
    if (this.preParsedStatement == null) {
      return null;
    }
    var where = ((SQLSelectStatement) this.preParsedStatement).getWhereClause();
    if (where == null) {
      return null;
    }
    var conditions =
        where.getIndexedFunctionConditions(iSchemaClass, db);

    var lastEstimation = Long.MAX_VALUE;
    SQLBinaryCondition bestCondition = null;
    if (conditions == null) {
      return null;
    }
    for (var condition : conditions) {
      var estimation =
          condition.estimateIndexed(
              ((SQLSelectStatement) this.preParsedStatement).getTarget(), getContext());
      if (estimation > -1 && estimation < lastEstimation) {
        lastEstimation = estimation;
        bestCondition = condition;
      }
    }

    if (bestCondition == null) {
      return null;
    }
    var result =
        bestCondition.executeIndexedFunction(
            ((SQLSelectStatement) this.preParsedStatement).getTarget(), getContext());
    if (result == null) {
      return null;
    }
    return result.iterator();
  }

  private boolean canOptimize(List<List<IndexSearchResult>> conditionHierarchy) {
    if (conditionHierarchy.size() > 1) {
      return false;
    }
    for (var subCoditions : conditionHierarchy) {
      if (subCoditions.size() > 1) {
        return false;
      }
    }
    return true;
  }

  /**
   * Use index to order documents by provided fields.
   *
   * @param iSchemaClass where search for indexes for optimization.
   * @param session
   * @return true if execution was optimized
   */
  private boolean optimizeSort(SchemaClassInternal iSchemaClass, DatabaseSessionInternal session) {
    var stream = getOptimizedSortStream(iSchemaClass, session);
    if (stream != null) {
      fetchValuesFromIndexStream(session, stream);
      return true;
    }
    return false;
  }

  private Stream<RawPair<Object, RID>> getOptimizedSortStream(SchemaClassInternal iSchemaClass,
      DatabaseSessionInternal session) {
    final List<String> fieldNames = new ArrayList<>();

    for (var pair : orderedFields) {
      fieldNames.add(pair.getKey());
    }

    final var indexes = iSchemaClass.getInvolvedIndexesInternal(session, fieldNames);

    for (var index : indexes) {
      if (orderByOptimizer.canBeUsedByOrderBy(index, orderedFields)) {

        final var ascSortOrder = orderedFields.get(0).getValue().equals(KEYWORD_ASC);

        final List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();

        Stream<RawPair<Object, RID>> stream = null;

        if (ascSortOrder) {
          stream = index.stream(session);
        } else {
          stream = index.descStream(session);
        }

        if (stream != null) {
          streams.add(stream);
        }

        if (!index.getDefinition().isNullValuesIgnored()) {
          final var nullRids = index
              .getRids(session, null);
          streams.add(nullRids.map((rid) -> new RawPair<>(null, rid)));
        }

        fullySortedByIndex = true;

        if (context.isRecordingMetrics()) {
          context.setVariable("indexIsUsedInOrderBy", true);
          context.setVariable("fullySortedByIndex", fullySortedByIndex);

          var idxNames = (Set<String>) context.getVariable("involvedIndexes");
          if (idxNames == null) {
            idxNames = new HashSet<String>();
            context.setVariable("involvedIndexes", idxNames);
          }

          idxNames.add(index.getName());
        }

        if (streams.isEmpty()) {
          return Stream.empty();
        }

        if (streams.size() == 1) {
          return streams.get(0);
        }

        var resultStream = streams.get(0);
        for (var i = 1; i < streams.size(); i++) {
          resultStream = Stream.concat(resultStream, streams.get(i));
        }

        return resultStream;
      }
    }

    metricRecorder.recordOrderByOptimizationMetric(false, this.fullySortedByIndex);
    return null;
  }

  private boolean fetchValuesFromIndexStream(DatabaseSessionInternal db,
      final Stream<RawPair<Object, RID>> stream) {
    return fetchFromTarget(db, stream.map((pair) -> pair.second).iterator());
  }

  private void fetchEntriesFromIndexStream(DatabaseSessionInternal db,
      final Stream<RawPair<Object, RID>> stream) {
    throw new UnsupportedOperationException();
  }

  private boolean isRidOnlySort() {
    if (parsedTarget.getTargetClasses() != null
        && this.orderedFields.size() == 1
        && this.orderedFields.get(0).getKey().toLowerCase(Locale.ENGLISH).equals("@rid")) {
      return this.target != null && target instanceof RecordIteratorClass;
    }
    return false;
  }

  private void applyOrderBy(boolean clearOrderedFields) {
    if (orderedFields.isEmpty() || fullySortedByIndex || isRidOnlySort()) {
      return;
    }

    final var startOrderBy = System.currentTimeMillis();
    try {
      if (tempResult instanceof MultiCollectionIterator) {
        final List<Identifiable> list = new ArrayList<Identifiable>();
        for (var o : tempResult) {
          list.add(o);
        }
        tempResult = list;
      }
      tempResult = applySort((List<Identifiable>) tempResult, orderedFields, context);
      if (clearOrderedFields) {
        orderedFields.clear();
      }
    } finally {
      metricRecorder.orderByElapsed(startOrderBy);
    }
  }

  private Iterable<Identifiable> applySort(
      List<Identifiable> iCollection,
      List<Pair<String, String>> iOrderFields,
      CommandContext iContext) {

    EntityHelper.sort(iCollection, iOrderFields, iContext);
    return iCollection;
  }

  /**
   * Extract the content of collections and/or links and put it as result
   */
  private void applyExpand(DatabaseSessionInternal session) {
    if (expandTarget == null) {
      return;
    }

    final var startExpand = System.currentTimeMillis();
    try {

      if (tempResult == null) {
        tempResult = new ArrayList<Identifiable>();
        if (expandTarget instanceof SQLFilterItemVariable) {
          var r = ((SQLFilterItemVariable) expandTarget).getValue(null, null, context);
          if (r != null) {
            if (r instanceof Identifiable) {
              ((Collection<Identifiable>) tempResult).add((Identifiable) r);
            } else if (r instanceof Iterator || MultiValue.isMultiValue(r)) {
              for (var o : MultiValue.getMultiValueIterable(r)) {
                ((Collection<Identifiable>) tempResult).add((Identifiable) o);
              }
            }
          }
        } else if (expandTarget instanceof SQLFunctionRuntime
            && !hasFieldItemParams((SQLFunctionRuntime) expandTarget)) {
          if (((SQLFunctionRuntime) expandTarget).aggregateResults()) {
            throw new CommandExecutionException(session,
                "Unsupported operation: aggregate function in expand(" + expandTarget + ")");
          } else {
            var r = ((SQLFunctionRuntime) expandTarget).execute(null, null, null, context);
            if (r instanceof Identifiable) {
              ((Collection<Identifiable>) tempResult).add((Identifiable) r);
            } else if (r instanceof Iterator || MultiValue.isMultiValue(r)) {
              for (var o : MultiValue.getMultiValueIterable(r)) {
                ((Collection<Identifiable>) tempResult).add((Identifiable) o);
              }
            }
          }
        }
      } else {
        if (tempResult == null) {
          tempResult = new ArrayList<Identifiable>();
        }
        final var finalResult =
            new MultiCollectionIterator<Identifiable>();

        if (orderedFields == null || orderedFields.size() == 0) {
          // expand is applied before sorting, so limiting the result set here would give wrong
          // results
          var iteratorLimit = 0;
          if (limit < 0) {
            iteratorLimit = -1;
          } else {
            iteratorLimit += limit;
          }
          finalResult.setLimit(iteratorLimit);
          finalResult.setSkip(skip);
        }

        for (var id : tempResult) {
          Object fieldValue;
          if (expandTarget instanceof SQLFilterItem) {
            fieldValue = ((SQLFilterItem) expandTarget).getValue(id.getRecord(session), null,
                context);
          } else if (expandTarget instanceof SQLFunctionRuntime) {
            fieldValue = ((SQLFunctionRuntime) expandTarget).getResult(
                context.getDatabaseSession());
          } else {
            fieldValue = expandTarget.toString();
          }

          if (fieldValue != null) {
            if (fieldValue instanceof Iterable && !(fieldValue instanceof Identifiable)) {
              fieldValue = ((Iterable) fieldValue).iterator();
            }
            if (fieldValue instanceof EntityImpl) {
              var partial = new ArrayList<EntityImpl>();
              partial.add((EntityImpl) fieldValue);
              finalResult.add(partial);
            } else if (fieldValue instanceof Collection<?>
                || fieldValue.getClass().isArray()
                || fieldValue instanceof Iterator<?>
                || fieldValue instanceof Identifiable
                || fieldValue instanceof RidBag) {
              finalResult.add(fieldValue);
            } else if (fieldValue instanceof Map<?, ?>) {
              finalResult.add(((Map<?, Identifiable>) fieldValue).values());
            }
          }
        }
        tempResult = finalResult;
      }
    } finally {
      context.setVariable("expandElapsed", (System.currentTimeMillis() - startExpand));
    }
  }

  private boolean hasFieldItemParams(SQLFunctionRuntime expandTarget) {
    var params = expandTarget.getConfiguredParameters();
    if (params == null) {
      return false;
    }
    for (var o : params) {
      if (o instanceof SQLFilterItemField) {
        return true;
      }
    }
    return false;
  }

  private void searchInIndex(DatabaseSessionInternal session) {
    IndexAbstract.manualIndexesWarning(session.getDatabaseName());

    final var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, parsedTarget.getTargetIndex());

    if (index == null) {
      throw new CommandExecutionException(session,
          "Target index '" + parsedTarget.getTargetIndex() + "' not found");
    }

    var ascOrder = true;
    if (!orderedFields.isEmpty()) {
      if (orderedFields.size() != 1) {
        throw new CommandExecutionException(session, "Index can be ordered only by key field");
      }

      final var fieldName = orderedFields.get(0).getKey();
      if (!fieldName.equalsIgnoreCase("key")) {
        throw new CommandExecutionException(session, "Index can be ordered only by key field");
      }

      final var order = orderedFields.get(0).getValue();
      ascOrder = order.equalsIgnoreCase(KEYWORD_ASC);
    }

    // nothing was added yet, so index definition for manual index was not calculated
    if (index.getDefinition() == null) {
      return;
    }

    if (compiledFilter != null && compiledFilter.getRootCondition() != null) {
      if (!"KEY".equalsIgnoreCase(compiledFilter.getRootCondition().getLeft().toString())) {
        throw new CommandExecutionException(session,
            "'Key' field is required for queries against indexes");
      }

      final var indexOperator = compiledFilter.getRootCondition().getOperator();

      if (indexOperator instanceof QueryOperatorBetween) {
        final var values = (Object[]) compiledFilter.getRootCondition().getRight();

        try (var stream =
            index
                .streamEntriesBetween(session,
                    getIndexKey(session, index.getDefinition(), values[0], context),
                    true,
                    getIndexKey(session, index.getDefinition(), values[2], context),
                    true, ascOrder)) {
          fetchEntriesFromIndexStream(session, stream);
        }
      } else if (indexOperator instanceof QueryOperatorMajor) {
        final var value = compiledFilter.getRootCondition().getRight();

        try (var stream =
            index
                .streamEntriesMajor(session,
                    getIndexKey(session, index.getDefinition(), value, context),
                    false, ascOrder)) {
          fetchEntriesFromIndexStream(session, stream);
        }
      } else if (indexOperator instanceof QueryOperatorMajorEquals) {
        final var value = compiledFilter.getRootCondition().getRight();
        try (var stream =
            index
                .streamEntriesMajor(session,
                    getIndexKey(session, index.getDefinition(), value, context), true, ascOrder)) {
          fetchEntriesFromIndexStream(session, stream);
        }

      } else if (indexOperator instanceof QueryOperatorMinor) {
        final var value = compiledFilter.getRootCondition().getRight();

        try (var stream =
            index
                .streamEntriesMinor(session,
                    getIndexKey(session, index.getDefinition(), value, context),
                    false, ascOrder)) {
          fetchEntriesFromIndexStream(session, stream);
        }
      } else if (indexOperator instanceof QueryOperatorMinorEquals) {
        final var value = compiledFilter.getRootCondition().getRight();

        try (var stream =
            index
                .streamEntriesMinor(session,
                    getIndexKey(session, index.getDefinition(), value, context), true, ascOrder)) {
          fetchEntriesFromIndexStream(session, stream);
        }
      } else if (indexOperator instanceof QueryOperatorIn) {
        final var origValues = (List<Object>) compiledFilter.getRootCondition().getRight();
        final List<Object> values = new ArrayList<Object>(origValues.size());
        for (var val : origValues) {
          if (index.getDefinition() instanceof CompositeIndexDefinition) {
            throw new CommandExecutionException(session, "Operator IN not supported yet.");
          }

          val = getIndexKey(session, index.getDefinition(), val, context);
          values.add(val);
        }

        try (var stream =
            index.streamEntries(session, values, true)) {
          fetchEntriesFromIndexStream(session, stream);
        }
      } else {
        final var right = compiledFilter.getRootCondition().getRight();
        var keyValue = getIndexKey(session, index.getDefinition(), right, context);
        if (keyValue == null) {
          return;
        }

        final Stream<RID> res;
        if (index.getDefinition().getParamCount() == 1) {
          // CONVERT BEFORE SEARCH IF NEEDED
          final var type = index.getDefinition().getTypes()[0];
          keyValue = PropertyType.convert(session, keyValue, type.getDefaultJavaType());

          res = index.getRids(session, keyValue);
        } else {
          final var secondKey = getIndexKey(session, index.getDefinition(), right, context);
          if (keyValue instanceof CompositeKey
              && secondKey instanceof CompositeKey
              && ((CompositeKey) keyValue).getKeys().size()
              == index.getDefinition().getParamCount()
              && ((CompositeKey) secondKey).getKeys().size()
              == index.getDefinition().getParamCount()) {
            res = index.getRids(session, keyValue);
          } else {
            try (var stream =
                index
                    .streamEntriesBetween(session, keyValue, true, secondKey, true, true)) {
              fetchEntriesFromIndexStream(session, stream);
            }
            return;
          }
        }

        final var resultKey = keyValue;
        BreakingForEach.forEach(
            res,
            (rid, breaker) -> {
              final var record = createIndexEntryAsEntity(session, resultKey, rid);
              applyGroupBy(record, context);
              if (!handleResult(record, context)) {
                // LIMIT REACHED
                breaker.stop();
              }
            });
      }

    } else {
      if (isIndexSizeQuery(session)) {
        getProjectionGroup(null, context)
            .applyValue(projections.keySet().iterator().next(), index.size(session));
        return;
      }

      if (isIndexKeySizeQuery(session)) {
        getProjectionGroup(null, context)
            .applyValue(projections.keySet().iterator().next(), index.size(session));
        return;
      }

      if (index instanceof SharedResource) {
        ((SharedResource) index).acquireExclusiveLock();
      }

      try {

        // ADD ALL THE ITEMS AS RESULT
        if (ascOrder) {
          try (var stream = index.stream(session)) {
            fetchEntriesFromIndexStream(session, stream);
          }
          fetchNullKeyEntries(session, index);
        } else {

          try (var stream = index.descStream(session)) {
            fetchNullKeyEntries(session, index);
            fetchEntriesFromIndexStream(session, stream);
          }
        }
      } finally {
        if (index instanceof SharedResource) {
          ((SharedResource) index).releaseExclusiveLock();
        }
      }
    }
  }

  private void fetchNullKeyEntries(DatabaseSessionInternal db, Index index) {
    if (index.getDefinition().isNullValuesIgnored()) {
      return;
    }

    throw new UnsupportedOperationException();
  }

  private boolean isIndexSizeQuery(DatabaseSession session) {
    if (!(aggregate && projections.size() == 1)) {
      return false;
    }

    final var projection = projections.values().iterator().next();
    if (!(projection instanceof SQLFunctionRuntime f)) {
      return false;
    }

    return f.getRoot(session).equals(SQLFunctionCount.NAME)
        && ((f.configuredParameters == null || f.configuredParameters.length == 0)
        || (f.configuredParameters.length == 1 && f.configuredParameters[0].equals("*")));
  }

  private boolean isIndexKeySizeQuery(DatabaseSession session) {
    if (!(aggregate && projections.size() == 1)) {
      return false;
    }

    final var projection = projections.values().iterator().next();
    if (!(projection instanceof SQLFunctionRuntime f)) {
      return false;
    }

    if (!f.getRoot(session).equals(SQLFunctionCount.NAME)) {
      return false;
    }

    if (!(f.configuredParameters != null
        && f.configuredParameters.length == 1
        && f.configuredParameters[0] instanceof SQLFunctionRuntime fConfigured)) {
      return false;
    }

    if (!fConfigured.getRoot(session).equals(SQLFunctionDistinct.NAME)) {
      return false;
    }

    if (!(fConfigured.configuredParameters != null
        && fConfigured.configuredParameters.length == 1
        && fConfigured.configuredParameters[0] instanceof SQLFilterItemField field)) {
      return false;
    }

    return field.getRoot(session).equals("key");
  }

  private void handleNoTarget() {
    if (parsedTarget == null && expandTarget == null)
    // ONLY LET, APPLY TO THEM
    {
      throw new UnsupportedOperationException("Projections are not supported by old engine");
    }
  }

  private void handleGroupBy(@Nonnull final CommandContext iContext) {
    if (aggregate && tempResult == null) {

      final var startGroupBy = System.currentTimeMillis();
      try {

        tempResult = new ArrayList<Identifiable>();

        for (var g : groupedResult.entrySet()) {
          if (g.getKey() != null || (groupedResult.size() == 1 && groupByFields == null)) {
            throw new UnsupportedOperationException("Group by not supported by old engine");
          }
        }

      } finally {
        iContext.setVariable("groupByElapsed", (System.currentTimeMillis() - startGroupBy));
      }
    }
  }

  public void setProjections(final Map<String, Object> projections) {
    this.projections = projections;
  }

  public Map<String, String> getProjectionDefinition() {
    return projectionDefinition;
  }

  public void setProjectionDefinition(final Map<String, String> projectionDefinition) {
    this.projectionDefinition = projectionDefinition;
  }

  public void setOrderedFields(final List<Pair<String, String>> orderedFields) {
    this.orderedFields = orderedFields;
  }

  public void setGroupByFields(final List<String> groupByFields) {
    this.groupByFields = groupByFields;
  }

  public void setFetchLimit(final int fetchLimit) {
    this.fetchLimit = fetchLimit;
  }

  public void setFetchPlan(final String fetchPlan) {
    this.fetchPlan = fetchPlan;
  }

  public void setParallel(final boolean parallel) {
    this.parallel = parallel;
  }

  public void setNoCache(final boolean noCache) {
    this.noCache = noCache;
  }
}
