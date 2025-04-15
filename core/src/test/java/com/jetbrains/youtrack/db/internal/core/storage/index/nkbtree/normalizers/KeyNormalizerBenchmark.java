package com.jetbrains.youtrack.db.internal.core.storage.index.nkbtree.normalizers;

import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Collator;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.StackProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Measurement(iterations = 1, batchSize = 1)
@Warmup(iterations = 1, batchSize = 1)
@Fork(1)
public class KeyNormalizerBenchmark {

  private KeyNormalizer keyNormalizer;

  private CompositeKey binaryCompositeKey;
  private PropertyTypeInternal[] binaryTypes;

  private CompositeKey dateCompositeKey;
  private PropertyTypeInternal[] dateTypes;

  private CompositeKey dateTimeCompositeKey;
  private PropertyTypeInternal[] dateTimeTypes;

  public static void main(String[] args) throws RunnerException {
    final var opt =
        new OptionsBuilder()
            .include("KeyNormalizerBenchmark.*")
            .addProfiler(StackProfiler.class, "detailLine=true;excludePackages=true;period=1")
            .jvmArgs("-server", "-XX:+UseConcMarkSweepGC", "-Xmx4G", "-Xms1G")
            // .result("target" + "/" + "results.csv")
            // .param("offHeapMessages", "true""
            // .resultFormat(ResultFormatType.CSV)
            .build();
    new Runner(opt).run();
  }

  @Setup(Level.Iteration)
  public void setup() {
    binaryFixture();
    dateFixture();
    dateTimeFixture();
  }

  private void binaryFixture() {
    keyNormalizer = new KeyNormalizer();
    final var binaryKey = new byte[]{1, 2, 3, 4, 5, 6};
    binaryCompositeKey = new CompositeKey();
    binaryCompositeKey.addKey(binaryKey);
    binaryTypes = new PropertyTypeInternal[1];
    binaryTypes[0] = PropertyTypeInternal.BINARY;
  }

  private void dateFixture() {
    final var key = new GregorianCalendar(2013, Calendar.NOVEMBER, 5).getTime();
    dateCompositeKey = new CompositeKey();
    dateCompositeKey.addKey(key);
    dateTypes = new PropertyTypeInternal[1];
    dateTypes[0] = PropertyTypeInternal.DATE;
  }

  private void dateTimeFixture() {
    final var ldt = LocalDateTime.of(2013, 11, 5, 3, 3, 3);
    final var key = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    dateTimeCompositeKey = new CompositeKey();
    dateTimeCompositeKey.addKey(key);
    dateTimeTypes = new PropertyTypeInternal[1];
    dateTimeTypes[0] = PropertyTypeInternal.DATETIME;
  }

  // final ByteArrayOutputStream bos = new ByteArrayOutputStream();

  @Benchmark
  public void normalizeCompositeNull() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(null);

    final var types = new PropertyTypeInternal[1];
    types[0] = null;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeAddKeyNull() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(null);
  }

  @Benchmark
  public void normalizeCompositeNullInt() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(null);
    compositeKey.addKey(5);

    final var types = new PropertyTypeInternal[2];
    types[0] = null;
    types[1] = PropertyTypeInternal.INTEGER;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeInt() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(5);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.INTEGER;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeFloat() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(1.5f);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.FLOAT;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeDouble() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(1.5d);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.DOUBLE;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeBoolean() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(true);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.BOOLEAN;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeLong() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(5L);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.LONG;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeByte() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey((byte) 3);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.BYTE;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeShort() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey((short) 3);

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.SHORT;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeDecimal() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(new BigDecimal("3.14159265359"));

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.DECIMAL;

    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeString() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("abcd");

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.STRING;
    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeTwoStrings() {
    final var compositeKey = new CompositeKey();
    final var key = "abcd";
    compositeKey.addKey(key);
    final var secondKey = "test";
    compositeKey.addKey(secondKey);

    final var types = new PropertyTypeInternal[2];
    types[0] = PropertyTypeInternal.STRING;
    types[1] = PropertyTypeInternal.STRING;
    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeDate() {
    keyNormalizer.normalize(dateTimeCompositeKey, dateTimeTypes, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeDateTime() {
    keyNormalizer.normalize(dateCompositeKey, dateTypes, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void normalizeCompositeBinary() {
    keyNormalizer.normalize(binaryCompositeKey, binaryTypes, Collator.NO_DECOMPOSITION);
  }

  @Benchmark
  public void decimalNormalizer() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey(new BigDecimal(new BigInteger("20"), 2));

    final var types = new PropertyTypeInternal[1];
    types[0] = PropertyTypeInternal.DECIMAL;
    keyNormalizer.normalize(compositeKey, types, Collator.NO_DECOMPOSITION);
  }
}
