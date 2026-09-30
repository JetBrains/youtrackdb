package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;

/**
 * Expands each upstream element payload into zero or more scalar property values — native
 * {@code values(k1, k2, …)} flat-map order. Keys are emitted in declaration order; absent properties
 * are skipped, matching {@code PropertiesStep} over {@code PropertyType.VALUE}. Works for both
 * vertices and edges ({@link Element#properties(String...)}).
 *
 * <p>YouTrackDB stores one cell per property key ({@code Cardinality.list} writes collapse), so
 * {@link Element#properties(String)} yields at most one {@link Property} per key.
 */
public final class ValuesFlatMapListShapingOp implements ListShapingOp {

  private final String[] keys;

  public ValuesFlatMapListShapingOp(String[] keys) {
    this.keys = Arrays.copyOf(keys, keys.length);
  }

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      private Iterator<Object> keyValues = Collections.emptyIterator();
      private Object pending;

      @Override
      public boolean hasNext() {
        advanceIfNeeded();
        return pending != null;
      }

      @Override
      public Object next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        var out = pending;
        pending = null;
        return out;
      }

      private void advanceIfNeeded() {
        while (pending == null) {
          if (keyValues.hasNext()) {
            pending = keyValues.next();
            return;
          }
          if (!upstream.hasNext()) {
            return;
          }
          keyValues = valuesForElement(upstream.next());
        }
      }

      private Iterator<Object> valuesForElement(Object payload) {
        if (!(payload instanceof Element element)) {
          return Collections.singletonList(payload).iterator();
        }
        return new Iterator<>() {
          private int index;
          private Iterator<? extends Property<Object>> propertyValues =
              Collections.emptyIterator();

          @Override
          public boolean hasNext() {
            while (!propertyValues.hasNext()) {
              if (index >= keys.length) {
                return false;
              }
              propertyValues = element.properties(keys[index++]);
            }
            return true;
          }

          @Override
          public Object next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            return propertyValues.next().value();
          }
        };
      }
    };
  }
}
