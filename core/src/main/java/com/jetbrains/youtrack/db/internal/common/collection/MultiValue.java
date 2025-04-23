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
package com.jetbrains.youtrack.db.internal.common.collection;

import com.jetbrains.youtrack.db.api.common.query.BasicResultSet;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalResultSet;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Multi-value types such as Arrays, Collections and Maps. It recognizes special YouTrackDB
 * collections.
 */
@SuppressWarnings("unchecked")
public class MultiValue {

  private static final Logger logger = LoggerFactory.getLogger(MultiValue.class);

  /**
   * Checks if a class is a multi-value type.
   *
   * @param iType Class to check
   * @return true if it's an array, a collection or a map, otherwise false
   */
  public static boolean isMultiValue(final Class<?> iType) {
    return DataContainer.class.isAssignableFrom(iType)
        || Collection.class.isAssignableFrom(iType)
        || iType.isArray()
        || Map.class.isAssignableFrom(iType)
        || MultiCollectionIterator.class.isAssignableFrom(iType)
        || (Iterable.class.isAssignableFrom(iType)
        && !(Identifiable.class.isAssignableFrom(iType)))
        || BasicResultSet.class.isAssignableFrom(iType);
  }

  /**
   * Checks if the object is a multi-value type.
   *
   * @param iObject Object to check
   * @return true if it's an array, a collection or a map, otherwise false
   */
  public static boolean isMultiValue(final Object iObject) {
    return iObject != null && isMultiValue(iObject.getClass());
  }

  public static boolean isIterable(final Object iObject) {
    return iObject != null && (iObject instanceof Iterable<?> || iObject instanceof Iterator<?>);
  }

  /**
   * Returns the size of the multi-value object
   *
   * @param iObject Multi-value object (array, collection or map)
   * @return the size of the multi value object
   */
  public static long getSize(final Object iObject) {
    if (iObject == null) {
      return 0;
    }

    if (iObject instanceof Sizeable sizeable && sizeable.isSizeable()) {
      return sizeable.size();
    }

    if (!isMultiValue(iObject)) {
      return 0;
    }

    if (iObject instanceof Collection<?>) {
      return ((Collection<Object>) iObject).size();
    }
    if (iObject instanceof Map<?, ?>) {
      return ((Map<?, Object>) iObject).size();
    }
    if (iObject.getClass().isArray()) {
      return Array.getLength(iObject);
    }
    if ((iObject instanceof Iterable && !(iObject instanceof EntityImpl))) {
      var i = 0;
      for (var o : (Iterable<?>) iObject) {
        i++;
      }
      return i;
    }
    if (iObject instanceof InternalResultSet) {
      return ((InternalResultSet) iObject).size();
    }
    if (iObject instanceof ResultSet resultSet) {
      var res = resultSet.stream().count();
      if (resultSet instanceof Resettable resettable && resettable.isResetable()) {
        resettable.reset();
      }
      return res;
    }
    return 0;
  }

  /**
   * Returns the first item of the Multi-value object (array, collection or map)
   *
   * @param iObject Multi-value object (array, collection or map)
   * @return The first item if any
   */
  @Nullable
  public static Object getFirstValue(final Object iObject) {
    if (iObject == null) {
      return null;
    }

    if (!isMultiValue(iObject) || getSize(iObject) == 0) {
      return null;
    }

    try {
      if (iObject instanceof List<?>) {
        return ((List<Object>) iObject).get(0);
      } else if (iObject instanceof Iterable<?>) {
        return ((Iterable<Object>) iObject).iterator().next();
      } else if (iObject instanceof Map<?, ?>) {
        return ((Map<?, Object>) iObject).values().iterator().next();
      } else if (iObject.getClass().isArray()) {
        return Array.get(iObject, 0);
      }
    } catch (RuntimeException e) {
      // IGNORE IT
      LogManager.instance()
          .debug(
              iObject, "Error on reading the first item of the Multi-value field '%s'", logger,
              iObject,
              e);
    }

    return null;
  }

  /**
   * Returns the last item of the Multi-value object (array, collection or map)
   *
   * @param iObject Multi-value object (array, collection or map)
   * @return The last item if any
   */
  @Nullable
  public static Object getLastValue(final Object iObject) {
    if (iObject == null) {
      return null;
    }

    if (!isMultiValue(iObject)) {
      return null;
    }

    try {
      if (iObject instanceof List<?>) {
        return ((List<Object>) iObject).get(((List<Object>) iObject).size() - 1);
      } else if (iObject instanceof Iterable<?>) {
        Object last = null;
        for (var o : (Iterable<Object>) iObject) {
          last = o;
        }
        return last;
      } else if (iObject instanceof Map<?, ?>) {
        Object last = null;
        for (var o : ((Map<?, Object>) iObject).values()) {
          last = o;
        }
        return last;
      } else if (iObject.getClass().isArray()) {
        return Array.get(iObject, Array.getLength(iObject) - 1);
      }
    } catch (RuntimeException e) {
      // IGNORE IT
      LogManager.instance()
          .debug(
              iObject, "Error on reading the last item of the Multi-value field '%s'", logger,
              iObject,
              e);
    }

    return null;
  }

  /**
   * Returns the iIndex item of the Multi-value object (array, collection or map)
   *
   * @param iObject Multi-value object (array, collection or map)
   * @param iIndex  integer as the position requested
   * @return The first item if any
   */
  @Nullable
  public static Object getValue(final Object iObject, final int iIndex) {
    if (iObject == null) {
      return null;
    }

    if (!isMultiValue(iObject)) {
      return null;
    }

    try {
      if (iObject instanceof List<?>) {
        return ((List<?>) iObject).get(iIndex);
      } else if (iObject instanceof Set<?>) {
        var i = 0;
        for (var o : ((Set<?>) iObject)) {
          if (i++ == iIndex) {
            return o;
          }
        }
      } else if (iObject instanceof Map<?, ?>) {
        var i = 0;
        for (var o : ((Map<?, ?>) iObject).values()) {
          if (i++ == iIndex) {
            return o;
          }
        }
      } else if (iObject.getClass().isArray()) {
        return Array.get(iObject, iIndex);
      } else if (iObject instanceof Iterator<?> || iObject instanceof Iterable<?>) {
        final Iterator<Object> it;
        if (iObject instanceof Iterable<?>) {
          it = ((Iterable<Object>) iObject).iterator();
        } else {
          it = (Iterator<Object>) iObject;
        }

        for (var i = 0; it.hasNext(); ++i) {
          final var o = it.next();
          if (i == iIndex) {
            if (it instanceof Resettable resettable && resettable.isResetable()) {
              resettable.reset();
            }

            return o;
          }
        }

        if (it instanceof Resettable resettable && resettable.isResetable()) {
          resettable.reset();
        }
      }
    } catch (RuntimeException e) {
      // IGNORE IT
      LogManager.instance()
          .debug(
              iObject, "Error on reading the first item of the Multi-value field '%s'", logger,
              iObject,
              e);
    }
    return null;
  }

  /**
   * Sets the value of the Multi-value object (array or collection) at iIndex
   *
   * @param iObject Multi-value object (array, collection)
   * @param iValue  The value to set at this specified index.
   * @param iIndex  integer as the position requested
   */
  public static void setValue(final Object iObject, final Object iValue, final int iIndex) {
    if (iObject instanceof List<?>) {
      ((List<Object>) iObject).set(iIndex, iValue);
    } else if (iObject.getClass().isArray()) {
      Array.set(iObject, iIndex, iValue);
    } else {
      throw new IllegalArgumentException("Can only set positional indices for Lists and Arrays");
    }
  }

  /**
   * Returns an <code>Iterable<Object></code> object to browse the multi-value instance (array,
   * collection or map).
   *
   * @param iObject Multi-value object (array, collection or map)
   */
  @Nullable
  public static Iterable<Object> getMultiValueIterable(final Object iObject) {
    if (iObject == null) {
      return null;
    }

    if (iObject instanceof Iterable<?> && !(iObject instanceof EntityImpl)) {
      return (Iterable<Object>) iObject;
    } else if (iObject instanceof Collection<?>) {
      return ((Collection<Object>) iObject);
    } else if (iObject instanceof Map<?, ?>) {
      return ((Map<?, Object>) iObject).values();
    } else if (iObject.getClass().isArray()) {
      return new IterableObjectArray<Object>(iObject);
    } else if (iObject instanceof Iterator<?>) {
      final List<Object> temp = new ArrayList<Object>();
      for (var it = (Iterator<Object>) iObject; it.hasNext(); ) {
        temp.add(it.next());
      }
      return temp;
    }

    return new IterableObject<Object>(iObject);
  }

  /**
   * Returns an <code>Iterator<Object></code> object to browse the multi-value instance (array,
   * collection or map)
   *
   * @param iObject Multi-value object (array, collection or map)
   */
  @Nullable
  public static Iterator<?> getMultiValueIterator(final Object iObject) {
    switch (iObject) {
      case null -> {
        return null;
      }
      case Iterator<?> iterator -> {
        return (Iterator<Object>) iObject;
      }
      case Iterable<?> objects -> {
        return ((Iterable<Object>) iObject).iterator();
      }
      case Map<?, ?> map -> {
        return ((Map<?, Object>) iObject).values().iterator();
      }
      default -> {
      }
    }
    if (iObject.getClass().isArray()) {
      return new IterableObjectArray<>(iObject).iterator();
    }

    return new IterableObject<>(iObject);
  }

  /**
   * Returns a stringified version of the multi-value object.
   *
   * @param iObject Multi-value object (array, collection or map)
   * @return a stringified version of the multi-value object.
   */
  public static String toString(final Object iObject) {
    final var sb = new StringBuilder(2048);

    if (iObject instanceof Iterable<?>) {
      final var coll = (Iterable<Object>) iObject;

      sb.append('[');
      for (final var it = coll.iterator(); it.hasNext(); ) {
        try {
          var e = it.next();
          sb.append(e == iObject ? "(this Collection)" : e);
          if (it.hasNext()) {
            sb.append(", ");
          }
        } catch (NoSuchElementException ignore) {
          // IGNORE THIS
        }
      }
      return sb.append(']').toString();
    } else if (iObject instanceof Map<?, ?>) {
      final var map = (Map<String, Object>) iObject;

      Entry<String, Object> e;

      sb.append('{');
      for (final var it = map.entrySet().iterator(); it.hasNext(); ) {
        try {
          e = it.next();

          sb.append(e.getKey());
          sb.append(":");
          sb.append(e.getValue() == iObject ? "(this Map)" : e.getValue());
          if (it.hasNext()) {
            sb.append(", ");
          }
        } catch (NoSuchElementException ignore) {
          // IGNORE THIS
        }
      }
      return sb.append('}').toString();
    }

    return iObject.toString();
  }

  /**
   * Utility function that add a value to the main object. It takes care about collections/array and
   * single values.
   *
   * @param iObject MultiValue where to add value(s)
   * @param iToAdd  Single value, array of values or collections of values. Map are not supported.
   * @return
   */
  public static Object add(final Object iObject, final Object iToAdd) {
    if (iObject != null) {
      if (iObject instanceof Collection<?> || iObject instanceof DataContainer<?>) {
        // COLLECTION - ?
        final DataContainer<Object> coll;
        if (iObject instanceof Collection<?>) {
          final var collection = (Collection<Object>) iObject;
          coll =
              new DataContainer<Object>() {
                @Override
                public void add(Object value) {
                  collection.add(value);
                }

                @Override
                public boolean remove(Object value) {
                  return collection.remove(value);
                }

                @Nonnull
                @Override
                public Iterator<Object> iterator() {
                  return collection.iterator();
                }

                @Override
                public int size() {
                  return collection.size();
                }

                @Override
                public boolean isSizeable() {
                  return true;
                }
              };
        } else {
          coll = (DataContainer<Object>) iObject;
        }

        if (!(iToAdd instanceof Map) && isMultiValue(iToAdd)) {
          // COLLECTION - COLLECTION
          for (var o : getMultiValueIterable(iToAdd)) {
            if (!(o instanceof Map) && isMultiValue(o)) {
              add(coll, o);
            } else {
              coll.add(o);
            }
          }
        } else if (iToAdd != null && iToAdd.getClass().isArray()) {
          // ARRAY - COLLECTION
          for (var i = 0; i < Array.getLength(iToAdd); ++i) {
            var o = Array.get(iToAdd, i);
            if (!(o instanceof Map) && isMultiValue(o)) {
              add(coll, o);
            } else {
              coll.add(o);
            }
          }

        } else if (iToAdd instanceof Map<?, ?>) {
          // MAP
          coll.add(iToAdd);
        } else if (iToAdd instanceof Iterator<?> it) {
          // ITERATOR
          for (; it.hasNext(); ) {
            coll.add(it.next());
          }
        } else {
          coll.add(iToAdd);
        }

      } else if (iObject.getClass().isArray()) {
        // ARRAY - ?

        final Object[] copy;
        if (iToAdd instanceof Collection<?>) {
          // ARRAY - COLLECTION
          final var tot = Array.getLength(iObject) + ((Collection<Object>) iToAdd).size();
          copy = Arrays.copyOf((Object[]) iObject, tot);
          final var it = ((Collection<Object>) iToAdd).iterator();
          for (var i = Array.getLength(iObject); i < tot; ++i) {
            copy[i] = it.next();
          }

        } else if (iToAdd != null && iToAdd.getClass().isArray()) {
          // ARRAY - ARRAY
          final var tot = Array.getLength(iObject) + Array.getLength(iToAdd);
          copy = Arrays.copyOf((Object[]) iObject, tot);
          System.arraycopy(iToAdd, 0, iObject, Array.getLength(iObject), Array.getLength(iToAdd));

        } else {
          copy = Arrays.copyOf((Object[]) iObject, Array.getLength(iObject) + 1);
          copy[copy.length - 1] = iToAdd;
        }
        return copy;
      }
    }

    return iObject;
  }

  /**
   * Utility function that remove a value from the main object. It takes care about
   * collections/array and single values.
   *
   * @param iObject         MultiValue where to add value(s)
   * @param iToRemove       Single value, array of values or collections of values. Map are not
   *                        supported.
   * @param iAllOccurrences True if the all occurrences must be removed or false of only the first
   *                        one (Like java.util.Collection.remove())
   * @return
   */
  public static Object remove(Object iObject, Object iToRemove, final boolean iAllOccurrences) {
    if (iObject != null) {
      if (iObject instanceof MultiCollectionIterator<?>) {
        final Collection<Object> list = new LinkedList<Object>();
        for (var o : ((MultiCollectionIterator<?>) iObject)) {
          list.add(o);
        }
        iObject = list;
      }

      if (iToRemove instanceof MultiCollectionIterator<?>) {
        // TRANSFORM IN SET ONCE TO OPTIMIZE LOOPS DURING REMOVE
        final Set<Object> set = new HashSet<Object>();
        for (var o : ((MultiCollectionIterator<?>) iToRemove)) {
          set.add(o);
        }
        iToRemove = set;
      }

      if (iObject instanceof Collection<?> || iObject instanceof DataContainer<?>) {
        // COLLECTION - ?

        final DataContainer<Object> coll;
        if (iObject instanceof Collection<?>) {
          final var collection = (Collection<Object>) iObject;
          coll =
              new DataContainer<Object>() {
                @Override
                public void add(Object value) {
                  collection.add(value);
                }

                @Override
                public boolean remove(Object value) {
                  return collection.remove(value);
                }

                @Override
                public Iterator<Object> iterator() {
                  return collection.iterator();
                }

                @Override
                public int size() {
                  return collection.size();
                }

                @Override
                public boolean isSizeable() {
                  return true;
                }
              };
        } else {
          coll = (DataContainer<Object>) iObject;
        }

        if (iToRemove instanceof Collection<?>) {
          // COLLECTION - COLLECTION
          for (var o : (Collection<Object>) iToRemove) {
            if (isMultiValue(o)) {
              remove(coll, o, iAllOccurrences);
            } else {
              removeFromOCollection(iObject, coll, o, iAllOccurrences);
            }
          }
        } else if (iToRemove != null && iToRemove.getClass().isArray()) {
          // ARRAY - COLLECTION
          for (var i = 0; i < Array.getLength(iToRemove); ++i) {
            var o = Array.get(iToRemove, i);
            if (isMultiValue(o)) {
              remove(coll, o, iAllOccurrences);
            } else {
              removeFromOCollection(iObject, coll, o, iAllOccurrences);
            }
          }

        } else if (iToRemove instanceof Map<?, ?>) {
          // MAP
          for (var entry : ((Map<Object, Object>) iToRemove).entrySet()) {
            coll.remove(entry.getKey());
          }
        } else if (iToRemove instanceof Iterator<?>) {
          // ITERATOR
          if (iToRemove instanceof MultiCollectionIterator<?>) {
            ((MultiCollectionIterator<?>) iToRemove).reset();
          }

          if (iAllOccurrences) {
            if (iObject instanceof DataContainer) {
              throw new IllegalStateException(
                  "Mutable collection cannot be used to remove all occurrences.");
            }

            final Collection<Object> collection = (Collection) iObject;
            var it = (MultiCollectionIterator<?>) iToRemove;
            batchRemove(collection, it);
          } else {
            var it = (Iterator<?>) iToRemove;
            if (it.hasNext()) {
              final var itemToRemove = it.next();
              coll.remove(itemToRemove);
            }
          }
        } else {
          removeFromOCollection(iObject, coll, iToRemove, iAllOccurrences);
        }

      } else if (iObject.getClass().isArray()) {
        // ARRAY - ?

        final Object[] copy;
        if (iToRemove instanceof Collection<?>) {
          // ARRAY - COLLECTION
          final var sourceTot = Array.getLength(iObject);
          final var tot = sourceTot - ((Collection<Object>) iToRemove).size();
          copy = new Object[tot];

          var k = 0;
          for (var i = 0; i < sourceTot; ++i) {
            var o = Array.get(iObject, i);
            if (o != null) {
              var found = false;
              for (var toRemove : (Collection<Object>) iToRemove) {
                if (o.equals(toRemove)) {
                  // SKIP
                  found = true;
                  break;
                }
              }

              if (!found) {
                copy[k++] = o;
              }
            }
          }

        } else if (iToRemove != null && iToRemove.getClass().isArray()) {
          throw new UnsupportedOperationException("Cannot execute remove() against an array");

        } else {
          throw new UnsupportedOperationException("Cannot execute remove() against an array");
        }
        return copy;

      } else if (iObject instanceof Map) {
        ((Map) iObject).remove(iToRemove);
      } else {
        throw new IllegalArgumentException("Object " + iObject + " is not a multi value");
      }
    }

    return iObject;
  }

  protected static void removeFromOCollection(
      final Object iObject,
      final DataContainer<Object> coll,
      final Object iToRemove,
      final boolean iAllOccurrences) {
    if (iAllOccurrences && !(iObject instanceof Set)) {
      // BROWSE THE COLLECTION ONE BY ONE TO REMOVE ALL THE OCCURRENCES
      final var it = coll.iterator();
      while (it.hasNext()) {
        final var o = it.next();
        if (iToRemove.equals(o)) {
          it.remove();
        }
      }
    } else {
      coll.remove(iToRemove);
    }
  }

  private static void batchRemove(Collection<Object> coll, Iterator<?> it) {
    int approximateRemainingSize;
    if (it instanceof Sizeable sizeable && sizeable.isSizeable()) {
      approximateRemainingSize = sizeable.size();
    } else {
      approximateRemainingSize = -1;
    }

    while (it.hasNext()) {
      var batch = prepareBatch(it, approximateRemainingSize);
      coll.removeAll(batch);
      approximateRemainingSize -= batch.size();
    }
  }

  private static Set<?> prepareBatch(Iterator<?> it, int approximateRemainingSize) {
    final HashSet<Object> batch;
    if (approximateRemainingSize > -1) {
      if (approximateRemainingSize > 10000) {
        batch = new HashSet<Object>(13400);
      } else {
        batch = new HashSet<Object>((int) (approximateRemainingSize / 0.75));
      }
    } else {
      batch = new HashSet<Object>();
    }

    var count = 0;
    while (count < 10000 && it.hasNext()) {
      batch.add(it.next());
      count++;
    }

    return batch;
  }

  public static Object[] array(final Object iValue) {
    return array(iValue, Object.class);
  }

  public static <T> T[] array(final Object iValue, final Class<? extends T> iClass) {
    return array(iValue, iClass, null);
  }

  @Nullable
  public static <T> T[] array(
      final Object iValue,
      final Class<? extends T> iClass,
      final CallableFunction<Object, Object> iCallback) {
    if (iValue == null) {
      return null;
    }

    final T[] result;

    if (isMultiValue(iValue)) {
      // CREATE STATIC ARRAY AND FILL IT
      result = (T[]) Array.newInstance(iClass, (int) getSize(iValue));
      var i = 0;
      for (var it = (Iterator<T>) getMultiValueIterator(iValue); it.hasNext(); ++i) {
        result[i] = (T) convert(it.next(), iCallback);
      }
    } else if (isIterable(iValue)) {
      // SIZE UNKNOWN: USE A LIST AS TEMPORARY OBJECT
      final List<T> temp = new ArrayList<T>();
      for (var it = (Iterator<T>) getMultiValueIterator(iValue); it.hasNext(); ) {
        temp.add((T) convert(it.next(), iCallback));
      }

      if (iClass.equals(Object.class)) {
        result = (T[]) temp.toArray();
      } else
      // CONVERT THEM
      {
        result = temp.toArray((T[]) Array.newInstance(iClass, (int) getSize(iValue)));
      }

    } else {
      result = (T[]) Array.newInstance(iClass, 1);
      result[0] = (T) convert(iValue, iCallback);
    }

    return result;
  }

  public static Object convert(final Object iObject,
      final CallableFunction<Object, Object> iCallback) {
    return iCallback != null ? iCallback.call(iObject) : iObject;
  }

  public static boolean equals(final Collection<Object> col1, final Collection<Object> col2) {
    if (col1.size() != col2.size()) {
      return false;
    }
    return col1.containsAll(col2) && col2.containsAll(col1);
  }

  public static boolean contains(final Object multiValue, final Object itemToCheck) {
    if (multiValue == null) {
      return false;
    }

    if (multiValue instanceof Collection) {
      return ((Collection<?>) multiValue).contains(itemToCheck);
    }

    if (multiValue.getClass().isArray()) {
      final var size = Array.getLength(multiValue);
      for (var i = 0; i < size; ++i) {
        final var item = Array.get(multiValue, i);
        if (item != null && item.equals(itemToCheck)) {
          return true;
        }
      }
    }

    var iterator = getMultiValueIterator(multiValue);
    if (iterator == null) {
      return false;
    }

    while (iterator.hasNext()) {
      if (itemToCheck.equals(iterator.next())) {
        return true;
      }
    }

    return false;
  }


  public static boolean contains(final Object iObject, final Function<Object, Boolean> iPredicate) {
    var iterator = getMultiValueIterator(iObject);
    if (iterator == null) {
      return false;
    }
    while (iterator.hasNext()) {
      if (iPredicate.apply(iterator.next())) {
        return true;
      }
    }
    return false;
  }


  public static int indexOf(final Object iObject, final Object iItem) {
    if (iObject == null) {
      return -1;
    }

    if (iObject instanceof List) {
      return ((List) iObject).indexOf(iItem);
    } else if (iObject.getClass().isArray()) {
      final var size = Array.getLength(iObject);
      for (var i = 0; i < size; ++i) {
        final var item = Array.get(iObject, i);
        if (item != null && item.equals(iItem)) {
          return i;
        }
      }
    }

    return -1;
  }

  public static Object toSet(final Object o) {
    if (o instanceof Set<?>) {
      return o;
    } else if (o instanceof Collection<?>) {
      return new HashSet<Object>((Collection<?>) o);
    } else if (o instanceof Map<?, ?>) {
      final var values = ((Map) o).values();
      return values instanceof Set ? values : new HashSet(values);
    } else if (o.getClass().isArray()) {
      final var set = new HashSet();
      var tot = Array.getLength(o);
      for (var i = 0; i < tot; ++i) {
        set.add(Array.get(o, i));
      }
      return set;
    } else if (o instanceof Iterator<?>) {
      final var set = new HashSet();
      while (((Iterator<?>) o).hasNext()) {
        set.add(((Iterator<?>) o).next());
      }

      if (o instanceof Resettable resettable && resettable.isResetable()) {
        resettable.reset();
      }

      return set;
    } else if (o instanceof Iterable && !(o instanceof Identifiable)) {
      var iterator = ((Iterable) o).iterator();
      Set result = new HashSet();
      while (iterator.hasNext()) {
        result.add(iterator.next());
      }
      return result;
    }

    final var set = new HashSet(1);
    set.add(o);
    return set;
  }

  public static <T> List<T> getSingletonList(final T item) {
    final List<T> list = new ArrayList<T>(1);
    list.add(item);
    return list;
  }
}
