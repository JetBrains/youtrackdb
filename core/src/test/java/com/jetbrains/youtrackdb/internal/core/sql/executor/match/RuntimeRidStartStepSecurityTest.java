package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.RuntimeRidStartTestFactory;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import java.util.Map;
import org.junit.Test;

/** Exercises current-session permissions through the direct RID load. */
public class RuntimeRidStartStepSecurityTest extends DbTestBase {

  private RuntimeRidStartStep step() {
    var templateContext = new BasicCommandContext();
    templateContext.setDatabaseSession(session);
    return new RuntimeRidStartStep(
        RuntimeRidStartTestFactory.create("source", "SecuredStart", 0), null,
        templateContext, false);
  }

  private boolean hasRow(RuntimeRidStartStep step, DatabaseSessionEmbedded db, RID rid) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(db);
    ctx.setInputParameters(Map.of(0, rid));
    db.begin();
    try {
      var stream = step.start(ctx);
      try {
        var hasRow = stream.hasNext(ctx);
        if (hasRow) {
          stream.next(ctx);
        }
        return hasRow;
      } finally {
        stream.close(ctx);
      }
    } finally {
      db.rollback();
    }
  }

  private RecordId createRecord(String name) {
    session.begin();
    var record = session.newEntity("SecuredStart");
    record.setProperty("name", name);
    session.commit();
    return new RecordId(record.getIdentity());
  }

  /** Class denial is not-found for a reader while an admin still sees the same RID. */
  @Test
  public void classDenialIsIsolatedToCurrentSession() {
    session.createClass("SecuredStart");
    var rid = createRecord("allowed");
    session.begin();
    var reader = session.getMetadata().getSecurity().getRole("reader");
    reader.grant(session, Rule.ResourceGeneric.CLASS, "SecuredStart", Role.PERMISSION_NONE);
    reader.revoke(session, Rule.ResourceGeneric.CLASS, "SecuredStart", Role.PERMISSION_READ);
    reader.save(session);
    session.commit();
    var step = step();
    try (var readerSession = openDatabase("reader", READER_PASSWORD)) {
      assertFalse(hasRow(step, readerSession, rid));
      assertTrue(hasRow(step, session, rid));
    }
  }

  /** A row-level policy returns not-found only for rows the current user cannot read. */
  @Test
  public void rowDenialIsIsolatedToCurrentSession() {
    session.createClass("SecuredStart");
    var allowed = createRecord("allowed");
    var denied = createRecord("denied");
    var security = session.getSharedContext().getSecurity();
    session.begin();
    var policy = security.createSecurityPolicy(session, "runtimeStartReadPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'allowed'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.SecuredStart", policy);
    session.commit();
    var step = step();
    try (var readerSession = openDatabase("reader", READER_PASSWORD)) {
      assertTrue(hasRow(step, readerSession, allowed));
      assertFalse(hasRow(step, readerSession, denied));
      assertTrue(hasRow(step, session, denied));
    }
  }

  /** Collection-level denial is an error, never a silent empty start. */
  @Test
  public void collectionDenialPropagates() {
    session.createClass("SecuredStart");
    var rid = createRecord("allowed");
    var collectionName = session.getCollectionNameById(rid.getCollectionId());
    session.begin();
    var reader = session.getMetadata().getSecurity().getRole("reader");
    reader.grant(session, Rule.ResourceGeneric.COLLECTION, collectionName, Role.PERMISSION_NONE);
    reader.revoke(session, Rule.ResourceGeneric.COLLECTION, collectionName, Role.PERMISSION_READ);
    reader.save(session);
    session.commit();
    var step = step();
    try (var readerSession = openDatabase("reader", READER_PASSWORD)) {
      assertThrows(SecurityException.class, () -> hasRow(step, readerSession, rid));
      assertTrue(hasRow(step, session, rid));
    }
  }
}
