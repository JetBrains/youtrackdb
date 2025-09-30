package com.jetbrains.youtrackdb.internal.core.metadata.schema.entities;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;
import org.jspecify.annotations.NonNull;


public class SchemaIndexEntity extends EntityImpl implements SchemaEntity {
  public static final String NAME = "name";
  public static final String PROPERTIES_TO_INDEX = "classPropertiesToIndex";
  public static final String CLASS_TO_INDEX = "classToIndex";
  public static final String METADATA = "metadata";
  public static final String NULL_VALUES_IGNORED = "nullValuesIgnored";
  public static final String INDEX_TYPE = "indexType";

  public static final String PROPERTY_MODIFIERS_METADATA = "propertyModifiersMetadata";

  public static final String INDEX_ID = "indexId";

  public enum ValueModifier {
    BY_VALUE,
    BY_KEY,
    NONE
  }

  public SchemaIndexEntity(@Nonnull RecordIdInternal recordId,
      @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  public String getName() {
    return getString(NAME);
  }

  public void setName(@Nonnull String name) {
    final var c = SchemaManager.checkIndexNameIfValid(name);
    if (c != null) {
      throw new IllegalArgumentException(
          "Invalid index name '" + name + "'. Character '" + c + "' is invalid");
    }

    setString(NAME, name);
  }

  public void setIndexId(int indexId) {
    setProperty(INDEX_ID, indexId);
  }

  @Nullable
  public Integer getIndexId() {
    return getInt(INDEX_ID);
  }

  public void setClassToIndex(@Nonnull SchemaClassEntity classToIndex) {
    setLink(CLASS_TO_INDEX, classToIndex);
  }

  @Nullable
  public SchemaClassEntity getClassToIndex() {
    return session.load(getLink(CLASS_TO_INDEX));
  }

  public Iterator<RawPair<SchemaPropertyEntity, ValueModifier>> getClassPropertiesWithModifiers() {
    var linkList = getLinkList(PROPERTIES_TO_INDEX);
    if (linkList == null) {
      return IteratorUtils.emptyIterator();
    }

    return YTDBIteratorUtils.unmodifiableIterator(
        YTDBIteratorUtils.map(linkList.iterator(), identifiable -> {
          SchemaPropertyEntity property;

          if (identifiable instanceof SchemaPropertyEntity schemaPropertyEntity) {
            property = schemaPropertyEntity;
          } else {
            property = session.load(identifiable.getIdentity());
          }

          var metadataMap = this.getEmbeddedMap(METADATA);
          if (metadataMap != null && metadataMap.containsKey(PROPERTY_MODIFIERS_METADATA)) {
            @SuppressWarnings("unchecked")
            var valueModifierMap = (Map<String, String>) metadataMap.get(
                PROPERTY_MODIFIERS_METADATA);
            var valueModifier = valueModifierMap.get(property.getName());
            if (valueModifier != null) {
              return new RawPair<>(property, ValueModifier.valueOf(valueModifier.toUpperCase()));
            } else {
              return new RawPair<>(property, ValueModifier.NONE);
            }
          } else {
            return new RawPair<>(property, ValueModifier.NONE);
          }
        }));
  }

  public List<PropertyTypeInternal> getKeyTypes() {
    var result = new ArrayList<PropertyTypeInternal>();
    var propertiesWithModifiers = getClassPropertiesWithModifiers();

    while (propertiesWithModifiers.hasNext()) {
      var pair = propertiesWithModifiers.next();
      var property = pair.first();
      var modifier = pair.second();

      var propertyType = property.getPropertyType();
      if (propertyType.isMultiValue()) {
        if (propertyType == PropertyTypeInternal.LINKLIST
            || propertyType == PropertyTypeInternal.EMBEDDEDLIST) {
          if (modifier == ValueModifier.BY_KEY) {
            result.add(PropertyTypeInternal.INTEGER);
          } else if (modifier == ValueModifier.BY_VALUE) {
            if (propertyType == PropertyTypeInternal.EMBEDDEDLIST) {
              var linkedType = inferKeyTypeOfEmbeddedCollection(property);
              result.add(linkedType);
            } else {
              result.add(PropertyTypeInternal.LINK);
            }
          }
        } else if (propertyType == PropertyTypeInternal.LINKSET) {
          if (modifier == ValueModifier.NONE) {
            result.add(PropertyTypeInternal.LINK);
          } else {
            throw new DatabaseException(session,
                "Can not index property as it is a LINKSET property but value modifier is "
                    + modifier);
          }
        } else if (propertyType == PropertyTypeInternal.EMBEDDEDSET) {
          if (modifier == ValueModifier.NONE) {
            var linkedType = inferKeyTypeOfEmbeddedCollection(property);
            result.add(linkedType);
          } else {
            throw new DatabaseException(session,
                "Can not index property as it is a EMBEDDEDSET property but value modifier is "
                    + modifier);
          }
        } else if (propertyType == PropertyTypeInternal.LINKMAP) {
          result.add(switch (modifier) {
            case BY_KEY -> PropertyTypeInternal.LINK;
            case BY_VALUE -> inferKeyTypeOfEmbeddedCollection(property);
            case NONE -> throw new DatabaseException(session,
                "Can not index property as it is a LINKMAP property but it's value modifier is "
                    + modifier);
          });
        } else if (propertyType == PropertyTypeInternal.EMBEDDEDMAP) {
          result.add(switch (modifier) {
            case BY_KEY -> PropertyTypeInternal.STRING;
            case BY_VALUE -> inferKeyTypeOfEmbeddedCollection(property);
            case NONE -> throw new DatabaseException(session,
                "Can not index property as it is a EMBEDDEDMAP property but it's value modifier is "
                    + modifier);
          });
        }
      } else {
        if (modifier == ValueModifier.NONE) {
          result.add(propertyType);
        } else {
          throw new DatabaseException(session,
              "Can not defer index key type for property : " + property
                  + " as it is not multivalue property but has modifier : " + modifier);
        }
      }
    }

    return result;
  }

  private @NonNull PropertyTypeInternal inferKeyTypeOfEmbeddedCollection(
      SchemaPropertyEntity property) {
    var linkedType = property.getLinkedPropertyType();
    if (linkedType == null) {
      throw new DatabaseException(session, "Can not index property " + property.getName()
          + " by value as it's linked type is not defined");
    } else if (linkedType.isMultiValue()) {
      throw new DatabaseException(session, "Can not index property " + property.getName()
          + " by value as it's linked type is multivalue");
    }
    return linkedType;
  }

  public Iterator<SchemaPropertyEntity> getClassProperties() {
    var linkList = getLinkList(PROPERTIES_TO_INDEX);
    return YTDBIteratorUtils.unmodifiableIterator(
        YTDBIteratorUtils.map(linkList.iterator(), identifiable -> {
          SchemaPropertyEntity property;

          if (identifiable instanceof SchemaPropertyEntity schemaPropertyEntity) {
            property = schemaPropertyEntity;
          } else {
            property = session.load(identifiable.getIdentity());
          }

          return property;
        }));
  }

  public void addClassPropertyToIndex(@Nonnull SchemaPropertyEntity property) {
    var linkList = getOrCreateLinkList(PROPERTIES_TO_INDEX);
    if (linkList.contains(property)) {
      return;
    }

    linkList.add(property);
  }


  public void setMetadata(Map<String, Object> metadata) {
    var propertyModifiersMetadata = metadata.get(PROPERTY_MODIFIERS_METADATA);

    if (propertyModifiersMetadata instanceof Map<?, ?> propertyModifiers) {
      for (var entry : propertyModifiers.entrySet()) {

        var propertyName = entry.getKey();
        if (!(propertyName instanceof String)) {
          var propertyValueModifier = entry.getValue();
          if (propertyValueModifier instanceof String valueModifier) {
            var lowerValueModifier = valueModifier.toUpperCase();

            if (!ValueModifier.BY_KEY.name().equals(lowerValueModifier) &&
                !ValueModifier.BY_VALUE.name().equals(lowerValueModifier)) {
              throw new DatabaseException(session,
                  "Unknown property modifier : " + propertyValueModifier);
            }
          }
        } else {
          throw new DatabaseException(session, "Property name is not a string : " + propertyName);
        }
      }
    }

    newEmbeddedMap(METADATA, metadata);
  }

  public Map<String, Object> getMetadata() {
    return getEmbeddedMap(METADATA);
  }

  public boolean isNullValuesIgnored() {
    return Boolean.TRUE.equals(getBoolean(NULL_VALUES_IGNORED));
  }

  public void setNullValuesIgnored(boolean value) {
    setBoolean(NULL_VALUES_IGNORED, value);
  }

  public void setIndexType(INDEX_TYPE indexType) {
    setString(INDEX_TYPE, indexType.name());
  }

  @Nullable
  public INDEX_TYPE getIndexType() {
    var indexType = getString(INDEX_TYPE);
    if (indexType == null) {
      return null;
    }

    return SchemaManager.INDEX_TYPE.valueOf(indexType);
  }
}
