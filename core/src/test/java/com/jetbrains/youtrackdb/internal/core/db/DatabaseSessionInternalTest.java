package com.jetbrains.youtrackdb.internal.core.db;

import static org.assertj.core.api.Java6Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Test;

public class DatabaseSessionInternalTest extends DbTestBase {

  @Test
  public void testBatchTransaction() {

    final var batchSize = 100;
    final var batchCount = 10;
    final var indexes = IntStream.range(0, batchSize * batchCount).boxed()
        .collect(Collectors.toSet());

    session.getSchema().createClass("TestBatchTransaction");
    session.executeInTxBatches(indexes, batchSize, (tx, i) -> {
      tx.newEntity("TestBatchTransaction").setProperty("i", i);
    });

    final var createdIndexes = session.computeInTx(tx ->
        tx.query("select i from TestBatchTransaction")
            .stream()
            .map(r -> r.getInt("i"))
            .collect(Collectors.toSet())
    );

    assertThat(createdIndexes).isEqualTo(indexes);
  }

}
