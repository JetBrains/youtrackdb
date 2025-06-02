package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VertexEdgeListWrapper implements List {
    private final List wrapped;
    private final YTDBElementImpl parent;

    public VertexEdgeListWrapper(List wrapped, YTDBElementImpl parentElement) {
        this.wrapped = wrapped;
        this.parent = parentElement;
    }

    private Object unbox(Object next) {
        if (next instanceof YTDBElementImpl) {
            return ((YTDBElementImpl) next).getRawEntity();
        }
        return next;
    }

    private Object box(Object elem) {
        if (elem instanceof RID rid) {
            var graph = parent.getGraph();
            var session = graph.getUnderlyingDatabaseSession();
            var tx = session.getActiveTransaction();
            elem = tx.loadEntity(rid);
        }
        if (elem instanceof Entity entity) {
            if (entity.isVertex()) {
                elem = parent.getGraph().elementFactory().wrapVertex(entity.asVertex());
            } else if (entity.isEdge()) {
                elem = parent.getGraph().elementFactory().wrapEdge(entity.asStatefulEdge());
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
        if (o instanceof YTDBElementImpl && wrapped.contains(
            ((YTDBElementImpl) o).getRawEntity())) {
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
    public boolean addAll(int index, Collection c) {
        for (Object o : c) {
            wrapped.add(index++, unbox(o));
        }
        return true;
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
    public void replaceAll(UnaryOperator operator) {
        wrapped.replaceAll(operator);
    }

    @Override
    public void sort(Comparator c) {
        wrapped.sort(c);
    }

    @Override
    public void clear() {
        wrapped.clear();
    }

    @Override
    public Object get(int index) {
        return box(wrapped.get(index));
    }

    @Override
    public Object set(int index, Object element) {
        return wrapped.set(index, unbox(element));
    }

    @Override
    public void add(int index, Object element) {
        wrapped.add(index, unbox(element));
    }

    @Override
    public Object remove(int index) {
        return wrapped.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return wrapped.indexOf(unbox(o));
    }

    @Override
    public int lastIndexOf(Object o) {
        return wrapped.lastIndexOf(unbox(o));
    }

    @Override
    public ListIterator listIterator() {
        return new ListIterator() {
            final ListIterator baseIter = wrapped.listIterator();

            @Override
            public boolean hasNext() {
                return baseIter.hasNext();
            }

            @Override
            public Object next() {
                return box(baseIter.next());
            }

            @Override
            public boolean hasPrevious() {
                return baseIter.hasNext();
            }

            @Override
            public Object previous() {
                return box(baseIter.previous());
            }

            @Override
            public int nextIndex() {
                return baseIter.nextIndex();
            }

            @Override
            public int previousIndex() {
                return baseIter.previousIndex();
            }

            @Override
            public void remove() {
                baseIter.remove();
            }

            @Override
            public void set(Object o) {
                baseIter.set(unbox(o));
            }

            @Override
            public void add(Object o) {
                baseIter.add(unbox(o));
            }
        };
    }

    @Override
    public ListIterator listIterator(int index) {
        return new ListIterator() {
            final ListIterator baseIter = wrapped.listIterator(index);

            @Override
            public boolean hasNext() {
                return baseIter.hasNext();
            }

            @Override
            public Object next() {
                return box(baseIter.next());
            }

            @Override
            public boolean hasPrevious() {
                return baseIter.hasNext();
            }

            @Override
            public Object previous() {
                return box(baseIter.previous());
            }

            @Override
            public int nextIndex() {
                return baseIter.nextIndex();
            }

            @Override
            public int previousIndex() {
                return baseIter.previousIndex();
            }

            @Override
            public void remove() {
                baseIter.remove();
            }

            @Override
            public void set(Object o) {
                baseIter.set(unbox(o));
            }

            @Override
            public void add(Object o) {
                baseIter.add(unbox(o));
            }
        };
    }

    @Override
    public List subList(int fromIndex, int toIndex) {
        return new VertexEdgeListWrapper(wrapped.subList(fromIndex, toIndex), parent);
    }

    @Override
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
