/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public abstract class CommandExecutorSQLSetAware extends CommandExecutorSQLAbstract {

  protected static final String KEYWORD_SET = "SET";
  protected static final String KEYWORD_CONTENT = "CONTENT";

  protected EntityImpl content = null;
  protected int parameterCounter = 0;

  protected void parseContent() {
    if (!parserIsEnded() && !parserGetLastWord().equals(KEYWORD_WHERE)) {
      content = parseJSON();
    }

    if (content == null) {
      throwSyntaxErrorException("Content not provided. Example: CONTENT { \"name\": \"Jay\" }");
    }
  }

  protected void parseSetFields(final SchemaClass iClass, final List<Pair<String, Object>> fields,
      DatabaseSessionInternal session) {
    String fieldName;
    String fieldValue;

    while (!parserIsEnded()
        && (fields.size() == 0
        || parserGetLastSeparator() == ','
        || parserGetCurrentChar() == ',')) {
      fieldName = parserRequiredWord(false, "Field name expected");
      if (fieldName.equalsIgnoreCase(KEYWORD_WHERE)) {
        parserGoBack();
        break;
      }

      parserNextChars(false, true, "=");
      fieldValue = parserRequiredWord(false, "Value expected", " =><,\r\n");

      // INSERT TRANSFORMED FIELD VALUE
      final Object v = convertValue(iClass, fieldName, getFieldValueCountingParameters(fieldValue),
          session);

      fields.add(new Pair(fieldName, v));
      parserSkipWhiteSpaces();
    }

    if (fields.size() == 0) {
      throwParsingException(
          "Entries to set <field> = <value> are missed. Example: name = 'Bill', salary = 300.2");
    }
  }

  protected SchemaClass extractClassFromTarget(String iTarget) {
    // CLASS
    if (!iTarget.toUpperCase(Locale.ENGLISH).startsWith(CommandExecutorSQLAbstract.CLUSTER_PREFIX)
        && !iTarget.startsWith(CommandExecutorSQLAbstract.INDEX_PREFIX)) {

      if (iTarget.toUpperCase(Locale.ENGLISH).startsWith(CommandExecutorSQLAbstract.CLASS_PREFIX))
      // REMOVE CLASS PREFIX
      {
        iTarget = iTarget.substring(CommandExecutorSQLAbstract.CLASS_PREFIX.length());
      }

      if (iTarget.charAt(0) == RID.PREFIX) {
        return getDatabase()
            .getMetadata()
            .getImmutableSchemaSnapshot()
            .getClassByClusterId(new RecordId(iTarget).getClusterId());
      }

      return getDatabase().getMetadata().getImmutableSchemaSnapshot().getClass(iTarget);
    }
    // CLUSTER
    if (iTarget
        .toUpperCase(Locale.ENGLISH)
        .startsWith(CommandExecutorSQLAbstract.CLUSTER_PREFIX)) {
      String clusterName =
          iTarget.substring(CommandExecutorSQLAbstract.CLUSTER_PREFIX.length()).trim();
      DatabaseSessionInternal db = getDatabase();
      if (clusterName.startsWith("[") && clusterName.endsWith("]")) {
        String[] clusterNames = clusterName.substring(1, clusterName.length() - 1).split(",");
        SchemaClass candidateClass = null;
        for (String cName : clusterNames) {
          final int clusterId = db.getClusterIdByName(cName.trim());
          if (clusterId < 0) {
            return null;
          }
          SchemaClass aClass =
              db.getMetadata().getImmutableSchemaSnapshot().getClassByClusterId(clusterId);
          if (aClass == null) {
            return null;
          }
          if (candidateClass == null
              || candidateClass.equals(aClass)
              || candidateClass.isSubClassOf(db, aClass)) {
            candidateClass = aClass;
          } else if (!candidateClass.isSuperClassOf(db, aClass)) {
            return null;
          }
        }
        return candidateClass;
      } else {
        final int clusterId = db.getClusterIdByName(clusterName);
        if (clusterId >= 0) {
          return db.getMetadata().getImmutableSchemaSnapshot().getClassByClusterId(clusterId);
        }
      }
    }
    return null;
  }

  protected Object convertValue(SchemaClass iClass, String fieldName, Object v,
      DatabaseSessionInternal session) {
    if (iClass != null) {
      // CHECK TYPE AND CONVERT IF NEEDED
      final SchemaProperty p = iClass.getProperty(session, fieldName);
      if (p != null) {
        final SchemaClass embeddedType = p.getLinkedClass(context.getDatabase());

        switch (p.getType()) {
          case EMBEDDED:
            // CONVERT MAP IN DOCUMENTS ASSIGNING THE CLASS TAKEN FROM SCHEMA
            if (v instanceof Map) {
              v = createDocumentFromMap(embeddedType, (Map<String, Object>) v);
            }
            break;

          case EMBEDDEDSET:
            // CONVERT MAPS IN DOCUMENTS ASSIGNING THE CLASS TAKEN FROM SCHEMA
            if (v instanceof Map) {
              return createDocumentFromMap(embeddedType, (Map<String, Object>) v);
            } else if (MultiValue.isMultiValue(v)) {
              final Set set = new HashSet();

              for (Object o : MultiValue.getMultiValueIterable(v)) {
                if (o instanceof Map) {
                  final EntityImpl entity =
                      createDocumentFromMap(embeddedType, (Map<String, Object>) o);
                  set.add(entity);
                } else if (o instanceof Identifiable) {
                  set.add(((Identifiable) o).getRecord());
                } else {
                  set.add(o);
                }
              }

              v = set;
            }
            break;

          case EMBEDDEDLIST:
            // CONVERT MAPS IN DOCUMENTS ASSIGNING THE CLASS TAKEN FROM SCHEMA
            if (v instanceof Map) {
              return createDocumentFromMap(embeddedType, (Map<String, Object>) v);
            } else if (MultiValue.isMultiValue(v)) {
              final List set = new ArrayList();

              for (Object o : MultiValue.getMultiValueIterable(v)) {
                if (o instanceof Map) {
                  final EntityImpl entity =
                      createDocumentFromMap(embeddedType, (Map<String, Object>) o);
                  set.add(entity);
                } else if (o instanceof Identifiable) {
                  set.add(((Identifiable) o).getRecord());
                } else {
                  set.add(o);
                }
              }

              v = set;
            }
            break;

          case EMBEDDEDMAP:
            // CONVERT MAPS IN DOCUMENTS ASSIGNING THE CLASS TAKEN FROM SCHEMA
            if (v instanceof Map) {
              final Map<String, Object> map = new HashMap<String, Object>();

              for (Map.Entry<String, Object> entry : ((Map<String, Object>) v).entrySet()) {
                if (entry.getValue() instanceof Map) {
                  final EntityImpl entity =
                      createDocumentFromMap(embeddedType, (Map<String, Object>) entry.getValue());
                  map.put(entry.getKey(), entity);
                } else if (entry.getValue() instanceof Identifiable) {
                  map.put(entry.getKey(), ((Identifiable) entry.getValue()).getRecord());
                } else {
                  map.put(entry.getKey(), entry.getValue());
                }
              }

              v = map;
            }
            break;
        }
      }
    }
    return v;
  }

  private EntityImpl createDocumentFromMap(SchemaClass embeddedType, Map<String, Object> o) {
    final EntityImpl entity = new EntityImpl();
    if (embeddedType != null) {
      entity.setClassName(embeddedType.getName());
    }

    entity.fromMap(o);
    return entity;
  }

  @Override
  public long getDistributedTimeout() {
    return getDatabase()
        .getConfiguration()
        .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_TASK_SYNCH_TIMEOUT);
  }

  protected Object getFieldValueCountingParameters(String fieldValue) {
    if (fieldValue.trim().equals("?")) {
      parameterCounter++;
    }
    return SQLHelper.parseValue(this, fieldValue, context, true);
  }

  protected EntityImpl parseJSON() {
    final String contentAsString = parserRequiredWord(false, "JSON expected").trim();
    final EntityImpl json = new EntityImpl();
    json.fromJSON(contentAsString);
    parserSkipWhiteSpaces();
    return json;
  }
}
