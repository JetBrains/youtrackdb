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
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationBinaryComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationCollectionComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationLinkbagComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationMapComparable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.validation.ValidationStringComparable;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
public final class SchemaPropertySnapshot implements ImmutableSchemaProperty {

  private final String name;
  private final String fullName;
  private final PropertyTypeInternal type;
  private final String description;

  // do not make it volatile it is already thread safe.
  private SchemaClassSnapshot linkedClass = null;

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
  private final SchemaClassSnapshot owner;
  private final Integer id;
  private final boolean readOnly;
  private final Comparable<Object> minComparable;
  private final Comparable<Object> maxComparable;
  private final Collection<Index> allIndexes;

  private int hashCode;

  public SchemaPropertySnapshot(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaPropertyEntity property,
      SchemaClassSnapshot owner) {
    name = property.getName();
    fullName = property.getFullName();
    type = property.getPropertyType();
    description = property.getDescription();

    var linkedClass = property.getLinkedClass();
    if (linkedClass != null) {
      linkedClassName = property.getName();
    } else {
      linkedClassName = null;
    }

    linkedType = property.getLinkedPropertyType();
    notNull = property.isNotNull();
    var collateName = property.getCollate();
    if (collateName != null) {
      collate = SQLEngine.getCollate(collateName);
    } else {
      collate = null;
    }
    mandatory = property.isMandatory();
    min = property.getMin();
    max = property.getMax();
    defaultValue = property.getDefaultValue();
    regexp = property.getRegexp();
    customProperties = new HashMap<>();

    var customPropertyNames = property.getCustomPropertyNames();
    for (var propertyName : customPropertyNames) {
      customProperties.put(propertyName, property.getCustomProperty(propertyName));
    }

    this.owner = owner;
    id = property.getGlobalPropertyId();
    readOnly = property.isReadonly();

    this.minComparable = createMinComparable(session, min, type, fullName);
    this.maxComparable = createMaxComparable(session, max, type, fullName);

    this.allIndexes = owner.getClassInvolvedIndexes(name);
  }

  public static @Nullable Comparable<Object> createMaxComparable(
      @Nonnull DatabaseSessionEmbedded session, @Nullable String max,
      @Nonnull PropertyTypeInternal type, @Nonnull String fullName) {
    Comparable<Object> maxComparable = null;
    if (max != null) {
      if (type.equals(PropertyTypeInternal.STRING)) {
        var conv = safeConvert(session, max, Integer.class, "max", fullName);
        if (conv != null) {

          maxComparable = new ValidationStringComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.BINARY)) {
        var conv = safeConvert(session, max, Integer.class, "max", fullName);
        if (conv != null) {

          maxComparable = new ValidationBinaryComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.DATE)) {
        // This is needed because a date is valid in any time range of the day.
        var maxDate = (Date) safeConvert(session, max, type.getDefaultJavaType(), "max", fullName);
        if (maxDate != null) {
          var cal = Calendar.getInstance();
          cal.setTime(maxDate);
          cal.add(Calendar.DAY_OF_MONTH, 1);
          maxDate = new Date(cal.getTime().getTime() - 1);
          //noinspection unchecked,rawtypes
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
        //noinspection unchecked
        maxComparable = (Comparable<Object>) safeConvert(session, max, type.getDefaultJavaType(),
            "max", fullName);
      } else if (type.equals(PropertyTypeInternal.EMBEDDEDLIST)
          || type.equals(PropertyTypeInternal.EMBEDDEDSET)
          || type.equals(PropertyTypeInternal.LINKLIST)
          || type.equals(PropertyTypeInternal.LINKSET)) {
        var conv = safeConvert(session, max, Integer.class, "max", fullName);
        if (conv != null) {

          maxComparable = new ValidationCollectionComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.LINKBAG)) {
        var conv = safeConvert(session, max, Integer.class, "max", fullName);
        if (conv != null) {

          maxComparable = new ValidationLinkbagComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.EMBEDDEDMAP) || type.equals(
          PropertyTypeInternal.LINKMAP)) {
        var conv = safeConvert(session, max, Integer.class, "max", fullName);
        if (conv != null) {
          maxComparable = new ValidationMapComparable(conv);
        }
      }
    }
    return maxComparable;
  }

  public static @Nullable Comparable<Object> createMinComparable(
      @Nonnull DatabaseSessionEmbedded session, @Nullable String min,
      @Nonnull PropertyTypeInternal type,
      @Nonnull String fullName) {
    Comparable<Object> minComparable = null;
    if (min != null) {
      if (type.equals(PropertyTypeInternal.STRING)) {
        var conv = safeConvert(session, min, Integer.class, "min", fullName);
        if (conv != null) {
          minComparable = new ValidationStringComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.BINARY)) {
        var conv = safeConvert(session, min, Integer.class, "min", fullName);
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
        //noinspection unchecked
        minComparable = (Comparable<Object>) safeConvert(session, min, type.getDefaultJavaType(),
            "min", fullName);
      } else if (type == PropertyTypeInternal.EMBEDDEDLIST
          || type == PropertyTypeInternal.EMBEDDEDSET
          || type == PropertyTypeInternal.LINKLIST
          || type == PropertyTypeInternal.LINKSET) {
        var conv = safeConvert(session, min, Integer.class, "min", fullName);
        if (conv != null) {

          minComparable = new ValidationCollectionComparable(conv);
        }
      } else if (type.equals(PropertyTypeInternal.LINKBAG)) {
        var conv = safeConvert(session, min, Integer.class, "min", fullName);
        if (conv != null) {

          minComparable = new ValidationLinkbagComparable(conv);
        }
      } else if (type == PropertyTypeInternal.EMBEDDEDMAP || type ==
          PropertyTypeInternal.LINKMAP) {
        var conv = safeConvert(session, min, Integer.class, "min", fullName);
        if (conv != null) {

          minComparable = new ValidationMapComparable(conv);
        }
      }
    }
    return minComparable;
  }

  private static <T> T safeConvert(DatabaseSessionInternal session, Object value, Class<T> target,
      String type, String fullName) {
    T mc;
    try {
      mc = PropertyTypeInternal.convert(session, value, target);
    } catch (RuntimeException e) {
      LogManager.instance()
          .error(session, "Error initializing %s value check on property %s", e, type, fullName);
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
  public String getDescription() {
    return description;
  }

  @Override
  public PropertyTypeInternal getType() {
    return type;
  }

  @Nullable
  @Override
  public SchemaClassSnapshot getLinkedClass() {
    if (linkedClassName == null) {
      return null;
    }

    if (linkedClass != null) {
      return linkedClass;
    }

    var schema = owner.getSchema();
    linkedClass = schema.getClass(linkedClassName);

    return linkedClass;
  }

  @Nullable
  @Override
  public PropertyTypeInternal getLinkedType() {
    return linkedType;
  }

  @Override
  public boolean isNotNull() {
    return notNull;
  }

  @Override
  public Collate getCollate() {
    return collate;
  }


  @Override
  public boolean isMandatory() {
    return mandatory;
  }

  @Override
  public boolean isReadonly() {
    return readOnly;
  }

  @Override
  public String getMin() {
    return min;
  }

  @Override
  public String getMax() {
    return max;
  }

  @Override
  public String getDefaultValue() {
    return defaultValue;
  }

  @Override
  public Collection<String> getIndexNames() {
    return this.allIndexes.stream().map(Index::getName).toList();
  }

  @Override
  public Collection<Index> getIndexes() {
    return allIndexes;
  }

  @Override
  public String getRegexp() {
    return regexp;
  }

  @Override
  public String getCustomProperty(String iName) {
    return customProperties.get(iName);
  }

  @Override
  public Set<String> getCustomPropertyNames() {
    return Collections.unmodifiableSet(customProperties.keySet());
  }

  @Override
  public SchemaClassSnapshot getOwnerClass() {
    return owner;
  }

  @Override
  public Integer getId() {
    return id;
  }

  @Override
  public String toString() {
    return name + " (type=" + type + ")";
  }

  @Override
  public Comparable<Object> getMaxComparable() {
    return maxComparable;
  }

  @Override
  public Comparable<Object> getMinComparable() {
    return minComparable;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return null;
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

    if (obj instanceof SchemaProperty schemaProperty) {
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
