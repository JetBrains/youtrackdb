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
package com.jetbrains.youtrack.db.internal.core.sql.filter;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.parser.BaseParser;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandPredicate;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.CommandExecutorSQLSelect;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorAnd;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorNot;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorOr;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Parses text in SQL format and build a tree of conditions.
 */
public class SQLPredicate extends BaseParser implements CommandPredicate {

  protected Set<SchemaProperty> properties = new HashSet<SchemaProperty>();
  protected SQLFilterCondition rootCondition;
  protected List<String> recordTransformed;
  protected List<SQLFilterItemParameter> parameterItems;
  protected int braces;

  @Nonnull
  protected CommandContext context;

  public SQLPredicate(@Nonnull CommandContext context) {
    this.context = context;
  }

  public SQLPredicate(@Nonnull CommandContext context, final String iText) {
    this.context = context;

    text(context.getDatabaseSession(), iText);
  }

  protected void throwSyntaxErrorException(String dbName, final String iText) {
    final var syntax = getSyntax();
    if (syntax.equals("?")) {
      throw new CommandSQLParsingException(dbName, iText, parserText, parserGetPreviousPosition());
    }

    throw new CommandSQLParsingException(dbName,
        iText + ". Use " + syntax, parserText, parserGetPreviousPosition());
  }

  public static String upperCase(String text) {
    var result = new StringBuilder(text.length());
    for (var c : text.toCharArray()) {
      var upper = ("" + c).toUpperCase(Locale.ENGLISH);
      if (upper.length() > 1) {
        result.append(c);
      } else {
        result.append(upper);
      }
    }
    return result.toString();
  }

  public SQLPredicate text(DatabaseSessionInternal session, final String iText) {
    if (iText == null) {
      throw new CommandSQLParsingException(session.getDatabaseName(), "Query text is null");
    }

    try {
      parserText = iText;
      parserTextUpperCase = upperCase(parserText);
      parserSetCurrentPosition(0);
      parserSkipWhiteSpaces();

      rootCondition = (SQLFilterCondition) extractConditions(session, null);

      optimize(session);
    } catch (QueryParsingException e) {
      if (e.getText() == null)
      // QUERY EXCEPTION BUT WITHOUT TEXT: NEST IT
      {
        throw BaseException.wrapException(
            new QueryParsingException(session.getDatabaseName(),
                "Error on parsing query", parserText, parserGetCurrentPosition()),
            e, session);
      }

      throw e;
    } catch (Exception t) {
      throw BaseException.wrapException(
          new QueryParsingException(session.getDatabaseName(),
              "Error on parsing query", parserText, parserGetCurrentPosition()),
          t, session);
    }
    return this;
  }

  public Object evaluate() {
    return evaluate(null, null, null);
  }

  public Object evaluate(final CommandContext iContext) {
    return evaluate(null, null, iContext);
  }

  public Object evaluate(
      final Result iRecord, EntityImpl iCurrentResult, final CommandContext iContext) {
    if (rootCondition == null) {
      return true;
    }

    return rootCondition.evaluate(iRecord, iCurrentResult, iContext);
  }

  protected Object extractConditions(DatabaseSessionInternal session,
      final SQLFilterCondition iParentCondition) {
    final var oldPosition = parserGetCurrentPosition();
    parserNextWord(true, " )=><,\r\n");
    final var word = parserGetLastWord();

    var inBraces =
        word.length() > 0 && word.charAt(0) == StringSerializerHelper.EMBEDDED_BEGIN;

    if (word.length() > 0
        && (word.equalsIgnoreCase("SELECT") || word.equalsIgnoreCase("TRAVERSE"))) {
      // SUB QUERY
      final var embedded = new StringBuilder(256);
      StringSerializerHelper.getEmbedded(parserText, oldPosition - 1, -1, embedded);
      parserSetCurrentPosition(oldPosition + embedded.length() + 1);
      return new SQLSynchQuery<Object>(embedded.toString());
    }

    parserSetCurrentPosition(oldPosition);
    var currentCondition = extractCondition(session);

    // CHECK IF THERE IS ANOTHER CONDITION ON RIGHT
    while (parserSkipWhiteSpaces()) {

      if (!parserIsEnded() && parserGetCurrentChar() == ')') {
        return currentCondition;
      }

      final var nextOperator = extractConditionOperator();
      if (nextOperator == null) {
        return currentCondition;
      }

      if (nextOperator.precedence > currentCondition.getOperator().precedence) {
        // SWAP ITEMS
        final var subCondition =
            new SQLFilterCondition(currentCondition.right, nextOperator);
        currentCondition.right = subCondition;
        subCondition.right = extractConditionItem(session, false, 1);
      } else {
        final var parentCondition =
            new SQLFilterCondition(currentCondition, nextOperator);
        parentCondition.right = extractConditions(session, parentCondition);
        currentCondition = parentCondition;
      }
    }

    currentCondition.inBraces = inBraces;

    // END OF TEXT
    return currentCondition;
  }

  protected SQLFilterCondition extractCondition(DatabaseSessionInternal session) {

    if (!parserSkipWhiteSpaces())
    // END OF TEXT
    {
      return null;
    }

    // EXTRACT ITEMS
    var left = extractConditionItem(session, true, 1);

    if (left != null && checkForEnd(left.toString())) {
      return null;
    }

    QueryOperator oper;
    final Object right;

    if (left instanceof QueryOperator && ((QueryOperator) left).isUnary()) {
      oper = (QueryOperator) left;
      left = extractConditionItem(session, false, 1);
      right = null;
    } else {
      oper = extractConditionOperator();

      if (oper instanceof QueryOperatorNot)
      // SPECIAL CASE: READ NEXT OPERATOR
      {
        oper = new QueryOperatorNot(extractConditionOperator());
      }

      if (oper instanceof QueryOperatorAnd || oper instanceof QueryOperatorOr) {
        right = extractCondition(session);
      } else {
        right = oper != null ? extractConditionItem(session, false, oper.expectedRightWords) : null;
      }
    }

    // CREATE THE CONDITION OBJECT
    return new SQLFilterCondition(left, oper, right);
  }

  protected boolean checkForEnd(final String iWord) {
    if (iWord != null
        && (iWord.equals(CommandExecutorSQLSelect.KEYWORD_ORDER)
        || iWord.equals(CommandExecutorSQLSelect.KEYWORD_LIMIT)
        || iWord.equals(CommandExecutorSQLSelect.KEYWORD_SKIP)
        || iWord.equals(CommandExecutorSQLSelect.KEYWORD_OFFSET))) {
      parserMoveCurrentPosition(iWord.length() * -1);
      return true;
    }
    return false;
  }

  private QueryOperator extractConditionOperator() {
    if (!parserSkipWhiteSpaces())
    // END OF PARSING: JUST RETURN
    {
      return null;
    }

    if (parserGetCurrentChar() == ')')
    // FOUND ')': JUST RETURN
    {
      return null;
    }

    final var operators = SQLEngine.getInstance().getRecordOperators();
    final var candidateOperators = new String[operators.length];
    for (var i = 0; i < candidateOperators.length; ++i) {
      candidateOperators[i] = operators[i].keyword;
    }

    final var operatorPos = parserNextChars(null, true, false, candidateOperators);

    if (operatorPos == -1) {
      parserGoBack();
      return null;
    }

    final var op = operators[operatorPos];
    if (op.expectsParameters) {
      // PARSE PARAMETERS IF ANY
      parserGoBack();

      parserNextWord(true, " 0123456789'\"");
      final var word = parserGetLastWord();

      final List<String> params = new ArrayList<String>();
      // CHECK FOR PARAMETERS
      if (word.length() > op.keyword.length()
          && word.charAt(op.keyword.length()) == StringSerializerHelper.EMBEDDED_BEGIN) {
        var paramBeginPos = parserGetCurrentPosition() - (word.length() - op.keyword.length());
        parserSetCurrentPosition(
            StringSerializerHelper.getParameters(parserText, paramBeginPos, -1, params));
      } else if (!word.equals(op.keyword)) {
        throw new QueryParsingException(null,
            "Malformed usage of operator '" + op + "'. Parsed operator is: " + word);
      }

      try {
        // CONFIGURE COULD INSTANTIATE A NEW OBJECT: ACT AS A FACTORY
        return op.configure(params);
      } catch (Exception e) {
        throw BaseException.wrapException(
            new QueryParsingException(null,
                "Syntax error using the operator '" + op + "'. Syntax is: " + op.getSyntax()),
            e, (String) null);
      }
    } else {
      parserMoveCurrentPosition(+1);
    }
    return op;
  }

  private Object extractConditionItem(DatabaseSessionInternal session,
      final boolean iAllowOperator,
      final int iExpectedWords) {
    final var result = new Object[iExpectedWords];

    for (var i = 0; i < iExpectedWords; ++i) {
      parserNextWord(false, " =><,\r\n");
      var word = parserGetLastWord();

      if (word.length() == 0) {
        break;
      }

      word = word.replaceAll("\\\\", "\\\\\\\\"); // see issue #5229

      final var uWord = word.toUpperCase(Locale.ENGLISH);

      final var lastPosition = parserIsEnded() ? parserText.length() : parserGetCurrentPosition();

      if (word.length() > 0 && word.charAt(0) == StringSerializerHelper.EMBEDDED_BEGIN) {
        braces++;

        // SUB-CONDITION
        parserSetCurrentPosition(lastPosition - word.length() + 1);

        final var subCondition = extractConditions(session, null);

        if (!parserSkipWhiteSpaces() || parserGetCurrentChar() == ')') {
          braces--;
          parserMoveCurrentPosition(+1);
        }
        if (subCondition instanceof SQLFilterCondition) {
          ((SQLFilterCondition) subCondition).inBraces = true;
        }
        result[i] = subCondition;
      } else if (word.charAt(0) == StringSerializerHelper.LIST_BEGIN) {
        // COLLECTION OF ELEMENTS
        parserSetCurrentPosition(lastPosition - getLastWordLength());

        final List<String> stringItems = new ArrayList<String>();
        parserSetCurrentPosition(
            StringSerializerHelper.getCollection(
                parserText, parserGetCurrentPosition(), stringItems));
        result[i] = convertCollectionItems(stringItems);

        parserMoveCurrentPosition(+1);

      } else if (uWord.startsWith(
          SQLFilterItemFieldAll.NAME + StringSerializerHelper.EMBEDDED_BEGIN)) {

        result[i] = new SQLFilterItemFieldAll(session, this, word, null);

      } else if (uWord.startsWith(
          SQLFilterItemFieldAny.NAME + StringSerializerHelper.EMBEDDED_BEGIN)) {

        result[i] = new SQLFilterItemFieldAny(session, this, word, null);

      } else {

        if (uWord.equals("NOT")) {
          if (iAllowOperator) {
            return new QueryOperatorNot();
          } else {
            // GET THE NEXT VALUE
            parserNextWord(false, " )=><,\r\n");
            final var nextWord = parserGetLastWord();

            if (nextWord.length() > 0) {
              word += " " + nextWord;

              if (word.endsWith(")")) {
                word = word.substring(0, word.length() - 1);
              }
            }
          }
        } else if (uWord.equals("AND"))
        // SPECIAL CASE IN "BETWEEN X AND Y"
        {
          result[i] = word;
        }

        while (word.endsWith(")")) {
          final var openParenthesis = word.indexOf('(');
          if (openParenthesis == -1) {
            // DISCARD END PARENTHESIS
            word = word.substring(0, word.length() - 1);
            parserMoveCurrentPosition(-1);
          } else {
            break;
          }
        }

        word = word.replaceAll("\\\\\\\\", "\\\\"); // see issue #5229
        result[i] = SQLHelper.parseValue(this, this, word, context);
      }
    }

    return iExpectedWords == 1 ? result[0] : result;
  }

  private List<Object> convertCollectionItems(List<String> stringItems) {
    List<Object> coll = new ArrayList<Object>();
    for (var s : stringItems) {
      coll.add(SQLHelper.parseValue(this, this, s, context));
    }
    return coll;
  }

  public SQLFilterCondition getRootCondition() {
    return rootCondition;
  }

  @Override
  public String toString() {
    if (rootCondition != null) {
      return "Parsed: " + rootCondition;
    }
    return "Unparsed: " + parserText;
  }

  /**
   * Binds parameters.
   */
  public void bindParameters(final Map<Object, Object> iArgs) {
    if (parameterItems == null || iArgs == null || iArgs.size() == 0) {
      return;
    }

    for (var i = 0; i < parameterItems.size(); i++) {
      var value = parameterItems.get(i);
      if ("?".equals(value.getName())) {
        value.setValue(iArgs.get(i));
      } else {
        value.setValue(iArgs.get(value.getName()));
      }
    }
  }

  public SQLFilterItemParameter addParameter(final String iName) {
    final String name;
    if (iName.charAt(0) == StringSerializerHelper.PARAMETER_NAMED) {
      name = iName.substring(1);

      // CHECK THE PARAMETER NAME IS CORRECT
      if (!StringSerializerHelper.isAlphanumeric(name)) {
        throw new QueryParsingException(null,
            "Parameter name '" + name + "' is invalid, only alphanumeric characters are allowed");
      }
    } else {
      name = iName;
    }

    final var param = new SQLFilterItemParameter(name);

    if (parameterItems == null) {
      parameterItems = new ArrayList<SQLFilterItemParameter>();
    }

    parameterItems.add(param);
    return param;
  }

  public void setRootCondition(final SQLFilterCondition iCondition) {
    rootCondition = iCondition;
  }

  protected void optimize(DatabaseSession session) {
    if (rootCondition != null) {
      computePrefetchFieldList(session, rootCondition, new HashSet<String>());
    }
  }

  protected Set<String> computePrefetchFieldList(
      DatabaseSession session, final SQLFilterCondition iCondition, final Set<String> iFields) {
    var left = iCondition.getLeft();
    var right = iCondition.getRight();
    if (left instanceof SQLFilterItemField) {
      ((SQLFilterItemField) left).setPreLoadedFields(iFields);
      iFields.add(((SQLFilterItemField) left).getRoot(session));
    } else if (left instanceof SQLFilterCondition) {
      computePrefetchFieldList(session, (SQLFilterCondition) left, iFields);
    }

    if (right instanceof SQLFilterItemField) {
      ((SQLFilterItemField) right).setPreLoadedFields(iFields);
      iFields.add(((SQLFilterItemField) right).getRoot(session));
    } else if (right instanceof SQLFilterCondition) {
      computePrefetchFieldList(session, (SQLFilterCondition) right, iFields);
    }

    return iFields;
  }
}
