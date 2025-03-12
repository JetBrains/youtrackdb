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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.PatternConst;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLUpdateItem;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("unchecked")
public class ServerCommandPostStudio extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"POST|studio/*"};

  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    DatabaseSessionInternal db = null;

    try {
      final var urlParts =
          checkSyntax(iRequest.getUrl(), 3, "Syntax error: studio/<database>/<context>");

      db = getProfiledDatabaseSessionInstance(iRequest);

      final var req = iRequest.getContent();

      // PARSE PARAMETERS
      String operation = null;
      String rid = null;
      String className = null;

      final Map<String, String> fields = new HashMap<String, String>();

      final var params = req.split("&");
      String value;

      for (var p : params) {
        var pairs = p.split("=");
        value = pairs.length == 1 ? null : pairs[1];

        if ("oper".equals(pairs[0])) {
          operation = value;
        } else if ("0".equals(pairs[0])) {
          rid = value;
        } else if ("1".equals(pairs[0])) {
          className = value;
        } else if (pairs[0].startsWith(EntityHelper.ATTRIBUTE_CLASS)) {
          className = value;
        } else if (pairs[0].startsWith("@") || pairs[0].equals("id")) {
          continue;
        } else {
          fields.put(pairs[0], value);
        }
      }

      var context = urlParts[2];
      if ("document".equals(context)) {
        executeDocument(db, iRequest, iResponse, operation, rid, className, fields);
      } else if ("classes".equals(context)) {
        executeClasses(iRequest, iResponse, db, operation, rid, className, fields);
      } else if ("clusters".equals(context)) {
        executeClusters(iRequest, iResponse, db, operation, rid, className, fields);
      } else if ("classProperties".equals(context)) {
        executeClassProperties(iRequest, iResponse, db, operation, rid, className, fields);
      } else if ("classIndexes".equals(context)) {
        executeClassIndexes(iRequest, iResponse, db, operation, rid, className, fields);
      }

    } finally {
      if (db != null) {
        db.close();
      }
    }
    return false;
  }

  private void executeClassProperties(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final DatabaseSessionInternal db,
      final String operation,
      final String rid,
      final String className,
      final Map<String, String> fields)
      throws IOException {
    // GET THE TARGET CLASS
    final var cls = db.getMetadata().getSchema().getClass(rid);
    if (cls == null) {
      iResponse.send(
          HttpUtils.STATUS_INTERNALERROR_CODE,
          "Error",
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Error: Class '" + rid + "' not found.",
          null);
      return;
    }

    if ("add".equals(operation)) {
      iRequest.getData().commandInfo = "Studio add property";

      try {
        var type = PropertyType.valueOf(fields.get("type"));

        SchemaPropertyImpl prop;
        if (type == PropertyType.LINK
            || type == PropertyType.LINKLIST
            || type == PropertyType.LINKSET
            || type == PropertyType.LINKMAP) {
          prop =
              (SchemaPropertyImpl)
                  cls.createProperty(
                      fields.get("name"),
                      type, db.getMetadata().getSchema().getClass(fields.get("linkedClass")));
        } else {
          prop = (SchemaPropertyImpl) cls.createProperty(fields.get("name"), type);
        }

        if (fields.get("linkedType") != null) {
          prop.setLinkedType(db, PropertyType.valueOf(fields.get("linkedType")));
        }
        if (fields.get("mandatory") != null) {
          prop.setMandatory(db, "on".equals(fields.get("mandatory")));
        }
        if (fields.get("readonly") != null) {
          prop.setReadonly(db, "on".equals(fields.get("readonly")));
        }
        if (fields.get("notNull") != null) {
          prop.setNotNull(db, "on".equals(fields.get("notNull")));
        }
        if (fields.get("min") != null) {
          prop.setMin(db, fields.get("min"));
        }
        if (fields.get("max") != null) {
          prop.setMax(db, fields.get("max"));
        }

        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Property " + fields.get("name") + " created successfully",
            null);

      } catch (Exception e) {
        iResponse.send(
            HttpUtils.STATUS_INTERNALERROR_CODE,
            "Error on creating a new property in class " + rid + ": " + e,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Error on creating a new property in class " + rid + ": " + e,
            null);
      }
    } else if ("del".equals(operation)) {
      iRequest.getData().commandInfo = "Studio delete property";

      cls.dropProperty(className);

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Property " + fields.get("name") + " deleted successfully.",
          null);
    }
  }

  private void executeClasses(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final DatabaseSessionInternal db,
      final String operation,
      final String rid,
      final String className,
      final Map<String, String> fields)
      throws IOException {
    if ("add".equals(operation)) {
      iRequest.getData().commandInfo = "Studio add class";
      try {
        final var superClassName = fields.get("superClass");
        final SchemaClass superClass;
        if (superClassName != null) {
          superClass = db.getMetadata().getSchema().getClass(superClassName);
        } else {
          superClass = null;
        }

        db.getMetadata().getSchema()
            .createClass(fields.get("name"), superClass);

        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Class '"
                + rid
                + "' created successfully with id="
                + db.getMetadata().getSchema().getClasses().size(),
            null);

      } catch (Exception e) {
        iResponse.send(
            HttpUtils.STATUS_INTERNALERROR_CODE,
            "Error on creating the new class '" + rid + "': " + e,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Error on creating the new class '" + rid + "': " + e,
            null);
      }
    } else if ("del".equals(operation)) {
      iRequest.getData().commandInfo = "Studio delete class";

      db.getMetadata().getSchema().dropClass(rid);

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Class '" + rid + "' deleted successfully.",
          null);
    }
  }

  private static void executeClusters(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final DatabaseSessionInternal db,
      final String operation,
      final String rid,
      final String iClusterName,
      final Map<String, String> fields)
      throws IOException {
    if ("add".equals(operation)) {
      iRequest.getData().commandInfo = "Studio add cluster";

      var clusterId = db.addCluster(fields.get("name"));

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Cluster " + fields.get("name") + "' created successfully with id=" + clusterId,
          null);

    } else if ("del".equals(operation)) {
      iRequest.getData().commandInfo = "Studio delete cluster";

      db.dropCluster(rid);

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Cluster " + fields.get("name") + "' deleted successfully",
          null);
    }
  }

  private void executeDocument(
      DatabaseSessionInternal session, final HttpRequest iRequest,
      final HttpResponse iResponse,
      final String operation,
      final String rid,
      final String className,
      final Map<String, String> fields)
      throws IOException {
    if ("edit".equals(operation)) {
      iRequest.getData().commandInfo = "Studio edit entity";

      if (rid == null) {
        throw new IllegalArgumentException("Record ID not found in request");
      }

      var entity = (EntityImpl) session.loadEntity(new RecordId(rid));
      if (!Objects.equals(entity.getSchemaClassName(), className)) {
        throw new IllegalArgumentException(
            "Record has different class name than the one provided in the request "
                + entity.getSchemaClassName() + " != " + className);
      }
      // BIND ALL CHANGED FIELDS
      for (var f : fields.entrySet()) {
        final var oldValue = entity.getProperty(f.getKey());
        var userValue = f.getValue();

        if (userValue != null && userValue.equals("undefined")) {
          entity.removeProperty(f.getKey());
        } else {
          var newValue = RecordSerializerStringAbstract.getTypeValue(session, userValue);

          if (newValue != null) {
            if (newValue instanceof Collection) {
              final var array = new ArrayList<Object>();
              for (var s : (Collection<String>) newValue) {
                var v = RecordSerializerStringAbstract.getTypeValue(session, s);
                array.add(v);
              }
              newValue = array;
            }
          }

          if (oldValue != null && oldValue.equals(userValue))
          // NO CHANGES
          {
            continue;
          }

          var schemaClass = entity.getImmutableSchemaClass(session);
          var property = schemaClass != null ? schemaClass.getProperty(f.getKey()) : null;
          entity.setProperty(f.getKey(),
              SQLUpdateItem.cleanPropertyValue(newValue, session, property));
        }
      }

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Record " + rid + " updated successfully.",
          null);
    } else if ("add".equals(operation)) {
      iRequest.getData().commandInfo = "Studio create entity";

      final var entity = new EntityImpl(session, className);

      // BIND ALL CHANGED FIELDS
      for (var f : fields.entrySet()) {
        entity.field(f.getKey(), f.getValue());
      }

      iResponse.send(
          201,
          "OK",
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Record " + entity.getIdentity() + " updated successfully.",
          null);

    } else if ("del".equals(operation)) {
      iRequest.getData().commandInfo = "Studio delete entity";

      if (rid == null) {
        throw new IllegalArgumentException("Record ID not found in request");
      }

      final EntityImpl entity = new RecordId(rid).getRecord(session);
      entity.delete();
      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Record " + rid + " deleted successfully.",
          null);

    } else {
      iResponse.send(500, "Error", HttpUtils.CONTENT_TEXT_PLAIN, "Operation not supported", null);
    }
  }

  private static void executeClassIndexes(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final DatabaseSessionInternal session,
      final String operation,
      final String rid,
      final String className,
      final Map<String, String> fields)
      throws IOException {
    // GET THE TARGET CLASS
    final var cls = session.getMetadata().getSchemaInternal().getClassInternal(rid);
    if (cls == null) {
      iResponse.send(
          HttpUtils.STATUS_INTERNALERROR_CODE,
          "Error",
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Error: Class '" + rid + "' not found.",
          null);
      return;
    }

    if ("add".equals(operation)) {
      iRequest.getData().commandInfo = "Studio add index";

      try {
        final var fieldNames =
            PatternConst.PATTERN_COMMA_SEPARATED.split(fields.get("fields").trim());
        final var indexType = fields.get("type");

        cls.createIndex(fields.get("name"), indexType, fieldNames);

        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Index " + fields.get("name") + " created successfully",
            null);

      } catch (Exception e) {
        iResponse.send(
            HttpUtils.STATUS_INTERNALERROR_CODE,
            "Error on creating a new index for class " + rid + ": " + e,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Error on creating a new index for class " + rid + ": " + e,
            null);
      }
    } else if ("del".equals(operation)) {
      iRequest.getData().commandInfo = "Studio delete index";

      try {
        final var index = cls.getClassIndex(session, className);
        if (index == null) {
          iResponse.send(
              HttpUtils.STATUS_INTERNALERROR_CODE,
              "Error",
              HttpUtils.CONTENT_TEXT_PLAIN,
              "Error: Index '" + className + "' not found in class '" + rid + "'.",
              null);
          return;
        }

        session.getMetadata().getIndexManagerInternal().dropIndex(session, index.getName());

        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Index " + className + " deleted successfully.",
            null);
      } catch (Exception e) {
        iResponse.send(
            HttpUtils.STATUS_INTERNALERROR_CODE,
            "Error on deletion index '" + className + "' for class " + rid + ": " + e,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Error on deletion index '" + className + "' for class " + rid + ": " + e,
            null);
      }
    } else {
      iResponse.send(
          HttpUtils.STATUS_INTERNALERROR_CODE,
          "Error",
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Operation not supported",
          null);
    }
  }

  public String[] getNames() {
    return NAMES;
  }
}
