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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.IOException;
import java.util.Map;

public class ServerCommandPostProperty extends ServerCommandAuthenticatedDbAbstract {

  private static final String PROPERTY_TYPE_JSON_FIELD = "propertyType";
  private static final String LINKED_CLASS_JSON_FIELD = "linkedClass";
  private static final String LINKED_TYPE_JSON_FIELD = "linkedType";
  private static final String[] NAMES = {"POST|property/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      if (iRequest.getContent() == null || iRequest.getContent().length() <= 0) {
        return addSingleProperty(iRequest, iResponse, db);
      } else {
        return addMultipreProperties(iRequest, iResponse, db);
      }
    }
  }

  @SuppressWarnings("unused")
  protected static boolean addSingleProperty(
      final HttpRequest iRequest, final HttpResponse iResponse,
      final DatabaseSessionInternal db)
      throws InterruptedException, IOException {
    var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            4,
            "Syntax error:"
                + " property/<database>/<class-name>/<property-name>/[<property-type>]/[<link-type>]");

    iRequest.getData().commandInfo = "Create property";
    iRequest.getData().commandDetail = urlParts[2] + "." + urlParts[3];

    if (db.getMetadata().getSchema().getClass(urlParts[2]) == null) {
      throw new IllegalArgumentException("Invalid class '" + urlParts[2] + "'");
    }

    final var cls = db.getMetadata().getSchema().getClass(urlParts[2]);

    final var propertyName = urlParts[3];

    final var propertyType =
        urlParts.length > 4 ? PropertyType.valueOf(urlParts[4]) : PropertyType.STRING;

    switch (propertyType) {
      case LINKLIST:
      case LINKMAP:
      case LINKSET:
      case LINK: {
        /* try link as PropertyType */
        PropertyType linkType = null;
        SchemaClass linkClass = null;
        if (urlParts.length >= 6) {
          try {
            linkType = PropertyType.valueOf(urlParts[5]);
          } catch (IllegalArgumentException ex) {
          }

          if (linkType == null) {
            linkClass = db.getMetadata().getSchema().getClass(urlParts[5]);
            if (linkClass == null) {
              throw new IllegalArgumentException(
                  "linked type declared as "
                      + urlParts[5]
                      + " can be either a Type or a Class, use the JSON entity usage instead.");
            }
          }
        }

        if (linkType != null) {
          final var prop = cls.createProperty(propertyName, propertyType, linkType);
        } else if (linkClass != null) {
          final var prop = cls.createProperty(propertyName, propertyType, linkClass);
        } else {
          final var prop = cls.createProperty(propertyName, propertyType);
        }
        break;
      }

      default:
        final var prop = cls.createProperty(propertyName, propertyType);
        break;
    }

    iResponse.send(
        HttpUtils.STATUS_CREATED_CODE,
        HttpUtils.STATUS_CREATED_DESCRIPTION,
        HttpUtils.CONTENT_TEXT_PLAIN,
        cls.getProperties().size(),
        null);

    return false;
  }

  @SuppressWarnings({"unchecked", "unused"})
  protected static boolean addMultipreProperties(
      final HttpRequest iRequest, final HttpResponse iResponse,
      final DatabaseSessionInternal db)
      throws IOException {
    var urlParts =
        checkSyntax(iRequest.getUrl(), 3, "Syntax error: property/<database>/<class-name>");

    iRequest.getData().commandInfo = "Create property";
    iRequest.getData().commandDetail = urlParts[2];

    if (db.getMetadata().getSchema().getClass(urlParts[2]) == null) {
      throw new IllegalArgumentException("Invalid class '" + urlParts[2] + "'");
    }

    final var cls = db.getMetadata().getSchema().getClass(urlParts[2]);

    final var properties = JSONSerializerJackson.INSTANCE.mapFromJson(iRequest.getContent());

    for (var entry : properties.entrySet()) {
      var propertyName = entry.getKey();
      final var map = (Map<String, String>) entry.getValue();
      final var propertyType = PropertyType.valueOf(map.get(PROPERTY_TYPE_JSON_FIELD));
      switch (propertyType) {
        case LINKLIST:
        case LINKMAP:
        case LINKSET: {
          final var linkType = map.get(LINKED_TYPE_JSON_FIELD);
          final var linkClass = map.get(LINKED_CLASS_JSON_FIELD);
          if (linkType != null) {
            final var prop =
                cls.createProperty(propertyName, propertyType, PropertyType.valueOf(linkType));
          } else if (linkClass != null) {
            final var prop =
                cls.createProperty(
                    propertyName, propertyType, db.getMetadata().getSchema().getClass(linkClass));
          } else {
            throw new IllegalArgumentException(
                "property named "
                    + propertyName
                    + " is declared as "
                    + propertyType
                    + " but linked type is not declared");
          }
          break;
        }
        case LINK: {
          final var linkClass = map.get(LINKED_CLASS_JSON_FIELD);
          if (linkClass != null) {
            final var prop =
                cls.createProperty(
                    propertyName, propertyType, db.getMetadata().getSchema().getClass(linkClass));
          } else {
            throw new IllegalArgumentException(
                "property named "
                    + propertyName
                    + " is declared as "
                    + propertyType
                    + " but linked Class is not declared");
          }
          break;
        }

        default:
          final var prop = cls.createProperty(propertyName, propertyType);
          break;
      }
    }

    iResponse.send(
        HttpUtils.STATUS_CREATED_CODE,
        HttpUtils.STATUS_CREATED_DESCRIPTION,
        HttpUtils.CONTENT_TEXT_PLAIN,
        cls.getProperties().size(),
        null);

    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
