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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CollectionDoesNotExistException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLCreateClassStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL CREATE CLASS command: Creates a new property in the target class.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLCreateClass extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_CREATE = "CREATE";
  public static final String KEYWORD_CLASS = "CLASS";
  public static final String KEYWORD_EXTENDS = "EXTENDS";
  public static final String KEYWORD_ABSTRACT = "ABSTRACT";
  public static final String KEYWORD_COLLECTION = "COLLECTION";
  public static final String KEYWORD_COLLECTIONS = "COLLECTIONS";
  public static final String KEYWORD_IF = "IF";
  public static final String KEYWORD_NOT = "NOT";
  public static final String KEYWORD_EXISTS = "EXISTS";

  private String className;
  private final List<SchemaClass> superClasses = new ArrayList<SchemaClass>();
  private int[] collectionIds;
  private Integer collections = null;
  private boolean ifNotExists = false;

  public CommandExecutorSQLCreateClass parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      var word = new StringBuilder();

      var oldPos = 0;
      var pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CREATE)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_CREATE + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CLASS)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_CLASS + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session, "Expected <class>", parserText, oldPos);
      }

      className = word.toString();
      if (this.preParsedStatement != null) {
        className = ((SQLCreateClassStatement) preParsedStatement).name.getStringValue();
      }
      if (className == null) {
        throw new CommandSQLParsingException(session, "Expected <class>", parserText, oldPos);
      }

      oldPos = pos;

      while ((pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true)) > -1) {
        final var k = word.toString();
        switch (k) {
          case KEYWORD_EXTENDS -> {
            boolean hasNext;
            var newParser = this.preParsedStatement != null;
            SchemaClass superClass;
            do {
              oldPos = pos;
              pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
              if (pos == -1) {
                throw new CommandSQLParsingException(session,
                    "Syntax error after EXTENDS for class "
                        + className
                        + ". Expected the super-class name. Use "
                        + getSyntax(),
                    parserText, oldPos);
              }
              var superclassName = decodeClassName(word.toString());

              if (!session.getMetadata().getImmutableSchemaSnapshot().existsClass(superclassName)
                  && !newParser) {
                throw new CommandSQLParsingException(session,
                    "Super-class " + word + " not exists", parserText, oldPos);
              }
              superClass = session.getMetadata().getSchema().getClass(superclassName);
              superClasses.add(superClass);
              hasNext = false;
              for (; pos < parserText.length(); pos++) {
                var ch = parserText.charAt(pos);
                if (ch == ',') {
                  hasNext = true;
                } else if (Character.isLetterOrDigit(ch)) {
                  break;
                } else if (ch == '`') {
                  break;
                }
              }
            } while (hasNext);
            if (newParser) {
              var statement = (SQLCreateClassStatement) this.preParsedStatement;
              var superclasses = statement.getSuperclasses();
              this.superClasses.clear();
              for (var superclass : superclasses) {
                var superclassName = superclass.getStringValue();
                if (!session.getMetadata().getSchema().existsClass(superclassName)) {
                  throw new CommandSQLParsingException(session,
                      "Super-class " + word + " not exists", parserText, oldPos);
                }
                superClass = session.getMetadata().getSchema().getClass(superclassName);
                this.superClasses.add(superClass);
              }
            }
          }
          case KEYWORD_COLLECTION -> {
            oldPos = pos;
            pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false, " =><()");
            if (pos == -1) {
              throw new CommandSQLParsingException(session,
                  "Syntax error after COLLECTION for class "
                      + className
                      + ". Expected the collection id or name. Use "
                      + getSyntax(),
                  parserText, oldPos);
            }

            final var collectionIdsAsStrings = word.toString().split(",");
            if (collectionIdsAsStrings.length > 0) {
              collectionIds = new int[collectionIdsAsStrings.length];
              for (var i = 0; i < collectionIdsAsStrings.length; ++i) {
                if (Character.isDigit(collectionIdsAsStrings[i].charAt(0)))
                // GET COLLECTION ID FROM NAME
                {
                  collectionIds[i] = Integer.parseInt(collectionIdsAsStrings[i]);
                } else
                // GET COLLECTION ID
                {
                  collectionIds[i] = session.getCollectionIdByName(collectionIdsAsStrings[i]);
                }

                if (collectionIds[i] == -1) {
                  throw new CommandSQLParsingException(session,
                      "Collection with id " + collectionIds[i] + " does not exists", parserText, oldPos);
                }

                try {
                  var collectionName = session.getCollectionNameById(collectionIds[i]);
                  if (collectionName == null) {
                    throw new CollectionDoesNotExistException(session.getDatabaseName(),
                        "Collection with id "
                            + collectionIds[i]
                            + " does not exist inside of storage "
                            + session.getDatabaseName());
                  }
                } catch (Exception e) {
                  throw BaseException.wrapException(
                      new CommandSQLParsingException(session,
                          "Collection with id " + collectionIds[i] + " does not exists",
                          parserText, oldPos),
                      e, session);
                }
              }
            }
          }
          case KEYWORD_COLLECTIONS -> {
            oldPos = pos;
            pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false, " =><()");
            if (pos == -1) {
              throw new CommandSQLParsingException(session,
                  "Syntax error after COLLECTIONS for class "
                      + className
                      + ". Expected the number of collections. Use "
                      + getSyntax(),
                  parserText, oldPos);
            }

            collections = Integer.parseInt(word.toString());
          }
          case KEYWORD_ABSTRACT -> collectionIds = new int[]{-1};
          case KEYWORD_IF -> {
            oldPos = pos;
            pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false, " =><()");
            if (!word.toString().equalsIgnoreCase(KEYWORD_NOT)) {
              throw new CommandSQLParsingException(session,
                  "Syntax error after IF for class "
                      + className
                      + ". Expected NOT. Use "
                      + getSyntax(),
                  parserText, oldPos);
            }
            oldPos = pos;
            pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false, " =><()");
            if (!word.toString().equalsIgnoreCase(KEYWORD_EXISTS)) {
              throw new CommandSQLParsingException(session,
                  "Syntax error after IF NOT for class "
                      + className
                      + ". Expected EXISTS. Use "
                      + getSyntax(),
                  parserText, oldPos);
            }
            ifNotExists = true;
          }
          default -> throw new CommandSQLParsingException(session.getDatabaseName(),
              "Invalid keyword: " + k);
        }

        oldPos = pos;
      }

      if (collectionIds == null) {
        final var collectionId = session.getCollectionIdByName(className);
        if (collectionId > -1) {
          collectionIds = new int[]{collectionId};
        }
      }

    } finally {
      textRequest.setText(originalQuery);
    }
    return this;
  }

  /**
   * Execute the CREATE CLASS.
   */
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (className == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    var alreadyExists = session.getMetadata().getSchema().existsClass(className);
    if (!alreadyExists || !ifNotExists) {
      if (collections != null) {
        session
            .getMetadata()
            .getSchema()
            .createClass(className, collections, superClasses.toArray(new SchemaClass[0]));
      } else {
        session
            .getMetadata()
            .getSchema()
            .createClass(className, collectionIds, superClasses.toArray(new SchemaClass[0]));
      }
    }

    return session.getMetadata().getSchema().getClasses().size();
  }

  @Override
  public String getSyntax() {
    return "CREATE CLASS <class> [IF NOT EXISTS] [EXTENDS <super-class> [,<super-class2>*] ]"
        + " [COLLECTION <collectionId>*] [COLLECTIONS <total-collection-number>] [ABSTRACT]";
  }

  public String getUndoCommand() {
    return "drop class " + className;
  }

}
