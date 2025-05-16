package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.IndexSearchInfo;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexCandidate;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 *
 */
public abstract class SQLBooleanExpression extends SimpleNode {

  public static final SQLBooleanExpression TRUE =
      new SQLBooleanExpression(0) {
        @Override
        public boolean evaluate(Identifiable currentRecord, CommandContext ctx) {
          return true;
        }

        @Override
        public boolean evaluate(Result currentRecord, CommandContext ctx) {
          return true;
        }

        @Override
        protected boolean supportsBasicCalculation() {
          return true;
        }

        @Override
        protected int getNumberOfExternalCalculations() {
          return 0;
        }

        @Override
        protected List<Object> getExternalCalculationConditions() {
          return Collections.EMPTY_LIST;
        }

        @Override
        public boolean needsAliases(Set<String> aliases) {
          return false;
        }

        @Override
        public SQLBooleanExpression copy() {
          return TRUE;
        }

        @Nullable
        @Override
        public List<String> getMatchPatternInvolvedAliases() {
          return null;
        }

        @Override
        public void translateLuceneOperator() {
        }

        @Override
        public boolean isCacheable(DatabaseSessionInternal session) {
          return true;
        }

        @Override
        public String toString() {
          return "true";
        }

        @Override
        public void toString(Map<Object, Object> params, StringBuilder builder) {
          builder.append("true");
        }

        @Override
        public void toGenericStatement(StringBuilder builder) {
          builder.append(PARAMETER_PLACEHOLDER);
        }

        @Override
        public boolean isEmpty() {
          return false;
        }

        @Override
        public void extractSubQueries(SubQueryCollector collector) {
        }

        @Override
        public boolean refersToParent() {
          return false;
        }

        @Override
        public boolean isAlwaysTrue() {
          return true;
        }
      };

  public static final SQLBooleanExpression FALSE =
      new SQLBooleanExpression(0) {
        @Override
        public boolean evaluate(Identifiable currentRecord, CommandContext ctx) {
          return false;
        }

        @Override
        public boolean evaluate(Result currentRecord, CommandContext ctx) {
          return false;
        }

        @Override
        protected boolean supportsBasicCalculation() {
          return true;
        }

        @Override
        protected int getNumberOfExternalCalculations() {
          return 0;
        }

        @Override
        protected List<Object> getExternalCalculationConditions() {
          return Collections.EMPTY_LIST;
        }

        @Override
        public boolean needsAliases(Set<String> aliases) {
          return false;
        }

        @Override
        public SQLBooleanExpression copy() {
          return FALSE;
        }

        @Nullable
        @Override
        public List<String> getMatchPatternInvolvedAliases() {
          return null;
        }

        @Override
        public void translateLuceneOperator() {
        }

        @Override
        public boolean isCacheable(DatabaseSessionInternal session) {
          return true;
        }

        @Override
        public String toString() {
          return "false";
        }

        @Override
        public void toString(Map<Object, Object> params, StringBuilder builder) {
          builder.append("false");
        }

        @Override
        public void toGenericStatement(StringBuilder builder) {
          builder.append(PARAMETER_PLACEHOLDER);
        }

        @Override
        public boolean isEmpty() {
          return false;
        }

        @Override
        public void extractSubQueries(SubQueryCollector collector) {
        }

        @Override
        public boolean refersToParent() {
          return false;
        }
      };

  public SQLBooleanExpression(int id) {
    super(id);
  }

  public SQLBooleanExpression(YouTrackDBSql p, int id) {
    super(p, id);
  }

  public abstract boolean evaluate(Identifiable currentRecord, CommandContext ctx);

  public abstract boolean evaluate(Result currentRecord, CommandContext ctx);

  /**
   * @return true if this expression can be calculated in plain Java, false otherwise (eg. LUCENE
   * operator)
   */
  protected abstract boolean supportsBasicCalculation();

  /**
   * @return the number of sub-expressions that have to be calculated using an external engine (eg.
   * LUCENE)
   */
  protected abstract int getNumberOfExternalCalculations();

  /**
   * @return the sub-expressions that have to be calculated using an external engine (eg. LUCENE)
   */
  protected abstract List<Object> getExternalCalculationConditions();

  @Nullable
  public List<SQLBinaryCondition> getIndexedFunctionConditions(
      SchemaClass iSchemaClass, DatabaseSessionInternal database) {
    return null;
  }

  public List<SQLAndBlock> flatten() {

    return Collections.singletonList(encapsulateInAndBlock(this));
  }

  protected SQLAndBlock encapsulateInAndBlock(SQLBooleanExpression item) {
    if (item instanceof SQLAndBlock) {
      return (SQLAndBlock) item;
    }
    var result = new SQLAndBlock(-1);
    result.subBlocks.add(item);
    return result;
  }

  public abstract boolean needsAliases(Set<String> aliases);

  @Override
  public abstract SQLBooleanExpression copy();

  public boolean isEmpty() {
    return false;
  }

  public abstract void extractSubQueries(SubQueryCollector collector);

  public abstract boolean refersToParent();

  /**
   * returns the equivalent of current condition as an UPDATE expression with the same syntax, if
   * possible.
   *
   * <p>Eg. name = 3 can be considered a condition or an assignment. This method transforms the
   * condition in an assignment. This is used mainly for UPSERT operations.
   *
   * @return the equivalent of current condition as an UPDATE expression with the same syntax, if
   * possible.
   */
  public Optional<SQLUpdateItem> transformToUpdateItem() {
    return Optional.empty();
  }

  public abstract List<String> getMatchPatternInvolvedAliases();

  public void translateLuceneOperator() {
  }

  @Nullable
  public static SQLBooleanExpression deserializeFromOResult(Result res) {
    try {
      var result =
          (SQLBooleanExpression)
              Class.forName(res.getProperty("__class"))
                  .getConstructor(Integer.class)
                  .newInstance(-1);
      result.deserialize(res);
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(""), e, (String) null);
    }
    return null;
  }

  public Result serialize(DatabaseSessionInternal db) {
    var result = new ResultInternal(db);
    result.setProperty("__class", getClass().getName());
    return result;
  }

  public void deserialize(Result fromResult) {
    throw new UnsupportedOperationException();
  }

  public abstract boolean isCacheable(DatabaseSessionInternal session);

  public SQLBooleanExpression rewriteIndexChainsAsSubqueries(CommandContext ctx,
      SchemaClassInternal clazz) {
    return this;
  }

  /**
   * returns true only if the expression does not need any further evaluation (eg. "true") and
   * always evaluates to true. It is supposed to be used as and optimization, and is allowed to
   * return false negatives
   */
  public boolean isAlwaysTrue() {
    return false;
  }

  public boolean isIndexAware(IndexSearchInfo info) {
    return false;
  }

  @Nullable
  public IndexCandidate findIndex(IndexFinder info, CommandContext ctx) {
    return null;
  }

  public boolean createRangeWith(SQLBooleanExpression match) {
    return false;
  }

  public boolean isFullTextIndexAware(String indexField) {
    return false;
  }

  public SQLExpression resolveKeyFrom(SQLBinaryCondition additional) {
    throw new UnsupportedOperationException("Cannot execute index query with " + this);
  }

  public SQLExpression resolveKeyTo(SQLBinaryCondition additional) {
    throw new UnsupportedOperationException("Cannot execute index query with " + this);
  }
}
