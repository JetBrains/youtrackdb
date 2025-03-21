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
package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.serialization.Streamable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * Base interface for identifiable objects. This abstraction is required to use RID and Record in
 * many points.
 */
public class Placeholder implements Identifiable, Streamable {

  private RecordId rid;
  private int recordVersion;

  /**
   * Empty constructor used by serialization
   */
  public Placeholder() {
  }

  public Placeholder(final RecordId rid, final int version) {
    this.rid = rid;
    this.recordVersion = version;
  }

  public Placeholder(final RecordAbstract iRecord) {
    rid = iRecord.getIdentity().copy();
    recordVersion = iRecord.getVersion();
  }

  @Nonnull
  @Override
  public RID getIdentity() {
    return rid;
  }


  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof Placeholder other)) {
      return false;
    }

    return rid.equals(other.rid) && recordVersion == other.recordVersion;
  }

  @Override
  public int hashCode() {
    return rid.hashCode() + recordVersion;
  }

  @Override
  public int compareTo(Identifiable o) {
    return rid.compareTo(o);
  }

  public int getVersion() {
    return recordVersion;
  }

  @Override
  public String toString() {
    return rid.toString() + " v." + recordVersion;
  }

  @Override
  public void toStream(final DataOutput out) throws IOException {
    rid.toStream(out);
    out.writeInt(recordVersion);
  }

  @Override
  public void fromStream(final DataInput in) throws IOException {
    var rid = new ChangeableRecordId();
    rid.fromStream(in);

    this.rid = rid.copy();
    recordVersion = in.readInt();
  }
}
