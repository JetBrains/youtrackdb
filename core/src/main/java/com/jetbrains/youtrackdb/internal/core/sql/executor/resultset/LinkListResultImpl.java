package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkList;
import com.jetbrains.youtrackdb.api.record.Identifiable;
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

public class LinkListResultImpl implements LinkList {
  private final ArrayList<Identifiable> list;

  @SuppressWarnings("unused")
  public LinkListResultImpl() {
    this.list = new ArrayList<>();
  }

  @SuppressWarnings("unused")
  public LinkListResultImpl(ArrayList<Identifiable> list) {
    this.list = new ArrayList<>(list);
  }

  public LinkListResultImpl(int initialCapacity) {
    this.list = new ArrayList<>(initialCapacity);
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
  public Iterator<Identifiable> iterator() {
    return list.iterator();
  }

  @Nonnull
  @Override
  public Object[] toArray() {
    return list.toArray();
  }

  @Nonnull
  @Override
  public <T> T[] toArray(@Nonnull T[] a) {
    return list.toArray(a);
  }

  @Override
  public boolean add(Identifiable identifiable) {
    return list.add(identifiable);
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
  public boolean addAll(@Nonnull Collection<? extends Identifiable> c) {
    return list.addAll(c);
  }

  @Override
  public boolean addAll(int index, @Nonnull Collection<? extends Identifiable> c) {
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
  public Identifiable get(int index) {
    return list.get(index);
  }

  @Override
  public Identifiable set(int index, Identifiable element) {
    return list.set(index, element);
  }

  @Override
  public void add(int index, Identifiable element) {
    list.add(index, element);
  }

  @Override
  public Identifiable remove(int index) {
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
  public ListIterator<Identifiable> listIterator() {
    return list.listIterator();
  }

  @Nonnull
  @Override
  public ListIterator<Identifiable> listIterator(int index) {
    return list.listIterator(index);
  }

  @Nonnull
  @Override
  public List<Identifiable> subList(int fromIndex, int toIndex) {
    return list.subList(fromIndex, toIndex);
  }

  @Override
  public void replaceAll(@Nonnull UnaryOperator<Identifiable> operator) {
    list.replaceAll(operator);
  }

  @Override
  public void sort(@Nullable Comparator<? super Identifiable> c) {
    list.sort(c);
  }

  @Nonnull
  @Override
  public Spliterator<Identifiable> spliterator() {
    return list.spliterator();
  }

  @Override
  public void addFirst(Identifiable identifiable) {
    list.addFirst(identifiable);
  }

  @Override
  public void addLast(Identifiable identifiable) {
    list.addLast(identifiable);
  }

  @Override
  public Identifiable getFirst() {
    return list.getFirst();
  }

  @Override
  public Identifiable getLast() {
    return list.getLast();
  }

  @Override
  public Identifiable removeFirst() {
    return list.removeFirst();
  }

  @Override
  public Identifiable removeLast() {
    return list.removeLast();
  }

  @Override
  public List<Identifiable> reversed() {
    return list.reversed();
  }

  @Override
  public <T> T[] toArray(@Nonnull IntFunction<T[]> generator) {
    return list.toArray(generator);
  }

  @Override
  public boolean removeIf(@Nonnull Predicate<? super Identifiable> filter) {
    return list.removeIf(filter);
  }

  @Nonnull
  @Override
  public Stream<Identifiable> stream() {
    return list.stream();
  }

  @Nonnull
  @Override
  public Stream<Identifiable> parallelStream() {
    return list.parallelStream();
  }

  @Override
  public void forEach(Consumer<? super Identifiable> action) {
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
    if (!(obj instanceof List<?>)) {
      return false;
    }

    return list.equals(obj);
  }

  @Override
  public String toString() {
    return list.toString();
  }
}
