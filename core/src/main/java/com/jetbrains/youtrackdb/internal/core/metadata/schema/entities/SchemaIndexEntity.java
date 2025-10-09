package com.jetbrains.youtrackdb.internal.core.metadata.schema.entities;

import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkList;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NonNull;


public class SchemaIndexEntity extends EntityImpl implements SchemaEntity {

  interface PropertyNames {

    String NAME = "name";
    String PROPERTIES_TO_INDEX = "classPropertiesToIndex";
    String INDEX_BY = "indexBy";
    String CLASS_TO_INDEX = "classToIndex";
    String NULL_VALUES_IGNORED = "nullValuesIgnored";
    String INDEX_TYPE = "indexType";
    String INDEX_ID = "indexId";
    String METADATA = "metadata";
  }

  public enum IndexBy {
    BY_VALUE {
      @Override
      public YTDBSchemaIndex.IndexBy toPublicIndexBy() {
        return YTDBSchemaIndex.IndexBy.BY_VALUE;
      }
    },
    BY_KEY {
      @Override
      public YTDBSchemaIndex.IndexBy toPublicIndexBy() {
        return YTDBSchemaIndex.IndexBy.BY_KEY;
      }
    };

    public abstract YTDBSchemaIndex.IndexBy toPublicIndexBy();

    public static IndexBy fromPublicIndexBy(@Nonnull YTDBSchemaIndex.IndexBy indexBy) {
      return switch (indexBy) {
        case BY_VALUE -> BY_VALUE;
        case BY_KEY -> BY_KEY;
      };
    }
  }

  public SchemaIndexEntity(@Nonnull RecordIdInternal recordId,
      @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  public String getName() {
    return getString(PropertyNames.NAME);
  }

  public void setName(@Nonnull String name) {
    final var c = SchemaManager.checkIndexNameIfValid(name);
    if (c != null) {
      throw new IllegalArgumentException(
          "Invalid index name '" + name + "'. Character '" + c + "' is invalid");
    }

    setString(PropertyNames.NAME, name);
  }

  public void setIndexId(int indexId) {
    setPropertyInternal(PropertyNames.INDEX_ID, indexId, PropertyTypeInternal.INTEGER);
  }

  @Nullable
  public Integer getIndexId() {
    return getPropertyInternal(PropertyNames.INDEX_ID);
  }

  public void setClassToIndex(@Nonnull SchemaClassEntity classToIndex) {
    setPropertyInternal(PropertyNames.CLASS_TO_INDEX, classToIndex, PropertyTypeInternal.LINK);
  }

  @Nullable
  public SchemaClassEntity getClassToIndex() {
    return session.load(getPropertyInternal(PropertyNames.CLASS_TO_INDEX));
  }


  public List<PropertyTypeInternal> getKeyTypes() {
    EmbeddedList<String> indexByList = getPropertyInternal(PropertyNames.INDEX_BY);
    LinkList propertiesToIndex = getPropertyInternal(PropertyNames.PROPERTIES_TO_INDEX);
    if (propertiesToIndex == null || propertiesToIndex.isEmpty()) {
      return Collections.emptyList();
    }

    assert indexByList.size() == propertiesToIndex.size();

    var result = new ArrayList<PropertyTypeInternal>(propertiesToIndex.size());

    for (var i = 0; i < propertiesToIndex.size(); i++) {
      SchemaPropertyEntity property = session.load(propertiesToIndex.get(i).getIdentity());
      var indexBy = IndexBy.valueOf(indexByList.get(i));

      var propertyType = property.getPropertyType();
      if (propertyType.isMultiValue()) {
        if (propertyType == PropertyTypeInternal.LINKLIST
            || propertyType == PropertyTypeInternal.EMBEDDEDLIST) {
          if (indexBy == IndexBy.BY_KEY) {
            result.add(PropertyTypeInternal.INTEGER);
          } else if (indexBy == IndexBy.BY_VALUE) {
            if (propertyType == PropertyTypeInternal.EMBEDDEDLIST) {
              var linkedType = inferKeyTypeOfEmbeddedCollection(property);
              result.add(linkedType);
            } else {
              result.add(PropertyTypeInternal.LINK);
            }
          }
        } else if (propertyType == PropertyTypeInternal.LINKSET) {
          if (indexBy == IndexBy.BY_VALUE) {
            result.add(PropertyTypeInternal.LINK);
          } else {
            throw new DatabaseException(session,
                "Can not index property as it is a LINKSET property but value modifier is "
                    + indexBy);
          }
        } else if (propertyType == PropertyTypeInternal.EMBEDDEDSET) {
          if (indexBy == IndexBy.BY_VALUE) {
            var linkedType = inferKeyTypeOfEmbeddedCollection(property);
            result.add(linkedType);
          } else {
            throw new DatabaseException(session,
                "Can not index property as it is a EMBEDDEDSET property but value modifier is "
                    + indexBy);
          }
        } else if (propertyType == PropertyTypeInternal.LINKMAP) {
          result.add(switch (indexBy) {
            case BY_KEY -> PropertyTypeInternal.LINK;
            case BY_VALUE -> inferKeyTypeOfEmbeddedCollection(property);
          });
        } else if (propertyType == PropertyTypeInternal.EMBEDDEDMAP) {
          result.add(switch (indexBy) {
            case BY_KEY -> PropertyTypeInternal.STRING;
            case BY_VALUE -> inferKeyTypeOfEmbeddedCollection(property);
          });
        }
      } else {
        if (indexBy == IndexBy.BY_VALUE) {
          result.add(propertyType);
        } else {
          throw new DatabaseException(session,
              "Can not defer index key type for property : " + property
                  + " as it is not multivalue property but has modifier : " + indexBy);
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

  public Iterator<SchemaPropertyEntity> getPropertiesToIndex() {
    var linkList = getLinkList(PropertyNames.PROPERTIES_TO_INDEX);
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

  public List<IndexBy> getIndexBys() {
    EmbeddedList<String> indexByList = getPropertyInternal(PropertyNames.INDEX_BY);
    if (indexByList == null) {
      return Collections.emptyList();
    }

    final List<IndexBy> result = new ArrayList<>(indexByList.size());
    for (var indexBy : indexByList) {
      result.add(IndexBy.valueOf(indexBy));
    }

    return result;
  }

  public void addClassPropertyToIndex(@Nonnull SchemaPropertyEntity property) {
    var linkList = getOrCreateLinkList(PropertyNames.PROPERTIES_TO_INDEX);
    if (linkList.contains(property)) {
      return;
    }

    linkList.add(property);
    EmbeddedList<String> indexByList = getPropertyInternal(PropertyNames.INDEX_BY);
    if (indexByList == null) {
      indexByList = session.newEmbeddedList();
      setPropertyInternal(PropertyNames.INDEX_BY, indexByList);
    }

    indexByList.add(IndexBy.BY_VALUE.name());

    assert indexByList.size() == linkList.size();
  }

  public void addClassPropertyToIndex(@Nonnull SchemaPropertyEntity property,
      @Nonnull IndexBy indexBy) {
    var linkList = getOrCreateLinkList(PropertyNames.PROPERTIES_TO_INDEX);
    if (linkList.contains(property)) {
      return;
    }

    EmbeddedList<String> indexByList = getPropertyInternal(PropertyNames.INDEX_BY);
    if (indexByList == null) {
      indexByList = session.newEmbeddedList();
      setPropertyInternal(PropertyNames.INDEX_BY, indexByList);
    }

    linkList.add(property);
    indexByList.add(indexBy.name());
  }

  public boolean isNullValuesIgnored() {
    return Boolean.TRUE.equals(getBoolean(PropertyNames.NULL_VALUES_IGNORED));
  }

  public void setNullValuesIgnored(boolean value) {
    setPropertyInternal(PropertyNames.NULL_VALUES_IGNORED, value, PropertyTypeInternal.BOOLEAN);
  }

  public void setIndexType(IndexType indexType) {
    setPropertyInternal(PropertyNames.INDEX_TYPE, indexType.name(), PropertyTypeInternal.STRING);
  }

  @Nullable
  public IndexType getIndexType() {
    String indexType = getPropertyInternal(PropertyNames.INDEX_TYPE);
    if (indexType == null) {
      return null;
    }

    return IndexType.valueOf(indexType);
  }

  public Map<String, ?> getMetadata() {
    return getPropertyInternal(PropertyNames.METADATA);
  }

  public void setMetadata(Map<String, ?> metadata) {
    setProperty(PropertyNames.METADATA, metadata, PropertyType.EMBEDDEDMAP);
  }
}
