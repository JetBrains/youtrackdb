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

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.traverse.Traverse;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
public class CommandExecutorSQLTraverse extends CommandExecutorSQLResultsetAbstract {

  public static final String KEYWORD_WHILE = "WHILE";
  public static final String KEYWORD_TRAVERSE = "TRAVERSE";
  public static final String KEYWORD_STRATEGY = "STRATEGY";
  public static final String KEYWORD_MAXDEPTH = "MAXDEPTH";

  // HANDLES ITERATION IN LAZY WAY
  private Traverse traverse;

  /**
   * Compile the filter conditions only the first time.
   */
  public CommandExecutorSQLTraverse parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;
    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      traverse = new Traverse(iRequest.getContext().getDatabaseSession());
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      super.parse(session, iRequest);

      final var pos = parseFields();
      if (pos == -1) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Traverse must have the field list. Use " + getSyntax());
      }
      parserSetCurrentPosition(pos);

      var endPosition = parserText.length();

      parsedTarget =
          SQLEngine
              .parseTarget(parserText.substring(pos, endPosition), getContext());

      if (parsedTarget.parserIsEnded()) {
        parserSetCurrentPosition(endPosition);
      } else {
        parserMoveCurrentPosition(parsedTarget.parserGetCurrentPosition());
      }

      if (!parserIsEnded()) {
        parserNextWord(true);

        if (parserGetLastWord().equalsIgnoreCase(KEYWORD_WHERE))
        // // TODO Remove the additional management of WHERE for TRAVERSE after a while
        {
          warnDeprecatedWhere();
        }

        if (parserGetLastWord().equalsIgnoreCase(KEYWORD_WHERE)
            || parserGetLastWord().equalsIgnoreCase(KEYWORD_WHILE)) {

          compiledFilter =
              SQLEngine
                  .parseCondition(
                      parserText.substring(parserGetCurrentPosition(), endPosition),
                      getContext(),
                      KEYWORD_WHILE);

          traverse.predicate(compiledFilter);
          optimize(session);
          int position;
          if (compiledFilter.parserIsEnded()) {
            position = endPosition;
          } else {
            position = compiledFilter.parserGetCurrentPosition() + parserGetCurrentPosition();
          }
          parserSetCurrentPosition(position);
        } else {
          parserGoBack();
        }
      }

      parserSkipWhiteSpaces();

      while (!parserIsEnded()) {
        if (parserOptionalKeyword(session.getDatabaseName(),
            KEYWORD_LIMIT,
            KEYWORD_SKIP,
            KEYWORD_OFFSET,
            KEYWORD_TIMEOUT,
            KEYWORD_MAXDEPTH, KEYWORD_STRATEGY)) {
          final var w = parserGetLastWord();
          if (w.equals(KEYWORD_LIMIT)) {
            parseLimit(w);
          } else if (w.equals(KEYWORD_SKIP) || w.equals(KEYWORD_OFFSET)) {
            parseSkip(w);
          } else if (w.equals(KEYWORD_TIMEOUT)) {
            parseTimeout(w);
          } else if (w.equals(KEYWORD_MAXDEPTH)) {
            parseMaxDepth(w);
          } else if (w.equals(KEYWORD_STRATEGY)) {
            parseStrategy(w);
          }
        }
      }

      if (limit == 0 || limit < -1) {
        throw new IllegalArgumentException("Limit must be > 0 or = -1 (no limit)");
      } else {
        traverse.limit(limit);
      }

      traverse.getContext().setParent(iRequest.getContext());
    } finally {
      textRequest.setText(originalQuery);
    }
    return this;
  }

  protected boolean parseMaxDepth(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_MAXDEPTH)) {
      return false;
    }

    var word = parserNextWord(true);

    try {
      traverse.setMaxDepth(Integer.parseInt(word));
    } catch (Exception ignore) {
      throwParsingException(null,
          "Invalid "
              + KEYWORD_MAXDEPTH
              + " value set to '"
              + word
              + "' but it should be a valid long. Example: "
              + KEYWORD_MAXDEPTH
              + " 3000");
    }

    if (traverse.getMaxDepth() < 0) {
      throwParsingException(null,
          "Invalid "
              + KEYWORD_MAXDEPTH
              + ": value set minor than ZERO. Example: "
              + KEYWORD_MAXDEPTH
              + " 3");
    }

    return true;
  }

  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    context.beginExecution(timeoutMs, timeoutStrategy);

    if (!assignTarget(session, iArgs)) {
      throw new QueryParsingException(session.getDatabaseName(),
          "No source found in query: specify class, cluster(s) or single record(s)");
    }

    if (traverse == null) {
      traverse = new Traverse(session);
    }

    // BROWSE ALL THE RECORDS AND COLLECTS RESULT
    final var result = traverse.execute(session);
    for (var r : result) {
      if (!handleResult(r, context))
      // LIMIT REACHED
      {
        break;
      }
    }

    return getResult(session);
  }

  @Override
  public CommandContext getContext() {
    return traverse.getContext();
  }

  public Iterator<Identifiable> iterator(DatabaseSessionInternal session) {
    return iterator(session, null);
  }

  public Iterator<Identifiable> iterator(DatabaseSessionInternal session,
      final Map<Object, Object> iArgs) {
    assignTarget(session, iArgs);
    return traverse;
  }

  public String getSyntax() {
    return "TRAVERSE <field>* FROM <target> [MAXDEPTH <max-depth>] [WHILE <condition>] [STRATEGY"
        + " <strategy>]";
  }

  protected void warnDeprecatedWhere() {
    LogManager.instance()
        .warn(
            this,
            "Keyword WHERE in traverse has been replaced by WHILE. Please change your query to"
                + " support WHILE instead of WHERE because now it's only deprecated, but in future"
                + " it will be removed the back-ward compatibility.");
  }

  @Override
  protected boolean assignTarget(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    if (super.assignTarget(session, iArgs)) {
      traverse.target(target);
      return true;
    }
    return false;
  }

  protected int parseFields() {
    var currentPos = 0;
    final var word = new StringBuilder();

    currentPos = nextWord(parserText, parserTextUpperCase, currentPos, word, true);
    if (!word.toString().equals(KEYWORD_TRAVERSE)) {
      return -1;
    }

    var fromPosition = parserTextUpperCase.indexOf(KEYWORD_FROM_2FIND, currentPos);
    if (fromPosition == -1) {
      throw new QueryParsingException(null, "Missed " + KEYWORD_FROM, parserText, currentPos);
    }

    Set<Object> fields = new HashSet<Object>();

    final var fieldString = parserText.substring(currentPos, fromPosition).trim();
    if (fieldString.length() > 0) {
      // EXTRACT PROJECTIONS
      final var items = StringSerializerHelper.smartSplit(fieldString, ',');

      for (var field : items) {
        final var fieldName = field.trim();

        if (fieldName.contains("(")) {
          fields.add(SQLHelper.parseValue(null, fieldName, context));
        } else {
          fields.add(fieldName);
        }
      }
    } else {
      throw new QueryParsingException(null,
          "Missed field list to cross in TRAVERSE. Use " + getSyntax(), parserText, currentPos);
    }

    currentPos = fromPosition + KEYWORD_FROM.length() + 1;

    traverse.fields(fields);

    return currentPos;
  }

  /**
   * Parses the strategy keyword if found.
   */
  protected boolean parseStrategy(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_STRATEGY)) {
      return false;
    }

    final var strategyWord = parserNextWord(true);

    try {
      traverse.setStrategy(Traverse.STRATEGY.valueOf(strategyWord.toUpperCase(Locale.ENGLISH)));
    } catch (IllegalArgumentException ignore) {
      throwParsingException(null,
          "Invalid "
              + KEYWORD_STRATEGY
              + ". Use one between "
              + Arrays.toString(Traverse.STRATEGY.values()));
    }
    return true;
  }
}
