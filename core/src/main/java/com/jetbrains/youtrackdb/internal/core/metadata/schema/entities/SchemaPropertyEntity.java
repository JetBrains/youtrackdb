package com.jetbrains.youtrackdb.internal.core.metadata.schema.entities;

import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;

public class SchemaPropertyEntity extends EntityImpl implements SchemaEntity {

  public interface PropertyNames {
    String CUSTOM_PROPERTIES = "customProperties";
    String GLOBAL_PROPERTY = "globalProperty";
    String NAME = "name";
    String TYPE = "type";
    String LINKED_CLASS = "linkedClass";
    String LINKED_TYPE = "linkedType";
    String NOT_NULL = "notNull";
    String COLLATE = "collate";
    String MAX = "max";
    String MIN = "min";
    String DEFAULT_VALUE = "defaultValue";
    String READ_ONLY = "readOnly";
    String REG_EXP = "regexp";
    String MANDATORY = "mandatory";
    String DESCRIPTION = "description";
  }

  public SchemaPropertyEntity(
      @Nonnull RecordIdInternal recordId, @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  public String getName() {
    return getPropertyInternal(PropertyNames.NAME);
  }

  public void setName(String name) {
    SchemaManager.checkPropertyNameIfValid(name);
    setPropertyInternal(PropertyNames.NAME, name);
  }

  public String getFullName() {
    var declaringClassLinkName = getOppositeLinkBagPropertyName(
        SchemaClassEntity.PropertyNames.DECLARED_PROPERTIES);
    LinkBag declaringClassLink = getPropertyInternal(declaringClassLinkName);
    if (declaringClassLink == null) {
      throw new IllegalStateException("Property '" + declaringClassLinkName + "' not found");
    }

    SchemaClassEntity declaringClass = session.load(declaringClassLink.iterator().next());
    return declaringClass.getName() + "." + getName();
  }

  @Nullable
  public PropertyTypeInternal getPropertyType() {
    String type = getPropertyInternal(PropertyNames.TYPE);
    if (type == null) {
      return null;
    }

    return PropertyTypeInternal.valueOf(type);
  }

  public void setPropertyType(@Nonnull PropertyTypeInternal type) {
    setPropertyInternal(PropertyNames.TYPE, type.name());
  }

  public String getType() {
    return getPropertyInternal(PropertyNames.TYPE);
  }

  public void setType(String type) {
    try {
      PropertyTypeInternal.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw new DatabaseException(session, "Invalid property type '" + type + "'");
    }

    setString(PropertyNames.TYPE, type);
  }

  public void setLinkedClass(@Nullable SchemaClassEntity entity) {
    setPropertyInternal(PropertyNames.LINKED_CLASS, entity);
  }

  @Nullable
  public SchemaClassEntity getLinkedClass() {
    RID link = getPropertyInternal(PropertyNames.LINKED_CLASS);
    if (link == null) {
      return null;
    }

    return session.load(link);
  }

  @Nullable
  public PropertyTypeInternal getLinkedPropertyType() {
    String linkedType = getPropertyInternal(PropertyNames.LINKED_TYPE);
    if (linkedType == null) {
      return null;
    }

    return PropertyTypeInternal.valueOf(linkedType);
  }

  public void setLinkedPropertyType(@Nullable PropertyTypeInternal type) {
    if (type == null) {
      setLinkedType(null);
    }

    setLinkedType(type.name());
  }

  public String getLinkedType() {
    return getPropertyInternal(PropertyNames.LINKED_TYPE);
  }

  public void setLinkedType(@Nullable String type) {
    try {
      PropertyTypeInternal.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw new DatabaseException(session, "Invalid property type '" + type + "'");
    }

    setString(PropertyNames.LINKED_TYPE, type);
  }

  public boolean isNotNull() {
    Boolean notNull = getProperty(PropertyNames.NOT_NULL);
    return Boolean.TRUE.equals(notNull);
  }

  public void setNotNull(boolean notNull) {
    setPropertyInternal(PropertyNames.NOT_NULL, notNull);
  }

  @Nullable
  public String getCollate() {
    return getPropertyInternal(PropertyNames.COLLATE);
  }

  public void setCollateName(@Nullable String collateName) {
    setPropertyInternal(PropertyNames.COLLATE, collateName);
  }

  @Nullable
  public String getMax() {
    return getPropertyInternal(PropertyNames.MAX);
  }

  public void setMax(@Nullable String max) {
    setPropertyInternal(PropertyNames.MAX, max);
  }

  @Nullable
  public String getDefaultValue() {
    return getPropertyInternal(PropertyNames.DEFAULT_VALUE);
  }

  public void setDefaultValue(@Nullable String defaultValue) {
    setPropertyInternal(PropertyNames.DEFAULT_VALUE, defaultValue);
  }

  public boolean isReadonly() {
    Boolean readonly = getPropertyInternal(PropertyNames.READ_ONLY);
    return Boolean.TRUE.equals(readonly);
  }

  public void setReadonly(boolean readonly) {
    setPropertyInternal(PropertyNames.READ_ONLY, readonly);
  }

  @Nullable
  public String getMin() {
    return getPropertyInternal(PropertyNames.MIN);
  }

  public void setMin(@Nullable String min) {
    setPropertyInternal(PropertyNames.MIN, min);
  }

  @Nullable
  public String getRegexp() {
    return getPropertyInternal(PropertyNames.REG_EXP);
  }

  public void setRegexp(String regexp) {
    setPropertyInternal(PropertyNames.REG_EXP, regexp);
  }

  public boolean isMandatory() {
    Boolean mandatory = getPropertyInternal(PropertyNames.MANDATORY);
    return Boolean.TRUE.equals(mandatory);
  }

  public void setMandatory(boolean mandatory) {
    setPropertyInternal(PropertyNames.MANDATORY, mandatory);
  }

  @Nullable
  public String getDescription() {
    return getPropertyInternal(PropertyNames.DESCRIPTION);
  }

  public void setDescription(@Nullable String description) {
    setPropertyInternal(PropertyNames.DESCRIPTION, description);
  }

  public SchemaClassEntity getDeclaringClass() {
    var declaringClassLinkName = getOppositeLinkBagPropertyName(
        SchemaClassEntity.PropertyNames.DECLARED_PROPERTIES);
    LinkBag link = getPropertyInternal(declaringClassLinkName);

    if (link == null || link.isEmpty()) {
      throw new IllegalStateException(
          "Declaring class link not found for property '" + getName() + "'");
    }

    assert link.size() == 1;
    return session.load(link.iterator().next());
  }

  @Nullable
  public String getCustomProperty(String name) {
    EmbeddedMap<String> customProperties = getPropertyInternal(
        PropertyNames.CUSTOM_PROPERTIES);

    if (customProperties == null) {
      return null;
    }

    return customProperties.get(name);
  }

  public void setCustomProperty(String name, String value) {
    EmbeddedMap<String> customProperties = getPropertyInternal(PropertyNames.CUSTOM_PROPERTIES);
    if (customProperties == null) {
      customProperties = session.newEmbeddedMap();
      setPropertyInternal(PropertyNames.CUSTOM_PROPERTIES, customProperties);
    }

    customProperties.put(name, value);
  }

  public void removeCustomProperty(String name) {
    EmbeddedMap<String> customProperties = getPropertyInternal(PropertyNames.CUSTOM_PROPERTIES);

    if (customProperties == null) {
      return;
    }

    customProperties.remove(name);
  }

  public void clearCustomProperties() {
    removeProperty(PropertyNames.CUSTOM_PROPERTIES);
  }

  public Set<String> getCustomPropertyNames() {
    EmbeddedMap<String> customProperties = getPropertyInternal(PropertyNames.CUSTOM_PROPERTIES);
    if (customProperties == null) {
      return Collections.emptySet();
    }

    return customProperties.keySet();
  }

  public RID getGlobalPropertyLink() {
    return getLinkPropertyInternal(PropertyNames.GLOBAL_PROPERTY);
  }

  @Nullable
  public Integer getGlobalPropertyId() {
    var link = getLinkPropertyInternal(PropertyNames.GLOBAL_PROPERTY);
    if (link == null) {
      return null;
    }

    SchemaGlobalPropertyEntity globalPropertyEntity = session.load(link);
    return globalPropertyEntity.getId();
  }

  public void setGlobalPropertyLink(@Nonnull SchemaGlobalPropertyEntity globalPropertyEntity) {
    setPropertyInternal(PropertyNames.GLOBAL_PROPERTY, globalPropertyEntity);
  }

  public Iterator<SchemaIndexEntity> getInvolvedIndexes() {
    var oppositeIndexLinkName =
        getOppositeLinkBagPropertyName(SchemaIndexEntity.PROPERTIES_TO_INDEX);
    LinkBag involvedIndexes = getPropertyInternal(oppositeIndexLinkName);

    if (involvedIndexes == null || involvedIndexes.isEmpty()) {
      return IteratorUtils.emptyIterator();
    }

    return YTDBIteratorUtils.unmodifiableIterator(YTDBIteratorUtils.map(
        involvedIndexes.iterator(), session::load));
  }

  @Override
  protected void customValidationRules() throws ValidationException {
    var name = validateName();

    validateGlobalPropertyLink(name);
    validateDeclaringClass(name);

    var propertyType = validatePropertyType(name);

    validateLinkedType(propertyType, name);
    validateLinkedClass(propertyType);

    validateMaxEntity();
    validateMinEntity();
  }

  public boolean isNameChangedBetweenCallbacks() {
    var entry = properties.get(PropertyNames.NAME);
    return entry != null && entry.isChanged();
  }

  public boolean isPropertyTypeChangedBetweenCallbacks() {
    var entry = properties.get(PropertyNames.TYPE);
    return entry != null && entry.isChanged();
  }

  public boolean isLinkedTypeChangedBetweenCallbacks() {
    var entry = properties.get(PropertyNames.LINKED_TYPE);
    return entry != null && entry.isChanged();
  }

  public boolean isLinkedClassChangedBetweenCallbacks() {
    var entry = properties.get(PropertyNames.LINKED_CLASS);
    return entry != null && entry.isChanged();
  }

  private void validateMinEntity() {
    var minEntity = properties.get(PropertyNames.MIN);
    if (minEntity != null && minEntity.isTxChanged() && minEntity.value != null) {
      checkCorrectLimitValue(session, minEntity.value.toString());
    }
  }

  private void validateMaxEntity() {
    var maxEntity = properties.get(PropertyNames.MAX);
    if (maxEntity != null && maxEntity.isTxChanged() && maxEntity.value != null) {
      checkCorrectLimitValue(session, maxEntity.value.toString());
    }
  }

  private void validateLinkedClass(PropertyTypeInternal propertyType) {
    var linkedClassEntity = properties.get(PropertyNames.LINKED_CLASS);
    if (linkedClassEntity != null && linkedClassEntity.isTxChanged()) {
      SchemaManager.checkSupportLinkedClass(propertyType);
    }
  }

  private void validateLinkedType(PropertyTypeInternal propertyType, String name) {
    var linkedTypeEntity = properties.get(PropertyNames.LINKED_TYPE);
    if (linkedTypeEntity != null && linkedTypeEntity.isTxChanged()) {
      var linkedTypeStr = linkedTypeEntity.value.toString();
      if (linkedTypeStr != null) {
        SchemaManager.checkLinkTypeSupport(propertyType);
        try {
          PropertyType.valueOf(linkedTypeStr);
        } catch (Exception e) {
          throw BaseException.wrapException(new ValidationException(session,
              "Invalid linked type for property with name " + name), e, session);
        }
      }
    }
  }

  private PropertyTypeInternal validatePropertyType(String name) {
    var typeEntity = properties.get(PropertyNames.TYPE);
    if (typeEntity == null || typeEntity.value == null) {
      throw new ValidationException(session,
          "Type is absent in property of schema with name " + name);
    }

    var type = typeEntity.value.toString();
    PropertyTypeInternal propertyType;
    try {
      propertyType = PropertyTypeInternal.valueOf(type);
    } catch (Exception e) {
      throw BaseException.wrapException(new ValidationException(session,
          "Invalid property type for property with name " + name), e, session);
    }

    if (typeEntity.isTxChanged()) {
      var onloadValue = typeEntity.getOnLoadValue(session);

      if (onloadValue != null) {
        var originalValue = PropertyTypeInternal.valueOf(onloadValue.toString());
        if (!originalValue.getCastable()
            .contains(propertyType)) {
          throw new ValidationException(session,
              "Cannot change property type from " + originalValue + " to " + propertyType);
        }
      }
    }
    return propertyType;
  }

  private void validateDeclaringClass(String name) {
    var declaringClassLinkName = getOppositeLinkBagPropertyName(
        SchemaClassEntity.PropertyNames.DECLARED_PROPERTIES);

    LinkBag link = getPropertyInternal(declaringClassLinkName);
    if (link == null || link.isEmpty()) {
      throw new ValidationException(session,
          "Declaring class link not found for property '" + name + "'");
    }
  }

  private void validateGlobalPropertyLink(String name) {
    var globalPropertyLink = getGlobalPropertyLink();
    if (globalPropertyLink == null) {
      throw new ValidationException(session,
          "Global property link is absent in property of schema with name " + name);
    }
  }

  private String validateName() {
    String name = getPropertyInternal(PropertyNames.NAME);
    if (name == null) {
      throw new ValidationException(session, "Name is absent in property of schema");
    }
    return name;
  }

  private void checkCorrectLimitValue(DatabaseSessionInternal session, final String value) {
    var propertyType = getPropertyType();
    if (value != null) {
      if (propertyType == PropertyTypeInternal.STRING
          || propertyType == PropertyTypeInternal.LINKBAG
          || propertyType == PropertyTypeInternal.BINARY
          || propertyType == PropertyTypeInternal.EMBEDDEDLIST
          || propertyType == PropertyTypeInternal.EMBEDDEDSET
          || propertyType == PropertyTypeInternal.LINKLIST
          || propertyType == PropertyTypeInternal.LINKSET
          || propertyType == PropertyTypeInternal.EMBEDDEDMAP
          || propertyType == PropertyTypeInternal.LINKMAP) {
        PropertyTypeInternal.convert(session, value, Integer.class);
      } else if (propertyType == PropertyTypeInternal.DATE
          || propertyType == PropertyTypeInternal.BYTE
          || propertyType == PropertyTypeInternal.SHORT
          || propertyType == PropertyTypeInternal.INTEGER
          || propertyType == PropertyTypeInternal.LONG
          || propertyType == PropertyTypeInternal.FLOAT
          || propertyType == PropertyTypeInternal.DOUBLE
          || propertyType == PropertyTypeInternal.DECIMAL
          || propertyType == PropertyTypeInternal.DATETIME) {
        PropertyTypeInternal.convert(session, value,
            propertyType.getDefaultJavaType());
      }
    }
  }
}
