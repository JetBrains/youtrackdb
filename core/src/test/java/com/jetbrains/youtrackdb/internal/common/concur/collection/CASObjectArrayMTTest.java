package com.jetbrains.youtrackdb.internal.common.concur.collection;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.ConcurrencyTest;

@ExtendWith(FrayTestExtension.class)
public class CASObjectArrayMTTest {

  public void addShouldReturnCorrectIndex() throws InterruptedException {
    var account = new CASObjectArray<Integer>();
    var t1 = new Thread(() -> {
      Integer value = 500;
      var idx = account.add(value);
      assert (account.get(idx).equals(value));
    });
    var t2 = new Thread(() -> {
      Integer value = 500;
      var idx = account.add(value);
      assert (account.get(idx).equals(value));
    });
    t1.start();
    t2.start();
    t1.join();
    t2.join();
  }


  @ConcurrencyTest(
      iterations = 1000
  )
  public void runTestUsingFray() throws InterruptedException {
    addShouldReturnCorrectIndex();
  }
}
