package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaPropertyInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaPropertyOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaPropertyPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;

public class SchemaPropertyEntity extends EntityImpl implements SchemaEntity {

  public static final String CUSTOM_PROPERTIES_PROPERTY_NAME = "customProperties";
  public static final String GLOBAL_PROPERTY_LINK_NAME = "globalProperty";

  public SchemaPropertyEntity(
      @Nonnull RecordIdInternal recordId, @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  public String getName() {
    return getString(YTDBSchemaPropertyPTokenInternal.name.name());
  }

  public void setName(String name) {
    SchemaManager.checkPropertyNameIfValid(name);
    setString(YTDBSchemaPropertyPTokenInternal.name.name(), name);
  }

  public String getFullName() {
    var declaringClassLinkName = getOppositeLinkBagPropertyName(
        YTDBSchemaPropertyInTokenInternal.declaredProperty.name());
    LinkBag declaringClassLink = getPropertyInternal(declaringClassLinkName);
    if (declaringClassLink == null) {
      throw new IllegalStateException("Property '" + declaringClassLinkName + "' not found");
    }

    SchemaClassEntity declaringClass = session.load(declaringClassLink.iterator().next());
    return declaringClass.getName() + "." + getName();
  }

  public PropertyType getPropertyType() {
    return PropertyType.valueOf(getString(YTDBSchemaPropertyPTokenInternal.type.name()));
  }

  public void setPropertyType(PropertyTypeInternal type) {
    setString(YTDBSchemaPropertyPTokenInternal.type.name(), type.name());
  }

  public String getType() {
    return getString(YTDBSchemaPropertyPTokenInternal.type.name());
  }

  public void setType(String type) {
    setString(YTDBSchemaPropertyPTokenInternal.type.name(), type);
  }

  public void setLinkedClass(SchemaClassEntity entity) {
    setLink(YTDBSchemaPropertyOutTokenInternal.linkedClass.name(), entity);
  }

  @Nullable
  public SchemaClassEntity getLinkedClass() {
    var link = getLink(YTDBSchemaPropertyOutTokenInternal.linkedClass.name());
    if (link == null) {
      return null;
    }

    return session.load(link);
  }

  public PropertyType getLinkedPropertyType() {
    return PropertyType.valueOf(getString(YTDBSchemaPropertyPTokenInternal.linkedType.name()));
  }

  public void setLinkedPropertyType(PropertyTypeInternal type) {
    if (type == null) {
      setLinkedType(null);
    }

    setLinkedType(type.name());
  }

  public String getLinkedType() {
    return getString(YTDBSchemaPropertyPTokenInternal.linkedType.name());
  }

  public void setLinkedType(String type) {
    setString(YTDBSchemaPropertyPTokenInternal.linkedType.name(), type);
  }

  public boolean isNotNull() {
    return getBoolean(YTDBSchemaPropertyPTokenInternal.notNull.name());
  }

  public void setNotNull(boolean notNull) {
    setBoolean(YTDBSchemaPropertyPTokenInternal.notNull.name(), notNull);
  }

  public String getCollateName() {
    return getString(YTDBSchemaPropertyPTokenInternal.collateName.name());
  }

  public void setCollateName(String collateName) {
    setString(YTDBSchemaPropertyPTokenInternal.collateName.name(), collateName);
  }

  public String getMax() {
    return getString(YTDBSchemaPropertyPTokenInternal.max.name());
  }

  public void setMax(String max) {
    setString(YTDBSchemaPropertyPTokenInternal.max.name(), max);
  }

  public String getDefaultValue() {
    return getString(YTDBSchemaPropertyPTokenInternal.defaultValue.name());
  }

  public void setDefaultValue(String defaultValue) {
    setString(YTDBSchemaPropertyPTokenInternal.defaultValue.name(), defaultValue);
  }

  public boolean isReadonly() {
    return getBoolean(YTDBSchemaPropertyPTokenInternal.readonly.name());
  }

  public void setReadonly(boolean readonly) {
    setBoolean(YTDBSchemaPropertyPTokenInternal.readonly.name(), readonly);
  }

  public String getMin() {
    return getString(YTDBSchemaPropertyPTokenInternal.min.name());
  }

  public void setMin(String min) {
    setString(YTDBSchemaPropertyPTokenInternal.min.name(), min);
  }

  public String getRegexp() {
    return getString(YTDBSchemaPropertyPTokenInternal.regexp.name());
  }

  public void setRegexp(String regexp) {
    setString(YTDBSchemaPropertyPTokenInternal.regexp.name(), regexp);
  }

  public boolean isMandatory() {
    return getBoolean(YTDBSchemaPropertyPTokenInternal.mandatory.name());
  }

  public void setMandatory(boolean mandatory) {
    setBoolean(YTDBSchemaPropertyPTokenInternal.mandatory.name(), mandatory);
  }

  public String getDescription() {
    return getString(YTDBSchemaPropertyPTokenInternal.description.name());
  }

  public void setDescription(String description) {
    setString(YTDBSchemaPropertyPTokenInternal.description.name(), description);
  }

  public SchemaClassEntity getDeclaringClass() {
    var declaringClassLinkName = getOppositeLinkBagPropertyName(
        YTDBSchemaPropertyInTokenInternal.declaredProperty.name());
    LinkBag link = getPropertyInternal(declaringClassLinkName);

    if (link == null || link.isEmpty()) {
      throw new IllegalStateException(
          "Declaring class link not found for property '" + getName() + "'");
    }

    return session.load(link.iterator().next());
  }

  @Nullable
  public String getCustomProperty(String name) {
    var customProperties = this.<String>getEmbeddedMap(CUSTOM_PROPERTIES_PROPERTY_NAME);

    if (customProperties == null) {
      return null;
    }

    return customProperties.get(name);
  }

  public void setCustomProperty(String name, String value) {
    var customProperties = this.<String>getOrCreateEmbeddedMap(CUSTOM_PROPERTIES_PROPERTY_NAME);
    customProperties.put(name, value);
  }

  public void removeCustomProperty(String name) {
    var customProperties = this.<String>getEmbeddedMap(CUSTOM_PROPERTIES_PROPERTY_NAME);

    if (customProperties == null) {
      return;
    }

    customProperties.remove(name);
  }

  public void clearCustomProperties() {
    removeProperty(CUSTOM_PROPERTIES_PROPERTY_NAME);
  }

  public Iterator<String> customPropertyNames() {
    var customProperties = this.<Map<String, String>>getEmbeddedMap(
        CUSTOM_PROPERTIES_PROPERTY_NAME);
    if (customProperties == null) {
      return IteratorUtils.emptyIterator();
    }

    return customProperties.keySet().iterator();
  }

  public RID getGlobalPropertyLink() {
    return getLink(GLOBAL_PROPERTY_LINK_NAME);
  }

  public void setGlobalPropertyLink(RID rid) {
    setLink(GLOBAL_PROPERTY_LINK_NAME, rid);
  }

  public Iterator<SchemaIndexEntity> getInvolvedIndexes() {
    var oppositeIndexLinkName =
        getOppositeLinkBagPropertyName(SchemaIndexEntity.CLASS_PROPERTIES_TO_INDEX);
    LinkBag involvedIndexes = getPropertyInternal(oppositeIndexLinkName);

    if (involvedIndexes == null || involvedIndexes.isEmpty()) {
      return IteratorUtils.emptyIterator();
    }

    return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(
        involvedIndexes.iterator(), session::load);
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

  private void validateMinEntity() {
    var minEntity = properties.get(YTDBSchemaPropertyPTokenInternal.min.name());
    if (minEntity != null && minEntity.isTxChanged() && minEntity.value != null) {
      checkCorrectLimitValue(session, minEntity.value.toString());
    }
  }

  private void validateMaxEntity() {
    var maxEntity = properties.get(YTDBSchemaPropertyPTokenInternal.max.name());
    if (maxEntity != null && maxEntity.isTxChanged() && maxEntity.value != null) {
      checkCorrectLimitValue(session, maxEntity.value.toString());
    }
  }

  private void validateLinkedClass(PropertyType propertyType) {
    var linkedClassEntity = properties.get(YTDBSchemaPropertyOutTokenInternal.linkedClass.name());
    if (linkedClassEntity != null && linkedClassEntity.isTxChanged()) {
      checkSupportLinkedClass(propertyType);
    }
  }

  private void validateLinkedType(PropertyType propertyType, String name) {
    var linkedTypeEntity = properties.get(YTDBSchemaPropertyPTokenInternal.linkedType.name());
    if (linkedTypeEntity != null && linkedTypeEntity.isTxChanged()) {
      var linkedTypeStr = linkedTypeEntity.value.toString();
      if (linkedTypeStr != null) {
        checkLinkTypeSupport(propertyType);
        try {
          PropertyType.valueOf(linkedTypeStr);
        } catch (Exception e) {
          throw BaseException.wrapException(new ValidationException(session,
              "Invalid linked type for property with name " + name), e, session);
        }
      }
    }
  }

  private PropertyType validatePropertyType(String name) {
    var typeEntity = properties.get(YTDBSchemaPropertyPTokenInternal.type.name());
    if (typeEntity == null || typeEntity.value == null) {
      throw new ValidationException(session,
          "Type is absent in property of schema with name " + name);
    }

    var type = typeEntity.value.toString();
    PropertyType propertyType;
    try {
      propertyType = PropertyType.valueOf(type);
    } catch (Exception e) {
      throw BaseException.wrapException(new ValidationException(session,
          "Invalid property type for property with name " + name), e, session);
    }


    if (typeEntity.isTxChanged()) {
      var onloadValue = typeEntity.getOnLoadValue(session);

      if (onloadValue != null) {
        var originalValue = PropertyTypeInternal.valueOf(onloadValue.toString());
        if (!originalValue.getCastable()
            .contains(PropertyTypeInternal.convertFromPublicType(propertyType))) {
          throw new ValidationException(session,
              "Cannot change property type from " + originalValue + " to " + propertyType);
        }
      }
    }
    return propertyType;
  }

  private void validateDeclaringClass(String name) {
    var declaringClassLinkName = getOppositeLinkBagPropertyName(
        YTDBSchemaPropertyInTokenInternal.declaredProperty.name());

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
    var name = getString(YTDBSchemaPropertyPTokenInternal.name.name());
    if (name == null) {
      throw new ValidationException(session, "Name is absent in property of schema");
    }
    return name;
  }

  private void checkSupportLinkedClass(PropertyType type) {
    if (type != PropertyType.LINK
        && type != PropertyType.LINKSET
        && type != PropertyType.LINKLIST
        && type != PropertyType.LINKMAP
        && type != PropertyType.LINKBAG) {
      throw new ValidationException(session, "Linked class is not supported for type: " + type);
    }
  }

  private void checkLinkTypeSupport(PropertyType type) {
    if (type != PropertyType.EMBEDDEDSET && type != PropertyType.EMBEDDEDLIST
        && type != PropertyType.EMBEDDEDMAP) {
      throw new ValidationException(session, "Linked type is not supported for type: " + type);
    }
  }

  private void checkCorrectLimitValue(DatabaseSessionInternal session, final String value) {
    var propertyType = getPropertyType();
    if (value != null) {
      if (propertyType == PropertyType.STRING
          || propertyType == PropertyType.LINKBAG
          || propertyType == PropertyType.BINARY
          || propertyType == PropertyType.EMBEDDEDLIST
          || propertyType == PropertyType.EMBEDDEDSET
          || propertyType == PropertyType.LINKLIST
          || propertyType == PropertyType.LINKSET
          || propertyType == PropertyType.EMBEDDEDMAP
          || propertyType == PropertyType.LINKMAP) {
        PropertyTypeInternal.convert(session, value, Integer.class);
      } else if (propertyType == PropertyType.DATE
          || propertyType == PropertyType.BYTE
          || propertyType == PropertyType.SHORT
          || propertyType == PropertyType.INTEGER
          || propertyType == PropertyType.LONG
          || propertyType == PropertyType.FLOAT
          || propertyType == PropertyType.DOUBLE
          || propertyType == PropertyType.DECIMAL
          || propertyType == PropertyType.DATETIME) {
        PropertyTypeInternal.convert(session, value,
            PropertyTypeInternal.convertFromPublicType(propertyType).getDefaultJavaType());
      }
    }
  }
}
