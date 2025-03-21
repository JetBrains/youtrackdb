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
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * FIND REFERENCES command: Finds references to records in all or part of database
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLFindReferences extends CommandExecutorSQLEarlyResultsetAbstract {

  public static final String KEYWORD_FIND = "FIND";
  public static final String KEYWORD_REFERENCES = "REFERENCES";

  private final Set<RID> recordIds = new HashSet<RID>();
  private String classList;
  private StringBuilder subQuery;

  public CommandExecutorSQLFindReferences parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;
    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      parserRequiredKeyword(session.getDatabaseName(), KEYWORD_FIND);
      parserRequiredKeyword(session.getDatabaseName(), KEYWORD_REFERENCES);
      final var target = parserRequiredWord(true, "Expected <target>", " =><,\r\n",
          session.getDatabaseName());

      if (target.charAt(0) == '(') {
        subQuery = new StringBuilder();
        parserSetCurrentPosition(
            StringSerializerHelper.getEmbedded(
                parserText, parserGetPreviousPosition(), -1, subQuery));
      } else {
        try {
          final var rid = new RecordId(target);
          if (!rid.isValidPosition()) {
            throwParsingException(session.getDatabaseName(),
                "Record ID " + target + " is not valid");
          }
          recordIds.add(rid);

        } catch (IllegalArgumentException iae) {
          throw BaseException.wrapException(
              new CommandSQLParsingException(session,
                  "Error reading record Id", parserText, parserGetPreviousPosition()),
              iae, session);
        }
      }

      parserSkipWhiteSpaces();
      classList = parserOptionalWord(true);
      if (classList != null) {
        classList = parserTextUpperCase.substring(parserGetPreviousPosition());

        if (!classList.startsWith("[") || !classList.endsWith("]")) {
          throwParsingException(session.getDatabaseName(), "Class list must be contained in []");
        }
        // GET THE CLUSTER LIST TO SEARCH, IF NULL WILL SEARCH ENTIRE DATABASE
        classList = classList.substring(1, classList.length() - 1);
      }

      return this;
    } finally {
      textRequest.setText(originalQuery);
    }
  }

  /**
   * Execute the FIND REFERENCES.
   */
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (recordIds.isEmpty() && subQuery == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }


    return FindReferenceHelper.findReferences(recordIds, classList, session);
  }

  @Override
  public String getSyntax() {
    return "FIND REFERENCES <rid|<sub-query>> [class-list]";
  }
}
