package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
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

public class LinkSetResultImpl implements LinkSet {

  private final HashSet<Identifiable> set;

  @SuppressWarnings("unused")
  public LinkSetResultImpl(Collection<? extends Identifiable> collection) {
    this.set = new HashSet<>(collection);
  }

  @SuppressWarnings("unused")
  public LinkSetResultImpl() {
    this.set = new HashSet<>();
  }

  @SuppressWarnings("unused")
  public LinkSetResultImpl(int initialCapacity) {
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
  public Iterator<Identifiable> iterator() {
    return set.iterator();
  }

  @Nonnull
  @Override
  public Object[] toArray() {
    return set.toArray();
  }

  @Nonnull
  @Override
  public <T> T[] toArray(@Nonnull T[] a) {
    return set.toArray(a);
  }

  @Override
  public boolean add(Identifiable identifiable) {
    return set.add(identifiable);
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
  public boolean addAll(@Nonnull Collection<? extends Identifiable> c) {
    return set.addAll(c);
  }

  @Override
  public boolean removeAll(@Nonnull Collection<?> c) {
    return set.removeAll(c);
  }

  @Override
  public boolean retainAll(@Nonnull Collection<?> c) {
    return set.retainAll(c);
  }

  @Override
  public void clear() {
    set.clear();
  }

  @Nonnull
  @Override
  public Spliterator<Identifiable> spliterator() {
    return set.spliterator();
  }

  @Override
  public <T> T[] toArray(@Nonnull IntFunction<T[]> generator) {
    return set.toArray(generator);
  }

  @Override
  public boolean removeIf(@Nonnull Predicate<? super Identifiable> filter) {
    return set.removeIf(filter);
  }

  @Nonnull
  @Override
  public Stream<Identifiable> stream() {
    return set.stream();
  }

  @Nonnull
  @Override
  public Stream<Identifiable> parallelStream() {
    return set.parallelStream();
  }

  @Override
  public void forEach(Consumer<? super Identifiable> action) {
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
