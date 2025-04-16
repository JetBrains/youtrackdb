package com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.LinksConsistencyException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BTreeLinkBagConcurrencySingleBasedLinkBagTestIT {

  private final ConcurrentSkipListSet<RID> ridTree = new ConcurrentSkipListSet<>();
  private final CountDownLatch latch = new CountDownLatch(1);

  private RID entityContainerRid;
  private final ExecutorService threadExecutor = Executors.newCachedThreadPool();

  private volatile boolean cont = true;

  private int topThreshold;
  private int bottomThreshold;

  private YouTrackDB youTrackDB;

  @Before
  public void beforeMethod() {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(30);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(20);

    youTrackDB = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(
        BTreeRidBagConcurrencySingleBasedRidBagTestIT.class));

    if (youTrackDB.exists(BTreeRidBagConcurrencySingleBasedRidBagTestIT.class.getSimpleName())) {
      youTrackDB.drop(BTreeRidBagConcurrencySingleBasedRidBagTestIT.class.getSimpleName());
    }

    youTrackDB.create(
        BTreeRidBagConcurrencySingleBasedRidBagTestIT.class.getSimpleName(),
        DatabaseType.DISK, "admin", "admin", "admin");
  }

  @After
  public void afterMethod() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);

    youTrackDB.close();
  }

  @Test
  public void testConcurrency() throws Exception {
    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(
        BTreeRidBagConcurrencySingleBasedRidBagTestIT.class.getSimpleName(), "admin", "admin")) {
      session.executeInTx(transaction -> {
        var entity = session.newEntity();
        var ridBag = new RidBag(session);
        entity.setProperty("ridBag", ridBag);

        for (var i = 0; i < 100; i++) {
          final var ridToAdd = session.newEntity().getIdentity();
          ridBag.add(ridToAdd);
          ridTree.add(ridToAdd);
        }

        entityContainerRid = entity.getIdentity();
      });

      List<Future<Void>> futures = new ArrayList<>();

      try (var pool = youTrackDB.cachedPool(
          BTreeRidBagConcurrencySingleBasedRidBagTestIT.class.getSimpleName(), "admin", "admin")) {
        for (var i = 0; i < 5; i++) {
          futures.add(threadExecutor.submit(new RidAdder(i, pool)));
        }

        for (var i = 0; i < 5; i++) {
          futures.add(threadExecutor.submit(new RidDeleter(i, pool)));
        }

        latch.countDown();

        Thread.sleep(60000);
        cont = false;

        for (var future : futures) {
          future.get();
        }
      }

      session.executeInTx(transaction -> {
        var entity = session.loadEntity(entityContainerRid);
        RidBag ridBag = entity.getProperty("ridBag");

        for (Identifiable identifiable : ridBag) {
          Assert.assertTrue(ridTree.remove(identifiable.getIdentity()));
        }

        Assert.assertTrue(ridTree.isEmpty());

        System.out.println("Result size is " + ridBag.size());
      });
    }
  }

  public class RidAdder implements Callable<Void> {

    private final int id;
    private final SessionPool pool;

    public RidAdder(int id, SessionPool pool) {
      this.id = id;
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      var addedRecords = 0;

      try (var db = pool.acquire()) {
        while (cont) {
          List<RID> ridsToAdd = new ArrayList<>();

          db.executeInTx(transaction -> {
            for (var i = 0; i < 10; i++) {
              ridsToAdd.add(transaction.newEntity().getIdentity());
            }
          });

          while (true) {
            try {
              db.executeInTx(transaction -> {
                var entity = transaction.loadEntity(entityContainerRid);
                LinkBag ridBag = entity.getProperty("ridBag");

                for (var rid : ridsToAdd) {
                  ridBag.add(rid);
                }
              });
            } catch (ConcurrentModificationException | LinksConsistencyException e) {
              continue;
            }

            break;
          }

          ridTree.addAll(ridsToAdd);
          addedRecords += ridsToAdd.size();
        }
      }

      System.out.println(
          RidAdder.class.getSimpleName() + ":" + id + " : " + addedRecords + " were added.");
      return null;
    }
  }

  public class RidDeleter implements Callable<Void> {

    private final int id;
    private final SessionPool pool;

    public RidDeleter(int id, SessionPool pool) {
      this.id = id;
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {
      latch.await();

      var deletedRecords = 0;

      var rnd = new Random();
      try (var db = pool.acquire()) {
        while (cont) {
          while (true) {
            try {
              var deletedRids = db.computeInTx(transaction -> {
                var entity = transaction.loadEntity(entityContainerRid);
                LinkBag linkBag = entity.getProperty("ridBag");
                var iterator = linkBag.iterator();

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

                return ridsToDelete;
              });

              deletedRids.forEach(ridTree::remove);
              deletedRecords += deletedRids.size();
              break;
            } catch (ConcurrentModificationException | LinksConsistencyException e) {
              //retry
            }
          }
        }
      }

      System.out.println(
          RidDeleter.class.getSimpleName() + ":" + id + " : " + deletedRecords + " were deleted.");
      return null;
    }
  }
}
