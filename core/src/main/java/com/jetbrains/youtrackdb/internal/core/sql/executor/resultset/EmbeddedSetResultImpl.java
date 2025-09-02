package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

public class EmbeddedSetResultImpl<T> implements EmbeddedSet<T> {
  private final Set<T> set;

  @SuppressWarnings("unused")
  public EmbeddedSetResultImpl(Collection<? extends T> set) {
    this.set = new HashSet<>(set);
  }

  @SuppressWarnings("unused")
  public EmbeddedSetResultImpl() {
    this.set = new HashSet<>();
  }

  @SuppressWarnings("unused")
  public EmbeddedSetResultImpl(int initialCapacity) {
    this.set = new HashSet<>(initialCapacity);
  }

  @Override
  public int size() {
    return set.size();
  }

  @Override
  public boolean isEmpty() {
    return set.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return set.contains(o);
  }

  @Nonnull
  @Override
  public Iterator<T> iterator() {
    return set.iterator();
  }

  @Nonnull
  @Override
  public Object[] toArray() {
    return set.toArray();
  }

  @Nonnull
  @Override
  public <T1> T1[] toArray(@Nonnull T1[] a) {
    return set.toArray(a);
  }

  @Override
  public boolean add(T t) {
    return set.add(t);
  }

  @Override
  public boolean remove(Object o) {
    return set.remove(o);
  }

  @Override
  public boolean containsAll(@Nonnull Collection<?> c) {
    return set.containsAll(c);
  }

  @Override
  public boolean addAll(@Nonnull Collection<? extends T> c) {
    return set.addAll(c);
  }

  @Override
  public boolean retainAll(@Nonnull Collection<?> c) {
    return set.retainAll(c);
  }

  @Override
  public boolean removeAll(@Nonnull Collection<?> c) {
    return set.removeAll(c);
  }

  @Override
  public void clear() {
    set.clear();
  }

  @Nonnull
  @Override
  public Spliterator<T> spliterator() {
    return set.spliterator();
  }

  @Override
  public <T1> T1[] toArray(@Nonnull IntFunction<T1[]> generator) {
    return set.toArray(generator);
  }

  @Override
  public boolean removeIf(@Nonnull Predicate<? super T> filter) {
    return set.removeIf(filter);
  }

  @Nonnull
  @Override
  public Stream<T> stream() {
    return set.stream();
  }

  @Nonnull
  @Override
  public Stream<T> parallelStream() {
    return set.parallelStream();
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    set.forEach(action);
  }

  @Override
  public int hashCode() {
    return set.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof Set<?>)) {
      return false;
    }

    return super.equals(obj);
  }


  @Override
  public String toString() {
    return set.toString();
  }
}
