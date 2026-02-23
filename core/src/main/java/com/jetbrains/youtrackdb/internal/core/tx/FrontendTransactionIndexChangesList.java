package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class FrontendTransactionIndexChangesList
    implements List<TransactionIndexEntry> {

  class Node {

    Node next;
    Node prev;
    TransactionIndexEntry entry;

    public void remove() {
      // remove the element from the linked list
      if (prev == null) {
        if (next == null) {
          first = null;
          last = null;
        } else {
          next.prev = null;
          first = next;
        }
      } else {
        if (next == null) {
          prev.next = null;
          last = prev;
        } else {
          prev.next = next;
          next.prev = prev;
        }
      }

      // update the map
      var rid = entry.getValue() == null ? null : entry.getValue().getIdentity();
      var ridList = ridToNodes.get(rid);
      var iter = ridList.iterator();
      while (iter.hasNext()) {
        if (iter.next() == this) {
          iter.remove();
          break;
        }
      }

      // update size
      size--;
    }

    public void onRidChange(RID oldRid, RID newRid) {
      ridToNodes.get(oldRid).remove(this);
      var newMapList = ridToNodes.get(newRid);
      if (newMapList == null) {
        newMapList = new ArrayList<>();
        ridToNodes.put(newRid, newMapList);
      }
      newMapList.add(this);
    }
  }

  private Node first;
  private Node last;
  private int size = 0;
  Map<RID, List<Node>> ridToNodes = new HashMap<>();

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public boolean contains(Object o) {
    if (!(o instanceof TransactionIndexEntry entry)) {
      return false;
    }

    var record = entry.getValue();
    var rid = record == null ? null : record.getIdentity();
    var items = ridToNodes.get(rid);
    if (items == null) {
      return false;
    }
    for (var item : items) {
      if (item.entry.equals(o)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Iterator<TransactionIndexEntry> iterator() {
    return new Iterator<TransactionIndexEntry>() {
      Node nextItem = first;
      Node lastReturned = null;

      @Override
      public boolean hasNext() {
        return nextItem != null;
      }

      @Override
      public TransactionIndexEntry next() {
        if (nextItem == null) {
          throw new IllegalStateException();
        }
        lastReturned = nextItem;
        var result = nextItem.entry;
        nextItem = nextItem.next;
        return result;
      }

      @Override
      public void remove() {
        if (lastReturned == null) {
          throw new IllegalStateException();
        }
        lastReturned.remove();
        lastReturned = null;
      }
    };
  }

  @Override
  public Object[] toArray() {
    var result = new Object[size];
    var iterator = this.iterator();
    for (var i = 0; i < size; i++) {
      try {
        result[i] = iterator.next();
      } catch (IllegalStateException x) {
        throw new ConcurrentModificationException();
      }
    }
    return result;
  }

  @Override
  public <T> T[] toArray(T[] a) {
    var result = a;
    if (a.length < size) {
      result = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
    }
    var it = iterator();
    var i = 0;
    while (it.hasNext()) {
      result[i++] = (T) it.next();
    }

    return result;
  }

  @Override
  public boolean add(TransactionIndexEntry item) {
    if (item == null) {
      throw new NullPointerException();
    }
    var node = new Node();
    node.entry = item;
    node.next = null;

    // update the linked list
    var previousLast = last;
    last = node;
    if (previousLast == null) {
      first = node;
      node.prev = null;
    } else {
      previousLast.next = node;
      node.prev = previousLast;
    }

    // update the map
    var nodeId = item.getValue() == null ? null : item.getValue().getIdentity();
    var mapList = ridToNodes.get(nodeId);
    if (mapList == null) {
      mapList = new ArrayList<>();
      ridToNodes.put(nodeId, mapList);
    }
    mapList.add(node);
    size++;
    return true;
  }

  @Override
  public boolean remove(Object o) {
    if (!(o instanceof TransactionIndexEntry item)) {
      return false;
    }

    var rid = item.getValue() == null ? null : item.getValue().getIdentity();
    var list = ridToNodes.get(rid);
    for (var node : list) {
      if (node.entry.equals(item)) {
        node.remove();
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    if (c == null) {
      return false;
    }
    for (var o : c) {
      if (!contains(o)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean addAll(
      Collection<? extends TransactionIndexEntry> c) {
    var result = false;

    for (var item : c) {
      result = result || add(item);
    }
    return result;
  }

  @Override
  public boolean addAll(
      int index, Collection<? extends TransactionIndexEntry> c) {

    for (var item : c) {
      add(index++, item);
    }
    return true;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    var result = false;
    for (var o : c) {
      result = result || remove(o);
    }
    return result;
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    var next = first;
    var result = false;
    while (next != null) {
      var current = next;
      next = current.next;
      if (!c.contains(current.entry)) {
        current.remove();
        result = true;
      }
    }
    return result;
  }

  @Override
  public void clear() {
    this.size = 0;
    this.first = null;
    this.last = null;
    this.ridToNodes.clear();
  }

  @Nullable
  @Override
  public TransactionIndexEntry get(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException();
    }
    var item = first;
    for (var i = 0; i < index; i++) {
      if (item.next == null) {
        return null;
      }
      item = item.next;
    }
    return item.entry;
  }

  @Override
  public TransactionIndexEntry set(
      int index, TransactionIndexEntry element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(int index, TransactionIndexEntry element) {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public TransactionIndexEntry remove(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException();
    }
    var item = first;
    for (var i = 0; i < index; i++) {
      if (item.next == null) {
        return null;
      }
      item = item.next;
    }

    var result = item.entry;
    item.remove();
    return result;
  }

  @Override
  public int indexOf(Object o) {
    var item = first;
    for (var i = 0; i < size; i++) {
      if (item.entry.equals(o)) {
        return i;
      }
      item = item.next;
    }

    return -1;
  }

  @Override
  public int lastIndexOf(Object o) {
    var item = last;
    for (var i = size - 1; i >= 0; i--) {
      if (item.entry.equals(o)) {
        return i;
      }
      item = item.prev;
    }

    return -1;
  }

  @Override
  public ListIterator<TransactionIndexEntry> listIterator() {
    return new ListIterator<TransactionIndexEntry>() {

      Node nextItem = first;
      int nextIndex = 0;

      @Override
      public boolean hasNext() {
        return nextItem != null;
      }

      @Override
      public TransactionIndexEntry next() {
        if (nextItem == null) {
          throw new IllegalStateException();
        }
        var result = nextItem.entry;
        nextItem = nextItem.next;
        nextIndex++;
        return result;
      }

      @Override
      public boolean hasPrevious() {
        return nextItem.prev != null;
      }

      @Override
      public TransactionIndexEntry previous() {
        if (!hasPrevious()) {
          throw new IllegalStateException();
        }
        var result = nextItem.prev.entry;
        nextItem = nextItem.prev;
        nextIndex--;
        return result;
      }

      @Override
      public int nextIndex() {
        return nextIndex;
      }

      @Override
      public int previousIndex() {
        return nextIndex - 1;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void set(
          TransactionIndexEntry transactionIndexEntry) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void add(
          TransactionIndexEntry transactionIndexEntry) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  public ListIterator<TransactionIndexEntry> listIterator(
      int index) {
    // TODO implement this
    throw new UnsupportedOperationException();
  }

  @Override
  public List<TransactionIndexEntry> subList(
      int fromIndex, int toIndex) {
    // TODO implement this
    throw new UnsupportedOperationException();
  }

  public Optional<Node> getFirstNode(RID rid, FrontendTransactionIndexChanges.OPERATION op) {
    var list = ridToNodes.get(rid);
    if (list != null) {
      return list.stream().filter(x -> x.entry.getOperation() == op).findFirst();
    }
    return Optional.empty();
  }

  public Optional<Node> getNode(TransactionIndexEntry entry) {
    var rid = entry.getValue() == null ? null : entry.getValue().getIdentity();
    var list = ridToNodes.get(rid);
    if (list != null) {
      return list.stream().filter(x -> x.entry == entry).findFirst();
    }
    return Optional.empty();
  }
}
