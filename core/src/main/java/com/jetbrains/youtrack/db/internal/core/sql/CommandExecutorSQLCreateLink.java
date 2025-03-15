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
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL CREATE LINK command: Transform a JOIN relationship to a physical LINK
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLCreateLink extends CommandExecutorSQLAbstract {

  public static final String KEYWORD_CREATE = "CREATE";
  public static final String KEYWORD_LINK = "LINK";
  private static final String KEYWORD_FROM = "FROM";
  private static final String KEYWORD_TO = "TO";
  private static final String KEYWORD_TYPE = "TYPE";

  private String destClassName;
  private String destField;
  private String sourceClassName;
  private String sourceField;
  private String linkName;
  private PropertyType linkType;
  private boolean inverse = false;

  public CommandExecutorSQLCreateLink parse(DatabaseSessionInternal session,
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
      if (pos == -1 || !word.toString().equals(KEYWORD_LINK)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_LINK + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_FROM + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      if (!word.toString().equalsIgnoreCase(KEYWORD_FROM)) {
        // GET THE LINK NAME
        linkName = word.toString();

        if (StringSerializerHelper.contains(linkName, ' ')) {
          throw new CommandSQLParsingException(session,
              "Link name '" + linkName + "' contains not valid characters", parserText, oldPos);
        }

        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      }

      if (word.toString().equalsIgnoreCase(KEYWORD_TYPE)) {
        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, pos, word, true);

        if (pos == -1) {
          throw new CommandSQLParsingException(session,
              "Link type missed. Use " + getSyntax(), parserText, oldPos);
        }

        linkType = PropertyType.valueOf(word.toString().toUpperCase(Locale.ENGLISH));

        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      }

      if (pos == -1 || !word.toString().equals(KEYWORD_FROM)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_FROM + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      var parts = word.toString().split("\\.");
      if (parts.length != 2) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      sourceClassName = parts[0];
      if (sourceClassName == null) {
        throw new CommandSQLParsingException(session, "Class not found", parserText, pos);
      }
      sourceField = parts[1];

      pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_TO)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_TO + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      parts = word.toString().split("\\.");
      if (parts.length != 2) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      destClassName = parts[0];
      if (destClassName == null) {
        throw new CommandSQLParsingException(session, "Class not found", parserText, pos);
      }
      destField = parts[1];

      pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      if (pos == -1) {
        return this;
      }

      if (!word.toString().equalsIgnoreCase("INVERSE")) {
        throw new CommandSQLParsingException(session,
            "Missed 'INVERSE'. Use " + getSyntax(), parserText, pos);
      }

      inverse = true;
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  /**
   * Execute the CREATE LINK.
   */
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (destField == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    if (session.getDatabaseOwner() == null) {
      throw new CommandSQLParsingException(session.getDatabaseName(),
          "This command supports only the database type DatabaseDocumentTx and type '"
              + session.getClass()
              + "' was found");
    }

    var sourceClass =
        session.getMetadata().getImmutableSchemaSnapshot().getClass(sourceClassName);
    if (sourceClass == null) {
      throw new CommandExecutionException(session,
          "Source class '" + sourceClassName + "' not found");
    }

    var destClass = session.getMetadata().getImmutableSchemaSnapshot()
        .getClass(destClassName);
    if (destClass == null) {
      throw new CommandExecutionException(session,
          "Destination class '" + destClassName + "' not found");
    }

    Object value;

    var cmd = "select from ";
    if (!EntityHelper.ATTRIBUTE_RID.equals(destField)) {
      cmd = "select from " + destClassName + " where " + destField + " = ";
    }

    List<EntityImpl> result;
    EntityImpl target;
    Object oldValue;
    long total = 0;

    if (linkName == null)
    // NO LINK NAME EXPRESSED: OVERWRITE THE SOURCE FIELD
    {
      linkName = sourceField;
    }

    boolean multipleRelationship;
    if (linkType != null)
    // DETERMINE BASED ON FORCED TYPE
    {
      multipleRelationship = linkType == PropertyType.LINKSET || linkType == PropertyType.LINKLIST;
    } else {
      multipleRelationship = false;
    }

    var totRecords = session.countClass(sourceClass.getName());
    long currRecord = 0;

    if (progressListener != null) {
      progressListener.onBegin(this, totRecords, false);
    }

    try {
      // BROWSE ALL THE RECORDS OF THE SOURCE CLASS
      var entityIterator = session.browseClass(sourceClass);
      while (entityIterator.hasNext()) {
        var entity = entityIterator.next();
        value = entity.getProperty(sourceField);

        if (value != null) {
          if (value instanceof EntityImpl || value instanceof RID) {
            // ALREADY CONVERTED
          } else if (value instanceof Collection<?>) {
            // TODO
          } else {
            // SEARCH THE DESTINATION RECORD
            target = null;

            if (!EntityHelper.ATTRIBUTE_RID.equals(destField) && value instanceof String) {
              if (((String) value).length() == 0) {
                value = null;
              } else {
                value = "'" + value + "'";
              }
            }

            result = session.command(new SQLSynchQuery<EntityImpl>(cmd + value))
                .execute(session);

            if (result == null || result.size() == 0) {
              value = null;
            } else if (result.size() > 1) {
              throw new CommandExecutionException(session,
                  "Cannot create link because multiple records was found in class '"
                      + destClass.getName()
                      + "' with value "
                      + value
                      + " in field '"
                      + destField
                      + "'");
            } else {
              target = result.get(0);
              value = target;
            }

            if (target != null && inverse) {
              // INVERSE RELATIONSHIP
              oldValue = target.getProperty(linkName);

              if (oldValue != null) {
                if (!multipleRelationship) {
                  multipleRelationship = true;
                }

                Collection<EntityImpl> coll;
                if (oldValue instanceof Collection) {
                  // ADD IT IN THE EXISTENT COLLECTION
                  coll = (Collection<EntityImpl>) oldValue;
                  target.setDirty();
                } else {
                  // CREATE A NEW COLLECTION FOR BOTH
                  coll = new ArrayList<EntityImpl>(2);
                  target.setProperty(linkName, coll);
                  coll.add((EntityImpl) oldValue);
                }
                coll.add(entity);
              } else {
                if (linkType != null) {
                  if (linkType == PropertyType.LINKSET) {
                    value = new LinkSet(target);
                    ((Set<Identifiable>) value).add(entity);
                  } else if (linkType == PropertyType.LINKLIST) {
                    value = new LinkList(target);
                    ((LinkList) value).add(entity);
                  } else
                  // IGNORE THE TYPE, SET IT AS LINK
                  {
                    value = entity;
                  }
                } else {
                  value = entity;
                }

                target.setProperty(linkName, value);
              }

            } else {
              // SET THE REFERENCE
              entity.setProperty(linkName, value);

            }

            total++;
          }
        }

        if (progressListener != null) {
          progressListener.onProgress(this, currRecord, currRecord * 100f / totRecords);
        }
      }

      if (total > 0) {
        if (inverse) {
          // REMOVE THE OLD PROPERTY IF ANY
          var prop = destClass.getProperty(linkName);
          destClass = session.getMetadata().getSchema().getClass(destClassName);
          if (prop != null) {
            destClass.dropProperty(linkName);
          }

          if (linkType == null) {
            linkType = multipleRelationship ? PropertyType.LINKSET : PropertyType.LINK;
          }

          // CREATE THE PROPERTY
          destClass.createProperty(linkName, linkType, sourceClass);

        } else {

          // REMOVE THE OLD PROPERTY IF ANY
          var prop = sourceClass.getProperty(linkName);
          sourceClass = session.getMetadata().getSchema().getClass(sourceClassName);
          if (prop != null) {
            sourceClass.dropProperty(linkName);
          }

          // CREATE THE PROPERTY
          sourceClass.createProperty(linkName, PropertyType.LINK, destClass);
        }
      }

      if (progressListener != null) {
        progressListener.onCompletition(session, this, true);
      }

    } catch (Exception e) {
      if (progressListener != null) {
        progressListener.onCompletition(session, this, false);
      }

      throw BaseException.wrapException(
          new CommandExecutionException(session, "Error on creation of links"), e, session);
    }
    return total;
  }

  @Override
  public String getSyntax() {
    return "CREATE LINK <link-name> [TYPE <link-type>] FROM <source-class>.<source-property> TO"
        + " <destination-class>.<destination-property> [INVERSE]";
  }
}
