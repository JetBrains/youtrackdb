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
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.id.ImmutableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.DirtyManager;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJSON;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings({"unchecked"})
public abstract class RecordAbstract implements DBRecord, RecordElement, SerializableStream,
    ChangeableIdentity {

  public static final String BASE_FORMAT =
      "rid,version,class,type,attribSameRow,keepTypes,alwaysFetchEmbedded";
  private static final String DEFAULT_FORMAT = BASE_FORMAT + "," + "fetchPlan:*:0";
  public static final String OLD_FORMAT_WITH_LATE_TYPES = BASE_FORMAT + "," + "fetchPlan:*:0";

  protected RecordId recordId;
  protected int recordVersion = 0;

  protected byte[] source;
  protected int size;

  protected transient RecordSerializer recordFormat;
  protected boolean dirty = true;
  protected boolean contentChanged = true;
  protected RecordElement.STATUS status = RecordElement.STATUS.LOADED;

  protected DirtyManager dirtyManager;

  private long loadingCounter;
  private DatabaseSessionInternal session;

  public RecordAbstract() {
  }

  public RecordAbstract(final byte[] iSource) {
    source = iSource;
    size = iSource.length;
    unsetDirty();
  }

  public final RecordId getIdentity() {
    return recordId;
  }

  public final RecordAbstract setIdentity(final RecordId iIdentity) {
    recordId = iIdentity;
    return this;
  }

  @Override
  public RecordElement getOwner() {
    return null;
  }

  @Nonnull
  public RecordAbstract getRecord() {
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
    status = RecordElement.STATUS.LOADED;
    recordVersion = 0;
    size = 0;

    source = null;
    setDirty();
    if (recordId != null) {
      recordId.reset();
    }

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
    if (dirty) {
      throw new DatabaseException("Cannot call fromStream() on dirty records");
    }

    contentChanged = false;
    dirtyManager = null;
    source = iRecordBuffer;
    size = iRecordBuffer != null ? iRecordBuffer.length : 0;
    status = RecordElement.STATUS.LOADED;

    return this;
  }

  protected RecordAbstract fromStream(final byte[] iRecordBuffer, DatabaseSessionInternal db) {
    if (dirty) {
      throw new DatabaseException("Cannot call fromStream() on dirty records");
    }

    contentChanged = false;
    dirtyManager = null;
    source = iRecordBuffer;
    size = iRecordBuffer != null ? iRecordBuffer.length : 0;
    status = RecordElement.STATUS.LOADED;

    return this;
  }

  public RecordAbstract setDirty() {
    if (!dirty && recordId.isPersistent()) {
      if (session == null) {
        throw new DatabaseException(createNotBoundToSessionMessage());
      }

      var tx = session.getTransaction();
      if (!tx.isActive()) {
        throw new DatabaseException("Cannot modify persisted record outside of transaction");
      }
    }

    if (!dirty && status != STATUS.UNMARSHALLING) {
      checkForBinding();

      dirty = true;
      source = null;
    }

    contentChanged = true;

    return this;
  }

  @Override
  public void setDirtyNoChanged() {
    if (!dirty && status != STATUS.UNMARSHALLING) {
      checkForBinding();

      dirty = true;
      source = null;
    }
  }

  public final boolean isDirty() {
    return dirty;
  }

  public final boolean isDirtyNoLoading() {
    return dirty;
  }

  public <RET extends DBRecord> RET fromJSON(final String iSource, final String iOptions) {
    status = STATUS.UNMARSHALLING;
    try {
      RecordSerializerJSON.INSTANCE.fromString(getSession(),
          iSource, this, null, iOptions, false); // Add new parameter to accommodate new API,
      // nothing change
      return (RET) this;
    } finally {
      status = STATUS.LOADED;
    }
  }

  public void fromJSON(final String iSource) {
    status = STATUS.UNMARSHALLING;
    try {
      RecordSerializerJSON.INSTANCE.fromString(getSessionIfDefined(), iSource, this, null);
    } finally {
      status = STATUS.LOADED;
    }
  }

  // Add New API to load record if rid exist
  public final <RET extends DBRecord> RET fromJSON(final String iSource, boolean needReload) {
    status = STATUS.UNMARSHALLING;
    try {
      return (RET) RecordSerializerJSON.INSTANCE.fromString(getSession(), iSource, this, null,
          needReload);
    } finally {
      status = STATUS.LOADED;
    }
  }

  public final <RET extends DBRecord> RET fromJSON(final InputStream iContentResult)
      throws IOException {
    status = STATUS.UNMARSHALLING;
    try {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      IOUtils.copyStream(iContentResult, out);
      RecordSerializerJSON.INSTANCE.fromString(getSession(), out.toString(), this, null);
      return (RET) this;
    } finally {
      status = STATUS.LOADED;
    }
  }

  public String toJSON() {
    checkForBinding();
    return toJSON(DEFAULT_FORMAT);
  }

  public String toJSON(final String format) {
    checkForBinding();

    return RecordSerializerJSON.INSTANCE
        .toString( this, new StringBuilder(1024), format == null ? "" : format)
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
    if (status != RecordElement.STATUS.NOT_LOADED) {
      source = null;
      status = RecordElement.STATUS.NOT_LOADED;
      session = null;
      unsetDirty();
    }
  }

  @Override
  public boolean isUnloaded() {
    return status == RecordElement.STATUS.NOT_LOADED;
  }

  @Override
  public boolean isNotBound(DatabaseSession session) {
    return isUnloaded() || this.session != session;
  }

  @Nonnull
  public DatabaseSessionInternal getSession() {
    assert session != null && session.assertIfNotActive();

    if (session == null) {
      throw new DatabaseException(createNotBoundToSessionMessage());
    }

    return session;
  }

  @Nullable
  protected DatabaseSessionInternal getSessionIfDefined() {
    assert session == null || session.assertIfNotActive();
    return session;
  }

  public void save() {
    getSession().save(this);
  }

  public void save(final String iClusterName) {
    getSession().save(this, iClusterName);
  }

  public void delete() {
    getSession().delete(this);
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
    if (obj == null) {
      return false;
    }

    if (obj instanceof Identifiable) {
      return recordId.equals(((Identifiable) obj).getIdentity());
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

  public RecordElement.STATUS getInternalStatus() {
    return status;
  }

  @Override
  public boolean exists() {
    return getSession().exists(recordId);
  }

  public void setInternalStatus(final RecordElement.STATUS iStatus) {
    this.status = iStatus;
  }

  public RecordAbstract copyTo(final RecordAbstract cloned) {
    checkForBinding();

    if (cloned.dirty) {
      throw new DatabaseException("Cannot copy to dirty records");
    }

    cloned.source = source;
    cloned.size = size;
    cloned.recordId = recordId.copy();
    cloned.recordVersion = recordVersion;
    cloned.status = status;
    cloned.recordFormat = recordFormat;
    cloned.dirty = false;
    cloned.contentChanged = false;
    cloned.dirtyManager = null;
    cloned.session = session;

    return cloned;
  }

  protected RecordAbstract fill(
      final RID iRid, final int iVersion, final byte[] iBuffer, boolean iDirty) {
    if (dirty) {
      throw new DatabaseException("Cannot call fill() on dirty records");
    }

    recordId.setClusterId(iRid.getClusterId());
    recordId.setClusterPosition(iRid.getClusterPosition());
    recordVersion = iVersion;
    status = RecordElement.STATUS.LOADED;
    source = iBuffer;
    size = iBuffer != null ? iBuffer.length : 0;
    dirtyManager = null;

    if (source != null && source.length > 0) {
      dirty = iDirty;
      contentChanged = iDirty;
    }

    if (dirty) {
      getDirtyManager().setDirty(this);
    }

    return this;
  }

  protected RecordAbstract fill(
      final RID iRid,
      final int iVersion,
      final byte[] iBuffer,
      boolean iDirty,
      DatabaseSessionInternal db) {
    if (dirty) {
      throw new DatabaseException("Cannot call fill() on dirty records");
    }

    recordId.setClusterId(iRid.getClusterId());
    recordId.setClusterPosition(iRid.getClusterPosition());
    recordVersion = iVersion;
    status = RecordElement.STATUS.LOADED;
    source = iBuffer;
    size = iBuffer != null ? iBuffer.length : 0;
    dirtyManager = null;

    if (source != null && source.length > 0) {
      dirty = iDirty;
      contentChanged = iDirty;
    }

    if (dirty) {
      getDirtyManager().setDirty(this);
    }

    return this;
  }

  protected final RecordAbstract setIdentity(final int iClusterId, final long iClusterPosition) {
    if (recordId == null || recordId == ImmutableRecordId.EMPTY_RECORD_ID) {
      recordId = new RecordId(iClusterId, iClusterPosition);
    } else {
      recordId.setClusterId(iClusterId);
      recordId.setClusterPosition(iClusterPosition);
    }
    return this;
  }

  protected void unsetDirty() {
    contentChanged = false;
    dirty = false;
    dirtyManager = null;
  }

  protected abstract byte getRecordType();

  public void setup(DatabaseSessionInternal db) {
    if (recordId == null) {
      recordId = new ChangeableRecordId();
    }

    this.session = db;
  }

  protected void checkForBinding() {
    assert loadingCounter >= 0;
    if (loadingCounter > 0 || status == RecordElement.STATUS.UNMARSHALLING) {
      return;
    }

    if (status == RecordElement.STATUS.NOT_LOADED) {
      if (!recordId.isValid()) {
        return;
      }

      throw new DatabaseException(createNotBoundToSessionMessage());
    }

    assert session == null || session.assertIfNotActive();
  }

  private String createNotBoundToSessionMessage() {
    return "Record "
        + recordId
        + " is not bound to the current session. Please bind record to the database session"
        + " by calling : "
        + DatabaseSession.class.getSimpleName()
        + ".bindToSession(record) before using it.";
  }

  public void incrementLoading() {
    assert loadingCounter >= 0;
    loadingCounter++;
  }

  public void decrementLoading() {
    loadingCounter--;
    assert loadingCounter >= 0;
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

  protected DirtyManager getDirtyManager() {
    if (this.dirtyManager == null) {

      this.dirtyManager = new DirtyManager();
      if (this.recordId.isNew() && getOwner() == null) {
        this.dirtyManager.setDirty(this);
      }
    }
    return this.dirtyManager;
  }

  void setDirtyManager(DirtyManager dirtyManager) {
    checkForBinding();

    if (this.dirtyManager != null && dirtyManager != null) {
      dirtyManager.merge(this.dirtyManager);
    }
    this.dirtyManager = dirtyManager;
    if (this.recordId.isNew() && getOwner() == null && this.dirtyManager != null) {
      this.dirtyManager.setDirty(this);
    }
  }

  protected void track(Identifiable id) {
    this.getDirtyManager().track(this, id);
  }

  protected void unTrack(Identifiable id) {
    this.getDirtyManager().unTrack(this, id);
  }

  public void resetToNew() {
    if (!recordId.isNew()) {
      throw new IllegalStateException(
          "Record id is not new " + recordId + " as expected, so record can't be reset.");
    }

    reset();
  }

  public abstract RecordAbstract copy();

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
    return getSessionIfDefined();
  }
}
