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
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorBetween;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorContains;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorIn;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMajor;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMajorEquals;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMinor;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMinorEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 *
 */
public class FilterAnalyzer {

  public static List<Index> getInvolvedIndexes(
      DatabaseSessionInternal session,
      SchemaClassInternal iSchemaClass,
      IndexSearchResult searchResultFields) {
    final Set<Index> involvedIndexes =
        iSchemaClass.getInvolvedIndexesInternal(session, searchResultFields.fields());

    final List<Index> result = new ArrayList<Index>(involvedIndexes.size());

    if (searchResultFields.lastField.isLong()) {
      result.addAll(
          ChainedIndexProxy.createProxies(session, iSchemaClass, searchResultFields.lastField));
    } else {
      result.addAll(involvedIndexes);
    }

    return result;
  }

  public List<List<IndexSearchResult>> analyzeMainCondition(
      SQLFilterCondition condition, final SchemaClass schemaClass, CommandContext context) {
    return analyzeOrFilterBranch(schemaClass, condition, context);
  }

  private List<List<IndexSearchResult>> analyzeOrFilterBranch(
      final SchemaClass iSchemaClass, SQLFilterCondition condition, CommandContext iContext) {
    if (condition == null) {
      return null;
    }

    QueryOperator operator = condition.getOperator();

    while (operator == null) {
      if (condition.getRight() == null && condition.getLeft() instanceof SQLFilterCondition) {
        condition = (SQLFilterCondition) condition.getLeft();
        operator = condition.getOperator();
      } else {
        return null;
      }
    }

    final IndexReuseType indexReuseType =
        operator.getIndexReuseType(condition.getLeft(), condition.getRight());
    if (IndexReuseType.INDEX_UNION.equals(indexReuseType)) {
      return analyzeUnion(iSchemaClass, condition, iContext);
    }

    List<List<IndexSearchResult>> result = new ArrayList<List<IndexSearchResult>>();
    List<IndexSearchResult> sub = analyzeCondition(condition, iSchemaClass, iContext);
    //    analyzeFilterBranch(iSchemaClass, condition, sub, iContext);
    result.add(sub);
    return result;
  }

  /**
   * Analyzes a query filter for a possible indexation options. The results are sorted by amount of
   * fields. So the most specific items go first.
   *
   * @param condition   to analyze
   * @param schemaClass the class that is scanned by query
   * @param context     of the query
   * @return list of IndexSearchResult items
   */
  public List<IndexSearchResult> analyzeCondition(
      SQLFilterCondition condition, final SchemaClass schemaClass, CommandContext context) {

    final List<IndexSearchResult> indexSearchResults = new ArrayList<IndexSearchResult>();
    IndexSearchResult lastCondition =
        analyzeFilterBranch(context.getDatabase(), schemaClass, condition, indexSearchResults,
            context);

    if (indexSearchResults.isEmpty() && lastCondition != null) {
      indexSearchResults.add(lastCondition);
    }
    indexSearchResults.sort((searchResultOne, searchResultTwo) ->
        searchResultTwo.getFieldCount() - searchResultOne.getFieldCount());

    return indexSearchResults;
  }

  private IndexSearchResult analyzeFilterBranch(
      DatabaseSession session, final SchemaClass iSchemaClass,
      SQLFilterCondition condition,
      final List<IndexSearchResult> iIndexSearchResults,
      CommandContext iContext) {
    if (condition == null) {
      return null;
    }

    QueryOperator operator = condition.getOperator();

    while (operator == null) {
      if (condition.getRight() == null && condition.getLeft() instanceof SQLFilterCondition) {
        condition = (SQLFilterCondition) condition.getLeft();
        operator = condition.getOperator();
      } else {
        return null;
      }
    }

    final IndexReuseType indexReuseType =
        operator.getIndexReuseType(condition.getLeft(), condition.getRight());
    return switch (indexReuseType) {
      case INDEX_INTERSECTION ->
          analyzeIntersection(session, iSchemaClass, condition, iIndexSearchResults, iContext);
      case INDEX_METHOD ->
          analyzeIndexMethod(session, iSchemaClass, condition, iIndexSearchResults, iContext);
      case INDEX_OPERATOR ->
          analyzeOperator(iSchemaClass, condition, iIndexSearchResults, iContext);
      default -> null;
    };
  }

  private static IndexSearchResult analyzeOperator(
      SchemaClass iSchemaClass,
      SQLFilterCondition condition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext iContext) {
    return condition
        .getOperator()
        .getOIndexSearchResult(iSchemaClass, condition, iIndexSearchResults, iContext);
  }

  private static IndexSearchResult analyzeIndexMethod(
      DatabaseSession session, SchemaClass iSchemaClass,
      SQLFilterCondition condition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext ctx) {
    IndexSearchResult result = createIndexedProperty(condition, condition.getLeft(), ctx);
    if (result == null) {
      result = createIndexedProperty(condition, condition.getRight(), ctx);
    }

    if (result == null) {
      return null;
    }

    if (checkIndexExistence(session, iSchemaClass, result)) {
      iIndexSearchResults.add(result);
    }

    return result;
  }

  private IndexSearchResult analyzeIntersection(
      DatabaseSession session, SchemaClass iSchemaClass,
      SQLFilterCondition condition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext iContext) {
    final IndexSearchResult leftResult =
        analyzeFilterBranch(session
            , iSchemaClass, (SQLFilterCondition) condition.getLeft(), iIndexSearchResults,
            iContext);
    final IndexSearchResult rightResult =
        analyzeFilterBranch(session
            , iSchemaClass,
            (SQLFilterCondition) condition.getRight(),
            iIndexSearchResults, iContext);

    if (leftResult != null && rightResult != null) {
      if (leftResult.canBeMerged(rightResult)) {
        final IndexSearchResult mergeResult = leftResult.merge(rightResult);
        if (iSchemaClass.areIndexed(iContext.getDatabase(), mergeResult.fields())) {
          iIndexSearchResults.add(mergeResult);
        }

        return leftResult.merge(rightResult);
      }
    }

    return null;
  }

  private List<List<IndexSearchResult>> analyzeUnion(
      SchemaClass iSchemaClass, SQLFilterCondition condition, CommandContext iContext) {
    List<List<IndexSearchResult>> result = new ArrayList<List<IndexSearchResult>>();

    result.addAll(
        analyzeOrFilterBranch(iSchemaClass, (SQLFilterCondition) condition.getLeft(), iContext));
    result.addAll(
        analyzeOrFilterBranch(iSchemaClass, (SQLFilterCondition) condition.getRight(), iContext));

    return result;
  }

  /**
   * Add SQL filter field to the search candidate list.
   *
   * @param iCondition Condition item
   * @param iItem      Value to search
   * @return true if the property was indexed and found, otherwise false
   */
  private static IndexSearchResult createIndexedProperty(
      final SQLFilterCondition iCondition, final Object iItem, CommandContext ctx) {
    if (iItem == null || !(iItem instanceof SQLFilterItemField item)) {
      return null;
    }

    if (iCondition.getLeft() instanceof SQLFilterItemField
        && iCondition.getRight() instanceof SQLFilterItemField) {
      return null;
    }

    if (item.hasChainOperators() && !item.isFieldChain()) {
      return null;
    }

    boolean inverted = iCondition.getRight() == iItem;
    final Object origValue = inverted ? iCondition.getLeft() : iCondition.getRight();

    QueryOperator operator = iCondition.getOperator();

    if (inverted) {
      if (operator instanceof QueryOperatorIn) {
        operator = new QueryOperatorContains();
      } else if (operator instanceof QueryOperatorContains) {
        operator = new QueryOperatorIn();
      } else if (operator instanceof QueryOperatorMajor) {
        operator = new QueryOperatorMinor();
      } else if (operator instanceof QueryOperatorMinor) {
        operator = new QueryOperatorMajor();
      } else if (operator instanceof QueryOperatorMajorEquals) {
        operator = new QueryOperatorMinorEquals();
      } else if (operator instanceof QueryOperatorMinorEquals) {
        operator = new QueryOperatorMajorEquals();
      }
    }

    if (iCondition.getOperator() instanceof QueryOperatorBetween
        || operator instanceof QueryOperatorIn) {

      return new IndexSearchResult(operator, item.getFieldChain(), origValue);
    }

    final Object value = SQLHelper.getValue(origValue, null, ctx);
    return new IndexSearchResult(operator, item.getFieldChain(), value);
  }

  private static boolean checkIndexExistence(DatabaseSession session,
      final SchemaClass iSchemaClass,
      final IndexSearchResult result) {
    return iSchemaClass.areIndexed(session, result.fields())
        && (!result.lastField.isLong() || checkIndexChainExistence(session, iSchemaClass, result));
  }

  private static boolean checkIndexChainExistence(DatabaseSession session, SchemaClass iSchemaClass,
      IndexSearchResult result) {
    final int fieldCount = result.lastField.getItemCount();
    SchemaClass cls = iSchemaClass.getProperty(session, result.lastField.getItemName(0))
        .getLinkedClass(session);

    for (int i = 1; i < fieldCount; i++) {
      if (cls == null || !cls.areIndexed(session, result.lastField.getItemName(i))) {
        return false;
      }

      cls = cls.getProperty(session, result.lastField.getItemName(i)).getLinkedClass(session);
    }
    return true;
  }
}
