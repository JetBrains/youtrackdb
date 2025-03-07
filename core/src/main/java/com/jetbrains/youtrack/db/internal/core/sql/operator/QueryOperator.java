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
package com.jetbrains.youtrack.db.internal.core.sql.operator;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.operator.math.QueryOperatorDivide;
import com.jetbrains.youtrack.db.internal.core.sql.operator.math.QueryOperatorMinus;
import com.jetbrains.youtrack.db.internal.core.sql.operator.math.QueryOperatorMod;
import com.jetbrains.youtrack.db.internal.core.sql.operator.math.QueryOperatorMultiply;
import com.jetbrains.youtrack.db.internal.core.sql.operator.math.QueryOperatorPlus;
import java.util.List;
import java.util.stream.Stream;

/**
 * Query Operators. Remember to handle the operator in OQueryItemCondition.
 */
public abstract class QueryOperator {

  public enum ORDER {
    /**
     * Used when order compared to other operator cannot be evaluated or has no consequences.
     */
    UNKNOWNED,
    /**
     * Used when this operator must be before the other one
     */
    BEFORE,
    /**
     * Used when this operator must be after the other one
     */
    AFTER,
    /**
     * Used when this operator is equal the other one
     */
    EQUAL
  }

  /**
   * Default operator order. can be used by additional operator to locate themself relatively to
   * default ones.
   *
   * <p>WARNING: ORDER IS IMPORTANT TO AVOID SUB-STRING LIKE "IS" and AND "INSTANCEOF": INSTANCEOF
   * MUST BE PLACED BEFORE! AND ALSO FOR PERFORMANCE (MOST USED BEFORE)
   */
  protected static final Class<?>[] DEFAULT_OPERATORS_ORDER = {
      QueryOperatorEquals.class,
      QueryOperatorAnd.class,
      QueryOperatorOr.class,
      QueryOperatorNotEquals.class,
      QueryOperatorNotEquals2.class,
      QueryOperatorNot.class,
      QueryOperatorMinorEquals.class,
      QueryOperatorMinor.class,
      QueryOperatorMajorEquals.class,
      QueryOperatorContainsAll.class,
      QueryOperatorMajor.class,
      QueryOperatorLike.class,
      QueryOperatorMatches.class,
      QueryOperatorInstanceof.class,
      QueryOperatorIs.class,
      QueryOperatorIn.class,
      QueryOperatorContainsKey.class,
      QueryOperatorContainsValue.class,
      QueryOperatorContainsText.class,
      QueryOperatorContains.class,
      QueryOperatorTraverse.class,
      QueryOperatorBetween.class,
      QueryOperatorPlus.class,
      QueryOperatorMinus.class,
      QueryOperatorMultiply.class,
      QueryOperatorDivide.class,
      QueryOperatorMod.class
  };

  public final String keyword;
  public final int precedence;
  public final int expectedRightWords;
  public final boolean unary;
  public final boolean expectsParameters;

  protected QueryOperator(final String iKeyword, final int iPrecedence, final boolean iUnary) {
    this(iKeyword, iPrecedence, iUnary, 1, false);
  }

  protected QueryOperator(
      final String iKeyword,
      final int iPrecedence,
      final boolean iUnary,
      final int iExpectedRightWords) {
    this(iKeyword, iPrecedence, iUnary, iExpectedRightWords, false);
  }

  protected QueryOperator(
      final String iKeyword,
      final int iPrecedence,
      final boolean iUnary,
      final int iExpectedRightWords,
      final boolean iExpectsParameters) {
    keyword = iKeyword;
    precedence = iPrecedence;
    unary = iUnary;
    expectedRightWords = iExpectedRightWords;
    expectsParameters = iExpectsParameters;
  }

  public abstract Object evaluateRecord(
      final Identifiable iRecord,
      EntityImpl iCurrentResult,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext,
      final EntitySerializer serializer);

  /**
   * Returns hint how index can be used to calculate result of operator execution.
   *
   * @param iLeft  Value of left query parameter.
   * @param iRight Value of right query parameter.
   * @return Hint how index can be used to calculate result of operator execution.
   */
  public abstract IndexReuseType getIndexReuseType(Object iLeft, Object iRight);

  public IndexSearchResult getOIndexSearchResult(
      SchemaClass iSchemaClass,
      SQLFilterCondition iCondition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext context) {

    return null;
  }

  /**
   * Performs index query and returns index stream which presents subset of index data which
   * corresponds to result of execution of given operator.
   *
   * <p>Query that should be executed can be presented like: [[property0 = keyParam0] and
   * [property1 = keyParam1] and] propertyN operator keyParamN.
   *
   * <p>It is supped that index which passed in as parameter is used to index properties listed
   * above and responsibility of given method execute query using given parameters.
   *
   * <p>Multiple parameters are passed in to implement composite indexes support.
   *
   * @param iContext
   * @param index        Instance of index that will be used to calculate result of operator
   *                     execution.
   * @param keyParams    Parameters of query is used to calculate query result.
   * @param ascSortOrder Data returned by cursors should be sorted in ascending or descending
   *                     order.
   * @return Cursor instance if index can be used to evaluate result of execution of given operator
   * and <code>null</code> otherwise.
   */
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, final List<Object> keyParams, boolean ascSortOrder) {
    return Stream.empty();
  }

  @Override
  public String toString() {
    return keyword;
  }

  /**
   * Default State-less implementation: does not save parameters and just return itself
   *
   * @param iParams
   * @return
   */
  public QueryOperator configure(final List<String> iParams) {
    return this;
  }

  public String getSyntax() {
    return "<left> " + keyword + " <right>";
  }

  public abstract RID getBeginRidRange(DatabaseSession session, final Object iLeft,
      final Object iRight);

  public abstract RID getEndRidRange(DatabaseSession session, final Object iLeft,
      final Object iRight);

  public boolean isUnary() {
    return unary;
  }

  /**
   * Check priority of this operator compare to given operator.
   *
   * @param other
   * @return ORDER place of this operator compared to given operator
   */
  public ORDER compare(QueryOperator other) {
    final Class<?> thisClass = this.getClass();
    final Class<?> otherClass = other.getClass();

    int thisPosition = -1;
    int otherPosition = -1;
    for (int i = 0; i < DEFAULT_OPERATORS_ORDER.length; i++) {
      // subclass of default operators inherit their parent ordering
      final Class<?> clazz = DEFAULT_OPERATORS_ORDER[i];
      if (clazz.isAssignableFrom(thisClass)) {
        thisPosition = i;
      }
      if (clazz.isAssignableFrom(otherClass)) {
        otherPosition = i;
      }
    }

    if (thisPosition == -1 || otherPosition == -1) {
      // cannot decide which comes first
      return ORDER.UNKNOWNED;
    }

    if (thisPosition > otherPosition) {
      return ORDER.AFTER;
    } else if (thisPosition < otherPosition) {
      return ORDER.BEFORE;
    }

    return ORDER.EQUAL;
  }

  protected void updateProfiler(
      final CommandContext iContext,
      final Index index,
      final List<Object> keyParams) {
    if (iContext.isRecordingMetrics()) {
      iContext.updateMetric("compositeIndexUsed", +1);
    }
  }

  public boolean canShortCircuit(Object l) {
    return false;
  }

  public boolean canBeMerged() {
    return true;
  }

  public boolean isSupportingBinaryEvaluate() {
    return false;
  }
}
