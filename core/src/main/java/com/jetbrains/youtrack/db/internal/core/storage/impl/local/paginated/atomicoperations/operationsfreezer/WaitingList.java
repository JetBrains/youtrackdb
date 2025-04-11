package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

final class WaitingList {

  private final AtomicReference<WaitingListNode> head = new AtomicReference<>();
  private final AtomicReference<WaitingListNode> tail = new AtomicReference<>();

  public void addThreadInWaitingList(final Thread thread) {
    final var node = new WaitingListNode(thread);

    while (true) {
      final var last = tail.get();

      if (tail.compareAndSet(last, node)) {
        if (last == null) {
          head.set(node);
        } else {
          last.next = node;
          last.linkLatch.countDown();
        }

        break;
      }
    }
  }

  @Nullable
  public WaitingListNode cutWaitingList() {
    while (true) {
      final var tail = this.tail.get();
      final var head = this.head.get();

      if (tail == null) {
        return null;
      }

      // head is null but tail is not null we are in the middle of addition of item in the list
      if (head == null) {
        // let other thread to make it's work
        Thread.yield();
        continue;
      }

      if (head == tail) {
        return new WaitingListNode(head.item);
      }

      if (this.head.compareAndSet(head, tail)) {
        var node = head;

        node.waitTillAllLinksWillBeCreated();

        while (node.next != tail) {
          node = node.next;

          node.waitTillAllLinksWillBeCreated();
        }

        node.next = new WaitingListNode(tail.item);

        return head;
      }
    }
  }
}
