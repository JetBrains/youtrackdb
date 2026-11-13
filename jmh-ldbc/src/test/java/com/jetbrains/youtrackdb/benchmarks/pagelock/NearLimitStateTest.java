package com.jetbrains.youtrackdb.benchmarks.pagelock;

/** A separate Surefire JVM keeps the near-limit lifecycle independent of record storage. */
public class NearLimitStateTest extends WriterStateTest {

  // The measured path returns no samples. Read-back outside it verifies every committed value.
  @Override
  protected void checkMeasuredPath() throws Exception {
    var settings = new WriterSettings(80, 20, 8, 17, 1);
    var root = folder.getRoot().toPath();
    var copy = NearLimitWorkload.prepare(root, 1, settings).freshCopy(root);
    try (var db =
        (com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
            .instance(copy.toString());
        var session = db.open(NearLimitWorkload.DATABASE, "admin", "admin")) {
      var writer = new NearLimitWorkload(session.getStorage(), 0, settings);
      writer.commitMeasured();
      var component = new PageWorkloadComponent(session.getStorage(), "near-limit-writer-0",
          16, true);
      component.open();
      var samples = component.read(java.util.stream.IntStream.range(0, 16).toArray()).pages();
      org.junit.Assert.assertEquals(16, samples.length);
      for (int i = 0; i < samples.length; i++) {
        org.junit.Assert.assertEquals(i, samples[i].pageIndex());
        org.junit.Assert.assertEquals(2, samples[i].value());
      }
      org.junit.Assert.assertEquals(void.class,
          NearLimitWorkload.class.getMethod("commitMeasured").getReturnType());
    } finally {
      SeedDatabase.deleteTree(copy);
    }
  }

  @Override
  protected String method() {
    return "nearLimit4";
  }
}
