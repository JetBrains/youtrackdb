package com.jetbrains.youtrack.db.internal.core.gremlin;


import com.jetbrains.youtrack.db.api.gremlin.YTDBElement;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public class YTDBPropertyImpl<V> implements
    org.apache.tinkerpop.gremlin.structure.Property<V> {

  protected String key;
  protected V value;
  protected Object wrappedValue;
  protected YTDBElementImpl element;
  private boolean removed = false;

  public YTDBPropertyImpl(String key, V value, YTDBElementImpl element) {
    this.key = key;
    this.value = value;
    this.element = element;
    this.wrappedValue = wrapIntoGraphElement(value);
  }

  private Object wrapIntoGraphElement(V value) {
    Object result = value;
    var graph = element.getGraph();
    var graphTx = graph.tx();
    if (result instanceof RID rid) {
      var session = graphTx.getSession();
      var tx = session.getActiveTransaction();
      result = tx.loadEntity(rid);
    }
    if (result instanceof Entity entity) {
      if (entity.isVertex()) {
        result =
            new YTDBVertexImpl(graph, entity.asVertex());
      } else if (entity.isStatefulEdge()) {
        result = new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
      }
    }
    if (result instanceof Collection<?> collection && containsGraphElements(collection)) {
      if (result instanceof List<?> list) {
        result = new VertexEdgeListWrapper(list, element);
      } else if (result instanceof Set<?> set) {
        result = new VertexEdgeSetWrapper(set, element);
      }
    }
    return result;
  }

  private static boolean containsGraphElements(Collection<?> result) {
    for (var o : result) {
      if (o instanceof Entity entity) {
        if (entity.isVertex() || entity.isStatefulEdge()) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public V value() throws NoSuchElementException {
    //noinspection unchecked
    return (V) wrappedValue;
  }

  @Override
  public boolean isPresent() {
    return !removed;
  }

  @Override
  public YTDBElement element() {
    return this.element;
  }

  @Override
  public void remove() {
    var entity = element.getRawEntity();
    entity.removeProperty(key);
    this.value = null;
    wrappedValue = null;
    removed = true;
  }

  @Override
  public String toString() {
    return StringFactory.propertyString(this);
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public boolean equals(final Object object) {
    return ElementHelper.areEqual(this, object);
  }

  @Override
  public int hashCode() {
    return ElementHelper.hashCode(this);
  }
}
