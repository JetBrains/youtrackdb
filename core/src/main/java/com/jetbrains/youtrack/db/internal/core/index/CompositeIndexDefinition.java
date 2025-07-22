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

import com.fasterxml.jackson.core.JsonGenerator;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Index that consist of several indexDefinitions like {@link PropertyIndexDefinition}.
 */
public class CompositeIndexDefinition extends AbstractIndexDefinition {

  private final List<IndexDefinition> indexDefinitions;
  private String className;
  private int multiValueDefinitionIndex = -1;
  private CompositeCollate collate = new CompositeCollate(this);

  public CompositeIndexDefinition() {
    indexDefinitions = new ArrayList<>(5);
  }

  /**
   * Constructor for new index creation.
   *
   * @param iClassName - name of class which is owner of this index
   */
  public CompositeIndexDefinition(final String iClassName) {
    super();

    indexDefinitions = new ArrayList<>(5);
    className = iClassName;
  }

  /**
   * Constructor for new index creation.
   *
   * @param iClassName - name of class which is owner of this index
   * @param iIndexes   List of indexDefinitions to add in given index.
   */
  public CompositeIndexDefinition(
      final String iClassName, final List<? extends IndexDefinition> iIndexes) {
    super();

    indexDefinitions = new ArrayList<>(5);
    for (var indexDefinition : iIndexes) {
      indexDefinitions.add(indexDefinition);
      collate.addCollate(indexDefinition.getCollate());

      if (indexDefinition instanceof IndexDefinitionMultiValue) {
        if (multiValueDefinitionIndex == -1) {
          multiValueDefinitionIndex = indexDefinitions.size() - 1;
        } else {
          throw new IndexException("Composite key cannot contain more than one collection item");
        }
      }
    }

    className = iClassName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getClassName() {
    return className;
  }

  /**
   * Add new indexDefinition in current composite.
   *
   * @param indexDefinition Index to add.
   */
  public void addIndex(final IndexDefinition indexDefinition) {
    indexDefinitions.add(indexDefinition);
    if (indexDefinition instanceof IndexDefinitionMultiValue) {
      if (multiValueDefinitionIndex == -1) {
        multiValueDefinitionIndex = indexDefinitions.size() - 1;
      } else {
        throw new IndexException("Composite key cannot contain more than one collection item");
      }
    }

    collate.addCollate(indexDefinition.getCollate());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<String> getProperties() {
    final List<String> fields = new LinkedList<>();
    for (final var indexDefinition : indexDefinitions) {
      fields.addAll(indexDefinition.getProperties());
    }
    return Collections.unmodifiableList(fields);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<String> getFieldsToIndex() {
    final List<String> fields = new LinkedList<>();
    for (final var indexDefinition : indexDefinitions) {
      fields.addAll(indexDefinition.getFieldsToIndex());
    }
    return Collections.unmodifiableList(fields);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Nullable
  public Object getDocumentValueToIndex(
      FrontendTransaction transaction, final EntityImpl entity) {
    final List<CompositeKey> compositeKeys = new ArrayList<>(10);
    final var firstKey = new CompositeKey();

    compositeKeys.add(firstKey);

    for (final var indexDefinition : indexDefinitions) {
      final var result = indexDefinition.getDocumentValueToIndex(transaction, entity);

      if (result == null && isNullValuesIgnored()) {
        return null;
      }

      // for empty collections we add null key in index
      if (result instanceof Collection
          && ((Collection<?>) result).isEmpty()
          && isNullValuesIgnored()) {
        return null;
      }

      addKey(firstKey, compositeKeys, result);
    }

    if (compositeKeys.size() == 1) {
      return firstKey;
    }

    return compositeKeys;
  }

  public int getMultiValueDefinitionIndex() {
    return multiValueDefinitionIndex;
  }

  @Nullable
  public String getMultiValueField() {
    if (multiValueDefinitionIndex >= 0) {
      return indexDefinitions.get(multiValueDefinitionIndex).getProperties().getFirst();
    }

    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Nullable
  public Object createValue(FrontendTransaction transaction, final List<?> params) {
    var currentParamIndex = 0;
    final var firstKey = new CompositeKey();

    final List<CompositeKey> compositeKeys = new ArrayList<>(10);
    compositeKeys.add(firstKey);

    for (final var indexDefinition : indexDefinitions) {
      if (currentParamIndex + 1 > params.size()) {
        break;
      }

      final var endIndex =
          Math.min(currentParamIndex + indexDefinition.getParamCount(), params.size());
      final var indexParams = params.subList(currentParamIndex, endIndex);
      currentParamIndex += indexDefinition.getParamCount();

      final var keyValue = indexDefinition.createValue(transaction, indexParams);

      if (keyValue == null && isNullValuesIgnored()) {
        return null;
      }

      // for empty collections we add null key in index
      if (keyValue instanceof Collection
          && ((Collection<?>) keyValue).isEmpty()
          && isNullValuesIgnored()) {
        return null;
      }

      addKey(firstKey, compositeKeys, keyValue);
    }

    if (compositeKeys.size() == 1) {
      return firstKey;
    }

    return compositeKeys;
  }

  @Nullable
  public IndexDefinitionMultiValue getMultiValueDefinition() {
    if (multiValueDefinitionIndex > -1) {
      return (IndexDefinitionMultiValue) indexDefinitions.get(multiValueDefinitionIndex);
    }

    return null;
  }

  @Nullable
  public CompositeKey createSingleValue(FrontendTransaction transaction, final List<?> params) {
    final var compositeKey = new CompositeKey();
    var currentParamIndex = 0;

    for (final var indexDefinition : indexDefinitions) {
      if (currentParamIndex + 1 > params.size()) {
        break;
      }

      final var endIndex =
          Math.min(currentParamIndex + indexDefinition.getParamCount(), params.size());

      final var indexParams = params.subList(currentParamIndex, endIndex);
      currentParamIndex += indexDefinition.getParamCount();

      final Object keyValue;

      if (indexDefinition instanceof IndexDefinitionMultiValue) {
        keyValue =
            ((IndexDefinitionMultiValue) indexDefinition)
                .createSingleValue(transaction, indexParams.toArray());
      } else {
        keyValue = indexDefinition.createValue(transaction, indexParams);
      }

      if (keyValue == null && isNullValuesIgnored()) {
        return null;
      }

      compositeKey.addKey(keyValue);
    }

    return compositeKey;
  }

  private static void addKey(
      CompositeKey firstKey,
      List<CompositeKey> compositeKeys,
      Object keyValue) {
    // in case of collection we split single composite key on several composite keys
    // each of those composite keys contain single collection item.
    // we can not contain more than single collection item in index
    if (keyValue instanceof Collection<?> collectionKey) {
      final int collectionSize;

      // we insert null if collection is empty
      if (collectionKey.isEmpty()) {
        collectionSize = 1;
      } else {
        collectionSize = collectionKey.size();
      }

      // if that is first collection we split single composite key on several keys, each of those
      // composite keys contain single item from collection
      // sure we need to expand collection only if collection size more than one, otherwise
      // collection of composite keys already contains original composite key
      for (var i = 1; i < collectionSize; i++) {
        final var compositeKey = new CompositeKey(firstKey.getKeys());
        compositeKeys.add(compositeKey);
      }

      var compositeIndex = 0;
      if (!collectionKey.isEmpty()) {
        for (final var keyItem : collectionKey) {
          final var compositeKey = compositeKeys.get(compositeIndex);
          compositeKey.addKey(keyItem);

          compositeIndex++;
        }
      } else {
        firstKey.addKey(null);
      }

    } else if (compositeKeys.size() > 1) {
      for (final var compositeKey : compositeKeys) {
        compositeKey.addKey(keyValue);
      }
    } else {
      firstKey.addKey(keyValue);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object createValue(FrontendTransaction transaction, final Object... params) {
    if (params.length == 1 && params[0] instanceof Collection) {
      return params[0];
    }

    return createValue(transaction, Arrays.asList(params));
  }

  public void processChangeEvent(
      FrontendTransaction transaction,
      MultiValueChangeEvent<?, ?> changeEvent,
      Object2IntOpenHashMap<CompositeKey> keysToAdd,
      Object2IntOpenHashMap<CompositeKey> keysToRemove,
      Object... params) {

    final var indexDefinitionMultiValue =
        (IndexDefinitionMultiValue) indexDefinitions.get(multiValueDefinitionIndex);

    final var compositeWrapperKeysToAdd =
        new CompositeWrapperMap(
            transaction, keysToAdd, indexDefinitions, params, multiValueDefinitionIndex);

    final var compositeWrapperKeysToRemove =
        new CompositeWrapperMap(
            transaction, keysToRemove, indexDefinitions, params, multiValueDefinitionIndex);

    indexDefinitionMultiValue.processChangeEvent(
        transaction, changeEvent, compositeWrapperKeysToAdd, compositeWrapperKeysToRemove);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getParamCount() {
    var total = 0;
    for (final var indexDefinition : indexDefinitions) {
      total += indexDefinition.getParamCount();
    }
    return total;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PropertyTypeInternal[] getTypes() {
    final List<PropertyTypeInternal> types = new LinkedList<>();
    for (final var indexDefinition : indexDefinitions) {
      Collections.addAll(types, indexDefinition.getTypes());
    }

    return types.toArray(new PropertyTypeInternal[0]);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (CompositeIndexDefinition) o;

    if (!className.equals(that.className)) {
      return false;
    }
    return indexDefinitions.equals(that.indexDefinitions);
  }

  @Override
  public int hashCode() {
    var result = indexDefinitions.hashCode();
    result = 31 * result + className.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "CompositeIndexDefinition{"
        + "indexDefinitions="
        + indexDefinitions
        + ", className='"
        + className
        + '\''
        + '}';
  }


  @Nonnull
  @Override
  public EmbeddedMap<Object> toMap(DatabaseSessionInternal session) {
    var result = session.newEmbeddedMap();
    serializeToMap(result, session);
    return result;
  }

  @Override
  public void toJson(@Nonnull JsonGenerator jsonGenerator) {
    try {
      jsonGenerator.writeStartObject();
      jsonGenerator.writeStringField("className", className);
      jsonGenerator.writeArrayFieldStart("indexDefinitions");

      for (final var indexDefinition : indexDefinitions) {
        indexDefinition.toJson(jsonGenerator);
      }
      jsonGenerator.writeEndArray();

      jsonGenerator.writeArrayFieldStart("indClasses");
      for (final var indexDefinition : indexDefinitions) {
        jsonGenerator.writeString(indexDefinition.getClass().getName());
      }
      jsonGenerator.writeEndArray();

      jsonGenerator.writeBooleanField("nullValuesIgnored", isNullValuesIgnored());
      jsonGenerator.writeEndObject();
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new IndexException((String) null, "Error during composite index serialization"), e,
          (String) null);
    }
  }


  @Override
  protected void serializeToMap(@Nonnull Map<String, Object> map, DatabaseSessionInternal session) {
    super.serializeToMap(map, session);

    final List<Map<String, Object>> inds = session.newEmbeddedList(indexDefinitions.size());
    final List<String> indClasses = session.newEmbeddedList(indexDefinitions.size());

    map.put("className", className);
    for (final var indexDefinition : indexDefinitions) {
      final var indexEntity = indexDefinition.toMap(session);
      inds.add(indexEntity);

      indClasses.add(indexDefinition.getClass().getName());
    }

    map.put("indexDefinitions", inds);
    map.put("indClasses", indClasses);
    map.put("nullValuesIgnored", isNullValuesIgnored());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toCreateIndexDDL(final String indexName, final String indexType, String engine) {
    final var ddl = new StringBuilder("create index ");
    ddl.append('`').append(indexName).append('`').append(" on ").append(className).append(" ( ");

    final var fieldIterator = getFieldsToIndex().iterator();
    if (fieldIterator.hasNext()) {
      ddl.append(quoteFieldName(fieldIterator.next()));
      while (fieldIterator.hasNext()) {
        ddl.append(", ").append(quoteFieldName(fieldIterator.next()));
      }
    }
    ddl.append(" ) ").append(indexType).append(' ');

    if (engine != null) {
      ddl.append("ENGINE ").append(engine).append(' ');
    }

    if (multiValueDefinitionIndex == -1) {
      var first = true;
      for (var oType : getTypes()) {
        if (first) {
          first = false;
        } else {
          ddl.append(", ");
        }

        ddl.append(oType.name());
      }
    }

    return ddl.toString();
  }

  @Nullable
  private static String quoteFieldName(String next) {
    if (next == null) {
      return null;
    }
    next = next.trim();
    if (!next.isEmpty() && next.charAt(0) == '`') {
      return next;
    }
    if (next.toLowerCase(Locale.ENGLISH).endsWith("collate ci")) {
      next = next.substring(0, next.length() - "collate ci".length());
      return "`" + next.trim() + "` collate ci";
    }
    return "`" + next + "`";
  }

  @Override
  public void fromMap(@Nonnull Map<String, ?> map) {
    serializeFromMap(map);
  }

  @Override
  protected void serializeFromMap(@Nonnull Map<String, ?> map) {
    super.serializeFromMap(map);

    try {
      className = (String) map.get("className");

      @SuppressWarnings("unchecked") final var inds = (List<Map<String, Object>>) map.get(
          "indexDefinitions");
      @SuppressWarnings("unchecked") final var indClasses = (List<String>) map.get("indClasses");

      indexDefinitions.clear();

      collate = new CompositeCollate(this);

      for (var i = 0; i < indClasses.size(); i++) {
        final var clazz = Class.forName(indClasses.get(i));
        final var indEntity = inds.get(i);

        final var indexDefinition =
            (IndexDefinition) clazz.getDeclaredConstructor().newInstance();
        indexDefinition.fromMap(indEntity);

        indexDefinitions.add(indexDefinition);
        collate.addCollate(indexDefinition.getCollate());

        if (indexDefinition instanceof IndexDefinitionMultiValue) {
          multiValueDefinitionIndex = indexDefinitions.size() - 1;
        }
      }

      setNullValuesIgnored(!Boolean.FALSE.equals(map.get("nullValuesIgnored")));
    } catch (final ClassNotFoundException
                   | InvocationTargetException
                   | InstantiationException
                   | IllegalAccessException
                   | NoSuchMethodException e) {
      throw BaseException.wrapException(
          new IndexException("Error during composite index deserialization"), e, (String) null);
    }
  }

  @Override
  public Collate getCollate() {
    return collate;
  }

  @Override
  public void setCollate(Collate collate) {
    throw new UnsupportedOperationException();
  }

  private static final class CompositeWrapperMap implements Object2IntMap<Object> {

    private final Object2IntOpenHashMap<CompositeKey> underlying;
    private final Object[] params;
    private final List<IndexDefinition> indexDefinitions;
    private final int multiValueIndex;
    private final FrontendTransaction transaction;

    private CompositeWrapperMap(
        FrontendTransaction transaction,
        Object2IntOpenHashMap<CompositeKey> underlying,
        List<IndexDefinition> indexDefinitions,
        Object[] params,
        int multiValueIndex) {
      this.transaction = transaction;
      this.underlying = underlying;
      this.params = params;
      this.multiValueIndex = multiValueIndex;
      this.indexDefinitions = indexDefinitions;
    }

    @Override
    public int size() {
      return underlying.size();
    }

    @Override
    public boolean isEmpty() {
      return underlying.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
      final var compositeKey = convertToCompositeKey(transaction, key);

      return underlying.containsKey(compositeKey);
    }

    @Override
    public void defaultReturnValue(int i) {
      underlying.defaultReturnValue(i);
    }

    @Override
    public int defaultReturnValue() {
      return underlying.defaultReturnValue();
    }

    @Override
    public ObjectSet<Entry<Object>> object2IntEntrySet() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsValue(int i) {
      return underlying.containsValue(i);
    }

    @Override
    public int getInt(Object o) {
      return underlying.getInt(convertToCompositeKey(transaction, o));
    }

    @Override
    public int put(Object key, int value) {
      final var compositeKey = convertToCompositeKey(transaction, key);
      return underlying.put(compositeKey, value);
    }

    @Override
    public int removeInt(Object key) {
      return underlying.removeInt(convertToCompositeKey(transaction, key));
    }

    @Override
    public void clear() {
      underlying.clear();
    }

    @Nonnull
    @Override
    public ObjectSet<Object> keySet() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@Nonnull Map<?, ? extends Integer> m) {
      throw new UnsupportedOperationException();
    }

    @Override
    @Nonnull
    public IntCollection values() {
      return underlying.values();
    }

    private CompositeKey convertToCompositeKey(FrontendTransaction transaction, Object key) {
      final var compositeKey = new CompositeKey();

      var paramsIndex = 0;
      for (var i = 0; i < indexDefinitions.size(); i++) {
        final var indexDefinition = indexDefinitions.get(i);
        if (i != multiValueIndex) {
          compositeKey.addKey(indexDefinition.createValue(transaction, params[paramsIndex]));
          paramsIndex++;
        } else {
          compositeKey.addKey(
              ((IndexDefinitionMultiValue) indexDefinition).createSingleValue(transaction, key));
        }
      }
      return compositeKey;
    }
  }

  @Override
  public boolean isAutomatic() {
    return indexDefinitions.getFirst().isAutomatic();
  }
}
