package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ThreadInterruptedException;
import java.util.concurrent.CountDownLatch;

final class WaitingListNode {

  /**
   * Latch which indicates that all links are created between add and existing list elements.
   */
  final CountDownLatch linkLatch = new CountDownLatch(1);

  final Thread item;
  volatile WaitingListNode next;

  WaitingListNode(Thread item) {
    this.item = item;
  }

  void waitTillAllLinksWillBeCreated() {
    try {
      linkLatch.await();
    } catch (java.lang.InterruptedException e) {
      throw BaseException.wrapException(
          new ThreadInterruptedException(
              "Thread was interrupted while was waiting for completion of 'waiting linked list'"
                  + " operation"),
          e, (String) null);
    }
  }
}
