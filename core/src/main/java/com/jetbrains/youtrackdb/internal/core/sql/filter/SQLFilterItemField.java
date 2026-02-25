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
package com.jetbrains.youtrackdb.internal.core.sql.filter;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.parser.BaseParser;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BytesContainer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodField;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Represent an object field as value in the query condition.
 */
public class SQLFilterItemField extends SQLFilterItemAbstract {

  protected Set<String> preLoadedFields;
  protected String[] preLoadedFieldsArray;
  protected String name;
  protected Collate collate;
  private boolean collatePreset = false;
  private String stringValue;

  /**
   * Represents filter item as chain of fields. Provide interface to work with this chain like with
   * sequence of field names.
   */
  public class FieldChain {

    private FieldChain() {
    }

    public String getItemName(int fieldIndex) {
      if (fieldIndex == 0) {
        return name;
      } else {
        return operationsChain.get(fieldIndex - 1).getValue()[0].toString();
      }
    }

    public int getItemCount() {
      if (operationsChain == null) {
        return 1;
      } else {
        return operationsChain.size() + 1;
      }
    }

    /**
     * Field chain is considered as long chain if it contains more than one item.
     *
     * @return true if this chain is long and false in another case.
     */
    public boolean isLong() {
      return operationsChain != null && operationsChain.size() > 0;
    }

    public boolean belongsTo(SQLFilterItemField filterItemField) {
      return SQLFilterItemField.this == filterItemField;
    }
  }

  public SQLFilterItemField(DatabaseSessionEmbedded db, final String iName,
      final SchemaClass iClass) {
    this.name = IOUtils.getStringContent(iName);
    collate = getCollateForField(db, iClass, name);
    if (iClass != null) {
      collatePreset = true;
    }
  }

  public SQLFilterItemField(
      DatabaseSessionEmbedded session, final BaseParser iQueryToParse, final String iName,
      final SchemaClass iClass) {
    super(session, iQueryToParse, iName);
    collate = getCollateForField(session, iClass, iName);
    if (iClass != null) {
      collatePreset = true;
    }
  }

  @Override
  public Object getValue(
      final Result iRecord, final Object iCurrentResult, final CommandContext iContext) {
    var session = iContext.getDatabaseSession();
    if (iRecord == null) {
      throw new CommandExecutionException(session,
          "expression item '" + name + "' cannot be resolved because current record is NULL");
    }

    if (preLoadedFields != null && preLoadedFields.size() == 1) {
      if ("@rid".equalsIgnoreCase(preLoadedFields.iterator().next())) {
        return iRecord.getIdentity();
      }
    }

    if (preLoadedFieldsArray == null
        && preLoadedFields != null
        && !preLoadedFields.isEmpty()
        && preLoadedFields.size() < 5) {
      // TRANSFORM THE SET IN ARRAY ONLY THE FIRST TIME AND IF FIELDS ARE MORE THAN ONE, OTHERWISE
      // GO WITH THE DEFAULT BEHAVIOR
      preLoadedFieldsArray = new String[preLoadedFields.size()];
      preLoadedFields.toArray(preLoadedFieldsArray);
    }

    final var v = stringValue == null ? iRecord.getProperty(name) : stringValue;
    if (!collatePreset && iRecord.isEntity()) {
      var entity = (EntityImpl) iRecord.asEntity();
      SchemaImmutableClass schemaClassResult = entity.getImmutableSchemaClass(session);
      SchemaClass schemaClass = schemaClassResult;
      if (schemaClass != null) {
        collate = getCollateForField(session, schemaClass, name);
      }
    }

    return transformValue(iRecord, iContext, v);
  }

  @Nullable
  public BinaryField getBinaryField(DatabaseSessionEmbedded session, final Identifiable iRecord) {
    if (iRecord == null) {
      throw new CommandExecutionException(session,
          "expression item '" + name + "' cannot be resolved because current record is NULL");
    }

    if (operationsChain != null && !operationsChain.isEmpty())
    // CANNOT USE BINARY FIELDS
    {
      return null;
    }

    var transaction = session.getActiveTransaction();
    final EntityImpl rec = transaction.load(iRecord);
    var encryption = rec.propertyEncryption;
    var serialized = new BytesContainer(rec.toStream());
    var version = serialized.bytes[serialized.offset++];
    var serializer = RecordSerializerBinary.INSTANCE.getSerializer(version);

    // check for embedded objects, they have invalid ID and they are serialized with class name
    SchemaImmutableClass result = null;
    if (rec != null) {
      result = rec.getImmutableSchemaClass(session);
    }
    return serializer.deserializeField(session,
        serialized,
        result,
        name,
        rec.isEmbedded(),
        session.getMetadata().getImmutableSchemaSnapshot(), encryption);
  }

  @Override
  public String getRoot(DatabaseSessionEmbedded session) {
    return name;
  }

  @Override
  public void setRoot(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iRoot) {
    if (isStringLiteral(iRoot)) {
      this.stringValue = IOUtils.getStringContent(iRoot);
    }
    // TODO support all the basic types
    this.name = IOUtils.getStringContent(iRoot);
  }

  private static boolean isStringLiteral(String iRoot) {
    if (iRoot.startsWith("'") && iRoot.endsWith("'")) {
      return true;
    }
    return iRoot.startsWith("\"") && iRoot.endsWith("\"");
  }

  /**
   * Check whether or not this filter item is chain of fields (e.g. "field1.field2.field3"). Return
   * true if filter item contains only field projections operators, if field item contains any other
   * projection operator the method returns false. When filter item does not contains any chain
   * operator, it is also field chain consist of one field.
   *
   * @return whether or not this filter item can be represented as chain of fields.
   */
  public boolean isFieldChain() {
    if (operationsChain == null) {
      return true;
    }

    for (var pair : operationsChain) {
      if (!pair.getKey().getMethod().getName().equals(SQLMethodField.NAME)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Creates {@code FieldChain} in case when filter item can have such representation.
   *
   * @return {@code FieldChain} representation of this filter item.
   * @throws IllegalStateException if this filter item cannot be represented as {@code FieldChain}.
   */
  public FieldChain getFieldChain() {
    if (!isFieldChain()) {
      throw new IllegalStateException("Filter item field contains not only field operators");
    }

    return new FieldChain();
  }

  public void setPreLoadedFields(final Set<String> iPrefetchedFieldList) {
    this.preLoadedFields = iPrefetchedFieldList;
  }

  public Collate getCollate() {
    return collate;
  }

  /**
   * get the collate of this expression, based on the fully evaluated field chain starting from the
   * passed object.
   *
   * @param session the active database session
   * @param object  the root element (entity?) of this field chain
   * @return the collate, null if no collate is defined
   */
  @Nullable
  public Collate getCollate(DatabaseSessionEmbedded session, Object object) {
    if (collate != null || operationsChain == null || !isFieldChain()) {
      return collate;
    }
    if (!(object instanceof Identifiable identifiable)) {
      return null;
    }
    var chain = getFieldChain();
    try {
      var transaction1 = session.getActiveTransaction();
      EntityImpl lastDoc = transaction1.load(identifiable);
      for (var i = 0; i < chain.getItemCount() - 1; i++) {
        var nextDoc = lastDoc.getProperty(chain.getItemName(i));
        if (!(nextDoc instanceof Identifiable nextIdentifiable)) {
          return null;
        }
        var transaction = session.getActiveTransaction();
        lastDoc = transaction.load(nextIdentifiable);
      }
      var schemaClass = lastDoc.getImmutableSchemaClass(session);
      if (schemaClass == null) {
        return null;
      }
      var property = schemaClass.getProperty(
          chain.getItemName(chain.getItemCount() - 1));
      if (property == null) {
        return null;
      }
      return property.getCollate();
    } catch (RecordNotFoundException rnf) {
      return null;
    }
  }
}
