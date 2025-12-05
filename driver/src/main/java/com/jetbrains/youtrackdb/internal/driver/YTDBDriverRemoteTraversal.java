package com.jetbrains.youtrackdb.internal.driver;

import static org.apache.tinkerpop.gremlin.process.remote.RemoteConnection.GREMLIN_REMOTE;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.remote.RemoteProtocolConstants;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.driver.ResultSet;
import org.apache.tinkerpop.gremlin.process.remote.traversal.AbstractRemoteTraversal;
import org.apache.tinkerpop.gremlin.process.remote.traversal.RemoteTraverser;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.EmptyTraverser;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.Attachable;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedElement;

public class YTDBDriverRemoteTraversal<S, E> extends AbstractRemoteTraversal<S, E> {

  private final HashMap<RecordIdInternal, Set<ChangeableRecordId>> changeableRIDs;
  private final boolean isSessionLess;
  private final Iterator<Traverser.Admin<E>> traversers;
  private Traverser.Admin<E> lastTraverser = EmptyTraverser.instance();

  private final ResultSet resultSet;

  public YTDBDriverRemoteTraversal(final ResultSet rs, final Client client, final boolean attach,
      final HashMap<RecordIdInternal, Set<ChangeableRecordId>> changeableRIDs,
      @SuppressWarnings("OptionalUsedAsFieldOrParameterType") final Optional<Configuration> conf) {
    // attaching is really just for testing purposes. it doesn't make sense in any real-world scenario as it would
    // require that the client have access to the Graph instance that produced the result. tests need that
    // attachment process to properly execute in full hence this little hack.
    if (attach) {
      if (conf.isEmpty()) {
        throw new IllegalStateException("Traverser can't be reattached for testing");
      }

      @SuppressWarnings("unchecked") final var graph = ((Supplier<Graph>) conf.get()
          .getProperty(GREMLIN_REMOTE + "attachment")).get();
      this.traversers = new AttachingTraverserIterator<>(rs.iterator(), graph);
    } else {
      this.traversers = new TraverserIterator<>(rs.iterator());
    }

    this.changeableRIDs = changeableRIDs;
    this.isSessionLess = !(client instanceof Client.SessionedClient);
    this.resultSet = rs;
  }

  @Override
  public boolean hasNext() {
    var result = this.lastTraverser.bulk() > 0L;

    while (!result && this.traversers.hasNext()) {
      lastTraverser = traversers.next();
      result = lastTraverser.bulk() > 0L;
    }

    if (!result) {
      updateRidsFromResultSet();
    }

    return result;
  }

  private void updateRidsFromResultSet() {
    try {
      var statusAttributes = resultSet.statusAttributes().get();

      @SuppressWarnings("unchecked")
      var committedRIDs = (Map<RecordIdInternal, RecordIdInternal>) statusAttributes.get(
          RemoteProtocolConstants.RESULT_METADATA_COMMITTED_RIDS_KEY);
      if (committedRIDs != null) {
        for (var committedRidEntry : committedRIDs.entrySet()) {
          var elementRids = changeableRIDs.remove(committedRidEntry.getKey());
          if (elementRids != null) {
            var newRidValue = committedRidEntry.getValue();

            for (var rid : elementRids) {
              rid.setCollectionAndPosition(newRidValue.getCollectionId(),
                  newRidValue.getCollectionPosition());
            }
          }
        }
      }
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException("Error during remote status attributes retrieval", e);
    }
  }

  @Override
  public E next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    if (1L == this.lastTraverser.bulk()) {
      final var result = this.lastTraverser.get();
      this.lastTraverser = EmptyTraverser.instance();
      rememberChangeableRid(result);
      return result;
    } else {
      this.lastTraverser.setBulk(this.lastTraverser.bulk() - 1L);
      var result = this.lastTraverser.get();
      rememberChangeableRid(result);
      return result;
    }

  }

  @Override
  public Traverser.Admin<E> nextTraverser() {
    // the lastTraverser is initialized as "empty" at start of iteration so the initial pass through will
    // call next() to begin the iteration
    if (0L == this.lastTraverser.bulk()) {
      if (this.traversers.hasNext()) {
        var result = this.traversers.next();
        rememberChangeableRid(result);

        return result;
      }

      updateRidsFromResultSet();
      throw new NoSuchElementException();
    } else {
      final var temp = this.lastTraverser;
      rememberChangeableRid(temp);

      this.lastTraverser = EmptyTraverser.instance();
      return temp;
    }
  }

  static class TraverserIterator<E> implements Iterator<Traverser.Admin<E>> {

    private final Iterator<Result> inner;

    public TraverserIterator(final Iterator<Result> resultIterator) {
      inner = resultIterator;
    }

    @Override
    public boolean hasNext() {
      return inner.hasNext();
    }

    @Override
    public Traverser.Admin<E> next() {
      //noinspection unchecked
      return (RemoteTraverser<E>) inner.next().getObject();
    }
  }

  static class AttachingTraverserIterator<E> extends TraverserIterator<E> {

    private final Graph graph;

    public AttachingTraverserIterator(final Iterator<Result> resultIterator, final Graph graph) {
      super(resultIterator);
      this.graph = graph;
    }

    @Override
    public Traverser.Admin<E> next() {
      final var traverser = super.next();

      if (traverser.get() instanceof Attachable && !(traverser.get() instanceof Property)) {
        //noinspection unchecked
        traverser.set(
            (E) ((Attachable<Element>) traverser.get()).attach(Attachable.Method.get(graph)));
      }
      return traverser;
    }
  }

  @Override
  public void close() throws Exception {
    super.close();

    if (isSessionLess) {
      changeableRIDs.clear();
    }
  }

  private void rememberChangeableRid(Object data) {
    switch (data) {
      case Traverser<?> traverser -> {
        var underlying = traverser.get();
        rememberChangeableRid(underlying);
      }
      case Collection<?> collection -> {
        for (var item : collection) {
          rememberChangeableRid(item);
        }
      }
      case Map<?, ?> map -> {
        for (var entry : map.entrySet()) {
          rememberChangeableRid(entry.getKey());
          rememberChangeableRid(entry.getValue());
        }
      }
      case Iterable<?> iterable -> {
        for (var item : iterable) {
          rememberChangeableRid(item);
        }
      }
      case DetachedElement<?> detachedElement -> {
        RecordIdInternal rid;
        if (detachedElement instanceof VertexProperty<?> vertexProperty) {
          var vertexPropertyId = (YTDBVertexPropertyId) vertexProperty.id();
          rid = (RecordIdInternal) vertexPropertyId.rid();
        } else {
          rid = (RecordIdInternal) detachedElement.id();
        }

        rememberChangeableRid(rid);
      }
      case RecordIdInternal rid -> {
        if (rid.isNew()) {
          changeableRIDs.compute(rid.copy(), (ridCopy, ridSet) -> {
            if (ridSet == null) {
              ridSet = Collections.newSetFromMap(new IdentityHashMap<>());
            }

            ridSet.add((ChangeableRecordId) rid);
            return ridSet;
          });
        }
      }

      case null, default -> {
      }
    }
  }
}
