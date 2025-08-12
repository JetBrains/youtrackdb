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
package com.jetbrains.youtrackdb.internal.core.record;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
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

  public static final String DEFAULT_FORMAT = "rid,version,class,type,keepTypes";

  @Nonnull
  protected final RecordId recordId;
  protected int recordVersion = 0;

  protected byte[] source;
  protected int size;

  public RecordSerializer recordSerializer;
  public long dirty = 1;
  protected boolean contentChanged = true;
  protected STATUS status = STATUS.NOT_LOADED;

  @Nonnull
  protected final DatabaseSessionEmbedded session;

  @Nullable
  public RecordOperation txEntry;
  public boolean processingInCallback = false;

  public RecordAbstract(@Nonnull DatabaseSessionEmbedded session) {
    recordId = new ChangeableRecordId();
    this.session = session;
  }

  public RecordAbstract(@Nonnull DatabaseSessionEmbedded session, final byte[] source) {
    this.source = source;
    size = source.length;

    recordId = new ChangeableRecordId();
    this.session = session;
  }

  public long getDirtyCounter() {
    return dirty;
  }

  @Override
  @Nonnull
  public final RecordId getIdentity() {
    return recordId;
  }

  @Override
  public RecordElement getOwner() {
    return null;
  }

  public boolean sourceIsParsedByProperties() {
    return status == STATUS.LOADED && source == null;
  }

  /**
   * Resets the record to be reused. The record is fresh like just created.
   */
  public RecordAbstract reset() {
    status = STATUS.LOADED;
    recordVersion = 0;
    size = 0;

    source = null;
    recordId.reset();

    setDirty();
    return this;
  }

  @Override
  public byte[] toStream() {
    checkForBinding();

    if (source == null) {
      source = recordSerializer.toStream(session, this);
    }

    return source;
  }

  @Override
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

  @Override
  public void setDirty() {
    assert session.assertIfNotActive() : createNotBoundToSessionMessage();
    checkForBinding();

    if (status != STATUS.UNMARSHALLING) {
      contentChanged = true;

      incrementDirtyCounterAndRegisterInTx();
    } else {
      assert dirty == 0;
    }
  }

  public void setDirty(long counter) {
    assert session.assertIfNotActive() : createNotBoundToSessionMessage();
    checkForBinding();

    this.dirty = counter;
  }

  @Override
  public void setDirtyNoChanged() {
    checkForBinding();

    if (status != STATUS.UNMARSHALLING) {
      source = null;

      incrementDirtyCounterAndRegisterInTx();
    } else {
      assert dirty == 0;
    }
  }

  private void incrementDirtyCounterAndRegisterInTx() {
    if (processingInCallback) {
      throw new IllegalStateException(
          "Cannot set dirty in callback processing. "
              + "If called this method in beforeCallbackXXX method, "
              + "please move this call to afterCallbackXX method.");
    }

    dirty++;

    assert txEntry == null || dirty >= txEntry.recordBeforeCallBackDirtyCounter + 1;
    assert txEntry == null || dirty >= txEntry.dirtyCounterOnClientSide + 1;

    assert txEntry == null || txEntry.record == this;

    //either record is not registered in transaction or callbacks were called on previous version of record
    //or record changes are not sent to client side for remote storage
    var tx = session.getTransactionInternal();
    if (txEntry == null || dirty == txEntry.recordBeforeCallBackDirtyCounter + 1
        || dirty == txEntry.dirtyCounterOnClientSide + 1) {
      if (!isEmbedded()) {
        tx.addRecordOperation(this, RecordOperation.UPDATED);
        assert session.getTransactionInternal().isScheduledForCallbackProcessing(
            recordId);
      }
    } else {
      assert session.getTransactionInternal().isScheduledForCallbackProcessing(
          recordId);
    }
  }

  @Override
  public final boolean isDirty() {
    return dirty != 0;
  }


  public <RET extends DBRecord> RET updateFromJSON(final String iSource, final String iOptions) {
    JSONSerializerJackson.INSTANCE.fromString(getSession(),
        iSource, this);
    // nothing change
    return (RET) this;
  }

  @Override
  public void updateFromJSON(final @Nonnull String iSource) {
    JSONSerializerJackson.INSTANCE.fromString(getSession(), iSource, this);
  }

  // Add New API to load record if rid exist
  public final <RET extends DBRecord> RET updateFromJSON(final String iSource, boolean needReload) {
    return (RET) JSONSerializerJackson.INSTANCE.fromString(getSession(), iSource, this);
  }

  public final <RET extends DBRecord> RET updateFromJSON(final InputStream iContentResult)
      throws IOException {
    final var out = new ByteArrayOutputStream();
    IOUtils.copyStream(iContentResult, out);
    JSONSerializerJackson.INSTANCE.fromString(getSession(), out.toString(), this);
    return (RET) this;
  }

  @Override
  public @Nonnull String toJSON() {
    checkForBinding();
    return toJSON(DEFAULT_FORMAT);
  }

  @Override
  @Nonnull
  public String toJSON(final @Nonnull String format) {
    checkForBinding();

    return JSONSerializerJackson.INSTANCE
        .toString(getSession(), this, new StringWriter(1024), format)
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
    return (recordId.isValidPosition() ? recordId : "")
        + (source != null ? Arrays.toString(source) : "[]")
        + " v"
        + recordVersion;
  }

  @Override
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
      unsetDirty();
      txEntry = null;
    }
  }

  @Override
  public boolean isUnloaded() {
    return status == STATUS.NOT_LOADED;
  }

  @Override
  public boolean isNotBound(@Nonnull DatabaseSession session) {
    assert ((DatabaseSessionInternal) session).assertIfNotActive();
    return this.session != session || this.status != STATUS.LOADED;
  }

  @Override
  @Nonnull
  public DatabaseSessionInternal getSession() {
    assert session.assertIfNotActive();
    return session;
  }

  @Override
  public void delete() {
    checkForBinding();
    var tx = session.getTransactionInternal();
    if (tx.isCallBackProcessingInProgress()) {
      throw new IllegalStateException("Cannot delete record in callback processing."
          + " If called this method in beforeCallbackXXX method, please move this call "
          + "to afterCallbackXX method.");
    }

    session.deleteInternal(this);
    internalReset();

    source = null;
    status = STATUS.NOT_LOADED;
    txEntry = null;
  }

  protected void internalReset() {

  }

  public int getSize() {
    return size;
  }

  @Override
  public int hashCode() {
    return recordId.hashCode();
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }

    switch (obj) {
      case RecordAbstract recordAbstract -> {
        if (session != recordAbstract.getBoundedToSession()) {
          throw new IllegalStateException(
              "Records  " + this + " and " + recordAbstract
                  + " are bound to different sessions and cannot be compared");
        }
        return recordId.equals(((Identifiable) obj).getIdentity())
            && recordVersion == recordAbstract.recordVersion;
      }
      case Identifiable identifiable -> {
        var transaction = session.getActiveTransaction();
        var record = (RecordAbstract) transaction.load(identifiable);
        return recordId.equals(record.recordId) && recordVersion == record.recordVersion;
      }
      case Result result when result.isIdentifiable() -> {
        var resultRecord = result.asRecord();
        return equals(resultRecord);
      }
      case null, default -> {
        return false;
      }
    }
  }

  @Override
  public int compareTo(@Nonnull final Identifiable iOther) {
    return recordId.compareTo(iOther.getIdentity());
  }

  @Override
  public boolean exists() {
    return getSession().exists(recordId);
  }

  public void setInternalStatus(final STATUS iStatus) {
    this.status = iStatus;
  }


  public RecordAbstract fill(
      @Nonnull final RID rid, final int version, final byte[] buffer, boolean dirty) {
    assert assertIfAlreadyLoaded(rid);
    var session = getSession();

    if (this.dirty > 0) {
      throw new DatabaseException(session.getDatabaseName(), "Cannot call fill() on dirty records");
    }

    recordId.setCollectionAndPosition(rid.getCollectionId(), rid.getCollectionPosition());

    recordVersion = version;
    status = STATUS.LOADED;
    source = buffer;
    size = buffer != null ? buffer.length : 0;

    if (source != null && source.length > 0 && dirty) {
      setDirty();
    }

    return this;
  }

  protected boolean assertIfAlreadyLoaded(RID rid) {
    var session = getSession();

    var tx = session.getTransactionInternal();
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

  public final RecordAbstract setIdentity(final int collectionId, final long collectionPosition) {
    assert assertIfAlreadyLoaded(new RecordId(collectionId, collectionPosition));

    recordId.setCollectionAndPosition(collectionId, collectionPosition);
    return this;
  }

  public final RecordAbstract setIdentity(RID recordId) {
    assert assertIfAlreadyLoaded(recordId);

    this.recordId.setCollectionAndPosition(recordId.getCollectionId(),
        recordId.getCollectionPosition());

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
      if (!recordId.isValidPosition()) {
        return;
      }

      throw new DatabaseException(session, createNotBoundToSessionMessage());
    }

    assert session.assertIfNotActive();
  }

  private String createNotBoundToSessionMessage() {
    return "Record "
        + recordId
        + " is not bound to the current session. Please bind record to the database session"
        + " by calling : "
        + Transaction.class.getSimpleName()
        + ".load(record) before using it.";
  }

  public boolean isContentChanged() {
    return contentChanged;
  }

  public void setContentChanged(boolean contentChanged) {
    checkForBinding();

    this.contentChanged = contentChanged;
  }

  public void clearSource() {
    this.source = null;
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
    assert session.assertIfNotActive();
    return session;
  }
}
