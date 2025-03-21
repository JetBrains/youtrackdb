package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLCluster;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 *
 */
public class FindReferencesStep extends AbstractExecutionStep {

  private final List<SQLIdentifier> classes;
  private final List<SQLCluster> clusters;

  public FindReferencesStep(
      List<SQLIdentifier> classes,
      List<SQLCluster> clusters,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.classes = classes;
    this.clusters = clusters;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var db = ctx.getDatabaseSession();
    var rids = fetchRidsToFind(ctx);
    var clustersIterators = initClusterIterators(ctx);
    Stream<Result> stream =
        clustersIterators.stream()
            .flatMap(
                (iterator) -> {
                  return StreamSupport.stream(
                          Spliterators.spliteratorUnknownSize(iterator, 0), false)
                      .flatMap((record) -> findMatching(db, rids, record));
                });
    return ExecutionStream.resultIterator(stream.iterator());
  }

  private static Stream<? extends Result> findMatching(DatabaseSessionInternal db,
      Set<RID> rids,
      DBRecord record) {
    var rec = new ResultInternal(db, record);
    List<Result> results = new ArrayList<>();
    for (var rid : rids) {
      var resultForRecord = checkObject(db, Collections.singleton(rid), rec, record, "");
      if (!resultForRecord.isEmpty()) {
        var nextResult = new ResultInternal(db);
        nextResult.setProperty("rid", rid);
        nextResult.setProperty("referredBy", rec);
        nextResult.setProperty("fields", resultForRecord);
        results.add(nextResult);
      }
    }
    return results.stream();
  }

  private List<RecordIteratorCluster<RecordAbstract>> initClusterIterators(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    Collection<String> targetClusterNames = new HashSet<>();

    if ((this.classes == null || this.classes.isEmpty())
        && (this.clusters == null || this.clusters.isEmpty())) {
      targetClusterNames.addAll(ctx.getDatabaseSession().getClusterNames());
    } else {
      if (this.clusters != null) {
        for (var c : this.clusters) {
          if (c.getClusterName() != null) {
            targetClusterNames.add(c.getClusterName());
          } else {
            var clusterName = session.getClusterNameById(c.getClusterNumber());
            if (clusterName == null) {
              throw new CommandExecutionException(ctx.getDatabaseSession(),
                  "Cluster not found: " + c.getClusterNumber());
            }
            targetClusterNames.add(clusterName);
          }
        }
        Schema schema = session.getMetadata().getImmutableSchemaSnapshot();
        assert this.classes != null;
        for (var className : this.classes) {
          var clazz = schema.getClass(className.getStringValue());
          if (clazz == null) {
            throw new CommandExecutionException(ctx.getDatabaseSession(),
                "Class not found: " + className);
          }
          for (var clusterId : clazz.getPolymorphicClusterIds()) {
            targetClusterNames.add(session.getClusterNameById(clusterId));
          }
        }
      }
    }

    return targetClusterNames.stream()
        .map(clusterName -> new RecordIteratorCluster<>(session,
            session.getClusterIdByName(clusterName), true))
        .collect(Collectors.toList());
  }

  private Set<RID> fetchRidsToFind(CommandContext ctx) {
    Set<RID> ridsToFind = new HashSet<>();

    var prevStep = prev;
    assert prevStep != null;
    var nextSlot = prevStep.start(ctx);
    while (nextSlot.hasNext(ctx)) {
      var nextRes = nextSlot.next(ctx);
      if (nextRes.isEntity()) {
        ridsToFind.add(nextRes.getIdentity());
      }
    }
    nextSlot.close(ctx);
    return ridsToFind;
  }

  private static List<String> checkObject(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs, final Object value,
      final DBRecord iRootObject, String prefix) {
    return switch (value) {
      case Identifiable identifiable ->
          checkRecord(db, iSourceRIDs, identifiable, iRootObject, prefix).stream()
              .map(y -> value + "." + y)
              .collect(Collectors.toList());
      case Collection<?> objects ->
          checkCollection(db, iSourceRIDs, objects, iRootObject, prefix).stream()
              .map(y -> value + "." + y)
              .collect(Collectors.toList());
      case Map<?, ?> map -> checkMap(db, iSourceRIDs, map, iRootObject, prefix).stream()
          .map(y -> value + "." + y)
          .collect(Collectors.toList());
      case Result result -> checkRoot(db, iSourceRIDs, result, iRootObject, prefix).stream()
          .map(y -> value + "." + y)
          .collect(Collectors.toList());
      case null, default -> new ArrayList<>();
    };
  }

  private static List<String> checkCollection(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Collection<?> values,
      final DBRecord iRootObject,
      String prefix) {
    final var it = values.iterator();
    List<String> result = new ArrayList<>();
    while (it.hasNext()) {
      result.addAll(checkObject(db, iSourceRIDs, it.next(), iRootObject, prefix));
    }
    return result;
  }

  private static List<String> checkMap(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Map<?, ?> values,
      final DBRecord iRootObject,
      String prefix) {
    List<String> result = new ArrayList<>();
    for (var o : values.values()) {
      result.addAll(checkObject(db, iSourceRIDs, o, iRootObject, prefix));
    }
    return result;
  }

  private static List<String> checkRecord(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Identifiable value,
      final DBRecord iRootObject,
      String prefix) {
    List<String> result = new ArrayList<>();
    if (iSourceRIDs.contains(value.getIdentity())) {
      result.add(prefix);
    } else {
      if (!((RecordId) value.getIdentity()).isValidPosition()) {
        var transaction1 = db.getActiveTransaction();
        if (transaction1.load(value) instanceof EntityImpl) {
          // embedded document
          var transaction = db.getActiveTransaction();
          var entity = (EntityImpl) transaction.loadEntity(value);
          for (var fieldName : entity.propertyNames()) {
            var fieldValue = entity.getProperty(fieldName);
            result.addAll(
                checkObject(db, iSourceRIDs, fieldValue, iRootObject, prefix + "." + fieldName));
          }
        }
      }
    }
    return result;
  }

  private static List<String> checkRoot(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs, final Result value,
      final DBRecord iRootObject,
      String prefix) {
    List<String> result = new ArrayList<>();
    for (var fieldName : value.getPropertyNames()) {
      var fieldValue = value.getProperty(fieldName);
      result.addAll(
          checkObject(db, iSourceRIDs, fieldValue, iRootObject, prefix + "." + fieldName));
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ FIND REFERENCES\n");
    result.append(spaces);

    if ((this.classes == null || this.classes.isEmpty())
        && (this.clusters == null || this.clusters.isEmpty())) {
      result.append("  (all db)");
    } else {
      if (this.classes != null && !this.classes.isEmpty()) {
        result.append("  classes: ").append(this.classes);
      }
      if (this.clusters != null && !this.clusters.isEmpty()) {
        result.append("  classes: ").append(this.clusters);
      }
    }
    return result.toString();
  }
}
