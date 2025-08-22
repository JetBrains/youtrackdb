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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.api.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationBinaryComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationCollectionComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationLinkbagComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationMapComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationStringComparable;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @since 10/21/14
 */
public class ImmutableSchemaProperty implements SchemaPropertyInternal {

  private final String name;
  private final String fullName;
  private final PropertyTypeInternal type;
  private final String description;

  // do not make it volatile it is already thread safe.
  private SchemaClass linkedClass = null;

  private final String linkedClassName;

  private final PropertyTypeInternal linkedType;
  private final boolean notNull;
  private final Collate collate;
  private final boolean mandatory;
  private final String min;
  private final String max;
  private final String defaultValue;
  private final String regexp;
  private final Map<String, String> customProperties;
  private final SchemaClass owner;
  private final Integer id;
  private final boolean readOnly;
  private final Comparable<Object> minComparable;
  private final Comparable<Object> maxComparable;
  private final Collection<Index> allIndexes;

  private int hashCode;

  public ImmutableSchemaProperty(@Nonnull DatabaseSessionInternal session,
      @Nonnull SchemaPropertyImpl property,
      SchemaImmutableClass owner) {
    name = property.getName();
    fullName = property.getFullName(session);
    type = PropertyTypeInternal.convertFromPublicType(property.getType());
    description = property.getDescription();

    if (property.getLinkedClass(session) != null) {
      linkedClassName = property.getLinkedClass(session).getName();
    } else {
      linkedClassName = null;
    }

    linkedType = property.getLinkedType();
    notNull = property.isNotNull();
    collate = property.getCollate();
    mandatory = property.isMandatory();
    min = property.getMin();
    max = property.getMax();
    defaultValue = property.getDefaultValue();
    regexp = property.getRegexp();
    customProperties = new HashMap<String, String>();

    for (var key : property.getCustomKeys()) {
      customProperties.put(key, property.getCustom(key));
    }

    this.owner = owner;
    id = property.getId();
    readOnly = property.isReadonly();
    Comparable<Object> minComparable = null;
    if (min != null) {
      if (type.equals(PropertyTypeInternal.STRING)) {
        var conv = safeConvert(session, min, Integer.class, "min");
        if (conv != null) {
          minComparable = new ValidationStringComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.BINARY)) {
        var conv = safeConvert(session, min, Integer.class, "min");
        if (conv != null) {
          minComparable = new ValidationBinaryComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.DATE)
          || type.equals(PropertyTypeInternal.BYTE)
          || type.equals(PropertyTypeInternal.SHORT)
          || type.equals(PropertyTypeInternal.INTEGER)
          || type.equals(PropertyTypeInternal.LONG)
          || type.equals(PropertyTypeInternal.FLOAT)
          || type.equals(PropertyTypeInternal.DOUBLE)
          || type.equals(PropertyTypeInternal.DECIMAL)
          || type.equals(PropertyTypeInternal.DATETIME)) {
        minComparable = (Comparable<Object>) safeConvert(session, min, type.getDefaultJavaType(),
            "min");
      } else if (type.equals(PropertyTypeInternal.EMBEDDEDLIST)
          || type.equals(PropertyTypeInternal.EMBEDDEDSET)
          || type.equals(PropertyTypeInternal.LINKLIST)
          || type.equals(PropertyTypeInternal.LINKSET)) {
        var conv = safeConvert(session, min, Integer.class, "min");
        if (conv != null) {

          minComparable = new ValidationCollectionComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.LINKBAG)) {
        var conv = safeConvert(session, min, Integer.class, "min");
        if (conv != null) {

          minComparable = new ValidationLinkbagComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.EMBEDDEDMAP) || type.equals(
          PropertyTypeInternal.LINKMAP)) {
        var conv = safeConvert(session, min, Integer.class, "min");
        if (conv != null) {

          minComparable = new ValidationMapComparable(conv);
        }
      }
    }
    this.minComparable = minComparable;
    Comparable<Object> maxComparable = null;
    if (max != null) {
      if (type.equals(PropertyTypeInternal.STRING)) {
        var conv = safeConvert(session, max, Integer.class, "max");
        if (conv != null) {

          maxComparable = new ValidationStringComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.BINARY)) {
        var conv = safeConvert(session, max, Integer.class, "max");
        if (conv != null) {

          maxComparable = new ValidationBinaryComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.DATE)) {
        // This is needed because a date is valid in any time range of the day.
        var maxDate = (Date) safeConvert(session, max, type.getDefaultJavaType(), "max");
        if (maxDate != null) {
          var cal = Calendar.getInstance();
          cal.setTime(maxDate);
          cal.add(Calendar.DAY_OF_MONTH, 1);
          maxDate = new Date(cal.getTime().getTime() - 1);
          maxComparable = (Comparable) maxDate;
        }
      } else if (type.equals(PropertyTypeInternal.BYTE)
          || type.equals(PropertyTypeInternal.SHORT)
          || type.equals(PropertyTypeInternal.INTEGER)
          || type.equals(PropertyTypeInternal.LONG)
          || type.equals(PropertyTypeInternal.FLOAT)
          || type.equals(PropertyTypeInternal.DOUBLE)
          || type.equals(PropertyTypeInternal.DECIMAL)
          || type.equals(PropertyTypeInternal.DATETIME)) {
        maxComparable = (Comparable<Object>) safeConvert(session, max, type.getDefaultJavaType(),
            "max");
      } else if (type.equals(PropertyTypeInternal.EMBEDDEDLIST)
          || type.equals(PropertyTypeInternal.EMBEDDEDSET)
          || type.equals(PropertyTypeInternal.LINKLIST)
          || type.equals(PropertyTypeInternal.LINKSET)) {
        var conv = safeConvert(session, max, Integer.class, "max");
        if (conv != null) {

          maxComparable = new ValidationCollectionComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.LINKBAG)) {
        var conv = safeConvert(session, max, Integer.class, "max");
        if (conv != null) {

          maxComparable = new ValidationLinkbagComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.EMBEDDEDMAP) || type.equals(
          PropertyTypeInternal.LINKMAP)) {
        var conv = safeConvert(session, max, Integer.class, "max");
        if (conv != null) {
          maxComparable = new ValidationMapComparable(conv);
        }
      }
    }

    this.maxComparable = maxComparable;
    this.allIndexes = property.getAllIndexesInternal(session);
  }

  private <T> T safeConvert(DatabaseSessionInternal session, Object value, Class<T> target,
      String type) {
    T mc;
    try {
      mc = PropertyTypeInternal.convert(session, value, target);
    } catch (RuntimeException e) {
      LogManager.instance()
          .error(this, "Error initializing %s value check on property %s", e, type, fullName);
      mc = null;
    }
    return mc;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getFullName() {
    return fullName;
  }

  @Override
  public SchemaProperty setName(String iName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public SchemaProperty setDescription(String iDescription) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void set(ATTRIBUTES attribute, Object iValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PropertyType getType() {
    return type.getPublicPropertyType();
  }

  @Nullable
  @Override
  public SchemaClass getLinkedClass() {
    if (linkedClassName == null) {
      return null;
    }

    if (linkedClass != null) {
      return linkedClass;
    }

    Schema schema = ((SchemaImmutableClass) owner).getSchema();
    linkedClass = schema.getClass(linkedClassName);

    return linkedClass;
  }

  @Override
  public SchemaProperty setLinkedClass(SchemaClass oClass) {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public PropertyType getLinkedType() {
    if (linkedType == null) {
      return null;
    }

    return linkedType.getPublicPropertyType();
  }

  @Override
  public SchemaProperty setLinkedType(@Nonnull PropertyType type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isNotNull() {
    return notNull;
  }

  @Override
  public SchemaProperty setNotNull(boolean iNotNull) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collate getCollate() {
    return collate;
  }

  @Override
  public SchemaProperty setCollate(String iCollateName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaProperty setCollate(Collate collate) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isMandatory() {
    return mandatory;
  }

  @Override
  public SchemaProperty setMandatory(boolean mandatory) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isReadonly() {
    return readOnly;
  }

  @Override
  public SchemaProperty setReadonly(boolean iReadonly) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getMin() {
    return min;
  }

  @Override
  public SchemaProperty setMin(String min) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getMax() {
    return max;
  }

  @Override
  public SchemaProperty setMax(String max) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getDefaultValue() {
    return defaultValue;
  }

  @Override
  public SchemaProperty setDefaultValue(String defaultValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String createIndex(INDEX_TYPE iType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String createIndex(String iType) {
    throw new UnsupportedOperationException();
  }


  @Override
  public String createIndex(String iType, Map<String, Object> metadata) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String createIndex(INDEX_TYPE iType, Map<String, Object> metadata) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<String> getAllIndexes() {
    return this.allIndexes.stream().map(Index::getName).toList();
  }


  @Override
  public String getRegexp() {
    return regexp;
  }

  @Override
  public SchemaProperty setRegexp(String regexp) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaProperty setType(PropertyType iType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getCustom(String iName) {
    return customProperties.get(iName);
  }

  @Override
  public SchemaProperty setCustom(String iName, String iValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeCustom(String iName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearCustom() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> getCustomKeys() {
    return Collections.unmodifiableSet(customProperties.keySet());
  }

  @Override
  public SchemaClass getOwnerClass() {
    return owner;
  }

  @Override
  public Object get(ATTRIBUTES attribute) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (attribute) {
      case LINKEDCLASS -> getLinkedClass();
      case LINKEDTYPE -> linkedType;
      case MIN -> min;
      case MANDATORY -> mandatory;
      case READONLY -> readOnly;
      case MAX -> max;
      case DEFAULT -> defaultValue;
      case NAME -> name;
      case NOTNULL -> notNull;
      case REGEXP -> regexp;
      case TYPE -> type;
      case COLLATE -> collate;
      case DESCRIPTION -> description;
      default -> throw new IllegalArgumentException("Cannot find attribute '" + attribute + "'");
    };

  }

  @Override
  public Integer getId() {
    return id;
  }

  @Override
  public String toString() {
    return name + " (type=" + type + ")";
  }

  public Comparable<Object> getMaxComparable() {
    return maxComparable;
  }

  public Comparable<Object> getMinComparable() {
    return minComparable;
  }

  @Override
  public Collection<Index> getAllIndexesInternal() {
    return this.allIndexes;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return null;
  }

  @Override
  public PropertyTypeInternal getTypeInternal() {
    return type;
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      hashCode = name.hashCode() + 31 * owner.getName().hashCode();
    }

    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }

    if (obj instanceof SchemaPropertyInternal schemaProperty) {
      if (schemaProperty.getBoundToSession() != null) {
        return false;
      }

      return name.equals(schemaProperty.getName())
          && owner.getName()
          .equals(schemaProperty.getOwnerClass().getName());
    }

    return false;
  }
}
