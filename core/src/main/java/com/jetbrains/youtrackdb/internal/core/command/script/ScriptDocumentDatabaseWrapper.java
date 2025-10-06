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
package com.jetbrains.youtrackdb.internal.core.command.script;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession.STATUS;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Document Database wrapper class to use from scripts.
 */
@Deprecated
public class ScriptDocumentDatabaseWrapper {

  protected DatabaseSessionInternal session;

  public ScriptDocumentDatabaseWrapper(final DatabaseSessionInternal session) {
    this.session = session;
  }

  public Map<?, ?>[] query(final String iText) {
    return query(iText, (Object[]) null);
  }

  public Map<?, ?>[] query(final String iText, final Object... iParameters) {
    try (var rs = session.query(iText, iParameters)) {
      return (Map<?, ?>[]) rs.stream().map(Result::toMap).toArray();
    }
  }

  /**
   * To maintain the compatibility with JS API.
   */
  public Object executeCommand(final String iText) {
    return command(iText, (Object[]) null);
  }

  /**
   * To maintain the compatibility with JS API.
   */
  public Object executeCommand(final String iText, final Object... iParameters) {
    return command(iText, iParameters);
  }

  public Object command(final String iText) {
    return command(iText, (Object[]) null);
  }

  public Object command(final String iText, final Object... iParameters) {
    try (var rs = session.execute(iText, iParameters)) {
      return rs.stream().map(Result::toMap).toArray();
    }
  }

  public Index getIndex(final String name) {
    return session.getMetadata().getFastImmutableSchema().getIndex(name);
  }

  public EntityImpl newInstance() {
    return session.newInstance();
  }

  public void reload() {
    session.reload();
  }

  public Entity newInstance(String iClassName) {
    return session.newInstance(iClassName);
  }

  public RecordIteratorClass browseClass(String iClassName) {
    return session.browseClass(iClassName);
  }

  public STATUS getStatus() {
    return session.getStatus();
  }

  public RecordIteratorClass browseClass(String iClassName, boolean iPolymorphic) {
    return session.browseClass(iClassName, iPolymorphic);
  }


  public String getName() {
    return session.getDatabaseName();
  }

  public String getURL() {
    return session.getURL();
  }

  public RecordIteratorCollection<EntityImpl> browseCollection(String iCollectionName) {
    return session.browseCollection(iCollectionName);
  }

  public boolean isClosed() {
    return session.isClosed();
  }


  public EntityImpl save(final Map<String, Object> iObject) {
    var entity = session.newInstance();
    entity.updateFromMap(iObject);
    return entity;
  }

  public Entity save(final String iString) {
    return session.createOrLoadEntityFromJson(iString);
  }

  public void close() {
    session.close();
  }

  public int getCollections() {
    return session.getCollections();
  }

  public Collection<String> getCollectionNames() {
    return session.getCollectionNames();
  }

  public FrontendTransaction getTransaction() {
    return session.getTransactionInternal();
  }

  public void begin() {
    session.begin();
  }

  public int getCollectionIdByName(String iCollectionName) {
    return session.getCollectionIdByName(iCollectionName);
  }

  public boolean isMVCC() {
    return session.isMVCC();
  }

  public String getCollectionNameById(int iCollectionId) {
    return session.getCollectionNameById(iCollectionId);
  }

  public DatabaseSession setMVCC(boolean iValue) {
    return session.setMVCC(iValue);
  }

  public boolean isValidationEnabled() {
    return session.isValidationEnabled();
  }

  public SecurityUser getUser() {
    return session.getCurrentUser();
  }

  public void setUser(SecurityUserImpl user) {
    session.setUser(user);
  }

  public SessionMetadata getMetadata() {
    return session.getMetadata();
  }

  public byte getRecordType() {
    return session.getRecordType();
  }

  public <RET extends DBRecord> RET load(RID iRecordId) {
    return session.load(iRecordId);
  }

  public <RET extends DBRecord> RET load(final String iRidAsString) {
    return session.load(RecordIdInternal.fromString(iRidAsString, false));
  }

  public Object setProperty(String iName, Object iValue) {
    return session.setProperty(iName, iValue);
  }

  public Object getProperty(String iName) {
    return session.getProperty(iName);
  }

  public Iterator<Entry<String, Object>> getProperties() {
    return session.getProperties();
  }

  public Object get(ATTRIBUTES iAttribute) {
    return session.get(iAttribute);
  }

  public void set(ATTRIBUTES attribute, Object iValue) {
    session.set(attribute, iValue);
  }

  public void setInternal(ATTRIBUTES attribute, Object iValue) {
    session.setInternal(attribute, iValue);
  }

  public boolean isRetainRecords() {
    return session.isRetainRecords();
  }

  public DatabaseSession setRetainRecords(boolean iValue) {
    return session.setRetainRecords(iValue);
  }

  public long getSize() {
    return session.getSize();
  }

  public void delete(EntityImpl iRecord) {
    session.delete(iRecord);
  }

  public long countClass(String iClassName) {
    return session.countClass(iClassName);
  }

  public void commit() {
    session.commit();
  }

  public void rollback() {
    session.rollback();
  }
}
