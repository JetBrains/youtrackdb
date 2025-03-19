package com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BTreeRidBagConcurrencySingleBasedRidBagTestIT {

  public static final String URL = "disk:target/testdb/BTreeRidBagConcurrencySingleBasedRidBagTestIT";
  private final AtomicInteger positionCounter = new AtomicInteger();
  private final ConcurrentSkipListSet<RID> ridTree = new ConcurrentSkipListSet<>();
  private final CountDownLatch latch = new CountDownLatch(1);
  private RID docContainerRid;
  private final ExecutorService threadExecutor = Executors.newCachedThreadPool();
  private volatile boolean cont = true;

  private int topThreshold;
  private int bottomThreshold;

  @Before
  public void beforeMethod() {
    topThreshold =
        GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(30);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(20);
  }

  @After
  public void afterMethod() {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);
  }

  @Test
  public void testConcurrency() throws Exception {
    DatabaseSessionInternal db = new DatabaseDocumentTx(URL);
    if (db.exists()) {
      db.open("admin", "admin");
      db.drop();
    }

    db.create();

    var document = ((EntityImpl) db.newEntity());
    var ridBag = new RidBag(db);

    document.setProperty("ridBag", ridBag);
    for (var i = 0; i < 100; i++) {
      final RID ridToAdd = new RecordId(0, positionCounter.incrementAndGet());
      ridBag.add(ridToAdd);
      ridTree.add(ridToAdd);
    }

    docContainerRid = document.getIdentity();

    List<Future<Void>> futures = new ArrayList<>();

    for (var i = 0; i < 5; i++) {
      futures.add(threadExecutor.submit(new RidAdder(i)));
    }

    for (var i = 0; i < 5; i++) {
      futures.add(threadExecutor.submit(new RidDeleter(i)));
    }

    latch.countDown();

    Thread.sleep(30 * 60000);
    cont = false;

    for (var future : futures) {
      future.get();
    }

    document = db.load(document.getIdentity());
    document.setLazyLoad(false);

    ridBag = document.getProperty("ridBag");

    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(ridTree.remove(identifiable.getIdentity()));
    }

    Assert.assertTrue(ridTree.isEmpty());

    System.out.println("Result size is " + ridBag.size());
    db.close();
  }

  public class RidAdder implements Callable<Void> {

    private final int id;

    public RidAdder(int id) {
      this.id = id;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      var addedRecords = 0;

      DatabaseSessionInternal db = new DatabaseDocumentTx(URL);
      try (db) {
        db.open("admin", "admin");
        while (cont) {
          List<RID> ridsToAdd = new ArrayList<>();
          for (var i = 0; i < 10; i++) {
            ridsToAdd.add(new RecordId(0, positionCounter.incrementAndGet()));
          }

          while (true) {
            EntityImpl document = db.load(docContainerRid);
            document.setLazyLoad(false);

            RidBag ridBag = document.getProperty("ridBag");
            for (var rid : ridsToAdd) {
              ridBag.add(rid);
            }

            try {

            } catch (ConcurrentModificationException e) {
              continue;
            }

            break;
          }

          ridTree.addAll(ridsToAdd);
          addedRecords += ridsToAdd.size();
        }
      }

      System.out.println(
          RidAdder.class.getSimpleName() + ":" + id + "-" + addedRecords + " were added.");
      return null;
    }
  }

  public class RidDeleter implements Callable<Void> {

    private final int id;

    public RidDeleter(int id) {
      this.id = id;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      var deletedRecords = 0;

      var rnd = new Random();
      DatabaseSessionInternal db = new DatabaseDocumentTx(URL);
      try (db) {
        db.open("admin", "admin");
        while (cont) {
          while (true) {
            EntityImpl document = db.load(docContainerRid);
            document.setLazyLoad(false);
            RidBag ridBag = document.getProperty("ridBag");
            var iterator = ridBag.iterator();

            List<RID> ridsToDelete = new ArrayList<>();
            var counter = 0;
            while (iterator.hasNext()) {
              Identifiable identifiable = iterator.next();
              if (rnd.nextBoolean()) {
                iterator.remove();
                counter++;
                ridsToDelete.add(identifiable.getIdentity());
              }

              if (counter >= 5) {
                break;
              }
            }

            try {

            } catch (ConcurrentModificationException e) {
              continue;
            }

            ridsToDelete.forEach(ridTree::remove);
            deletedRecords += ridsToDelete.size();
            break;
          }
        }
      }

      System.out.println(
          RidDeleter.class.getSimpleName() + ":" + id + "-" + deletedRecords + " were deleted.");
      return null;
    }
  }
}
