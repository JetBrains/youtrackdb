package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.api.transaction.TxConsumer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

public class LazySchemaClassTest {

  ExecutorService executors = Executors.newFixedThreadPool(10);

  @Test
  public void justCreatedSchemaClassShouldBeLoaded() {
    var classId = new RecordId(1, 1);
    var delegate = mock(SchemaClassImpl.class);
    var lazyClass = LazySchemaClass.fromTemplate(classId, delegate, true);
    assertTrue(lazyClass.isFullyLoaded());
  }

  @Test
  public void shouldLoadClassOnlyOnce() {
    var session = mock(DatabaseSessionInternal.class);
    var classId = new RecordId(1, 1);
    var delegate = mock(SchemaClassImpl.class);
    var lazyClass = LazySchemaClass.fromTemplate(classId, delegate, false);
    doAnswer((Answer<Void>) invocation -> {
      TxConsumer<Transaction, ?> call = invocation.getArgument(0);
      var transactionMock = mock(Transaction.class);
      call.accept(transactionMock);
      return null;
    }).when(session).executeInTx(any());

    Runnable task = () -> {
      lazyClass.loadIfNeeded(session);
    };
    List<Future<?>> futures = IntStream.range(
            0, 50)
        .mapToObj(i -> task)
        .map(executors::submit)
        .collect(Collectors.toList());

    List<?> results = futures.stream()
        .map(future -> {
          try {
            return future.get();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          } catch (ExecutionException e) {
            throw new RuntimeException(e);
          }
        })
        .collect(Collectors.toList());

    assertTrue(lazyClass.isFullyLoaded());
    verify(session, times(1)).load(classId);
    verify(delegate, times(1)).fromStream(any(), any());
  }
}