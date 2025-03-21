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
package com.jetbrains.youtrack.db.api.record;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Implementation of a generic entity. It's bound to the record and allows to read and write
 * values.
 */
public interface Entity extends DBRecord, Result {

  String DEFAULT_CLASS_NAME = "O";


  /**
   * Gets a property value on time of transaction start. This will work for scalar values, and
   * collections of scalar values. Will throw exception in case of called with name starting with
   * {@code #Vertex.DIRECTION_OUT_PREFIX} or {@code #Vertex.DIRECTION_IN_PREFIX}.
   *
   * @param name  the property name*
   * @param <RET> the type of the property
   * @return Returns property value on time of transaction start.
   * @throws IllegalArgumentException if name starts with {@code #Vertex.DIRECTION_OUT_PREFIX} or
   *                                  {@code #Vertex.DIRECTION_IN_PREFIX}.
   */
  <RET> RET getPropertyOnLoadValue(@Nonnull String name);

  @Nullable
  PropertyType getPropertyType(final String propertyName);

  @Nullable
  EmbeddedEntity getEmbeddedEntity(@Nonnull String name);

  /**
   * Sets a property value
   *
   * @param name  the property name
   * @param value the property value
   */
  void setProperty(@Nonnull String name, @Nullable Object value);

  /**
   * Sets a property value
   *
   * @param propertyName the property name
   * @param value        the property value
   * @param propertyType Forced type (not auto-determined)
   */
  void setProperty(@Nonnull String propertyName, @Nullable Object value,
      @Nonnull PropertyType propertyType);

  void setProperty(@Nonnull String propertyName, @Nullable Object value,
      @Nonnull PropertyType propertyType, @Nonnull PropertyType linkedType);

  default void setBoolean(@Nonnull String name, @Nullable Boolean value) {
    setProperty(name, value, PropertyType.BOOLEAN);
  }

  default void setByte(@Nonnull String name, @Nullable Byte value) {
    setProperty(name, value, PropertyType.BYTE);
  }

  default void setInt(@Nonnull String name, @Nullable Integer value) {
    setProperty(name, value, PropertyType.INTEGER);
  }

  default void setShort(@Nonnull String name, @Nullable Short value) {
    setProperty(name, value, PropertyType.SHORT);
  }

  default void setLong(@Nonnull String name, @Nullable Long value) {
    setProperty(name, value, PropertyType.LONG);
  }

  default void setFloat(@Nonnull String name, @Nullable Float value) {
    setProperty(name, value, PropertyType.FLOAT);
  }

  default void setDouble(@Nonnull String name, @Nullable Double value) {
    setProperty(name, value, PropertyType.DOUBLE);
  }

  default void setString(@Nonnull String name, @Nullable String value) {
    setProperty(name, value, PropertyType.STRING);
  }

  default void setDate(@Nonnull String name, @Nullable Date value) {
    setProperty(name, value, PropertyType.DATE);
  }

  default void setDateTime(@Nonnull String name, @Nullable Date value) {
    setProperty(name, value, PropertyType.DATETIME);
  }

  default void setBinary(@Nonnull String name, @Nullable byte[] value) {
    setProperty(name, value, PropertyType.BINARY);
  }

  default void setLink(@Nonnull String name, @Nullable Identifiable value) {
    setProperty(name, value, PropertyType.LINK);
  }

  default void setEmbeddedEntity(@Nonnull String name, @Nullable EmbeddedEntity value) {
    if (value != null && !value.isEmbedded()) {
      throw new IllegalArgumentException("Entity is not embedded");
    }
    setProperty(name, value, PropertyType.EMBEDDED);
  }

  default void setDecimal(@Nonnull String name, @Nullable BigDecimal value) {
    setProperty(name, value, PropertyType.DECIMAL);
  }

  default <T> void setEmbeddedList(@Nonnull String name, @Nullable List<T> value) {
    setProperty(name, value, PropertyType.EMBEDDEDLIST);
  }

  default <T> void setEmbeddedSet(@Nonnull String name, @Nullable Set<T> value) {
    setProperty(name, value, PropertyType.EMBEDDEDSET);
  }

  default <T> void setEmbeddedMap(@Nonnull String name, @Nullable Map<String, T> value) {
    setProperty(name, value, PropertyType.EMBEDDEDMAP);
  }

  default void setLinkList(@Nonnull String name, @Nullable List<Identifiable> value) {
    setProperty(name, value, PropertyType.LINKLIST);
  }

  default void setLinkSet(@Nonnull String name, @Nullable Set<Identifiable> value) {
    setProperty(name, value, PropertyType.LINKSET);
  }

  default void setLinkMap(@Nonnull String name, @Nullable Map<String, Identifiable> value) {
    setProperty(name, value, PropertyType.LINKMAP);
  }

  @Nonnull
  <T> List<T> newEmbeddedList(@Nonnull String name);

  @Nonnull
  <T> List<T> newEmbeddedList(@Nonnull String name, PropertyType linkedType);

  @Nonnull
  <T> List<T> newEmbeddedList(@Nonnull String name, List<T> source);

  @Nonnull
  <T> List<T> newEmbeddedList(@Nonnull String name, List<T> source, PropertyType linkedType);

  @Nonnull
  <T> List<T> newEmbeddedList(@Nonnull String name, T[] source);

  @Nonnull
  List<Byte> newEmbeddedList(@Nonnull String name, byte[] source);

  @Nonnull
  List<Short> newEmbeddedList(@Nonnull String name, short[] source);

  @Nonnull
  List<Integer> newEmbeddedList(@Nonnull String name, int[] source);

  @Nonnull
  List<Long> newEmbeddedList(@Nonnull String name, long[] source);

  @Nonnull
  List<Boolean> newEmbeddedList(@Nonnull String name, boolean[] source);

  @Nonnull
  List<Float> newEmbeddedList(@Nonnull String name, float[] source);

  @Nonnull
  List<Double> newEmbeddedList(@Nonnull String name, double[] source);

  @Nonnull
  <T> Set<T> newEmbeddedSet(@Nonnull String name);

  @Nonnull
  <T> Set<T> newEmbeddedSet(@Nonnull String name, @Nonnull PropertyType linkedType);

  @Nonnull
  <T> Set<T> newEmbeddedSet(@Nonnull String name, @Nonnull Set<T> source);

  @Nonnull
  <T> Set<T> newEmbeddedSet(@Nonnull String name, Set<T> source, @Nonnull PropertyType linkedType);

  @Nonnull
  <T> Map<String, T> newEmbeddedMap(@Nonnull String name);

  @Nonnull
  <T> Map<String, T> newEmbeddedMap(@Nonnull String name, @Nonnull PropertyType linkedType);

  @Nonnull
  <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source);

  @Nonnull
  <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source,
      @Nonnull PropertyType linkedType);

  @Nonnull
  List<Identifiable> newLinkList(@Nonnull String name);

  @Nonnull
  List<Identifiable> newLinkList(@Nonnull String name, List<Identifiable> source);

  @Nonnull
  Set<Identifiable> newLinkSet(@Nonnull String name);

  @Nonnull
  Set<Identifiable> newLinkSet(@Nonnull String name, Set<Identifiable> source);

  @Nonnull
  Map<String, Identifiable> newLinkMap(@Nonnull String name);

  @Nonnull
  Map<String, Identifiable> newLinkMap(@Nonnull String name, Map<String, Identifiable> source);

  @Nonnull
  <T> List<T> getOrCreateEmbeddedList(@Nonnull String name);

  @Nonnull
  <T> List<T> getOrCreateEmbeddedList(@Nonnull String name, @Nonnull PropertyType linkedType);

  @Nonnull
  <T> Set<T> getOrCreateEmbeddedSet(@Nonnull String name);

  @Nonnull
  <T> Set<T> getOrCreateEmbeddedSet(@Nonnull String name, @Nonnull PropertyType linkedType);

  @Nonnull
  <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name);

  @Nonnull
  <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name, @Nonnull PropertyType linkedType);

  @Nonnull
  List<Identifiable> getOrCreateLinkList(@Nonnull String name);

  @Nonnull
  Set<Identifiable> getOrCreateLinkSet(@Nonnull String name);

  @Nonnull
  Map<String, Identifiable> getOrCreateLinkMap(@Nonnull String name);

  /**
   * Remove a property
   *
   * @param name the property name
   */
  <RET> RET removeProperty(@Nonnull String name);

  /**
   * Retrieves the schema class associated with this entity.
   *
   * @return the schema class associated with this entity, or null if it does not have a schema
   * class
   */
  @Nullable
  SchemaClass getSchemaClass();

  /**
   * Retrieves the class name associated with this entity.
   *
   * @return the class name associated with this entity, or null if it does not have a schema class
   */
  @Nullable
  String getSchemaClassName();

  /**
   * Returns true if the current entity is embedded
   *
   * @return true if the current entity is embedded
   */
  boolean isEmbedded();

  /**
   * Fills an entity passing the property names/values as a Map String,Object where the keys are the
   * property names and the values are the property values.
   */
  void updateFromMap(@Nonnull final Map<String, ?> map);

  void updateFromResult(@Nonnull final Result result);

  /**
   * Returns the entity as <code>Map</code>. If specified includes entity metadata:
   *
   * <ol>
   *  <li>If the entity has identity, then the @rid entry is added.</li>
   *  <li>If the entity has a class, then the @class entry is added.</li>
   *  <li>If entity is embedded, then the @embedded entry is added.</li>
   * </ol>
   *
   * @param includeMetadata if true, includes metadata in the map
   */
  @Nonnull
  Map<String, Object> toMap(boolean includeMetadata);

  List<String> getDirtyProperties();

  List<String> getDirtyPropertiesBetweenCallbacks();

  @Nonnull
  @Override
  StatefulEdge asStatefulEdge();

  @Nullable
  @Override
  StatefulEdge asStatefulEdgeOrNull();

  @Nonnull
  @Override
  Edge asEdge();

  @Nullable
  @Override
  Edge asEdgeOrNull();

  @Nonnull
  @Override
  Vertex asVertex();

  @Nullable
  @Override
  default Entity asEntityOrNull() {
    return this;
  }

  @Nonnull
  @Override
  default Entity asEntity() {
    return this;
  }

  @Nonnull
  @Override
  default Identifiable asIdentifiable() {
    return this;
  }

  @Nullable
  @Override
  default Identifiable asIdentifiableOrNull() {
    return this;
  }

  @Nonnull
  @Override
  default Blob asBlob() {
    throw new IllegalStateException("Entity is not a Blob");
  }

  @Nullable
  @Override
  default Blob asBlobOrNull() {
    return null;
  }

  @Nullable
  @Override
  Vertex asVertexOrNull();

  @Nonnull
  @Override
  default DBRecord asRecord() {
    return this;
  }

  @Nullable
  @Override
  default DBRecord asRecordOrNull() {
    return this;
  }

  @Override
  default boolean isBlob() {
    return false;
  }

  @Override
  default boolean isEntity() {
    return true;
  }

  @Override
  boolean isStatefulEdge();

  @Override
  boolean isVertex();

  @Override
  default boolean isRecord() {
    return true;
  }
}
