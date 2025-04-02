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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext.TIMEOUT_STRATEGY;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.StatementCache;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SQL abstract Command Executor implementation.
 */
public abstract class CommandExecutorSQLAbstract extends CommandExecutorAbstract {

  public static final String KEYWORD_TIMEOUT = "TIMEOUT";
  public static final String COLLECTION_PREFIX = "COLLECTION:";
  public static final String CLASS_PREFIX = "CLASS:";
  public static final String INDEX_PREFIX = "INDEX:";

  public static final String INDEX_VALUES_PREFIX = "INDEXVALUES:";
  public static final String INDEX_VALUES_ASC_PREFIX = "INDEXVALUESASC:";
  public static final String INDEX_VALUES_DESC_PREFIX = "INDEXVALUESDESC:";

  public static final String METADATA_PREFIX = "METADATA:";
  public static final String METADATA_SCHEMA = "SCHEMA";
  public static final String METADATA_INDEXMGR = "INDEXMANAGER";

  public static final String DEFAULT_PARAM_USER = "$user";

  protected long timeoutMs = GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();
  protected TIMEOUT_STRATEGY timeoutStrategy = TIMEOUT_STRATEGY.EXCEPTION;
  protected SQLStatement preParsedStatement;

  public boolean isIdempotent() {
    return false;
  }

  protected void throwSyntaxErrorException(String dbName, final String iText) {
    throw new CommandSQLParsingException(dbName,
        iText + ". Use " + getSyntax(), parserText, parserGetPreviousPosition());
  }

  protected void throwParsingException(String dbName, final String iText) {
    throw new CommandSQLParsingException(dbName, iText, parserText, parserGetPreviousPosition());
  }

  protected void throwParsingException(String dbName, final String iText, Exception e) {
    throw BaseException.wrapException(
        new CommandSQLParsingException(dbName, iText, parserText, parserGetPreviousPosition()), e,
        dbName);
  }

  /**
   * Parses the timeout keyword if found.
   */
  protected void parseTimeout(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_TIMEOUT)) {
      return;
    }

    var word = parserNextWord(true);

    try {
      timeoutMs = Long.parseLong(word);
    } catch (NumberFormatException ignore) {
      throwParsingException(null,
          "Invalid "
              + KEYWORD_TIMEOUT
              + " value set to '"
              + word
              + "' but it should be a valid long. Example: "
              + KEYWORD_TIMEOUT
              + " 3000");
    }

    if (timeoutMs < 0) {
      throwParsingException(null,
          "Invalid "
              + KEYWORD_TIMEOUT
              + ": value set minor than ZERO. Example: "
              + KEYWORD_TIMEOUT
              + " 10000");
    }

    word = parserNextWord(true);

    if (word != null) {
      if (word.equals(TIMEOUT_STRATEGY.EXCEPTION.toString())) {
        timeoutStrategy = TIMEOUT_STRATEGY.EXCEPTION;
      } else if (word.equals(TIMEOUT_STRATEGY.RETURN.toString())) {
        timeoutStrategy = TIMEOUT_STRATEGY.RETURN;
      } else {
        parserGoBack();
      }
    }

  }

  protected Set<String> getInvolvedCollectionsOfClasses(final Collection<String> iClassNames,
      DatabaseSessionInternal db) {
    final Set<String> collections = new HashSet<String>();

    for (var clazz : iClassNames) {
      final var cls = db.getMetadata().getImmutableSchemaSnapshot().getClass(clazz);
      if (cls != null) {
        for (var clId : cls.getPolymorphicCollectionIds()) {
          // FILTER THE COLLECTION WHERE THE USER HAS THE RIGHT ACCESS
          if (clId > -1 && checkCollectionAccess(db, db.getCollectionNameById(clId))) {
            collections.add(db.getCollectionNameById(clId).toLowerCase(Locale.ENGLISH));
          }
        }
      }
    }

    return collections;
  }

  protected Set<String> getInvolvedCollectionsOfCollections(DatabaseSessionInternal db,
      final Collection<String> iCollectionNames) {
    final Set<String> collections = new HashSet<String>();

    for (var collection : iCollectionNames) {
      final var c = collection.toLowerCase(Locale.ENGLISH);
      // FILTER THE COLLECTION WHERE THE USER HAS THE RIGHT ACCESS
      if (checkCollectionAccess(db, c)) {
        collections.add(c);
      }
    }

    return collections;
  }

  protected static Set<String> getInvolvedCollectionsOfIndex(DatabaseSessionInternal db,
      final String iIndexName) {
    final Set<String> collections = new HashSet<String>();

    final var metadata = db.getMetadata();
    final var idx = metadata.getIndexManagerInternal().getIndex(db, iIndexName);
    if (idx != null && idx.getDefinition() != null) {
      final var clazz = idx.getDefinition().getClassName();

      if (clazz != null) {
        final var cls = metadata.getImmutableSchemaSnapshot().getClass(clazz);
        if (cls != null) {
          for (var clId : cls.getCollectionIds()) {
            final var clName = db.getCollectionNameById(clId);
            if (clName != null) {
              collections.add(clName.toLowerCase(Locale.ENGLISH));
            }
          }
        }
      }
    }

    return collections;
  }

  protected boolean checkCollectionAccess(final DatabaseSessionInternal db,
      final String iCollectionName) {
    return db.getCurrentUser() == null
        || db.getCurrentUser()
        .checkIfAllowed(db,
            Rule.ResourceGeneric.COLLECTION, iCollectionName, getSecurityOperationType())
        != null;
  }

  protected void bindDefaultContextVariables(DatabaseSessionInternal db) {
    if (context != null) {
      if (db != null && db.getCurrentUser() != null) {
        context.setVariable(DEFAULT_PARAM_USER,
            db.getCurrentUser().getIdentity());
      }
    }
  }

  protected String preParse(DatabaseSessionInternal session, final String queryText,
      final CommandRequest iRequest) {
    final var strict = session.getStorageInfo().getConfiguration().isStrictSql();

    if (strict) {
      try {
        final var result = StatementCache.get(queryText, session);
        preParsedStatement = result;

        if (iRequest instanceof CommandRequestAbstract) {
          final var params = ((CommandRequestAbstract) iRequest).getParameters();
          var builder = new StringBuilder();
          result.toString(params, builder);
          return builder.toString();
        }
        return result.toString();
      } catch (CommandSQLParsingException sqlx) {
        throw sqlx;
      } catch (Exception e) {
        throwParsingException(session.getDatabaseName(),
            "Error parsing query: \n" + queryText + "\n" + e.getMessage(), e);
      }
    }
    return queryText;
  }

  protected static String decodeClassName(String s) {
    return SchemaClassImpl.decodeClassName(s);
  }
}
