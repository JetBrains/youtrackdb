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
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.PatternConst;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * SQL CREATE INDEX command: Create a new index against a property.
 *
 * <p>
 *
 * <p>Supports following grammar: <br>
 * "CREATE" "INDEX" &lt;indexName&gt; ["ON" &lt;className&gt; "(" &lt;propName&gt; (","
 * &lt;propName&gt;)* ")"] &lt;indexType&gt; [&lt;keyType&gt; ("," &lt;keyType&gt;)*]
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLCreateIndex extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_CREATE = "CREATE";
  public static final String KEYWORD_INDEX = "INDEX";
  public static final String KEYWORD_ON = "ON";
  public static final String KEYWORD_METADATA = "METADATA";
  public static final String KEYWORD_ENGINE = "ENGINE";

  private String indexName;
  private SchemaClass oClass;
  private String[] fields;
  private SchemaClass.INDEX_TYPE indexType;
  private PropertyTypeInternal[] keyTypes;
  private byte serializerKeyId;
  private String engine;
  private Map<String, Object> metadata = null;
  private String[] collates;

  public CommandExecutorSQLCreateIndex parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      final var word = new StringBuilder();

      var oldPos = 0;
      var pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CREATE)) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Keyword " + KEYWORD_CREATE + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_INDEX)) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Keyword " + KEYWORD_INDEX + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Expected index name. Use " + getSyntax(), parserText, oldPos);
      }

      indexName = decodeClassName(word.toString());

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Index type requested. Use " + getSyntax(), parserText, oldPos + 1);
      }

      if (word.toString().equals(KEYWORD_ON)) {
        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
        if (pos == -1) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "Expected class name. Use " + getSyntax(), parserText, oldPos);
        }
        oldPos = pos;
        oClass = findClass(session, decodeClassName(word.toString()));

        if (oClass == null) {
          throw new CommandExecutionException(session.getDatabaseName(),
              "Class " + word + " not found");
        }

        pos = parserTextUpperCase.indexOf(')');
        if (pos == -1) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "No right bracket found. Use " + getSyntax(), parserText, oldPos);
        }

        final var props = parserText.substring(oldPos, pos).trim().substring(1);

        List<String> propList = new ArrayList<String>();
        Collections.addAll(propList, PatternConst.PATTERN_COMMA_SEPARATED.split(props.trim()));

        fields = new String[propList.size()];
        propList.toArray(fields);

        for (var i = 0; i < fields.length; i++) {
          final var fieldName = fields[i];

          final var collatePos = fieldName.toUpperCase(Locale.ENGLISH).indexOf(" COLLATE ");

          if (collatePos > 0) {
            if (collates == null) {
              collates = new String[fields.length];
            }

            collates[i] =
                fieldName
                    .substring(collatePos + " COLLATE ".length())
                    .toLowerCase(Locale.ENGLISH)
                    .trim();
            fields[i] = fieldName.substring(0, collatePos);
          } else {
            if (collates != null) {
              collates[i] = null;
            }
          }
          fields[i] = decodeClassName(fields[i]);
        }

        for (var propToIndex : fields) {
          checkMapIndexSpecifier(propToIndex, parserText, oldPos);

          propList.add(propToIndex);
        }

        oldPos = pos + 1;
        pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
        if (pos == -1) {
          throw new CommandSQLParsingException(session,
              "Index type requested. Use " + getSyntax(), parserText, oldPos + 1);
        }
      } else {
        if (indexName.indexOf('.') > 0) {
          final var parts = indexName.split("\\.");

          oClass = findClass(session, parts[0]);
          if (oClass == null) {
            throw new CommandExecutionException(session.getDatabaseName(),
                "Class " + parts[0] + " not found");
          }

          fields = new String[]{parts[1]};
        }
      }

      indexType = SchemaClass.INDEX_TYPE.valueOf(word.toString());

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);

      if (word.toString().equals(KEYWORD_ENGINE)) {
        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
        oldPos = pos;
        engine = word.toString().toUpperCase(Locale.ENGLISH);
      } else {
        parserGoBack();
      }

      final var configPos = parserTextUpperCase.indexOf(KEYWORD_METADATA, oldPos);

      if (configPos > -1) {
        final var configString =
            parserText.substring(configPos + KEYWORD_METADATA.length()).trim();
        var doc = new EntityImpl(session);
        doc.updateFromJSON(configString);
        metadata = doc.toMap();
      }

      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos != -1
          && !word.toString().equalsIgnoreCase("NULL")
          && !word.toString().equalsIgnoreCase(KEYWORD_METADATA)) {
        final String typesString;
        if (configPos > -1) {
          typesString = parserTextUpperCase.substring(oldPos, configPos).trim();
        } else {
          typesString = parserTextUpperCase.substring(oldPos).trim();
        }

        if (word.toString().equalsIgnoreCase("RUNTIME")) {
          oldPos = pos;
          pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);

          serializerKeyId = Byte.parseByte(word.toString());
        } else {
          var keyTypeList = new ArrayList<PropertyTypeInternal>();
          for (var typeName : PatternConst.PATTERN_COMMA_SEPARATED.split(typesString)) {
            keyTypeList.add(PropertyTypeInternal.valueOf(typeName));
          }

          keyTypes = new PropertyTypeInternal[keyTypeList.size()];
          keyTypeList.toArray(keyTypes);

          if (fields != null && fields.length != 0 && fields.length != keyTypes.length) {
            throw new CommandSQLParsingException(session.getDatabaseName(),
                "Count of fields does not match with count of property types. "
                    + "Fields: "
                    + Arrays.toString(fields)
                    + "; Types: "
                    + Arrays.toString(keyTypes),
                parserText, oldPos);
          }
        }
      }

    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  /**
   * Execute the CREATE INDEX.
   */
  @Nullable
  @SuppressWarnings("rawtypes")
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (indexName == null) {
      throw new CommandExecutionException(session.getDatabaseName(),
          "Cannot execute the command because it has not been parsed yet");
    }

    final Index idx;
    List<Collate> collatesList = null;

    if (collates != null) {
      collatesList = new ArrayList<Collate>();

      for (var collate : collates) {
        if (collate != null) {
          final var col = SQLEngine.getCollate(collate);
          collatesList.add(col);
        } else {
          collatesList.add(null);
        }
      }
    }

    if (fields == null || fields.length == 0) {
      throw new DatabaseException(session,
          "Impossible to create an index without specify the key type or the associated"
              + " property");
    } else {
      if ((keyTypes == null || keyTypes.length == 0) && collates == null) {
        oClass.createIndex(indexName, indexType.toString(), null, metadata, engine,
            fields);
        idx = session.getMetadata().getIndexManagerInternal().getIndex(session, indexName);
      } else {
        final List<PropertyTypeInternal> fieldTypeList;
        if (keyTypes == null) {
          for (final var fieldName : fields) {
            if (!fieldName.equals("@rid") && !oClass.existsProperty(fieldName)) {
              throw new IndexException(session,
                  "Index with name : '"
                      + indexName
                      + "' cannot be created on class : '"
                      + oClass.getName()
                      + "' because field: '"
                      + fieldName
                      + "' is absent in class definition.");
            }
          }
          fieldTypeList = ((SchemaClassInternal) oClass).extractFieldTypes(fields);
        } else {
          fieldTypeList = Arrays.asList(keyTypes);
        }

        final var idxDef =
            IndexDefinitionFactory.createIndexDefinition(
                oClass,
                Arrays.asList(fields),
                fieldTypeList,
                collatesList,
                indexType.toString());

        idx =
            session
                .getMetadata()
                .getIndexManagerInternal()
                .createIndex(
                    session,
                    indexName,
                    indexType.name(),
                    idxDef,
                    oClass.getPolymorphicCollectionIds(),
                    null,
                    metadata,
                    engine);
      }
    }

    if (idx != null) {
      return idx.size(session);
    }

    return null;
  }

  @Override
  public String getSyntax() {
    return "CREATE INDEX <name> [ON <class-name> (prop-names [COLLATE <collate>])] <type>"
        + " [<key-type>] [ENGINE <engine>] [METADATA {JSON Index Metadata Document}]";
  }

  private SchemaClass findClass(DatabaseSessionInternal db, String part) {
    return db.getMetadata().getSchema().getClass(part);
  }

  private void checkMapIndexSpecifier(final String fieldName, final String text, final int pos) {
    final var fieldNameParts = PatternConst.PATTERN_SPACES.split(fieldName);
    if (fieldNameParts.length == 1) {
      return;
    }

    if (fieldNameParts.length == 3) {
      if ("by".equals(fieldNameParts[1].toLowerCase(Locale.ENGLISH))) {
        try {
          PropertyMapIndexDefinition.INDEX_BY.valueOf(
              fieldNameParts[2].toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException iae) {
          throw BaseException.wrapException(
              new CommandSQLParsingException(
                  "Illegal field name format, should be '<property> [by key|value]' but was '"
                      + fieldName
                      + "'",
                  text, pos),
              iae, (String) null);
        }
        return;
      }
      throw new CommandSQLParsingException(
          "Illegal field name format, should be '<property> [by key|value]' but was '"
              + fieldName
              + "'",
          text, pos);
    }

    throw new CommandSQLParsingException(
        "Illegal field name format, should be '<property> [by key|value]' but was '"
            + fieldName
            + "'",
        text, pos);
  }

  public String getUndoCommand() {
    return "drop index " + indexName;
  }
}
