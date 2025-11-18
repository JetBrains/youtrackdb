package com.jetbrains.youtrackdb.internal.core.storage.ridbag.sbtree;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.LinksConsistencyException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawTriple;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BTreeLinkBagConcurrencySingleBasedLinkBagTestIT {

  private final Set<RID> ridSet = ConcurrentHashMap.newKeySet();
  private final CountDownLatch latch = new CountDownLatch(1);

  private RID entityContainerRid;
  private final ExecutorService threadExecutor = Executors.newCachedThreadPool();

  private volatile boolean cont = true;

  private int topThreshold;
  private int bottomThreshold;

  private YouTrackDBImpl youTrackDB;

  @Before
  public void beforeMethod() {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(30);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(20);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(
        BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class));

    if (youTrackDB.exists(BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class.getSimpleName())) {
      youTrackDB.drop(BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class.getSimpleName());
    }

    youTrackDB.create(
        BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class.getSimpleName(),
        DatabaseType.DISK, "admin", "admin", "admin");
  }

  @After
  public void afterMethod() {
    youTrackDB.drop(BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class.getSimpleName());

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);

    youTrackDB.close();
  }

  @Test
  public void testConcurrency() throws Exception {
    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(
        BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class.getSimpleName(), "admin", "admin")) {
      var addedRids = session.computeInTx(transaction -> {
        var entity = session.newEntity();
        var linkBag = new LinkBag(session);
        entity.setProperty("linkBag", linkBag);

        var rids = new ArrayList<RID>();
        for (var i = 0; i < 100; i++) {
          final var ridToAdd = session.newEntity().getIdentity();
          linkBag.add(ridToAdd);
          rids.add(ridToAdd);
        }

        entityContainerRid = entity.getIdentity();
        return rids;
      });

      ridSet.addAll(addedRids);

      List<Future<Void>> addFutures = new ArrayList<>();
      List<Future<HashSet<RID>>> remoteFutures = new ArrayList<>();
      var postDeletedCounter = 0;

      try (var pool = youTrackDB.cachedPool(
          BTreeLinkBagConcurrencySingleBasedLinkBagTestIT.class.getSimpleName(), "admin",
          "admin")) {
        for (var i = 0; i < 5; i++) {
          addFutures.add(threadExecutor.submit(new RidAdder(i, pool)));
        }

        for (var i = 0; i < 5; i++) {
          remoteFutures.add(threadExecutor.submit(new RidDeleter(i, pool)));
        }

        latch.countDown();

        Thread.sleep(30 * 60_000);
        cont = false;

        for (var future : addFutures) {
          future.get();
        }

        for (var future : remoteFutures) {
          var ridsToDelete = future.get();

          for (var rid : ridsToDelete) {
            Assert.assertTrue(ridSet.remove(rid));
            postDeletedCounter++;
          }
        }
      }

      System.out.println("Post delete counter: " + postDeletedCounter);

      session.executeInTx(transaction -> {
        var entity = session.loadEntity(entityContainerRid);
        LinkBag linkBag = entity.getProperty("linkBag");

        for (Identifiable identifiable : linkBag) {
          Assert.assertTrue(ridSet.remove(identifiable.getIdentity()));
        }

        Assert.assertTrue(ridSet.isEmpty());

        System.out.println("Result size is " + linkBag.size());
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
      try {
        latch.await();

        var addedRecords = 0;

        try (var db = pool.acquire()) {
          while (cont) {
            List<RID> ridsToAdd = new ArrayList<>();

            if (ridSet.size() > 100_000) {
              Thread.yield();
              continue;
            }

            db.executeInTx(transaction -> {
              for (var i = 0; i < 10; i++) {
                ridsToAdd.add(transaction.newEntity().getIdentity());
              }
            });

            while (true) {
              try {
                db.executeInTx(transaction -> {
                  var entity = transaction.loadEntity(entityContainerRid);
                  LinkBag linkBag = entity.getProperty("linkBag");

                  for (var rid : ridsToAdd) {
                    linkBag.add(rid);
                  }
                });
              } catch (ConcurrentModificationException | LinksConsistencyException e) {
                continue;
              }

              break;
            }

            ridSet.addAll(ridsToAdd);
            addedRecords += ridsToAdd.size();
          }
        }

        System.out.println(
            RidAdder.class.getSimpleName() + ":" + id + " : " + addedRecords + " were added.");
        return null;
      } catch (Throwable e) {
        e.printStackTrace();
        throw e;
      }

    }
  }

  public class RidDeleter implements Callable<HashSet<RID>> {
    private final int id;
    private final SessionPool pool;

    public RidDeleter(int id, SessionPool pool) {
      this.id = id;
      this.pool = pool;
    }

    @Override
    public HashSet<RID> call() throws Exception {
      try {
        latch.await();

        var deletedRecords = 0;

        var ridsToDeleteFromSet = new HashSet<RID>();
        var rnd = new Random();
        try (var db = pool.acquire()) {
          while (cont) {

            if (ridSet.size() < 10) {
              Thread.yield();
              continue;
            }

            while (true) {
              try {
                var triple = db.computeInTx(transaction -> {
                  var entity = transaction.loadEntity(entityContainerRid);
                  LinkBag linkBag = entity.getProperty("linkBag");
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

                  assert ridsToDelete.isEmpty() || entity.isDirty();
                  return RawTriple.of(ridsToDelete, entity.getVersion(), entity);
                });

                var deletedRids = triple.first();
                if (!deletedRids.isEmpty()) {
                  var entityVersion = triple.second();
                  var entity = triple.third();
                  assert entity.getVersion() == entityVersion + 1;
                }

                for (var rid : deletedRids) {
                  ridsToDeleteFromSet.remove(rid);

                  if (!ridSet.remove(rid)) {
                    ridsToDeleteFromSet.add(rid);
                  } else {
                    deletedRecords++;
                  }
                }

                var iter = ridsToDeleteFromSet.iterator();
                while (iter.hasNext()) {
                  var rid = iter.next();

                  if (ridSet.remove(rid)) {
                    iter.remove();
                    deletedRecords++;
                  }
                }

                break;
              } catch (ConcurrentModificationException | LinksConsistencyException |
                       RecordNotFoundException e) {
                //retry
              }
            }
          }
        }

        System.out.println(
            RidDeleter.class.getSimpleName() + ":" + id + " : " + deletedRecords
                + " were deleted.");
        return ridsToDeleteFromSet;
      } catch (Throwable e) {
        e.printStackTrace();
        throw e;
      }
    }
  }
}
