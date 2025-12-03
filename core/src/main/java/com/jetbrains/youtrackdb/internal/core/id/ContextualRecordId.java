/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrackdb.internal.core.id;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

public final class ContextualRecordId implements RecordIdInternal {

  private Map<String, Object> context;
  private final RecordIdInternal delegate;

  private ContextualRecordId(final RecordIdInternal delegate, Map<String, Object> context) {
    this.delegate = delegate;
    this.context = context;
  }

  public ContextualRecordId(final String recordIdStr) {
    delegate = RecordIdInternal.fromString(recordIdStr, false);
  }

  public ContextualRecordId setContext(final Map<String, Object> context) {
    this.context = context;
    return this;
  }

  public Map<String, Object> getContext() {
    return context;
  }

  @Override
  public boolean isValidPosition() {
    return delegate.isValidPosition();
  }

  @Override
  public boolean isPersistent() {
    return delegate.isPersistent();
  }

  @Override
  public boolean isNew() {
    return delegate.isNew();
  }

  @Override
  public boolean isTemporary() {
    return delegate.isTemporary();
  }

  @Override
  public StringBuilder toString(StringBuilder stringBuilder) {
    return delegate.toString(stringBuilder);
  }

  @Override
  public RecordIdInternal copy() {
    return new ContextualRecordId(delegate.copy(), new HashMap<>(context));
  }

  @Override
  public void toStream(DataOutput out) throws IOException {
    delegate.toStream(out);
  }

  @Override
  public int toStream(OutputStream iStream) throws IOException {
    return delegate.toStream(iStream);
  }

  @Override
  public int toStream(MemoryStream iStream) throws IOException {
    return delegate.toStream(iStream);
  }

  @Override
  public byte[] toStream() {
    return delegate.toStream();
  }

  @Override
  public int getCollectionId() {
    return delegate.getCollectionId();
  }

  @Override
  public long getCollectionPosition() {
    return delegate.getCollectionPosition();
  }

  @Override
  public String next() {
    return delegate.next();
  }

  @Override
  public int compareTo(@Nonnull Identifiable o) {
    return delegate.compareTo(o);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public boolean equals(Object obj) {
    return delegate.equals(obj);
  }

  @Override
  public String toString() {
    return delegate.toString();
  }
}
