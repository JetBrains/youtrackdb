/*
 *
 *
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
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import static com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_CLASS_NAME;
import static com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_INDEX_NAME;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 * Shared helpers for the {@code core/db/tool/importer} test cluster. The
 * importer-converter tests all need to register the export-import RID-mapping
 * schema and seed a single {@code from -> to} mapping; centralising that here
 * keeps the per-test setup down to a single call and ensures a future schema
 * tightening (e.g. renaming the {@code key} property) does not silently desync
 * across the cluster.
 */
final class ImporterTestFixtures {

  private ImporterTestFixtures() {
  }

  /**
   * Creates the {@code EXPORT_IMPORT_CLASS_NAME} schema with its unique index on
   * {@code key} and inserts a single mapping row {@code from -> to}. The schema
   * mutation runs outside a transaction (mandatory in YouTrackDB); the row
   * insert runs inside its own transaction.
   */
  static void setupRidMapping(DatabaseSessionEmbedded session, RID from, RID to) {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass(EXPORT_IMPORT_CLASS_NAME);
    cls.createProperty("key", PropertyType.STRING);
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex(EXPORT_IMPORT_INDEX_NAME, INDEX_TYPE.UNIQUE, "key");

    session.executeInTx(tx -> {
      var mapping = (EntityImpl) tx.newEntity(EXPORT_IMPORT_CLASS_NAME);
      mapping.setProperty("key", from.toString());
      mapping.setProperty("value", to.toString());
    });
  }
}
