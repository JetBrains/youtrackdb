package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

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

  /**
   * Detaches the currently linked chain and returns it for the caller to unpark; the captured
   * tail stays behind as the list's sole remaining element and is represented in the returned
   * chain by a fresh terminal copy (the {@code head == tail} single-element case likewise
   * returns a copy and leaves the node in place — a benign duplicate unpark at worst, since a
   * waiter always enqueues a fresh node before it parks).
   *
   * <p>The method is {@code synchronized} because the cut protocol is only sound for ONE cutter
   * at a time: the capture below reads {@code tail} BEFORE {@code head}, so a second concurrent
   * cutter that completed a full cut (plus at least one enqueue) between those two reads would
   * hand this thread a cross-generation pair whose head lies at or past the captured tail; the
   * head CAS would still succeed (the head value itself is current — after the first-ever
   * enqueue, cutters are the only head mutators), the list head would swing backwards onto a
   * detached node, and the traversal would chase a tail that is behind it — blocking forever on
   * the link latch of a node that never receives a successor (or of a detached tail copy, whose
   * latch is never counted down at all). Historically the single release-side cutter upheld the
   * invariant implicitly; the freezer gate's operator-arm cut added a second concurrent caller,
   * so the monitor now enforces it structurally ({@code OperationsFreezerLivenessTest} is the
   * driven reproducer of the unserialized two-cutter wedge). Concurrent ENQUEUES remain safe and
   * lock-free (the link latches exist exactly for them), so the latch waits inside the monitor
   * are bounded by an in-flight enqueuer's two plain stores — no enqueuer ever takes this
   * monitor, and the monitor is a leaf lock (nothing else is acquired while it is held).
   */
  @Nullable public synchronized WaitingListNode cutWaitingList() {
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
