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

package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Contains helper methods for {@link IndexDefinition} creation.
 *
 * <p><b>IMPORTANT:</b> This class designed for internal usage only.
 */
public class IndexDefinitionFactory {

  private static final Pattern FILED_NAME_PATTERN = Pattern.compile("\\s+");

  /**
   * Creates an instance of {@link IndexDefinition} for automatic index.
   *
   * @param oClass     class which will be indexed
   * @param fieldNames list of properties which will be indexed. Format should be '<property> [by
   *                   key|value]', use 'by key' or 'by value' to describe how to index maps. By
   *                   default maps indexed by key
   * @param types      types of indexed properties
   * @param collates
   * @param indexKind
   * @param algorithm
   * @return index definition instance
   */
  public static IndexDefinition createIndexDefinition(
      DatabaseSession session,
      final SchemaClass oClass,
      final List<String> fieldNames,
      final List<PropertyType> types,
      List<Collate> collates,
      String indexKind,
      String algorithm) {
    checkTypes(session, oClass, fieldNames, types);

    if (fieldNames.size() == 1) {
      Collate collate = null;
      PropertyType linkedType = null;
      PropertyType type = types.get(0);
      String field = fieldNames.get(0);
      final String fieldName =
          SchemaClassImpl.decodeClassName(
              adjustFieldName(session, oClass, extractFieldName(field)));
      if (collates != null) {
        collate = collates.get(0);
      }
      SchemaProperty property = oClass.getProperty(session, fieldName);
      if (property != null) {
        if (collate == null) {
          collate = property.getCollate();
        }
        linkedType = property.getLinkedType();
      }

      final PropertyMapIndexDefinition.INDEX_BY indexBy = extractMapIndexSpecifier(field);
      return createSingleFieldIndexDefinition(
          oClass.getName(), fieldName, type, linkedType, collate, indexKind, indexBy);
    } else {
      return createMultipleFieldIndexDefinition(session,
          oClass, fieldNames, types, collates, indexKind, algorithm);
    }
  }

  /**
   * Extract field name from '<property> [by key|value]' field format.
   *
   * @param fieldDefinition definition of field
   * @return extracted property name
   */
  public static String extractFieldName(final String fieldDefinition) {
    String[] fieldNameParts = FILED_NAME_PATTERN.split(fieldDefinition);
    if (fieldNameParts.length == 0) {
      throw new IllegalArgumentException(
          "Illegal field name format, should be '<property> [by key|value]' but was '"
              + fieldDefinition
              + '\'');
    }
    if (fieldNameParts.length == 3 && "by".equalsIgnoreCase(fieldNameParts[1])) {
      return fieldNameParts[0];
    }

    if (fieldNameParts.length == 1) {
      return fieldDefinition;
    }

    StringBuilder result = new StringBuilder();
    result.append(fieldNameParts[0]);
    for (int i = 1; i < fieldNameParts.length; i++) {
      result.append(" ");
      result.append(fieldNameParts[i]);
    }
    return result.toString();
  }

  private static IndexDefinition createMultipleFieldIndexDefinition(
      DatabaseSession session,
      final SchemaClass oClass,
      final List<String> fieldsToIndex,
      final List<PropertyType> types,
      List<Collate> collates,
      String indexKind,
      String algorithm) {
    final IndexFactory factory = Indexes.getFactory(indexKind, algorithm);
    final String className = oClass.getName();
    final CompositeIndexDefinition compositeIndex = new CompositeIndexDefinition(className);

    for (int i = 0, fieldsToIndexSize = fieldsToIndex.size(); i < fieldsToIndexSize; i++) {
      Collate collate = null;
      PropertyType linkedType = null;
      PropertyType type = types.get(i);
      if (collates != null) {
        collate = collates.get(i);
      }
      String field = fieldsToIndex.get(i);
      final String fieldName =
          SchemaClassImpl.decodeClassName(
              adjustFieldName(session, oClass, extractFieldName(field)));
      SchemaProperty property = oClass.getProperty(session, fieldName);
      if (property != null) {
        if (collate == null) {
          collate = property.getCollate();
        }
        linkedType = property.getLinkedType();
      }
      final PropertyMapIndexDefinition.INDEX_BY indexBy = extractMapIndexSpecifier(field);

      compositeIndex.addIndex(
          createSingleFieldIndexDefinition(
              className, fieldName, type, linkedType, collate, indexKind, indexBy));
    }
    return compositeIndex;
  }

  private static void checkTypes(
      DatabaseSession session,
      SchemaClass oClass, List<String> fieldNames,
      List<PropertyType> types) {
    if (fieldNames.size() != types.size()) {
      throw new IllegalArgumentException(
          "Count of field names doesn't match count of field types. It was "
              + fieldNames.size()
              + " fields, but "
              + types.size()
              + " types.");
    }

    for (int i = 0, fieldNamesSize = fieldNames.size(); i < fieldNamesSize; i++) {
      final String fieldName = fieldNames.get(i);
      final PropertyType type = types.get(i);

      final SchemaProperty property = oClass.getProperty(session, fieldName);
      if (property != null && !type.equals(property.getType())) {
        throw new IllegalArgumentException("Property type list not match with real property types");
      }
    }
  }

  public static IndexDefinition createSingleFieldIndexDefinition(
      final String className,
      final String fieldName,
      final PropertyType type,
      final PropertyType linkedType,
      Collate collate,
      final String indexKind,
      final PropertyMapIndexDefinition.INDEX_BY indexBy) {
    // TODO: let index implementations name their preferences_
    if (type.equals(PropertyType.EMBEDDED)) {
      if (indexKind.equals("FULLTEXT")) {
        throw new UnsupportedOperationException(
            "Fulltext index does not support embedded types: " + type);
      }
    }

    final IndexDefinition indexDefinition;

    final PropertyType indexType;
    if (type == PropertyType.EMBEDDEDMAP || type == PropertyType.LINKMAP) {

      if (indexBy == null) {
        throw new IllegalArgumentException(
            "Illegal field name format, should be '<property> [by key|value]' but was '"
                + fieldName
                + '\'');
      }
      if (indexBy.equals(PropertyMapIndexDefinition.INDEX_BY.KEY)) {
        indexType = PropertyType.STRING;
      } else {
        if (type == PropertyType.LINKMAP) {
          indexType = PropertyType.LINK;
        } else {
          indexType = linkedType;
          if (indexType == null) {
            throw new IndexException(
                "Linked type was not provided. You should provide linked type for embedded"
                    + " collections that are going to be indexed.");
          }
        }
      }
      indexDefinition = new PropertyMapIndexDefinition(className, fieldName, indexType, indexBy);
    } else if (type.equals(PropertyType.EMBEDDEDLIST)
        || type.equals(PropertyType.EMBEDDEDSET)
        || type.equals(PropertyType.LINKLIST)
        || type.equals(PropertyType.LINKSET)) {
      if (type.equals(PropertyType.LINKSET)) {
        indexType = PropertyType.LINK;
      } else if (type.equals(PropertyType.LINKLIST)) {
        indexType = PropertyType.LINK;
      } else {
        indexType = linkedType;
        if (indexType == null) {
          throw new IndexException(
              "Linked type was not provided. You should provide linked type for embedded"
                  + " collections that are going to be indexed.");
        }
      }
      indexDefinition = new PropertyListIndexDefinition(className, fieldName, indexType);
    } else if (type.equals(PropertyType.LINKBAG)) {
      indexDefinition = new PropertyRidBagIndexDefinition(className, fieldName);
    } else {
      indexDefinition = new PropertyIndexDefinition(className, fieldName, type);
    }
    if (collate != null) {
      indexDefinition.setCollate(collate);
    }
    return indexDefinition;
  }

  private static PropertyMapIndexDefinition.INDEX_BY extractMapIndexSpecifier(
      final String fieldName) {
    final String[] fieldNameParts = FILED_NAME_PATTERN.split(fieldName);
    if (fieldNameParts.length == 1) {
      return PropertyMapIndexDefinition.INDEX_BY.KEY;
    }
    if (fieldNameParts.length == 3) {

      if ("by".equalsIgnoreCase(fieldNameParts[1])) {
        try {
          return PropertyMapIndexDefinition.INDEX_BY.valueOf(fieldNameParts[2].toUpperCase());
        } catch (IllegalArgumentException iae) {
          throw new IllegalArgumentException(
              "Illegal field name format, should be '<property> [by key|value]' but was '"
                  + fieldName
                  + '\'',
              iae);
        }
      }
    }
    throw new IllegalArgumentException(
        "Illegal field name format, should be '<property> [by key|value]' but was '"
            + fieldName
            + '\'');
  }

  private static String adjustFieldName(DatabaseSession session, final SchemaClass clazz,
      final String fieldName) {
    final SchemaProperty property = clazz.getProperty(session, fieldName);
    if (property != null) {
      return property.getName();
    } else {
      return fieldName;
    }
  }
}
