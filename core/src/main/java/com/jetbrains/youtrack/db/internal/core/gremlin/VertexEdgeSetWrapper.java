package com.jetbrains.youtrack.db.internal.core.gremlin;


import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

public class VertexEdgeSetWrapper implements Set {

  private final Set wrapped;
  private final YTDBElementImpl parent;

  public VertexEdgeSetWrapper(Set wrapped, YTDBElementImpl parentElement) {
    this.wrapped = wrapped;
    this.parent = parentElement;
  }

  private Object unbox(Object next) {
    if (next instanceof YTDBElementImpl gremlinElement) {
      return gremlinElement.getRawEntity();
    }
    return next;
  }

  private Object box(Object elem) {
    var graph = parent.getGraph();
    var graphTx = graph.tx();
    if (elem instanceof RID rid) {
      var session = graphTx.getDatabaseSession();
      var tx = session.getActiveTransaction();
      elem = tx.loadEntity(rid);
    }
    if (elem instanceof Entity entity) {
      if (entity.isVertex()) {
        elem = new YTDBVertexImpl(graph, entity.asVertex());
      } else if (entity.isStatefulEdge()) {
        elem = new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
      }
    }
    return elem;
  }

  @Override
  public int size() {
    return wrapped.size();
  }

  @Override
  public boolean isEmpty() {
    return wrapped.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    if (o instanceof YTDBElementImpl gremlinElement && wrapped.contains(
        gremlinElement.getRawEntity())) {
      return true;
    }
    return wrapped.contains(o);
  }

  @Override
  public Iterator iterator() {

    return new Iterator() {
      final Iterator baseIter = wrapped.iterator();

      @Override
      public boolean hasNext() {
        return baseIter.hasNext();
      }

      @Override
      public Object next() {
        return box(baseIter.next());
      }
    };
  }

  @Override
  public Object[] toArray() {
    return wrapped.stream().map(x -> box(x)).toArray();
  }

  @Override
  public Object[] toArray(Object[] a) {
    return wrapped.stream().map(x -> box(x)).toArray();
  }

  @Override
  public boolean add(Object o) {
    return wrapped.add(unbox(o));
  }

  @Override
  public boolean remove(Object o) {
    return wrapped.remove(unbox(o));
  }

  @Override
  public boolean containsAll(Collection c) {

    return wrapped.containsAll((List) c.stream().map(x -> unbox(x)).collect(Collectors.toList()));
  }

  @Override
  public boolean addAll(Collection c) {
    boolean changed = false;
    for (Object o : c) {
      changed = changed || wrapped.add(unbox(o));
    }
    return changed;
  }

  @Override
  public void clear() {
    wrapped.clear();
  }

  @Override
  public boolean removeAll(Collection c) {
    boolean changed = false;
    for (Object o : c) {
      changed = changed || wrapped.remove(unbox(o));
    }
    return changed;
  }

  @Override
  public boolean retainAll(Collection c) {
    return wrapped.retainAll(
        (Collection<?>) c.stream().map(x -> unbox(x)).collect(Collectors.toList()));
  }

  @Override
  @Nonnull
  public Spliterator spliterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeIf(Predicate filter) {
    return wrapped.removeIf(filter);
  }

  @Override
  public Stream stream() {
    return wrapped.stream().map(x -> box(x));
  }

  @Override
  public Stream parallelStream() {
    return wrapped.parallelStream().map(x -> box(x));
  }

  @Override
  public void forEach(Consumer action) {
    wrapped.forEach(action);
  }
}
