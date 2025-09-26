package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

public class SchemaIndexEntity extends EntityImpl {

  public static final String NAME = "name";
  public static final String CLASS_PROPERTIES_TO_INDEX = "classPropertiesToIndex";
  public static final String CLASS_TO_INDEX = "classToIndex";
  public static final String METADATA = "metadata";
  public static final String COLLATE = "collate";
  public static final String NULL_VALUES_IGNORED = "nullValuesIgnored";
  public static final String INDEX_TYPE = "indexType";

  public static final String PROPERTY_MODIFIERS_METADATA = "propertyModifiersMetadata";

  public static final String INDEX_ID = "indexId";

  public static final String INDEX_BY_VALUE = "byValue";
  public static final String INDEX_BY_KEY = "byKey";

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

  public Iterator<ObjectObjectImmutablePair<SchemaPropertyEntity, String>> getClassPropertiesToIndexWithModifiers() {
    var linkList = getLinkList(CLASS_PROPERTIES_TO_INDEX);
    return IteratorUtils.map(linkList.iterator(), identifiable -> {
      SchemaPropertyEntity property;

      if (identifiable instanceof SchemaPropertyEntity schemaPropertyEntity) {
        property = schemaPropertyEntity;
      } else {
        property = session.load(identifiable.getIdentity());
      }

      var metadataMap = this.getEmbeddedMap(METADATA);
      if (metadataMap != null && metadataMap.containsKey(PROPERTY_MODIFIERS_METADATA)) {
        @SuppressWarnings("unchecked")
        var valueModifierMap = (Map<String, String>) metadataMap.get(PROPERTY_MODIFIERS_METADATA);
        var valueModifier = valueModifierMap.get(property.getName());
        if (valueModifier != null) {
          return ObjectObjectImmutablePair.of(property, valueModifier);
        } else {
          return ObjectObjectImmutablePair.of(property, "");
        }
      } else {
        return ObjectObjectImmutablePair.of(property, "");
      }
    });
  }

  public Iterator<SchemaPropertyEntity> getClassPropertiesToIndex() {
    var linkList = getLinkList(CLASS_PROPERTIES_TO_INDEX);
    return IteratorUtils.map(linkList.iterator(), identifiable -> {
      SchemaPropertyEntity property;

      if (identifiable instanceof SchemaPropertyEntity schemaPropertyEntity) {
        property = schemaPropertyEntity;
      } else {
        property = session.load(identifiable.getIdentity());
      }

      return property;
    });
  }

  public void addClassPropertyToIndex(@Nonnull SchemaPropertyEntity property) {
    var linkList = getOrCreateLinkList(CLASS_PROPERTIES_TO_INDEX);
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
          if (!INDEX_BY_KEY.equals(propertyValueModifier) && !INDEX_BY_VALUE.equals(
              propertyValueModifier)) {
            throw new DatabaseException(session,
                "Unknown property modifier : " + propertyValueModifier);
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

  public void setCollate(String collate) {
    setString(COLLATE, collate);
  }

  public String getCollate() {
    return getString(COLLATE);
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
