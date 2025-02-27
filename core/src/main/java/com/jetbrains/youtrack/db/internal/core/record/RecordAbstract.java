/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.record;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings({"unchecked"})
public abstract class RecordAbstract implements DBRecord, RecordElement, SerializableStream,
    ChangeableIdentity {

  public static final String DEFAULT_FORMAT = "rid,version,class,type,keepTypes,markEmbeddedDocs";

  protected final RecordId recordId;
  protected int recordVersion = 0;

  protected byte[] source;
  protected int size;

  public RecordSerializer recordFormat;
  protected long dirty = 1;
  protected boolean contentChanged = true;
  protected STATUS status = STATUS.LOADED;

  @Nullable
  protected DatabaseSessionInternal session;

  public RecordAbstract(@Nonnull DatabaseSessionInternal session) {
    recordId = new ChangeableRecordId();
    this.session = session;
  }

  public RecordAbstract(@Nonnull DatabaseSessionInternal session, final byte[] source) {
    this.source = source;
    size = source.length;

    recordId = new ChangeableRecordId();

    unsetDirty();
    this.session = session;
  }

  public long getDirtyCounter() {
    return dirty;
  }

  @Nonnull
  public final RecordId getIdentity() {
    return recordId;
  }

  @Override
  public RecordElement getOwner() {
    return null;
  }

  @Nonnull
  public RecordAbstract getRecord(@Nonnull DatabaseSession session) {
    return this;
  }

  public void clear() {
    checkForBinding();

    setDirty();
  }

  /**
   * Resets the record to be reused. The record is fresh like just created.
   */
  public RecordAbstract reset() {
    status = STATUS.LOADED;
    recordVersion = 0;
    size = 0;

    source = null;
    if (recordId != null) {
      recordId.reset();
    }

    setDirty();
    return this;
  }

  public byte[] toStream() {
    checkForBinding();

    if (source == null) {
      source = recordFormat.toStream(session, this);
    }

    return source;
  }

  public RecordAbstract fromStream(final byte[] iRecordBuffer) {
    var session = getSession();
    if (dirty > 0) {
      throw new DatabaseException(session.getDatabaseName(),
          "Cannot call fromStream() on dirty records");
    }

    contentChanged = false;
    source = iRecordBuffer;
    size = iRecordBuffer != null ? iRecordBuffer.length : 0;
    status = STATUS.LOADED;

    return this;
  }


  public boolean isEmbedded() {
    return false;
  }

  public void setDirty() {
    assert session != null && session.assertIfNotActive() : createNotBoundToSessionMessage();
    if (status != STATUS.UNMARSHALLING) {
      dirty++;

      if (dirty == 1) {
        registerInTx();

        source = null;
      }

      contentChanged = true;
    } else {
      assert dirty == 0;
    }
  }

  @Override
  public void setDirtyNoChanged() {
    if (status != STATUS.UNMARSHALLING) {
      if (dirty == 0) {
        checkForBinding();
        registerInTx();

        source = null;
      }

      dirty++;
    } else {
      assert dirty == 0;
    }
  }

  private void registerInTx() {
    if (recordId.isPersistent()) {
      var tx = session.getTransaction();
      if (!isEmbedded()) {
        assert recordId.isPersistent();

        var optimistic = (FrontendTransactionOptimistic) tx;
        optimistic.addRecordOperation(this, RecordOperation.UPDATED, null);
      }
    }
  }

  public final boolean isDirty() {
    return dirty != 0;
  }


  public <RET extends DBRecord> RET updateFromJSON(final String iSource, final String iOptions) {
    RecordSerializerJackson.fromString(getSession(),
        iSource, this);
    // nothing change
    return (RET) this;
  }

  public void updateFromJSON(final @Nonnull String iSource) {
    RecordSerializerJackson.fromString(getSession(), iSource, this);
  }

  // Add New API to load record if rid exist
  public final <RET extends DBRecord> RET updateFromJSON(final String iSource, boolean needReload) {
    return (RET) RecordSerializerJackson.fromString(getSession(), iSource, this);
  }

  public final <RET extends DBRecord> RET updateFromJSON(final InputStream iContentResult)
      throws IOException {
    final var out = new ByteArrayOutputStream();
    IOUtils.copyStream(iContentResult, out);
    RecordSerializerJackson.fromString(getSession(), out.toString(), this);
    return (RET) this;
  }

  public @Nonnull String toJSON() {
    checkForBinding();
    return toJSON(DEFAULT_FORMAT);
  }

  @Nonnull
  public String toJSON(final @Nonnull String format) {
    checkForBinding();

    return RecordSerializerJackson
        .toString(getSession(), this, new StringWriter(1024),
            format == null ? "" : format)
        .toString();
  }

  public void toJSON(final String format, final OutputStream stream) throws IOException {
    checkForBinding();
    stream.write(toJSON(format).getBytes());
  }

  public void toJSON(final OutputStream stream) throws IOException {
    checkForBinding();
    stream.write(toJSON().getBytes());
  }

  @Override
  public String toString() {
    return (recordId.isValid() ? recordId : "")
        + (source != null ? Arrays.toString(source) : "[]")
        + " v"
        + recordVersion;
  }

  public final int getVersion() {
    return recordVersion;
  }

  public final int getVersionNoLoad() {
    return recordVersion;
  }

  public final void setVersion(final int iVersion) {
    recordVersion = iVersion;
  }

  public void unload() {
    if (status != STATUS.NOT_LOADED) {
      source = null;
      status = STATUS.NOT_LOADED;
      session = null;
      unsetDirty();
    }
  }

  @Override
  public boolean isUnloaded() {
    return status == STATUS.NOT_LOADED;
  }

  @Override
  public boolean isNotBound(@Nonnull DatabaseSession session) {
    return isUnloaded() || this.session != session;
  }

  @Nonnull
  public DatabaseSessionInternal getSession() {
    if (session == null) {
      throw new DatabaseException(createNotBoundToSessionMessage());
    }

    assert session.assertIfNotActive();
    return session;
  }

  public void delete() {
    checkForBinding();

    dirty++;
    session.deleteInternal(this);

    source = null;
    status = STATUS.NOT_LOADED;
    session = null;
  }

  public int getSize() {
    return size;
  }

  @Override
  public int hashCode() {
    return recordId != null ? recordId.hashCode() : 0;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }

    switch (obj) {
      case null -> {
        return false;
      }
      case RecordAbstract recordAbstract -> {
        if (session != recordAbstract.getBoundedToSession()) {
          return false;
        }
        return recordId.equals(((Identifiable) obj).getIdentity())
            && recordVersion == recordAbstract.recordVersion;
      }
      case Identifiable identifiable -> {
        if (session == null) {
          return recordId.equals(identifiable.getIdentity());
        } else {
          var record = (RecordAbstract) identifiable.getRecord(session);
          return recordId.equals(record.recordId) && recordVersion == record.recordVersion;
        }
      }
      default -> {
      }
    }

    return false;
  }

  public int compare(final Identifiable iFirst, final Identifiable iSecond) {
    if (iFirst == null || iSecond == null) {
      return -1;
    }

    return iFirst.compareTo(iSecond);
  }

  public int compareTo(@Nonnull final Identifiable iOther) {
    if (recordId == null) {
      return iOther.getIdentity() == null ? 0 : 1;
    }

    return recordId.compareTo(iOther.getIdentity());
  }

  public STATUS getInternalStatus() {
    return status;
  }

  @Override
  public boolean exists() {
    return getSession().exists(recordId);
  }

  public void setInternalStatus(final STATUS iStatus) {
    this.status = iStatus;
  }


  protected RecordAbstract fill(
      @Nonnull final RID rid, final int version, final byte[] buffer, boolean dirty) {
    assert assertIfAlreadyLoaded(rid);
    var session = getSession();

    if (this.dirty > 0) {
      throw new DatabaseException(session.getDatabaseName(), "Cannot call fill() on dirty records");
    }

    recordId.setClusterId(rid.getClusterId());
    recordId.setClusterPosition(rid.getClusterPosition());

    recordVersion = version;
    status = STATUS.LOADED;
    source = buffer;
    size = buffer != null ? buffer.length : 0;

    if (source != null && source.length > 0) {
      this.dirty = dirty ? 1 : 0;
      contentChanged = dirty;
    }

    return this;
  }

  protected boolean assertIfAlreadyLoaded(RID rid) {
    var session = getSession();

    var tx = session.getTransaction();
    if (tx.isActive()) {
      var txEntry = tx.getRecordEntry(rid);
      if (txEntry != null) {
        if (txEntry.record != this) {
          throw new DatabaseException(
              "Instance of record with rid : " + rid + " is already registered in session.");
        }
      }
    }

    var localCache = session.getLocalCache();
    var localRecord = localCache.findRecord(rid);
    if (localRecord != null && localRecord != this) {
      throw new DatabaseException(
          "Instance of record with rid : " + rid + " is already registered in session.");
    }

    return true;
  }

  public final RecordAbstract setIdentity(final int clusterId, final long clusterPosition) {
    assert assertIfAlreadyLoaded(new RecordId(clusterId, clusterPosition));

    recordId.setClusterId(clusterId);
    recordId.setClusterPosition(clusterPosition);

    return this;
  }

  public final RecordAbstract setIdentity(RID recordId) {
    assert assertIfAlreadyLoaded(recordId);

    this.recordId.setClusterId(recordId.getClusterId());
    this.recordId.setClusterPosition(recordId.getClusterPosition());

    return this;
  }


  public void unsetDirty() {
    contentChanged = false;
    dirty = 0;
  }

  public abstract byte getRecordType();

  public void checkForBinding() {
    if (status == STATUS.UNMARSHALLING) {
      return;
    }

    if (status == STATUS.NOT_LOADED) {
      if (!recordId.isValid()) {
        return;
      }

      throw new DatabaseException(session != null ? session.getDatabaseName() : null,
          createNotBoundToSessionMessage());
    }

    if (session == null) {
      throw new DatabaseException(createNotBoundToSessionMessage());
    }

    assert session.assertIfNotActive();
  }

  private String createNotBoundToSessionMessage() {
    return "Record "
        + recordId
        + " is not bound to the current session. Please bind record to the database session"
        + " by calling : "
        + DatabaseSession.class.getSimpleName()
        + ".bindToSession(record) before using it.";
  }

  protected boolean isContentChanged() {
    return contentChanged;
  }

  protected void setContentChanged(boolean contentChanged) {
    checkForBinding();

    this.contentChanged = contentChanged;
  }

  protected void clearSource() {
    this.source = null;
  }

  public void resetToNew() {
    if (!recordId.isNew()) {
      throw new IllegalStateException(
          "Record id is not new " + recordId + " as expected, so record can't be reset.");
    }

    reset();
  }


  @Override
  public void addIdentityChangeListener(IdentityChangeListener identityChangeListeners) {
    if (recordId instanceof ChangeableIdentity) {
      ((ChangeableIdentity) recordId).addIdentityChangeListener(identityChangeListeners);
    }
  }

  @Override
  public void removeIdentityChangeListener(IdentityChangeListener identityChangeListener) {
    if (recordId instanceof ChangeableIdentity) {
      ((ChangeableIdentity) recordId).removeIdentityChangeListener(identityChangeListener);
    }
  }

  @Override
  public boolean canChangeIdentity() {
    if (recordId instanceof ChangeableIdentity) {
      return ((ChangeableIdentity) recordId).canChangeIdentity();
    }

    return false;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundedToSession() {
    assert session == null || session.assertIfNotActive();
    return session;
  }
}
