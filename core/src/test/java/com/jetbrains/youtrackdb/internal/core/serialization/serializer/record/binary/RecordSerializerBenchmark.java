package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmark comparing V1 vs V2 serialization performance at different property counts. Measures
 * serialize, full deserialize, and partial deserialize (single field) throughput.
 *
 * <p>Property count scenarios:
 *
 * <ul>
 *   <li>5 — V2 linear mode, baseline comparison against V1
 *   <li>20 — V2 cuckoo mode, moderate entity
 *   <li>50 — V2 cuckoo mode, large entity
 * </ul>
 *
 * <p>Run: {@code java -jar core/target/benchmarks.jar RecordSerializerBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class RecordSerializerBenchmark {

  private static final String DB_NAME = "serializerBench";

  @Param({"5", "20", "50"})
  private int propertyCount;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  private final RecordSerializerBinaryV1 v1 = new RecordSerializerBinaryV1();
  private final RecordSerializerBinaryV2 v2 = new RecordSerializerBinaryV2();

  // Pre-built entity for serialization benchmarks
  private EntityImpl sourceEntity;

  // Pre-serialized bytes for deserialization benchmarks
  private byte[] v1Bytes;
  private byte[] v2Bytes;

  // Field name for partial deserialization (target the last field to exercise worst-case V1 scan)
  private String partialFieldName;

  @Setup(Level.Trial)
  public void setUp() {
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(RecordSerializerBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(
        DB_NAME,
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", "adminpwd");

    // Build the source entity with mixed property types
    session.begin();
    sourceEntity = (EntityImpl) session.newEntity();
    for (int i = 0; i < propertyCount; i++) {
      switch (i % 4) {
        case 0 -> sourceEntity.setString("prop_" + i, "value_" + i);
        case 1 -> sourceEntity.setInt("prop_" + i, i * 100);
        case 2 -> sourceEntity.setDouble("prop_" + i, i * 1.5);
        case 3 -> sourceEntity.setBoolean("prop_" + i, i % 2 == 0);
      }
    }

    // Target the last property for partial deserialization — worst case for V1 linear scan
    partialFieldName = "prop_" + (propertyCount - 1);

    // Pre-serialize for deserialization benchmarks
    var v1Container = new BytesContainer();
    v1.serialize(session, sourceEntity, v1Container);
    v1Bytes = v1Container.fitBytes();

    var v2Container = new BytesContainer();
    v2.serialize(session, sourceEntity, v2Container);
    v2Bytes = v2Container.fitBytes();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (session != null && !session.isClosed()) {
      session.rollback();
      session.close();
    }
    if (youTrackDB != null) {
      youTrackDB.drop(DB_NAME);
      youTrackDB.close();
    }
  }

  // --- Serialization ---

  @Benchmark
  public void serializeV1(Blackhole bh) {
    var bytes = new BytesContainer();
    v1.serialize(session, sourceEntity, bytes);
    bh.consume(bytes);
  }

  @Benchmark
  public void serializeV2(Blackhole bh) {
    var bytes = new BytesContainer();
    v2.serialize(session, sourceEntity, bytes);
    bh.consume(bytes);
  }

  // --- Full deserialization ---

  @Benchmark
  public void deserializeFullV1(Blackhole bh) {
    var entity = (EntityImpl) session.newEntity();
    v1.deserialize(session, entity, new BytesContainer(v1Bytes));
    bh.consume(entity);
  }

  @Benchmark
  public void deserializeFullV2(Blackhole bh) {
    var entity = (EntityImpl) session.newEntity();
    v2.deserialize(session, entity, new BytesContainer(v2Bytes));
    bh.consume(entity);
  }

  // --- Partial deserialization (single field) ---

  @Benchmark
  public void deserializePartialV1(Blackhole bh) {
    var entity = (EntityImpl) session.newEntity();
    v1.deserializePartial(
        session, entity, new BytesContainer(v1Bytes), new String[] {partialFieldName});
    bh.consume(entity);
  }

  @Benchmark
  public void deserializePartialV2(Blackhole bh) {
    var entity = (EntityImpl) session.newEntity();
    v2.deserializePartial(
        session, entity, new BytesContainer(v2Bytes), new String[] {partialFieldName});
    bh.consume(entity);
  }

  public static void main(String[] args) throws Exception {
    var opt =
        new OptionsBuilder()
            .include("RecordSerializerBenchmark.*")
            .build();
    new Runner(opt).run();
  }
}
