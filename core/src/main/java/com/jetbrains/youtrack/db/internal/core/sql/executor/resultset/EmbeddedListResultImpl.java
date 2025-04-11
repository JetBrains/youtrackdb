package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EmbeddedListResultImpl<T> implements EmbeddedList<T> {
  private final ArrayList<T> list;

  public EmbeddedListResultImpl() {
    this.list = new ArrayList<>();
  }

  public EmbeddedListResultImpl(int initialCapacity) {
    this.list = new ArrayList<>(initialCapacity);
  }

  @SuppressWarnings("unused")
  public EmbeddedListResultImpl(@Nonnull Collection<? extends T> c) {
    this.list = new ArrayList<>(c);
  }

  @Override
  public int size() {
    return list.size();
  }

  @Override
  public boolean isEmpty() {
    return list.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return list.contains(o);
  }

  @Nonnull
  @Override
  public Iterator<T> iterator() {
    return list.iterator();
  }

  @Nonnull
  @Override
  public Object[] toArray() {
    return list.toArray();
  }

  @Nonnull
  @Override
  public <T1> T1[] toArray(@Nonnull T1[] a) {
    return list.toArray(a);
  }

  @Override
  public boolean add(T t) {
    return list.add(t);
  }

  @Override
  public boolean remove(Object o) {
    return list.remove(o);
  }

  @Override
  public boolean containsAll(@Nonnull Collection<?> c) {
    return list.containsAll(c);
  }

  @Override
  public boolean addAll(@Nonnull Collection<? extends T> c) {
    return list.addAll(c);
  }

  @Override
  public boolean addAll(int index, @Nonnull Collection<? extends T> c) {
    return list.addAll(index, c);
  }

  @Override
  public boolean removeAll(@Nonnull Collection<?> c) {
    return list.removeAll(c);
  }

  @Override
  public boolean retainAll(@Nonnull Collection<?> c) {
    return list.retainAll(c);
  }

  @Override
  public void clear() {
    list.clear();
  }

  @Override
  public T get(int index) {
    return list.get(index);
  }

  @Override
  public T set(int index, T element) {
    return list.set(index, element);
  }

  @Override
  public void add(int index, T element) {
    list.add(index, element);
  }

  @Override
  public T remove(int index) {
    return list.remove(index);
  }

  @Override
  public int indexOf(Object o) {
    return list.indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return list.lastIndexOf(o);
  }

  @Nonnull
  @Override
  public ListIterator<T> listIterator() {
    return list.listIterator();
  }

  @Nonnull
  @Override
  public ListIterator<T> listIterator(int index) {
    return list.listIterator(index);
  }

  @Nonnull
  @Override
  public List<T> subList(int fromIndex, int toIndex) {
    return list.subList(fromIndex, toIndex);
  }

  @Override
  public void replaceAll(@Nonnull UnaryOperator<T> operator) {
    list.replaceAll(operator);
  }

  @Override
  public void sort(@Nullable Comparator<? super T> c) {
    list.sort(c);
  }

  @Nonnull
  @Override
  public Spliterator<T> spliterator() {
    return list.spliterator();
  }

  @Override
  public void addFirst(T t) {
    list.addFirst(t);
  }

  @Override
  public void addLast(T t) {
    list.addLast(t);
  }

  @Override
  public T getFirst() {
    return list.getFirst();
  }

  @Override
  public T getLast() {
    return list.getLast();
  }

  @Override
  public T removeFirst() {
    return list.removeFirst();
  }

  @Override
  public T removeLast() {
    return list.removeLast();
  }

  @Override
  public List<T> reversed() {
    return list.reversed();
  }

  @Override
  public <T1> T1[] toArray(@Nonnull IntFunction<T1[]> generator) {
    return list.toArray(generator);
  }

  @Override
  public boolean removeIf(@Nonnull Predicate<? super T> filter) {
    return list.removeIf(filter);
  }

  @Nonnull
  @Override
  public Stream<T> stream() {
    return list.stream();
  }

  @Nonnull
  @Override
  public Stream<T> parallelStream() {
    return list.parallelStream();
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    list.forEach(action);
  }

  @Override
  public int hashCode() {
    return list.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof List)) {
      return false;
    }

    return list.equals(obj);
  }

  @Override
  public String toString() {
    return list.toString();
  }
}
