package com.jetbrains.youtrack.db.api.record;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface StatefulEdge extends Edge, Entity {
  /**
   * Returns the names of defined properties except of properties used to manage edges.
   *
   * @return all the names of defined properties
   */
  @Nonnull
  Collection<String> getPropertyNames();

  /**
   * Gets a property given its name. if the property name equals to {@link #DIRECTION_IN} or
   * {@link #DIRECTION_OUT} method throws {@link IllegalArgumentException}. Those names are used to
   * manage edges.
   *
   * @param name the property name
   * @return Returns the property value
   * @throws IllegalArgumentException if booked property name is used.
   */
  <RET> RET getProperty(@Nonnull String name);

  /**
   * This method similar to {@link com.jetbrains.youtrack.db.api.query.Result#getProperty(String)}
   * bun unlike before mentioned method it does not load link automatically. if the property name
   * equals to {@link #DIRECTION_IN} or {@link #DIRECTION_OUT} method throws
   * {@link IllegalArgumentException}. Those names are used to manage edges.
   *
   * @param name the name of the link property
   * @return the link property value, or null if the property does not exist
   * @throws IllegalArgumentException if booked property name is used or requested property is not a
   *                                  link.
   * @see com.jetbrains.youtrack.db.api.query.Result#getProperty(String)
   */
  @Nullable
  RID getLink(@Nonnull String name);

  /**
   * Check if a property exists in the Element. if the property name equals to {@link #DIRECTION_IN}
   * or {@link #DIRECTION_OUT} method throws {@link IllegalArgumentException}. Those names are used
   * to manage edges.
   *
   * @param propertyName Name of the property to check.
   * @return true if exists otherwise false.
   * @throws IllegalArgumentException if booked property name is used.
   */
  boolean hasProperty(final @Nonnull String propertyName);

  /**
   * Sets a property value, if the property name equals to {@link #DIRECTION_IN} or
   * {@link #DIRECTION_OUT} update of such property is aborted. Those names are used to
   *
   * @param name  the property name
   * @param value the property value
   * @throws IllegalArgumentException if booked property name is used.
   */
  void setProperty(@Nonnull String name, @Nullable Object value);

  /**
   * Sets a property value, if the property name equals to {@link #DIRECTION_IN} or
   * {@link #DIRECTION_OUT} update of such property is aborted. Those names are used to manage
   * edges.
   *
   * @param propertyName the property name
   * @param value        the property value
   * @param fieldType    Forced type (not auto-determined)
   * @throws IllegalArgumentException if booked property name is used.
   */
  void setProperty(@Nonnull String propertyName, Object value, @Nonnull PropertyType fieldType);

  /**
   * Remove a property, if the property name equals to {@link #DIRECTION_IN} or
   * {@link #DIRECTION_OUT} removal of such property is aborted. Those names are used to manage
   * edges.
   *
   * @param name the property name
   * @throws IllegalArgumentException if booked property name is used.
   */
  <RET> RET removeProperty(@Nonnull String name);

  @Nonnull
  @Override
  StatefulEdge asStatefulEdge();

  @Nullable
  @Override
  StatefulEdge asStatefulEdgeOrNull();
}
