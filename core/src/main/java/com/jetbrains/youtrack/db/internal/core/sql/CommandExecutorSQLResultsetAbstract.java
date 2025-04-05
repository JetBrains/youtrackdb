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
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClassDescendentOrder;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClusters;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLTarget;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorEquals;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorNotEquals;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLAsynchQuery;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a TRAVERSE crossing records. Returns a List<Identifiable> containing all the traversed
 * records that match the WHERE condition.
 *
 * <p>SYNTAX: <code>TRAVERSE <field>* FROM <target> WHERE <condition></code>
 *
 * <p>In the command context you've access to the variable $depth containing the depth level from
 * the root node. This is useful to limit the traverse up to a level. For example to consider from
 * the first depth level (0 is root node) to the third use: <code> TRAVERSE children FROM #5:23
 * WHERE $depth BETWEEN 1 AND 3</code>. To filter traversed records use it combined with a SELECT
 * statement:
 *
 * <p><code>
 * SELECT FROM (TRAVERSE children FROM #5:23 WHERE $depth BETWEEN 1 AND 3) WHERE city.name = 'Rome'
 * </code>
 */
@SuppressWarnings("unchecked")
public abstract class CommandExecutorSQLResultsetAbstract extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest, Iterable<Identifiable>,
    IterableRecordSource {

  protected static final String KEYWORD_FROM_2FIND = " " + KEYWORD_FROM + " ";
  protected static final String KEYWORD_LET_2FIND = " " + KEYWORD_LET + " ";

  protected SQLAsynchQuery<EntityImpl> request;
  protected SQLTarget parsedTarget;
  protected SQLFilter compiledFilter;
  protected Map<String, Object> let = null;
  protected Iterator<? extends Identifiable> target;
  protected Iterable<Identifiable> tempResult;
  protected int resultCount;
  protected AtomicInteger serialTempRID = new AtomicInteger(0);
  protected int skip = 0;
  protected boolean lazyIteration = true;

  private static final class IndexValuesIterator implements Iterator<Identifiable> {

    private final Iterator<RID> indexValuesIterator;

    private IndexValuesIterator(String indexName, boolean ascOrder) {
      final DatabaseSessionInternal database = getDatabase();
      if (ascOrder) {
        indexValuesIterator =
            database
                .getMetadata()
                .getIndexManagerInternal()
                .getIndex(database, indexName)
                .getInternal()
                .stream(database)
                .map((pair) -> pair.second)
                .iterator();
      } else {
        indexValuesIterator =
            database
                .getMetadata()
                .getIndexManagerInternal()
                .getIndex(database, indexName)
                .getInternal()
                .descStream(database)
                .map((pair) -> pair.second)
                .iterator();
      }
    }

    @Override
    public boolean hasNext() {
      return indexValuesIterator.hasNext();
    }

    @Override
    public Identifiable next() {
      return indexValuesIterator.next();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Compile the filter conditions only the first time.
   */
  public CommandExecutorSQLResultsetAbstract parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    init(textRequest);

    if (iRequest instanceof SQLSynchQuery) {
      request = (SQLSynchQuery<EntityImpl>) iRequest;
    } else {
      if (iRequest instanceof SQLAsynchQuery) {
        request = (SQLAsynchQuery<EntityImpl>) iRequest;
      } else {
        // BUILD A QUERY OBJECT FROM THE COMMAND REQUEST
        request = new SQLSynchQuery<EntityImpl>(textRequest.getText());
        if (textRequest.getResultListener() != null) {
          request.setResultListener(textRequest.getResultListener());
        }
      }
    }
    return this;
  }

  @Override
  public boolean isIdempotent() {
    return true;
  }

  public boolean isLazyIteration() {
    return lazyIteration;
  }

  public void setLazyIteration(final boolean lazyIteration) {
    this.lazyIteration = lazyIteration;
  }

  @Override
  public CommandDistributedReplicateRequest.DISTRIBUTED_EXECUTION_MODE
  getDistributedExecutionMode() {
    return DISTRIBUTED_EXECUTION_MODE.REPLICATE;
  }

  @Override
  public DISTRIBUTED_RESULT_MGMT getDistributedResultManagement() {
    return DISTRIBUTED_RESULT_MGMT.MERGE;
  }

  /**
   * Assign the right TARGET if found.
   *
   * @param iArgs Parameters to bind
   * @return true if the target has been recognized, otherwise false
   */
  protected boolean assignTarget(final Map<Object, Object> iArgs) {
    parameters = iArgs;
    if (parsedTarget == null) {
      return true;
    }

    if (iArgs != null && iArgs.size() > 0 && compiledFilter != null) {
      compiledFilter.bindParameters(iArgs);
    }

    var db = getDatabase();
    if (target == null) {
      if (parsedTarget.getTargetClasses() != null) {
        searchInClasses();
      } else {
        if (parsedTarget.getTargetIndexValues() != null) {
          target =
              new IndexValuesIterator(
                  parsedTarget.getTargetIndexValues(), parsedTarget.isTargetIndexValuesAsc());
        } else {
          if (parsedTarget.getTargetClusters() != null) {
            searchInClusters();
          } else {
            if (parsedTarget.getTargetRecords() != null) {
              if (!lazyIteration && parsedTarget.getTargetQuery() != null) {
                // EXECUTE THE QUERY TO ALLOW DISTRIB EXECUTION
                target =
                    ((Iterable<? extends Identifiable>)
                        db
                            .command(new CommandSQL(parsedTarget.getTargetQuery()))
                            .execute(db, iArgs))
                        .iterator();
              } else {
                if (parsedTarget.getTargetRecords() instanceof IterableRecordSource) {
                  target =
                      ((IterableRecordSource) parsedTarget.getTargetRecords()).iterator(
                          getDatabase(), iArgs);
                } else {
                  target = parsedTarget.getTargetRecords().iterator();
                }
              }
            } else {
              if (parsedTarget.getTargetVariable() != null) {
                final Object var = getContext().getVariable(parsedTarget.getTargetVariable());
                if (var == null) {
                  target = Collections.EMPTY_LIST.iterator();
                  return true;
                } else {
                  if (var instanceof Identifiable) {
                    final ArrayList<Identifiable> list = new ArrayList<Identifiable>();
                    list.add((Identifiable) var);
                    target = list.iterator();
                  } else {
                    if (var instanceof Iterable<?>) {
                      target = ((Iterable<? extends Identifiable>) var).iterator();
                    }
                  }
                }
              } else {
                return false;
              }
            }
          }
        }
      }
    }

    return true;
  }

  protected Object getResultInstance() {
    if (request instanceof SQLSynchQuery) {
      return ((SQLSynchQuery<EntityImpl>) request).getResult();
    }

    return request.getResultListener().getResult();
  }

  protected Object getResult(DatabaseSessionInternal session) {
    try {
      if (tempResult != null) {
        int fetched = 0;

        for (Object d : tempResult) {
          if (d != null) {
            if (!(d instanceof Identifiable))
            // NON-DOCUMENT AS RESULT, COMES FROM EXPAND? CREATE A DOCUMENT AT THE FLY
            {
              d = new EntityImpl().field("value", d);
            } else {
              d = ((Identifiable) d).getRecord();
            }

            if (limit > -1 && fetched >= limit) {
              break;
            }

            if (!pushResult(session, d)) {
              break;
            }

            ++fetched;
          }
        }
      }

      return getResultInstance();
    } finally {
      request.getResultListener().end();
    }
  }

  protected boolean pushResult(DatabaseSessionInternal session, Object rec) {
    if (rec instanceof RecordAbstract record) {
      final DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
      if (db != null) {
        var cached = db.getLocalCache().findRecord(record.getIdentity());
        if (cached != record) {
          if (cached != null) {
            record.copyTo(cached);
            rec = cached;
          } else {
            db.getLocalCache().updateRecord(record);
          }
        }
      }
    }

    return request.getResultListener().result(session, rec);
  }

  protected boolean handleResult(final Identifiable iRecord, final CommandContext iContext) {
    if (iRecord != null) {
      resultCount++;

      Identifiable identifiable =
          iRecord instanceof DBRecord ? ((DBRecord) iRecord) : iRecord.getIdentity();

      // CALL THE LISTENER NOW
      if (identifiable != null && request.getResultListener() != null) {
        final boolean result = pushResult(iContext.getDatabase(), identifiable);
        if (!result) {
          return false;
        }
      }

      // BREAK THE EXECUTION
      return limit <= -1 || resultCount < limit;
    }
    return true;
  }

  protected void parseLet(DatabaseSessionInternal session) {
    let = new LinkedHashMap<String, Object>();

    boolean stop = false;
    while (!stop) {
      // PARSE THE KEY
      final String letName = parserNextWord(false);

      parserOptionalKeyword("=");

      parserNextWord(false, " =><,\r\n", true);

      // PARSE THE VALUE
      String letValueAsString = parserGetLastWord();
      final Object letValue;

      // TRY TO PARSE AS FUNCTION
      final Object func = SQLHelper.getFunction(session, parsedTarget, letValueAsString);
      if (func != null) {
        letValue = func;
      } else {
        if (letValueAsString.startsWith("(")) {
          letValue =
              new SQLSynchQuery<Object>(
                  letValueAsString.substring(1, letValueAsString.length() - 1));
        } else {
          letValue = letValueAsString;
        }
      }

      let.put(letName, letValue);
      stop = parserGetLastSeparator() == ' ';
    }
  }

  /**
   * Parses the limit keyword if found.
   *
   * @param w
   * @return the limit found as integer, or -1 if no limit is found. -1 means no limits.
   * @throws CommandSQLParsingException if no valid limit has been found
   */
  protected int parseLimit(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_LIMIT)) {
      return -1;
    }

    final String word = parserNextWord(true);

    try {
      limit = Integer.parseInt(word);
    } catch (NumberFormatException ignore) {
      throwParsingException(
          "Invalid LIMIT value setted to '"
              + word
              + "' but it should be a valid integer. Example: LIMIT 10");
    }

    if (limit == 0) {
      throwParsingException(
          "Invalid LIMIT value setted to ZERO. Use -1 to ignore the limit or use a positive number."
              + " Example: LIMIT 10");
    }

    return limit;
  }

  /**
   * Parses the skip keyword if found.
   *
   * @param w
   * @return the skip found as integer, or -1 if no skip is found. -1 means no skip.
   * @throws CommandSQLParsingException if no valid skip has been found
   */
  protected int parseSkip(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_SKIP) && !w.equals(KEYWORD_OFFSET)) {
      return -1;
    }

    final String word = parserNextWord(true);

    try {
      skip = Integer.parseInt(word);

    } catch (NumberFormatException ignore) {
      throwParsingException(
          "Invalid SKIP value setted to '"
              + word
              + "' but it should be a valid positive integer. Example: SKIP 10");
    }

    if (skip < 0) {
      throwParsingException(
          "Invalid SKIP value setted to the negative number '"
              + word
              + "'. Only positive numbers are valid. Example: SKIP 10");
    }

    return skip;
  }

  protected boolean filter(final DBRecord iRecord, final CommandContext iContext) {
    if (iRecord instanceof EntityImpl recordSchemaAware) {
      // CHECK THE TARGET CLASS
      Map<String, String> targetClasses = parsedTarget.getTargetClasses();
      // check only classes that specified in query will go to result set
      if ((targetClasses != null) && (!targetClasses.isEmpty())) {
        for (String targetClass : targetClasses.keySet()) {
          DatabaseSessionInternal database = getDatabase();
          if (!database
              .getMetadata()
              .getImmutableSchemaSnapshot()
              .getClass(targetClass)
              .isSuperClassOf(database,
                  EntityInternalUtils.getImmutableSchemaClass(recordSchemaAware))) {
            return false;
          }
        }
        iContext.updateMetric("documentAnalyzedCompatibleClass", +1);
      }
    }

    return evaluateRecord(iRecord, iContext);
  }

  protected boolean evaluateRecord(final DBRecord iRecord, final CommandContext iContext) {
    iContext.setVariable("current", iRecord);
    iContext.updateMetric("evaluated", +1);

    assignLetClauses(iContext.getDatabase(), iRecord);
    if (compiledFilter == null) {
      return true;
    }
    Boolean evaluate = (Boolean) compiledFilter.evaluate(iRecord, null, iContext);
    return evaluate != null && evaluate;
  }

  protected void assignLetClauses(DatabaseSession session, final DBRecord iRecord) {
    if (let != null && !let.isEmpty()) {
      // BIND CONTEXT VARIABLES
      for (Map.Entry<String, Object> entry : let.entrySet()) {
        String varName = entry.getKey();
        if (varName.startsWith("$")) {
          varName = varName.substring(1);
        }

        final Object letValue = entry.getValue();

        Object varValue;
        if (letValue instanceof SQLSynchQuery<?>) {
          final SQLSynchQuery<Object> subQuery = (SQLSynchQuery<Object>) letValue;
          subQuery.reset();
          subQuery.resetPagination();
          subQuery.getContext().setParent(context);
          subQuery.getContext().setVariable("parentQuery", this);
          subQuery.getContext().setVariable("current", iRecord);
          varValue = DatabaseRecordThreadLocal.instance().get().query(subQuery);
          if (varValue instanceof LegacyResultSet) {
            varValue = ((LegacyResultSet) varValue).copy();
          }

        } else {
          if (letValue instanceof SQLFunctionRuntime f) {
            if (f.getFunction().aggregateResults()) {
              f.execute(iRecord, iRecord, null, context);
              varValue = f.getFunction().getResult();
            } else {
              varValue = f.execute(iRecord, iRecord, null, context);
            }
          } else {
            if (letValue instanceof String) {
              SQLPredicate pred = new SQLPredicate(getContext(), ((String) letValue).trim());
              varValue = pred.evaluate(iRecord, (EntityImpl) iRecord, context);
            } else {
              varValue = letValue;
            }
          }
        }

        context.setVariable(varName, varValue);
      }
    }
  }

  protected void searchInClasses() {
    searchInClasses(true);
  }

  protected void searchInClasses(final boolean iAscendentOrder) {
    final String cls = parsedTarget.getTargetClasses().keySet().iterator().next();
    target =
        searchInClasses(
            getDatabase().getMetadata().getImmutableSchemaSnapshot().getClass(cls),
            true,
            iAscendentOrder);
  }

  protected Iterator<? extends Identifiable> searchInClasses(
      final SchemaClass iCls, final boolean iPolymorphic, final boolean iAscendentOrder) {

    final DatabaseSessionInternal database = getDatabase();
    database.checkSecurity(
        Rule.ResourceGeneric.CLASS,
        Role.PERMISSION_READ,
        iCls.getName().toLowerCase(Locale.ENGLISH));

    final RID[] range = getRange(database);
    if (iAscendentOrder) {
      return new RecordIteratorClass<DBRecord>(database, iCls.getName(), iPolymorphic, false)
          .setRange(range[0], range[1]);
    } else {
      return new RecordIteratorClassDescendentOrder<DBRecord>(
          database, database, iCls.getName(), iPolymorphic)
          .setRange(range[0], range[1]);
    }
  }

  protected boolean isUseCache() {
    return request.isUseCache();
  }

  protected void searchInClusters() {
    final DatabaseSessionInternal database = getDatabase();

    final IntOpenHashSet clusterIds = new IntOpenHashSet();
    for (String clusterName : parsedTarget.getTargetClusters().keySet()) {
      if (clusterName == null || clusterName.isEmpty()) {
        throw new CommandExecutionException("No cluster or schema class selected in query");
      }

      database.checkSecurity(
          Rule.ResourceGeneric.CLUSTER,
          Role.PERMISSION_READ,
          clusterName.toLowerCase(Locale.ENGLISH));

      if (Character.isDigit(clusterName.charAt(0))) {
        // GET THE CLUSTER NUMBER
        for (int clusterId : StringSerializerHelper.splitIntArray(clusterName)) {
          if (clusterId == -1) {
            throw new CommandExecutionException("Cluster '" + clusterName + "' not found");
          }

          clusterIds.add(clusterId);
        }
      } else {
        // GET THE CLUSTER NUMBER BY THE CLASS NAME
        final int clusterId = database.getClusterIdByName(clusterName.toLowerCase(Locale.ENGLISH));
        if (clusterId == -1) {
          throw new CommandExecutionException("Cluster '" + clusterName + "' not found");
        }

        clusterIds.add(clusterId);
      }
    }

    final RID[] range = getRange(database);
    target =
        new RecordIteratorClusters<>(database, clusterIds.toIntArray())
            .setRange(range[0], range[1]);
  }

  protected void applyLimitAndSkip() {
    if (tempResult != null && (limit > 0 || skip > 0)) {
      final List<Identifiable> newList = new ArrayList<Identifiable>();

      // APPLY LIMIT
      if (tempResult instanceof List<?>) {
        final List<Identifiable> t = (List<Identifiable>) tempResult;
        final int start = Math.min(skip, t.size());

        int tot = t.size();
        if (limit > -1) {
          tot = Math.min(limit + start, tot);
        }
        for (int i = start; i < tot; ++i) {
          newList.add(t.get(i));
        }

        t.clear();
        tempResult = newList;
      }
    }
  }

  /**
   * Optimizes the condition tree.
   */
  protected void optimize(DatabaseSession session) {
    if (compiledFilter != null) {
      optimizeBranch(session, null, compiledFilter.getRootCondition());
    }
  }

  /**
   * Check function arguments and pre calculate it if possible
   *
   * @param function
   * @return optimized function, same function if no change
   */
  protected Object optimizeFunction(SQLFunctionRuntime function) {
    // boolean precalculate = true;
    // for (int i = 0; i < function.configuredParameters.length; ++i) {
    // if (function.configuredParameters[i] instanceof SQLFilterItemField) {
    // precalculate = false;
    // } else if (function.configuredParameters[i] instanceof SQLFunctionRuntime) {
    // final Object res = optimizeFunction((SQLFunctionRuntime) function.configuredParameters[i]);
    // function.configuredParameters[i] = res;
    // if (res instanceof SQLFunctionRuntime || res instanceof SQLFilterItemField) {
    // // function might have been optimized but result is still not static
    // precalculate = false;
    // }
    // }
    // }
    //
    // if (precalculate) {
    // // all fields are static, we can calculate it only once.
    // return function.execute(null, null, null); // we can pass nulls here, they wont be used
    // } else {
    return function;
    // }
  }

  protected void optimizeBranch(
      DatabaseSession session, final SQLFilterCondition iParentCondition,
      SQLFilterCondition iCondition) {
    if (iCondition == null) {
      return;
    }

    Object left = iCondition.getLeft();

    if (left instanceof SQLFilterCondition) {
      // ANALYSE LEFT RECURSIVELY
      optimizeBranch(session, iCondition, (SQLFilterCondition) left);
    } else {
      if (left instanceof SQLFunctionRuntime) {
        left = optimizeFunction((SQLFunctionRuntime) left);
        iCondition.setLeft(left);
      }
    }

    Object right = iCondition.getRight();

    if (right instanceof SQLFilterCondition) {
      // ANALYSE RIGHT RECURSIVELY
      optimizeBranch(session, iCondition, (SQLFilterCondition) right);
    } else {
      if (right instanceof SQLFunctionRuntime) {
        right = optimizeFunction((SQLFunctionRuntime) right);
        iCondition.setRight(right);
      }
    }

    final QueryOperator oper = iCondition.getOperator();

    Object result = null;

    if (left instanceof SQLFilterItemField && right instanceof SQLFilterItemField) {
      if (((SQLFilterItemField) left).getRoot(session)
          .equals(((SQLFilterItemField) right).getRoot(session))) {
        if (oper instanceof QueryOperatorEquals) {
          result = Boolean.TRUE;
        } else {
          if (oper instanceof QueryOperatorNotEquals) {
            result = Boolean.FALSE;
          }
        }
      }
    }

    if (result != null) {
      if (iParentCondition != null) {
        if (iCondition == iParentCondition.getLeft())
        // REPLACE LEFT
        {
          iCondition.setLeft(result);
        } else
        // REPLACE RIGHT
        {
          iCondition.setRight(result);
        }
      } else {
        // REPLACE ROOT CONDITION
        if (result instanceof Boolean && ((Boolean) result)) {
          compiledFilter.setRootCondition(null);
        }
      }
    }
  }

  protected RID[] getRange(DatabaseSession session) {
    final RID beginRange;
    final RID endRange;

    final SQLFilterCondition rootCondition =
        compiledFilter == null ? null : compiledFilter.getRootCondition();
    if (compiledFilter == null || rootCondition == null) {
      if (request instanceof SQLSynchQuery) {
        beginRange = ((SQLSynchQuery<EntityImpl>) request).getNextPageRID();
      } else {
        beginRange = null;
      }
      endRange = null;
    } else {
      final RID conditionBeginRange = rootCondition.getBeginRidRange(session);
      final RID conditionEndRange = rootCondition.getEndRidRange(session);
      final RID nextPageRid;

      if (request instanceof SQLSynchQuery) {
        nextPageRid = ((SQLSynchQuery<EntityImpl>) request).getNextPageRID();
      } else {
        nextPageRid = null;
      }

      if (conditionBeginRange != null && nextPageRid != null) {
        beginRange =
            conditionBeginRange.compareTo(nextPageRid) > 0 ? conditionBeginRange : nextPageRid;
      } else {
        if (conditionBeginRange != null) {
          beginRange = conditionBeginRange;
        } else {
          beginRange = nextPageRid;
        }
      }

      endRange = conditionEndRange;
    }

    return new RID[]{beginRange, endRange};
  }

  public Iterator<? extends Identifiable> getTarget() {
    return target;
  }

  public void setTarget(final Iterator<? extends Identifiable> target) {
    this.target = target;
  }

  public void setRequest(final SQLAsynchQuery<EntityImpl> request) {
    this.request = request;
  }

  public void setParsedTarget(final SQLTarget parsedTarget) {
    this.parsedTarget = parsedTarget;
  }

  public void setCompiledFilter(final SQLFilter compiledFilter) {
    this.compiledFilter = compiledFilter;
  }

  @Override
  public boolean isCacheable() {
    return true;
  }

  public Object mergeResults(Map<String, Object> results) throws Exception {

    if (results.isEmpty()) {
      return null;
    }

    // TODO: DELEGATE MERGE AT EVERY COMMAND
    final ArrayList<Object> mergedResult = new ArrayList<Object>();

    final Object firstResult = results.values().iterator().next();

    for (Map.Entry<String, Object> entry : results.entrySet()) {
      final String nodeName = entry.getKey();
      final Object nodeResult = entry.getValue();

      if (nodeResult instanceof Collection) {
        mergedResult.addAll((Collection<?>) nodeResult);
      } else {
        if (nodeResult instanceof Exception)
        // RECEIVED EXCEPTION
        {
          throw (Exception) nodeResult;
        } else {
          mergedResult.add(nodeResult);
        }
      }
    }

    Object result = null;

    if (firstResult instanceof LegacyResultSet) {
      // REUSE THE SAME RESULTSET TO AVOID DUPLICATES
      ((LegacyResultSet) firstResult).clear();
      ((LegacyResultSet) firstResult).addAll(mergedResult);
      result = firstResult;
    } else {
      result = new ArrayList<Object>(mergedResult);
    }

    return result;
  }
}
