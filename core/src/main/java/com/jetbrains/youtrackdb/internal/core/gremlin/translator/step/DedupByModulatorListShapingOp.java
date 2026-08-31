package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Keeps the first projected element payload per distinct {@code by(...)} modulator value — native
 * {@code dedup().by(key)} over an {@link BoundaryOutputType#ELEMENT} stream. MATCH {@code RETURN
 * DISTINCT} dedups whole rows and cannot express "unique by property, emit the current element", so
 * this stage runs after row projection on the element payloads.
 */
public final class DedupByModulatorListShapingOp implements ListShapingOp {

  private final ByModulatorTranslator.DedupModulatorKey modulatorKey;

  public DedupByModulatorListShapingOp(ByModulatorTranslator.DedupModulatorKey modulatorKey) {
    this.modulatorKey = modulatorKey;
  }

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    Set<Object> seen = new HashSet<>();
    return new Iterator<>() {
      private Object nextPayload;
      private boolean ready;

      @Override
      public boolean hasNext() {
        if (ready) {
          return true;
        }
        while (upstream.hasNext()) {
          var payload = upstream.next();
          if (seen.add(extractKey(payload))) {
            nextPayload = payload;
            ready = true;
            return true;
          }
        }
        return false;
      }

      @Override
      public Object next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        ready = false;
        return nextPayload;
      }
    };
  }

  private Object extractKey(Object payload) {
    if (!(payload instanceof Element element)) {
      return payload;
    }
    if (modulatorKey.recordAttribute()) {
      if ("@rid".equals(modulatorKey.fieldName())) {
        return element.id();
      }
      if ("@class".equals(modulatorKey.fieldName())) {
        return element.label();
      }
    }
    var property = element.property(modulatorKey.fieldName());
    return property.isPresent() ? property.value() : null;
  }
}
