package com.jetbrains.youtrackdb.internal.core.iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Test;

public class RecordIteratorCollectionTest extends DbTestBase {

  @Test
  public void testIteration() {
    for (var run = 0; run < 10; run++) {
      final var existingCount = RandomUtils.nextInt(0, run * 100);
      final var createdBeforeIt = RandomUtils.nextInt(0, run * 100);
      final var createdAfterIt = RandomUtils.nextInt(0, run * 100);

      final var clazz =
          session.getMetadata().getSchema().createClass("TEST_CLASS_" + run, 1);

      assertEquals(1, clazz.getCollectionIds().length);
      final var collectionId = clazz.getCollectionIds()[0];

      final var existing =
          session.computeInTx(tx -> createNEntities(tx, clazz, existingCount));

      session.executeInTx(tx -> {

        final var createdBefore = createNEntities(tx, clazz, createdBeforeIt);

        final var forwardIt =
            new RecordIteratorCollection<EntityImpl>(session, collectionId, true);
        final var backwardIt =
            new RecordIteratorCollection<EntityImpl>(session, collectionId, false);

        createNEntities(tx, clazz, createdAfterIt);

        final var forwardResults =
            IteratorUtils.toList(forwardIt)
                .stream().map(RecordAbstract::getIdentity).toList();
        final var backwardResults = IteratorUtils.toList(backwardIt)
            .stream().map(RecordAbstract::getIdentity).toList();

        assertEquals(
            new ArrayList<RID>() {{
              addAll(createdBefore);
              addAll(existing);
            }},
            forwardResults
        );

        assertEquals(
            forwardResults.reversed(),
            backwardResults
        );
      });
    }

  }

  private static List<RID> createNEntities(Transaction tx, SchemaClass clazz, int n) {
    return IntStream
        .range(0, n)
        .mapToObj(i -> tx.newEntity(clazz).getIdentity())
        .sorted()
        .toList();
  }

}