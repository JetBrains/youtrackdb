package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHookAbstract;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class ProvisionalClassInMemorySchemaCheckTest extends BaseMemoryInternalDatabase {

  private CommandCounter commands;

  @Before
  public void registerCommandCounter() {
    commands = new CommandCounter();
    session.registerListener(commands);
  }

  @Test
  public void emptyTransactionCreatedClassUsesFastPath() {
    session.executeInTx(transaction -> {
      var schemaClass = session.getMetadata().getSchema().createClass("EmptyFast");
      commands.reset();
      schemaClass.createProperty("value", PropertyType.INTEGER);
      assertEquals(0, commands.get());
      assertNotNull(schemaClass.getProperty("value"));
    });
  }

  @Test
  public void incompatibleValueMatchesCommittedMessage() {
    var fastMessage = incompatibleMessage(true, "IncompatibleParity");
    var queryMessage = incompatibleMessage(false, "IncompatibleParity");
    assertEquals(queryMessage, fastMessage);
  }

  @Test
  public void migratableValueMatchesCommittedResult() {
    assertEquals(Integer.class, migratedValueClass(true, "MigratableFast"));
    assertEquals(Integer.class, migratedValueClass(false, "MigratableQuery"));
  }

  @Test
  public void linkedCollectionAndScalarMismatchesMatchCommittedMessages() {
    var target = session.getMetadata().getSchema().createClass("LinkedTarget");
    assertEquals(
        linkedMismatchMessage(true, "ListParity", target, true),
        linkedMismatchMessage(false, "ListParity", target, true));
    assertEquals(
        linkedMismatchMessage(true, "ScalarParity", target, false),
        linkedMismatchMessage(false, "ScalarParity", target, false));
  }

  @Test
  public void violatingRecordOrderMatchesWithinCollectionAndAcrossSubtree() {
    assertEquals(
        orderedFailure(false, true, "OneCollectionOrder"),
        orderedFailure(true, true, "OneCollectionOrderFast"));
    assertEquals(
        orderedFailure(false, false, "SubtreeOrder"),
        orderedFailure(true, false, "SubtreeOrderFast"));
  }

  @Test
  public void deletedRecordIsIgnoredOnBothPaths() {
    assertEquals(0, deletedRecordCommands(true, "DeletedFast"));
    assertTrue(deletedRecordCommands(false, "DeletedQuery") > 0);
  }

  @Test
  public void createdThenUpdatedRecordUsesUpdatedValue() {
    assertUpdatedRecordFails(true, "UpdatedFast");
    assertUpdatedRecordFails(false, "UpdatedQuery");
  }

  @Test
  public void emptyMultivalueIsIgnoredOnBothPaths() {
    assertEquals(0, emptyListCommands(true, "EmptyListFast"));
    assertTrue(emptyListCommands(false, "EmptyListQuery") > 0);
  }

  @Test
  public void transactionCreatedParentWithCommittedPopulatedSubclassUsesQueryPath() {
    var schema = session.getMetadata().getSchema();
    var child = schema.createClass("CommittedPopulatedChild");
    session.executeInTx(
        transaction -> session.newEntity(child.getName()).setProperty("value", 1));

    session.executeInTx(transaction -> {
      var parent = schema.createClass("TransactionParent");
      child.addSuperClass(parent);
      commands.reset();
      parent.createProperty("value", PropertyType.INTEGER);
      assertTrue(commands.get() > 0);
    });
  }

  @Test
  public void committedEmptyClassUsesQueryPath() {
    var schemaClass = session.getMetadata().getSchema().createClass("CommittedEmpty");
    commands.reset();
    schemaClass.createProperty("value", PropertyType.INTEGER);
    assertTrue(commands.get() > 0);
  }

  @Test
  public void classGrantDeniedRecordNowFailsPropertyCreation() {
    assertGrantHiddenValidationDifference(Rule.ResourceGeneric.CLASS);
  }

  @Test
  public void grantDeniedRecordNowMigrates() {
    assertGrantHiddenMigrationDifference(Rule.ResourceGeneric.CLASS);
  }

  @Test
  public void readHooksDoNotObserveInternalSchemaChecks() {
    var readCount = new AtomicInteger();
    session.registerHook(new RecordHookAbstract() {
      @Override
      public void onRecordRead(DBRecord record) {
        readCount.incrementAndGet();
      }
    });

    session.executeInTx(transaction -> {
      var schemaClass = session.getMetadata().getSchema().createClass("NoReadHooksFast");
      session.newEntity(schemaClass.getName()).setProperty("value", 1);
      commands.reset();
      schemaClass.createProperty("value", PropertyType.INTEGER);
      assertEquals(0, readCount.get());
      assertEquals(0, commands.get());
    });
  }

  @Test
  public void noCommandStartsOnFastPath() {
    session.executeInTx(transaction -> {
      var schemaClass = session.getMetadata().getSchema().createClass("NoCommandsFast");
      session.newEntity(schemaClass.getName()).setProperty("value", 1);
      commands.reset();
      schemaClass.createProperty("value", PropertyType.INTEGER);
      assertEquals(0, commands.get());
      assertTrue(session.getActiveQueries().isEmpty());
    });
  }

  @Test
  public void backslashSuffixedPropertyNameSucceedsOnFastPath() {
    var propertyName = "broken\\";
    session.executeInTx(transaction -> {
      var schemaClass = session.getMetadata().getSchema().createClass("EscapedNameFast");
      session.newEntity(schemaClass.getName()).setProperty(propertyName, 1);
      commands.reset();
      schemaClass.createProperty(propertyName, PropertyType.INTEGER);
      assertEquals(0, commands.get());
      assertNotNull(schemaClass.getProperty(propertyName));
    });

    var committed = session.getMetadata().getSchema().createClass("EscapedNameQuery");
    session.executeInTx(
        transaction -> session.newEntity(committed.getName()).setProperty(propertyName, 1));
    commands.reset();
    assertThrows(Exception.class,
        () -> committed.createProperty(propertyName, PropertyType.INTEGER));
  }

  @Test
  public void abstractClassOutsideTransactionRunsFewerTransactionCycles() {
    var cycleCount = new AtomicInteger();
    session.registerListener(new SessionListener() {
      @Override
      public void onBeforeTxBegin(
          com.jetbrains.youtrackdb.internal.core.tx.Transaction transaction) {
        cycleCount.incrementAndGet();
      }
    });
    var linkedClass = session.getMetadata().getSchema().createClass("CycleLinkedClass");
    var schemaClass =
        session.getMetadata().getSchema().createAbstractClass("CycleAbstractClass");
    cycleCount.set(0);
    commands.reset();
    schemaClass.createProperty("value", PropertyType.LINK, linkedClass);
    var fastCycles = cycleCount.get();
    assertEquals(0, commands.get());

    var committedClass = session.getMetadata().getSchema().createClass("CycleCommittedClass");
    cycleCount.set(0);
    commands.reset();
    committedClass.createProperty("value", PropertyType.LINK, linkedClass);
    var queryCycles = cycleCount.get();
    assertTrue(commands.get() > 0);

    assertEquals(2, queryCycles - fastCycles);
  }

  @Test
  public void embeddedMapLinkMapAndLinkBagRemainUncheckedOnBothPaths() {
    for (var type : List.of(
        PropertyType.EMBEDDEDMAP, PropertyType.LINKMAP, PropertyType.LINKBAG)) {
      assertUncheckedLinkedType(type, true, "UncheckedFast" + type);
      assertUncheckedLinkedType(type, false, "UncheckedQuery" + type);
    }
  }

  @Test
  public void callbackRejectionSurfacesDuringPropertyCreation() {
    var callbackCount = new AtomicInteger();
    session.registerHook(new RecordHookAbstract() {
      @Override
      public void onBeforeRecordCreate(DBRecord record) {
        if (record instanceof EntityImpl entity
            && "CallbackRejected".equals(entity.getSchemaClassName())) {
          callbackCount.incrementAndGet();
          throw new IllegalStateException("callback rejected pending create");
        }
      }
    });

    session.begin();
    var schemaClass = session.getMetadata().getSchema().createClass("CallbackRejected");
    session.newEntity("CallbackRejected").setProperty("value", 1);
    commands.reset();
    var error = assertThrows(
        IllegalStateException.class,
        () -> schemaClass.createProperty("value", PropertyType.INTEGER));
    assertEquals("callback rejected pending create", error.getMessage());
    assertEquals(1, callbackCount.get());
    assertEquals(0, commands.get());
    finishRollingBackTransaction();
  }

  @Test
  public void failedValidationLeavesEquivalentTransactionStateOnBothPaths() {
    var fast = transactionOutcomeAfterFailure(true, "RollbackFast");
    var query = transactionOutcomeAfterFailure(false, "RollbackQuery");
    assertEquals(query, fast);
  }

  @Test
  public void typeFailurePrecedesLinkedClassFailureOnBothPaths() {
    var target = session.getMetadata().getSchema().createClass("PassOrderTarget");
    assertEquals(
        passOrderMessage(true, "PassOrderParity", target),
        passOrderMessage(false, "PassOrderParity", target));
  }

  @Test
  public void committedAbstractClassWithoutDescendantsUsesFastPath() {
    var schemaClass =
        session.getMetadata().getSchema().createAbstractClass("CommittedAbstractEmpty");
    commands.reset();
    schemaClass.createProperty("value", PropertyType.INTEGER);
    assertEquals(0, commands.get());
  }

  @Test
  public void migrationRewritesMultipleRecordsAcrossCollectionsAndTerminates() {
    session.executeInTx(transaction -> {
      var schema = session.getMetadata().getSchema();
      var parent = schema.createClass("MigrationParent");
      schema.createClass("MigrationChild", parent);
      var parentRecord = (EntityImpl) session.newEntity("MigrationParent");
      var childRecordOne = (EntityImpl) session.newEntity("MigrationChild");
      var childRecordTwo = (EntityImpl) session.newEntity("MigrationChild");
      parentRecord.setProperty("value", (short) 1);
      childRecordOne.setProperty("value", (short) 2);
      childRecordTwo.setProperty("value", (short) 3);

      commands.reset();
      parent.createProperty("value", PropertyType.INTEGER);

      assertEquals(0, commands.get());
      assertEquals(Integer.class, parentRecord.getPropertyInternal("value").getClass());
      assertEquals(Integer.class, childRecordOne.getPropertyInternal("value").getClass());
      assertEquals(Integer.class, childRecordTwo.getPropertyInternal("value").getClass());
    });
  }

  private void assertGrantHiddenValidationDifference(
      Rule.ResourceGeneric resourceGeneric) {
    var suffix = resourceGeneric == Rule.ResourceGeneric.CLASS ? "Class" : "Collection";
    var fastName = "GrantValidationFast" + suffix;
    var queryName = "GrantValidationQuery" + suffix;
    var queryClass = session.getMetadata().getSchema().createClass(queryName);
    session.executeInTx(
        transaction -> session.newEntity(queryName).setProperty("value", "bad"));
    prepareReaderForGrantTest(resourceGeneric, fastName, queryName);
    queryClass = session.getMetadata().getSchema().getClass(queryName);

    session.begin();
    var fastClass = session.getMetadata().getSchema().createClass(fastName);
    session.newEntity(fastName).setProperty("value", "bad");
    commands.reset();
    assertThrows(
        SchemaException.class,
        () -> fastClass.createProperty("value", PropertyType.INTEGER));
    assertEquals(0, commands.get());
    finishRollingBackTransaction();

    commands.reset();
    queryClass.createProperty("value", PropertyType.INTEGER);
    assertTrue(commands.get() > 0);
    assertNotNull(queryClass.getProperty("value"));
  }

  private void assertGrantHiddenMigrationDifference(
      Rule.ResourceGeneric resourceGeneric) {
    var suffix = resourceGeneric == Rule.ResourceGeneric.CLASS ? "Class" : "Collection";
    var fastName = "GrantMigrationFast" + suffix;
    var queryName = "GrantMigrationQuery" + suffix;
    var queryClass = session.getMetadata().getSchema().createClass(queryName);
    var queryRid = new RID[1];
    session.executeInTx(transaction -> {
      var queryRecord = (EntityImpl) session.newEntity(queryName);
      queryRecord.setProperty("value", (short) 1);
      queryRid[0] = queryRecord.getIdentity();
    });
    prepareReaderForGrantTest(resourceGeneric, fastName, queryName);
    queryClass = session.getMetadata().getSchema().getClass(queryName);

    session.begin();
    var fastClass = session.getMetadata().getSchema().createClass(fastName);
    var fastRecord = (EntityImpl) session.newEntity(fastName);
    fastRecord.setProperty("value", (short) 1);
    commands.reset();
    fastClass.createProperty("value", PropertyType.INTEGER);
    assertEquals(0, commands.get());
    assertEquals(Integer.class, fastRecord.getPropertyInternal("value").getClass());
    session.rollback();

    commands.reset();
    queryClass.createProperty("value", PropertyType.INTEGER);
    assertTrue(commands.get() > 0);
    reOpen(adminUser, adminPassword);
    var queryValueClass = session.computeInTx(
        transaction -> ((EntityImpl) transaction.load(queryRid[0]))
            .getPropertyInternal("value").getClass());
    assertEquals(Short.class, queryValueClass);
  }

  private void prepareReaderForGrantTest(
      Rule.ResourceGeneric resourceGeneric, String fastName, String queryName) {
    session.begin();
    var role = session.getMetadata().getSecurity().getRole(readerUser);
    role.grant(session, Rule.ResourceGeneric.SCHEMA, null, Role.PERMISSION_ALL);
    role.grant(session, Rule.ResourceGeneric.CLASS, null, Role.PERMISSION_ALL);
    role.grant(session, Rule.ResourceGeneric.COLLECTION, null, Role.PERMISSION_ALL);
    var allowedWrites = Role.PERMISSION_CREATE | Role.PERMISSION_UPDATE | Role.PERMISSION_DELETE;
    role.grant(session, Rule.ResourceGeneric.COLLECTION, "internal", Role.PERMISSION_ALL);
    if (resourceGeneric == Rule.ResourceGeneric.CLASS) {
      role.grant(session, resourceGeneric, fastName, Role.PERMISSION_NONE);
      role.grant(session, resourceGeneric, fastName, allowedWrites);
      role.grant(session, resourceGeneric, queryName, Role.PERMISSION_NONE);
      role.grant(session, resourceGeneric, queryName, allowedWrites);
    } else {
      role.grant(session, resourceGeneric, null, Role.PERMISSION_NONE);
      role.grant(session, resourceGeneric, null, allowedWrites);
    }
    role.save(session);
    session.commit();
    reOpen(readerUser, readerPassword);
    registerCommandCounter();
  }

  private void assertUncheckedLinkedType(
      PropertyType type, boolean fast, String className) {
    var schema = session.getMetadata().getSchema();
    var target = schema.createClass(className + "Target");
    var wrong = type == PropertyType.EMBEDDEDMAP
        ? schema.createAbstractClass(className + "Wrong")
        : schema.createClass(className + "Wrong");
    if (fast) {
      session.begin();
      var schemaClass = schema.createClass(className);
      createUncheckedLinkedRecord(type, className, wrong.getName());
      commands.reset();
      schemaClass.createProperty("value", type, target);
      assertEquals(0, commands.get());
      session.rollback();
    } else {
      var schemaClass = schema.createClass(className);
      session.executeInTx(
          transaction -> createUncheckedLinkedRecord(type, className, wrong.getName()));
      commands.reset();
      schemaClass.createProperty("value", type, target);
      assertTrue(commands.get() > 0);
    }
  }

  private void createUncheckedLinkedRecord(
      PropertyType type, String className, String wrongClassName) {
    var record = (EntityImpl) session.newEntity(className);
    switch (type) {
      case EMBEDDEDMAP -> {
        var values = session.newEmbeddedMap();
        values.put("wrong", session.newEmbeddedEntity(wrongClassName));
        record.setEmbeddedMap("value", values);
      }
      case LINKMAP -> {
        var values = session.newLinkMap();
        values.put("wrong", session.newEntity(wrongClassName));
        record.setLinkMap("value", values);
      }
      case LINKBAG -> {
        var wrongEntity = session.newEntity(wrongClassName);
        var values = new LinkBag(session);
        values.add(wrongEntity.getIdentity());
        record.setProperty("value", values);
      }
      default -> throw new AssertionError("Unexpected type " + type);
    }
  }

  private TransactionOutcome transactionOutcomeAfterFailure(boolean fast, String className) {
    SchemaClass schemaClass;
    if (fast) {
      session.begin();
      schemaClass = session.getMetadata().getSchema().createClass(className);
    } else {
      schemaClass = session.getMetadata().getSchema().createClass(className);
      session.begin();
    }
    session.newEntity(className).setProperty("value", "bad");
    commands.reset();
    assertThrows(
        SchemaException.class,
        () -> schemaClass.createProperty("value", PropertyType.INTEGER));
    if (fast) {
      assertEquals(0, commands.get());
    } else {
      assertTrue(commands.get() > 0);
    }
    var outcome = new TransactionOutcome(
        session.getTransactionInternal().getStatus().name(),
        session.getTransactionInternal().isActive());
    finishRollingBackTransaction();
    return outcome;
  }

  private String passOrderMessage(
      boolean fast, String className, SchemaClass target) {
    if (fast) {
      session.begin();
    }
    var schemaClass = session.getMetadata().getSchema().createClass(className);
    Runnable records = () -> {
      session.newEntity(className).setProperty("value", "type failure");
      session.newEntity(className).setProperty("value", session.newEntity(className));
    };
    if (fast) {
      records.run();
    } else {
      session.executeInTx(transaction -> records.run());
    }
    commands.reset();
    var error = assertThrows(
        SchemaException.class,
        () -> schemaClass.createProperty("value", PropertyType.LINK, target));
    if (fast) {
      assertEquals(0, commands.get());
      finishRollingBackTransaction();
    } else {
      assertTrue(commands.get() > 0);
    }
    assertTrue(error.getMessage().contains("not compatible with the type LINK."));
    return error.getMessage();
  }

  private record TransactionOutcome(String status, boolean active) {
  }

  private String orderedFailure(boolean fast, boolean oneCollection, String className) {
    var target = session.getMetadata().getSchema().createClass(className + "Target");
    if (fast) {
      session.begin();
    }
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass(className);
    var child = oneCollection ? null : schema.createClass(className + "Child", parent);
    Runnable createRecords = () -> {
      addMarkerRecord(parent.getName(), "first-created");
      addMarkerRecord(child == null ? parent.getName() : child.getName(), "second-created");
    };
    if (fast) {
      createRecords.run();
    } else {
      session.executeInTx(transaction -> createRecords.run());
    }
    commands.reset();
    var error = assertThrows(
        SchemaException.class,
        () -> parent.createProperty("value", PropertyType.EMBEDDEDLIST, target));
    if (fast) {
      assertEquals(0, commands.get());
      finishRollingBackTransaction();
    } else {
      assertTrue(commands.get() > 0);
    }
    return error.getMessage().substring(error.getMessage().lastIndexOf(' ') + 1);
  }

  private void addMarkerRecord(String className, String marker) {
    var record = (EntityImpl) session.newEntity(className);
    var values = session.newEmbeddedList();
    values.add(marker);
    record.setEmbeddedList("value", values);
  }

  private int deletedRecordCommands(boolean fast, String className) {
    var result = new int[1];
    if (fast) {
      session.executeInTx(transaction -> {
        var schemaClass = session.getMetadata().getSchema().createClass(className);
        var record = session.newEntity(className);
        record.setProperty("value", "bad");
        session.delete(record);
        commands.reset();
        schemaClass.createProperty("value", PropertyType.INTEGER);
        result[0] = commands.get();
      });
    } else {
      var schemaClass = session.getMetadata().getSchema().createClass(className);
      session.executeInTx(transaction -> {
        var record = session.newEntity(className);
        record.setProperty("value", "bad");
        session.delete(record);
      });
      commands.reset();
      schemaClass.createProperty("value", PropertyType.INTEGER);
      result[0] = commands.get();
    }
    return result[0];
  }

  private void assertUpdatedRecordFails(boolean fast, String className) {
    if (fast) {
      session.begin();
    }
    var schemaClass = session.getMetadata().getSchema().createClass(className);
    Runnable createRecord = () -> {
      var record = session.newEntity(className);
      record.setProperty("value", 1);
      record.setProperty("value", "updated bad value");
    };
    if (fast) {
      createRecord.run();
    } else {
      session.executeInTx(transaction -> createRecord.run());
    }
    commands.reset();
    assertThrows(
        SchemaException.class,
        () -> schemaClass.createProperty("value", PropertyType.INTEGER));
    if (fast) {
      assertEquals(0, commands.get());
      finishRollingBackTransaction();
    } else {
      assertTrue(commands.get() > 0);
    }
  }

  private int emptyListCommands(boolean fast, String className) {
    var result = new int[1];
    if (fast) {
      session.executeInTx(transaction -> {
        var schemaClass = session.getMetadata().getSchema().createClass(className);
        ((EntityImpl) session.newEntity(className))
            .setEmbeddedList("value", session.newEmbeddedList());
        commands.reset();
        schemaClass.createProperty("value", PropertyType.EMBEDDEDLIST);
        result[0] = commands.get();
      });
    } else {
      var schemaClass = session.getMetadata().getSchema().createClass(className);
      session.executeInTx(
          transaction -> ((EntityImpl) session.newEntity(className))
              .setEmbeddedList("value", session.newEmbeddedList()));
      commands.reset();
      schemaClass.createProperty("value", PropertyType.EMBEDDEDLIST);
      result[0] = commands.get();
    }
    return result[0];
  }

  private String incompatibleMessage(boolean fast, String className) {
    if (fast) {
      session.begin();
    }
    var schemaClass = session.getMetadata().getSchema().createClass(className);
    if (fast) {
      session.newEntity(className).setProperty("value", "bad");
    } else {
      session.executeInTx(
          transaction -> session.newEntity(className).setProperty("value", "bad"));
    }
    commands.reset();
    var error = assertThrows(
        SchemaException.class,
        () -> schemaClass.createProperty("value", PropertyType.INTEGER));
    assertEquals(fast ? 0 : 1, commands.get());
    if (fast) {
      finishRollingBackTransaction();
    }
    return error.getMessage();
  }

  private Class<?> migratedValueClass(boolean fast, String className) {
    var observedClass = new Class<?>[1];
    var rid = new RID[1];
    if (fast) {
      session.executeInTx(transaction -> {
        var schemaClass = session.getMetadata().getSchema().createClass(className);
        var record = (EntityImpl) session.newEntity(className);
        record.setProperty("value", (short) 42);
        commands.reset();
        schemaClass.createProperty("value", PropertyType.INTEGER);
        assertEquals(0, commands.get());
        observedClass[0] = record.getPropertyInternal("value").getClass();
      });
    } else {
      var schemaClass = session.getMetadata().getSchema().createClass(className);
      session.executeInTx(transaction -> {
        var record = (EntityImpl) session.newEntity(className);
        record.setProperty("value", (short) 42);
        rid[0] = record.getIdentity();
      });
      commands.reset();
      schemaClass.createProperty("value", PropertyType.INTEGER);
      assertTrue(commands.get() > 0);
      observedClass[0] = session.computeInTx(
          transaction -> ((EntityImpl) transaction.load(rid[0]))
              .getPropertyInternal("value").getClass());
    }
    return observedClass[0];
  }

  private String linkedMismatchMessage(
      boolean fast, String className, SchemaClass target, boolean collection) {
    if (fast) {
      session.begin();
    }
    var schemaClass = session.getMetadata().getSchema().createClass(className);
    Runnable createRecord = () -> {
      var record = (EntityImpl) session.newEntity(className);
      if (collection) {
        var values = session.newEmbeddedList();
        values.add("wrong linked value");
        record.setEmbeddedList("value", values);
      } else {
        record.setProperty("value", session.newEntity(className));
      }
    };
    if (fast) {
      createRecord.run();
    } else {
      session.executeInTx(transaction -> createRecord.run());
    }
    commands.reset();
    var type = collection ? PropertyType.EMBEDDEDLIST : PropertyType.LINK;
    var error = assertThrows(
        SchemaException.class,
        () -> schemaClass.createProperty("value", type, target));
    if (fast) {
      assertEquals(0, commands.get());
      finishRollingBackTransaction();
    } else {
      assertTrue(commands.get() > 0);
    }
    return error.getMessage();
  }

  private void finishRollingBackTransaction() {
    while (session.getTransactionInternal().isActive()) {
      session.rollback();
    }
  }

  private static final class CommandCounter implements SessionListener {

    private final AtomicInteger count = new AtomicInteger();

    @Override
    public void onCommandStart(DatabaseSessionEmbedded database, ResultSet resultSet) {
      count.incrementAndGet();
    }

    int get() {
      return count.get();
    }

    void reset() {
      count.set(0);
    }
  }
}
