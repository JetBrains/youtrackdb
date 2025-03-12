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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.parser.BaseParser;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.CommandExecutorSQLAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.CommandExecutorSQLResultsetDelegate;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQLResultset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Target parser.
 */
public class SQLTarget extends BaseParser {

  protected final boolean empty;
  protected final CommandContext context;
  protected String targetVariable;
  protected String targetQuery;
  protected Iterable<? extends Identifiable> targetRecords;
  protected Map<String, String> targetClusters;
  protected Map<String, String> targetClasses;

  protected String targetIndex;

  protected String targetIndexValues;
  protected boolean targetIndexValuesAsc;

  public SQLTarget(final String iText, final CommandContext iContext) {
    super();
    context = iContext;
    parserText = iText;
    parserTextUpperCase = SQLPredicate.upperCase(iText);

    try {
      empty = !extractTargets(iContext.getDatabaseSession());

    } catch (QueryParsingException e) {
      if (e.getText() == null)
      // QUERY EXCEPTION BUT WITHOUT TEXT: NEST IT
      {
        throw BaseException.wrapException(
            new QueryParsingException(iContext.getDatabaseSession().getDatabaseName(),
                "Error on parsing query", parserText, parserGetCurrentPosition()),
            e, iContext.getDatabaseSession().getDatabaseName());
      }

      throw e;
    } catch (CommandExecutionException ex) {
      throw ex;
    } catch (Exception e) {
      throw BaseException.wrapException(
          new QueryParsingException(iContext.getDatabaseSession().getDatabaseName(),
              "Error on parsing query", parserText, parserGetCurrentPosition()),
          e, iContext.getDatabaseSession().getDatabaseName());
    }
  }

  public Map<String, String> getTargetClusters() {
    return targetClusters;
  }

  public Map<String, String> getTargetClasses() {
    return targetClasses;
  }

  public Iterable<? extends Identifiable> getTargetRecords() {
    return targetRecords;
  }

  public String getTargetQuery() {
    return targetQuery;
  }

  public String getTargetIndex() {
    return targetIndex;
  }

  public String getTargetIndexValues() {
    return targetIndexValues;
  }

  public boolean isTargetIndexValuesAsc() {
    return targetIndexValuesAsc;
  }

  @Override
  public String toString() {
    if (targetClasses != null) {
      return "class " + targetClasses.keySet();
    } else if (targetClusters != null) {
      return "cluster " + targetClusters.keySet();
    }
    if (targetIndex != null) {
      return "index " + targetIndex;
    }
    if (targetRecords != null) {
      return "records from " + targetRecords.getClass().getSimpleName();
    }
    if (targetVariable != null) {
      return "variable " + targetVariable;
    }
    return "?";
  }

  public String getTargetVariable() {
    return targetVariable;
  }

  public boolean isEmpty() {
    return empty;
  }

  @Override
  protected void throwSyntaxErrorException(String dbName, String iText) {
    throw new CommandSQLParsingException(dbName,
        iText + ". Use " + getSyntax(), parserText, parserGetPreviousPosition());
  }

  @SuppressWarnings("unchecked")
  private boolean extractTargets(DatabaseSessionInternal session) {
    parserSkipWhiteSpaces();

    if (parserIsEnded()) {
      throw new QueryParsingException(null, "No query target found", parserText, 0);
    }

    final var c = parserGetCurrentChar();

    if (c == '$') {
      targetVariable = parserRequiredWord(false, "No valid target", session.getDatabaseName());
      targetVariable = targetVariable.substring(1);
    } else if (c == StringSerializerHelper.LINK || Character.isDigit(c)) {
      // UNIQUE RID
      targetRecords = new ArrayList<Identifiable>();
      ((List<Identifiable>) targetRecords)
          .add(new RecordId(parserRequiredWord(true, "No valid RID", session.getDatabaseName())));

    } else if (c == StringSerializerHelper.EMBEDDED_BEGIN) {
      // SUB QUERY
      final var subText = new StringBuilder(256);
      parserSetCurrentPosition(
          StringSerializerHelper.getEmbedded(parserText, parserGetCurrentPosition(), -1, subText)
              + 1);
      final CommandSQL subCommand = new CommandSQLResultset(subText.toString());

      final var executor =
          (CommandExecutorSQLResultsetDelegate)
              session.getSharedContext()
                  .getYouTrackDB()
                  .getScriptManager()
                  .getCommandManager()
                  .getExecutor(subCommand);
      executor.setContext(context);
      var commandContext = new BasicCommandContext();
      context.setChild(commandContext);

      subCommand.setContext(commandContext);
      executor.setProgressListener(subCommand.getProgressListener());
      executor.parse(session, subCommand);
      var childContext = executor.getContext();
      if (childContext != null) {
        childContext.setParent(context);
      }


      targetQuery = subText.toString();
      targetRecords = executor.toIterable(session, null);

    } else if (c == StringSerializerHelper.LIST_BEGIN) {
      // COLLECTION OF RIDS
      final List<String> rids = new ArrayList<String>();
      parserSetCurrentPosition(
          StringSerializerHelper.getCollection(parserText, parserGetCurrentPosition(), rids));

      targetRecords = new ArrayList<Identifiable>();
      for (var rid : rids) {
        ((List<Identifiable>) targetRecords).add(new RecordId(rid));
      }

      parserMoveCurrentPosition(1);
    } else {

      while (!parserIsEnded()
          && (targetClasses == null
          && targetClusters == null
          && targetIndex == null
          && targetIndexValues == null
          && targetRecords == null)) {
        var originalSubjectName = parserRequiredWord(false, "Target not found",
            session.getDatabaseName());
        var subjectName = originalSubjectName.toUpperCase(Locale.ENGLISH);

        final String alias;
        if (subjectName.equals("AS")) {
          alias = parserRequiredWord(true, "Alias not found", session.getDatabaseName());
        } else {
          alias = subjectName;
        }

        final var subjectToMatch = subjectName;
        if (subjectToMatch.startsWith(CommandExecutorSQLAbstract.CLUSTER_PREFIX)) {
          // REGISTER AS CLUSTER
          if (targetClusters == null) {
            targetClusters = new HashMap<String, String>();
          }
          final var clusterNames =
              subjectName.substring(CommandExecutorSQLAbstract.CLUSTER_PREFIX.length());
          if (clusterNames.startsWith("[") && clusterNames.endsWith("]")) {
            final Collection<String> clusters = new HashSet<String>(3);
            StringSerializerHelper.getCollection(clusterNames, 0, clusters);
            for (var cl : clusters) {
              targetClusters.put(cl, cl);
            }
          } else {
            targetClusters.put(clusterNames, alias);
          }

        } else if (subjectToMatch.startsWith(CommandExecutorSQLAbstract.INDEX_PREFIX)) {
          // REGISTER AS INDEX
          targetIndex =
              originalSubjectName.substring(CommandExecutorSQLAbstract.INDEX_PREFIX.length());
        } else if (subjectToMatch.startsWith(CommandExecutorSQLAbstract.METADATA_PREFIX)) {
          // METADATA
          final var metadataTarget =
              subjectName.substring(CommandExecutorSQLAbstract.METADATA_PREFIX.length());
          targetRecords = new ArrayList<Identifiable>();

          if (metadataTarget.equals(CommandExecutorSQLAbstract.METADATA_SCHEMA)) {
            ((ArrayList<Identifiable>) targetRecords)
                .add(
                    new RecordId(
                        session
                            .getStorageInfo()
                            .getConfiguration()
                            .getSchemaRecordId()));
          } else if (metadataTarget.equals(CommandExecutorSQLAbstract.METADATA_INDEXMGR)) {
            ((ArrayList<Identifiable>) targetRecords)
                .add(
                    new RecordId(
                        session
                            .getStorageInfo()
                            .getConfiguration()
                            .getIndexMgrRecordId()));
          } else {
            throw new QueryParsingException(session.getDatabaseName(),
                "Metadata entity not supported: " + metadataTarget);
          }

        } else if (subjectToMatch.startsWith(CommandExecutorSQLAbstract.INDEX_VALUES_PREFIX)) {
          targetIndexValues =
              originalSubjectName.substring(
                  CommandExecutorSQLAbstract.INDEX_VALUES_PREFIX.length());
          targetIndexValuesAsc = true;
        } else if (subjectToMatch.startsWith(CommandExecutorSQLAbstract.INDEX_VALUES_ASC_PREFIX)) {
          targetIndexValues =
              originalSubjectName.substring(
                  CommandExecutorSQLAbstract.INDEX_VALUES_ASC_PREFIX.length());
          targetIndexValuesAsc = true;
        } else if (subjectToMatch.startsWith(
            CommandExecutorSQLAbstract.INDEX_VALUES_DESC_PREFIX)) {
          targetIndexValues =
              originalSubjectName.substring(
                  CommandExecutorSQLAbstract.INDEX_VALUES_DESC_PREFIX.length());
          targetIndexValuesAsc = false;
        } else {
          if (subjectToMatch.startsWith(CommandExecutorSQLAbstract.CLASS_PREFIX))
          // REGISTER AS CLASS
          {
            subjectName = subjectName.substring(CommandExecutorSQLAbstract.CLASS_PREFIX.length());
          }

          // REGISTER AS CLASS
          if (targetClasses == null) {
            targetClasses = new HashMap<String, String>();
          }

          final var cls =
              session
                  .getMetadata()
                  .getImmutableSchemaSnapshot()
                  .getClass(subjectName);
          if (cls == null) {
            throw new CommandExecutionException(session,
                "Class '"
                    + subjectName
                    + "' was not found in database '"
                    + session.getDatabaseName()
                    + "'");
          }

          targetClasses.put(cls.getName(), alias);
        }
      }
    }

    return !parserIsEnded();
  }
}
