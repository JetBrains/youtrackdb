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

package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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
   * @return index definition instance
   */
  public static IndexDefinition createIndexDefinition(
      final SchemaClass oClass,
      final List<String> fieldNames,
      final List<PropertyTypeInternal> types,
      List<Collate> collates,
      String indexKind) {
    checkTypes(oClass, fieldNames, types);

    if (fieldNames.size() == 1) {
      Collate collate = null;
      PropertyTypeInternal linkedType = null;
      var type = types.getFirst();
      var field = fieldNames.getFirst();
      final var fieldName =
          SchemaClassImpl.decodeClassName(
              adjustFieldName(oClass, extractFieldName(field)));
      if (collates != null) {
        collate = collates.getFirst();
      }
      var property = oClass.getProperty(fieldName);
      if (property != null) {
        if (collate == null) {
          collate = property.getCollate();
        }
        linkedType = PropertyTypeInternal.convertFromPublicType(property.getLinkedType());
      }

      final var indexBy = extractMapIndexSpecifier(field);
      return createSingleFieldIndexDefinition(
          oClass.getName(), fieldName, type, linkedType, collate, indexKind, indexBy);
    } else {
      return createMultipleFieldIndexDefinition(
          oClass, fieldNames, types, collates, indexKind);
    }
  }

  /**
   * Extract field name from '<property> [by key|value]' field format.
   *
   * @param fieldDefinition definition of field
   * @return extracted property name
   */
  public static String extractFieldName(final String fieldDefinition) {
    var fieldNameParts = FILED_NAME_PATTERN.split(fieldDefinition);
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

    var result = new StringBuilder();
    result.append(fieldNameParts[0]);
    for (var i = 1; i < fieldNameParts.length; i++) {
      result.append(" ");
      result.append(fieldNameParts[i]);
    }
    return result.toString();
  }

  private static IndexDefinition createMultipleFieldIndexDefinition(
      final SchemaClass oClass,
      final List<String> fieldsToIndex,
      final List<PropertyTypeInternal> types,
      List<Collate> collates,
      String indexKind) {
    final var className = oClass.getName();
    final var compositeIndex = new CompositeIndexDefinition(className);

    for (int i = 0, fieldsToIndexSize = fieldsToIndex.size(); i < fieldsToIndexSize; i++) {
      Collate collate = null;
      PropertyTypeInternal linkedType = null;
      var type = types.get(i);
      if (collates != null) {
        collate = collates.get(i);
      }

      var field = fieldsToIndex.get(i);
      final var fieldName =
          SchemaClassImpl.decodeClassName(
              adjustFieldName(oClass, extractFieldName(field)));
      var property = oClass.getProperty(fieldName);
      if (property != null) {
        if (collate == null) {
          collate = property.getCollate();
        }
        linkedType = PropertyTypeInternal.convertFromPublicType(property.getLinkedType());
      }
      final var indexBy = extractMapIndexSpecifier(field);

      compositeIndex.addIndex(
          createSingleFieldIndexDefinition(
              className, fieldName, type, linkedType, collate, indexKind, indexBy));
    }

    return compositeIndex;
  }

  private static void checkTypes(SchemaClass oClass,
      List<String> fieldNames,
      List<PropertyTypeInternal> types) {
    if (fieldNames.size() != types.size()) {
      throw new IllegalArgumentException(
          "Count of field names doesn't match count of field types. It was "
              + fieldNames.size()
              + " fields, but "
              + types.size()
              + " types.");
    }

    for (int i = 0, fieldNamesSize = fieldNames.size(); i < fieldNamesSize; i++) {
      final var fieldName = fieldNames.get(i);
      final var type = types.get(i);

      final var property = oClass.getProperty(fieldName);
      if (property != null && type != PropertyTypeInternal.convertFromPublicType(
          property.getType())) {
        throw new IllegalArgumentException("Property type list not match with real property types");
      }
    }
  }

  public static IndexDefinition createSingleFieldIndexDefinition(
      final String className,
      final String fieldName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      Collate collate,
      final String indexKind,
      final PropertyMapIndexDefinition.INDEX_BY indexBy) {
    // TODO: let index implementations name their preferences_
    if (type.equals(PropertyTypeInternal.EMBEDDED)) {
      if (indexKind.equals("FULLTEXT")) {
        throw new UnsupportedOperationException(
            "Fulltext index does not support embedded types: " + type);
      }
    }

    final IndexDefinition indexDefinition;

    final PropertyTypeInternal indexType;
    if (type == PropertyTypeInternal.EMBEDDEDMAP || type == PropertyTypeInternal.LINKMAP) {

      if (indexBy == null) {
        throw new IllegalArgumentException(
            "Illegal field name format, should be '<property> [by key|value]' but was '"
                + fieldName
                + '\'');
      }
      if (indexBy.equals(PropertyMapIndexDefinition.INDEX_BY.KEY)) {
        indexType = PropertyTypeInternal.STRING;
      } else {
        if (type == PropertyTypeInternal.LINKMAP) {
          indexType = PropertyTypeInternal.LINK;
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
    } else if (type.equals(PropertyTypeInternal.EMBEDDEDLIST)
        || type.equals(PropertyTypeInternal.EMBEDDEDSET)
        || type.equals(PropertyTypeInternal.LINKLIST)
        || type.equals(PropertyTypeInternal.LINKSET)) {
      if (type.equals(PropertyTypeInternal.LINKSET)) {
        indexType = PropertyTypeInternal.LINK;
      } else if (type.equals(PropertyTypeInternal.LINKLIST)) {
        indexType = PropertyTypeInternal.LINK;
      } else {
        indexType = linkedType;
        if (indexType == null) {
          throw new IndexException(
              "Linked type was not provided. You should provide linked type for embedded"
                  + " collections that are going to be indexed.");
        }
      }
      indexDefinition = new PropertyListIndexDefinition(className, fieldName, indexType);
    } else if (type.equals(PropertyTypeInternal.LINKBAG)) {
      indexDefinition = new PropertyLinkBagIndexDefinition(className, fieldName);
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
    final var fieldNameParts = FILED_NAME_PATTERN.split(fieldName);
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

  private static String adjustFieldName(final SchemaClass clazz,
      final String fieldName) {
    final var property = clazz.getProperty(fieldName);
    if (property != null) {
      return property.getName();
    } else {
      return fieldName;
    }
  }
}
