/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.jdbc;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 *
 */
public class YouTrackDbJdbcDatabaseMetaData implements DatabaseMetaData {

  protected static final List<String> TABLE_TYPES = Arrays.asList("TABLE", "SYSTEM TABLE");
  private final YouTrackDbJdbcConnection connection;
  private final DatabaseSessionInternal session;

  public YouTrackDbJdbcDatabaseMetaData(
      YouTrackDbJdbcConnection iConnection, DatabaseSessionInternal iDatabase) {
    connection = iConnection;
    session = iDatabase;
  }

  public boolean allProceduresAreCallable() throws SQLException {
    return true;
  }

  public boolean allTablesAreSelectable() throws SQLException {
    return true;
  }

  public String getURL() throws SQLException {
    return session.getURL();
  }

  public String getUserName() throws SQLException {
    return session.geCurrentUser().getName(session);
  }

  public boolean isReadOnly() throws SQLException {

    return false;
  }

  public boolean nullsAreSortedHigh() throws SQLException {

    return false;
  }

  public boolean nullsAreSortedLow() throws SQLException {

    return false;
  }

  public boolean nullsAreSortedAtStart() throws SQLException {

    return false;
  }

  public boolean nullsAreSortedAtEnd() throws SQLException {

    return false;
  }

  public String getDatabaseProductName() throws SQLException {
    return "YouTrackDB";
  }

  public String getDatabaseProductVersion() throws SQLException {
    return YouTrackDBConstants.getVersion();
  }

  public String getDriverName() throws SQLException {
    return "YouTrackDB JDBC Driver";
  }

  public String getDriverVersion() throws SQLException {
    return YouTrackDbJdbcDriver.getVersion();
  }

  public int getDriverMajorVersion() {
    return YouTrackDBConstants.getVersionMajor();
  }

  public int getDriverMinorVersion() {
    return YouTrackDBConstants.getVersionMinor();
  }

  public boolean usesLocalFiles() throws SQLException {

    return false;
  }

  public boolean usesLocalFilePerTable() throws SQLException {

    return false;
  }

  public boolean supportsMixedCaseIdentifiers() throws SQLException {

    return false;
  }

  public boolean storesUpperCaseIdentifiers() throws SQLException {

    return false;
  }

  public boolean storesLowerCaseIdentifiers() throws SQLException {

    return false;
  }

  public boolean storesMixedCaseIdentifiers() throws SQLException {

    return false;
  }

  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {

    return false;
  }

  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {

    return false;
  }

  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {

    return false;
  }

  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {

    return false;
  }

  public String getIdentifierQuoteString() throws SQLException {
    return " ";
  }

  public String getSQLKeywords() throws SQLException {
    return "@rid,@class,@version,@size,@type,@this,CONTAINS,CONTAINSALL,CONTAINSKEY,"
        + "CONTAINSVALUE,CONTAINSTEXT,MATCHES,TRAVERSE";
  }

  public String getNumericFunctions() throws SQLException {

    return null;
  }

  public String getStringFunctions() throws SQLException {

    return "";
  }

  public String getSystemFunctions() throws SQLException {

    return "";
  }

  public String getTimeDateFunctions() throws SQLException {
    return "date,sysdate";
  }

  public String getSearchStringEscape() throws SQLException {

    return null;
  }

  public String getExtraNameCharacters() throws SQLException {
    return null;
  }

  public boolean supportsAlterTableWithAddColumn() throws SQLException {

    return false;
  }

  public boolean supportsAlterTableWithDropColumn() throws SQLException {

    return false;
  }

  public boolean supportsColumnAliasing() throws SQLException {

    return false;
  }

  public boolean nullPlusNonNullIsNull() throws SQLException {

    return false;
  }

  public boolean supportsConvert() throws SQLException {

    return false;
  }

  public boolean supportsConvert(int fromType, int toType) throws SQLException {

    return false;
  }

  public boolean supportsTableCorrelationNames() throws SQLException {

    return false;
  }

  public boolean supportsDifferentTableCorrelationNames() throws SQLException {

    return false;
  }

  public boolean supportsExpressionsInOrderBy() throws SQLException {

    return false;
  }

  public boolean supportsOrderByUnrelated() throws SQLException {

    return false;
  }

  public boolean supportsGroupBy() throws SQLException {

    return true;
  }

  public boolean supportsGroupByUnrelated() throws SQLException {

    return false;
  }

  public boolean supportsGroupByBeyondSelect() throws SQLException {

    return false;
  }

  public boolean supportsLikeEscapeClause() throws SQLException {

    return false;
  }

  public boolean supportsMultipleResultSets() throws SQLException {

    return false;
  }

  public boolean supportsMultipleTransactions() throws SQLException {

    return true;
  }

  public boolean supportsNonNullableColumns() throws SQLException {

    return true;
  }

  public boolean supportsMinimumSQLGrammar() throws SQLException {

    return false;
  }

  public boolean supportsCoreSQLGrammar() throws SQLException {

    return false;
  }

  public boolean supportsExtendedSQLGrammar() throws SQLException {

    return false;
  }

  public boolean supportsANSI92EntryLevelSQL() throws SQLException {

    return false;
  }

  public boolean supportsANSI92IntermediateSQL() throws SQLException {

    return false;
  }

  public boolean supportsANSI92FullSQL() throws SQLException {

    return false;
  }

  public boolean supportsIntegrityEnhancementFacility() throws SQLException {

    return false;
  }

  public boolean supportsOuterJoins() throws SQLException {

    return false;
  }

  public boolean supportsFullOuterJoins() throws SQLException {

    return false;
  }

  public boolean supportsLimitedOuterJoins() throws SQLException {

    return false;
  }

  public String getSchemaTerm() throws SQLException {

    return null;
  }

  public String getProcedureTerm() throws SQLException {
    return "Function";
  }

  public String getCatalogTerm() throws SQLException {

    return null;
  }

  public boolean isCatalogAtStart() throws SQLException {

    return false;
  }

  public String getCatalogSeparator() throws SQLException {

    return null;
  }

  public boolean supportsSchemasInDataManipulation() throws SQLException {

    return false;
  }

  public boolean supportsSchemasInProcedureCalls() throws SQLException {

    return false;
  }

  public boolean supportsSchemasInTableDefinitions() throws SQLException {

    return false;
  }

  public boolean supportsSchemasInIndexDefinitions() throws SQLException {

    return false;
  }

  public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {

    return false;
  }

  public boolean supportsCatalogsInDataManipulation() throws SQLException {

    return false;
  }

  public boolean supportsCatalogsInProcedureCalls() throws SQLException {

    return false;
  }

  public boolean supportsCatalogsInTableDefinitions() throws SQLException {

    return false;
  }

  public boolean supportsCatalogsInIndexDefinitions() throws SQLException {

    return false;
  }

  public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {

    return false;
  }

  public boolean supportsPositionedDelete() throws SQLException {

    return false;
  }

  public boolean supportsPositionedUpdate() throws SQLException {

    return false;
  }

  public boolean supportsSelectForUpdate() throws SQLException {

    return false;
  }

  public boolean supportsStoredProcedures() throws SQLException {

    return true;
  }

  public boolean supportsSubqueriesInComparisons() throws SQLException {

    return false;
  }

  public boolean supportsSubqueriesInExists() throws SQLException {

    return false;
  }

  public boolean supportsSubqueriesInIns() throws SQLException {

    return true;
  }

  public boolean supportsSubqueriesInQuantifieds() throws SQLException {

    return false;
  }

  public boolean supportsCorrelatedSubqueries() throws SQLException {

    return false;
  }

  public boolean supportsUnion() throws SQLException {

    return true;
  }

  public boolean supportsUnionAll() throws SQLException {

    return false;
  }

  public boolean supportsOpenCursorsAcrossCommit() throws SQLException {

    return false;
  }

  public boolean supportsOpenCursorsAcrossRollback() throws SQLException {

    return false;
  }

  public boolean supportsOpenStatementsAcrossCommit() throws SQLException {

    return false;
  }

  public boolean supportsOpenStatementsAcrossRollback() throws SQLException {

    return false;
  }

  public int getMaxBinaryLiteralLength() throws SQLException {

    return 0;
  }

  public int getMaxCharLiteralLength() throws SQLException {

    return 0;
  }

  public int getMaxColumnNameLength() throws SQLException {

    return 0;
  }

  public int getMaxColumnsInGroupBy() throws SQLException {

    return 0;
  }

  public int getMaxColumnsInIndex() throws SQLException {

    return 0;
  }

  public int getMaxColumnsInOrderBy() throws SQLException {

    return 0;
  }

  public int getMaxColumnsInSelect() throws SQLException {

    return 0;
  }

  public int getMaxColumnsInTable() throws SQLException {

    return 0;
  }

  public int getMaxConnections() throws SQLException {

    return 0;
  }

  public int getMaxCursorNameLength() throws SQLException {

    return 0;
  }

  public int getMaxIndexLength() throws SQLException {

    return 0;
  }

  public int getMaxSchemaNameLength() throws SQLException {
    return 0;
  }

  public int getMaxProcedureNameLength() throws SQLException {

    return 0;
  }

  public int getMaxCatalogNameLength() throws SQLException {

    return 0;
  }

  public int getMaxRowSize() throws SQLException {
    return 0;
  }

  public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {

    return false;
  }

  public int getMaxStatementLength() throws SQLException {
    return 0;
  }

  public int getMaxStatements() throws SQLException {
    return 0;
  }

  public int getMaxTableNameLength() throws SQLException {
    return 1024;
  }

  public int getMaxTablesInSelect() throws SQLException {
    return 1;
  }

  public int getMaxUserNameLength() throws SQLException {

    return 0;
  }

  public int getDefaultTransactionIsolation() throws SQLException {
    return java.sql.Connection.TRANSACTION_NONE;
  }

  public boolean supportsTransactions() throws SQLException {

    return true;
  }

  public boolean supportsTransactionIsolationLevel(int level) throws SQLException {

    return false;
  }

  public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {

    return false;
  }

  public boolean supportsDataManipulationTransactionsOnly() throws SQLException {

    return false;
  }

  public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
    return false;
  }

  public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
    return true;
  }

  public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
      throws SQLException {
    final var resultSet = new InternalResultSet(session);

    var functionLibrary = session.getMetadata().getFunctionLibrary();

    for (var functionName : functionLibrary.getFunctionNames()) {

      if (YouTrackDbJdbcUtils.like(functionName, procedureNamePattern)) {
        var element = new ResultInternal(session);
        element.setProperty("PROCEDURE_CAT", null);
        element.setProperty("PROCEDURE_SCHEM", null);
        element.setProperty("PROCEDURE_NAME", functionName);
        element.setProperty("REMARKS", "");
        element.setProperty("PROCEDURE_TYPE", procedureResultUnknown);
        element.setProperty("SPECIFIC_NAME", functionName);

        resultSet.add(element);
      }
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getProcedureColumns(
      String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern)
      throws SQLException {
    final var resultSet = new InternalResultSet(session);
    var functionLibrary = session.getMetadata().getFunctionLibrary();

    for (var functionName : functionLibrary.getFunctionNames()) {

      if (YouTrackDbJdbcUtils.like(functionName, procedureNamePattern)) {

        final var f = functionLibrary.getFunction(session, procedureNamePattern);

        for (var p : f.getParameters()) {
          final var entity = new ResultInternal(session);
          entity.setProperty("PROCEDURE_CAT", session.getDatabaseName());
          entity.setProperty("PROCEDURE_SCHEM", session.getDatabaseName());
          entity.setProperty("PROCEDURE_NAME", f.getName());
          entity.setProperty("COLUMN_NAME", p);
          entity.setProperty("COLUMN_TYPE", procedureColumnIn);
          entity.setProperty("DATA_TYPE", java.sql.Types.OTHER);
          entity.setProperty("SPECIFIC_NAME", f.getName());

          resultSet.add(entity);
        }

        final var entity = new ResultInternal(session);

        entity.setProperty("PROCEDURE_CAT", session.getDatabaseName());
        entity.setProperty("PROCEDURE_SCHEM", session.getDatabaseName());
        entity.setProperty("PROCEDURE_NAME", f.getName());
        entity.setProperty("COLUMN_NAME", "return");
        entity.setProperty("COLUMN_TYPE", procedureColumnReturn);
        entity.setProperty("DATA_TYPE", java.sql.Types.OTHER);
        entity.setProperty("SPECIFIC_NAME", f.getName());

        resultSet.add(entity);
      }
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  @Override
  public ResultSet getTables(
      String catalog, String schemaPattern, String tableNamePattern, String[] types)
      throws SQLException {
    final var classes = session.getMetadata().getSchema().getClasses();

    var resultSet = new InternalResultSet(session);

    final List tableTypes = types != null ? Arrays.asList(types) : TABLE_TYPES;
    for (var cls : classes) {
      final var className = cls.getName();
      final String type;

      if (MetadataInternal.SYSTEM_CLUSTER.contains(
          cls.getName().toLowerCase(Locale.ENGLISH))) {
        type = "SYSTEM TABLE";
      } else {
        type = "TABLE";
      }

      if (tableTypes.contains(type)
          && (tableNamePattern == null
          || tableNamePattern.equals("%")
          || tableNamePattern.equalsIgnoreCase(className))) {

        var entity = new ResultInternal(session);

        entity.setProperty("TABLE_CAT", session.getDatabaseName());
        entity.setProperty("TABLE_SCHEM", session.getDatabaseName());
        entity.setProperty("TABLE_NAME", className);
        entity.setProperty("TABLE_TYPE", type);
        entity.setProperty("REMARKS", null);
        entity.setProperty("TYPE_NAME", null);
        entity.setProperty("REF_GENERATION", null);
        resultSet.add(entity);
      }
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  @Override
  public ResultSet getSchemas() throws SQLException {
    var resultSet = new InternalResultSet(session);

    final var field = new ResultInternal(session);
    field.setProperty("TABLE_SCHEM", session.getDatabaseName());
    field.setProperty("TABLE_CATALOG", session.getDatabaseName());

    resultSet.add(field);

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getCatalogs() throws SQLException {
    var resultSet = new InternalResultSet(session);

    final var field = new ResultInternal(session);
    field.setProperty("TABLE_CAT", session.getDatabaseName());

    resultSet.add(field);

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  @Override
  public ResultSet getTableTypes() throws SQLException {
    var resultSet = new InternalResultSet(session);
    for (var tableType : TABLE_TYPES) {
      final var field = new ResultInternal(session);
      field.setProperty("TABLE_TYPE", tableType);
      resultSet.add(field);
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  @Override
  public ResultSet getColumns(
      final String catalog,
      final String schemaPattern,
      final String tableNamePattern,
      final String columnNamePattern)
      throws SQLException {
    final var resultSet = new InternalResultSet(session);
    final Schema schema = session.getMetadata().getImmutableSchemaSnapshot();
    for (var clazz : schema.getClasses()) {
      if (YouTrackDbJdbcUtils.like(clazz.getName(), tableNamePattern)) {
        for (var prop : clazz.properties()) {
          if (columnNamePattern == null) {
            resultSet.add(getPropertyAsDocument(clazz, prop));
          } else {
            if (YouTrackDbJdbcUtils.like(prop.getName(), columnNamePattern)) {
              resultSet.add(getPropertyAsDocument(clazz, prop));
            }
          }
        }
      }
    }
    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getColumnPrivileges(
      final String catalog, final String schema, final String table, final String columnNamePattern)
      throws SQLException {
    return getEmptyResultSet();
  }

  public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
      throws SQLException {

    return getEmptyResultSet();
  }

  public ResultSet getBestRowIdentifier(
      String catalog, String schema, String table, int scope, boolean nullable)
      throws SQLException {

    return getEmptyResultSet();
  }

  public ResultSet getVersionColumns(String catalog, String schema, String table)
      throws SQLException {

    return getEmptyResultSet();
  }

  @Override
  public ResultSet getPrimaryKeys(final String catalog, final String schema, final String table)
      throws SQLException {
    final var classIndexes =
        session.getMetadata().getIndexManagerInternal().getClassIndexes(session, table);

    final Set<Index> uniqueIndexes = new HashSet<>();

    for (var index : classIndexes) {
      if (index.getType().equals(INDEX_TYPE.UNIQUE.name())) {
        uniqueIndexes.add(index);
      }
    }

    final var resultSet = new InternalResultSet(session);

    for (var unique : uniqueIndexes) {
      var keyFiledSeq = 1;
      for (var keyFieldName : unique.getDefinition().getFields()) {
        final var res = new ResultInternal(session);
        res.setProperty("TABLE_CAT", catalog);
        res.setProperty("TABLE_SCHEM", catalog);
        res.setProperty("TABLE_NAME", table);
        res.setProperty("COLUMN_NAME", keyFieldName);
        res.setProperty("KEY_SEQ", keyFiledSeq);
        res.setProperty("PK_NAME", unique.getName());
        keyFiledSeq++;

        resultSet.add(res);
      }
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getImportedKeys(String catalog, String schema, String table)
      throws SQLException {
    var aClass = session.getMetadata().getSchema().getClass(table);

    aClass.declaredProperties().stream().forEach(p -> p.getType());
    return getEmptyResultSet();
  }

  private ResultSet getEmptyResultSet() throws SQLException {
    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, new InternalResultSet(session),
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getExportedKeys(String catalog, String schema, String table)
      throws SQLException {

    return getEmptyResultSet();
  }

  public ResultSet getCrossReference(
      String parentCatalog,
      String parentSchema,
      String parentTable,
      String foreignCatalog,
      String foreignSchema,
      String foreignTable)
      throws SQLException {

    return getEmptyResultSet();
  }

  public ResultSet getTypeInfo() throws SQLException {
    final var resultSet = new InternalResultSet(session);

    var res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.BINARY.toString());
    res.setProperty("DATA_TYPE", Types.BINARY);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.BOOLEAN.toString());
    res.setProperty("DATA_TYPE", Types.BOOLEAN);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.BYTE.toString());
    res.setProperty("DATA_TYPE", Types.TINYINT);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.DATE.toString());
    res.setProperty("DATA_TYPE", Types.DATE);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.DATETIME.toString());
    res.setProperty("DATA_TYPE", Types.DATE);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.DECIMAL.toString());
    res.setProperty("DATA_TYPE", Types.DECIMAL);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", false);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.FLOAT.toString());
    res.setProperty("DATA_TYPE", Types.FLOAT);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", false);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.DOUBLE.toString());
    res.setProperty("DATA_TYPE", Types.DOUBLE);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", false);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.EMBEDDED.toString());
    res.setProperty("DATA_TYPE", Types.STRUCT);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.EMBEDDEDLIST.toString());
    res.setProperty("DATA_TYPE", Types.ARRAY);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.INTEGER.toString());
    res.setProperty("DATA_TYPE", Types.INTEGER);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", false);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.LINKLIST.toString());
    res.setProperty("DATA_TYPE", Types.ARRAY);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.LONG.toString());
    res.setProperty("DATA_TYPE", Types.BIGINT);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", false);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.STRING.toString());
    res.setProperty("DATA_TYPE", Types.VARCHAR);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    res = new ResultInternal(session);
    res.setProperty("TYPE_NAME", PropertyType.SHORT.toString());
    res.setProperty("DATA_TYPE", Types.SMALLINT);
    res.setProperty("NULLABLE", DatabaseMetaData.typeNullable);
    res.setProperty("CASE_SENSITIVE", true);
    res.setProperty("UNSIGNED_ATTRIBUTE", false);
    res.setProperty("SEARCHABLE", true);
    resultSet.add(res);

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  @Override
  public ResultSet getIndexInfo(
      String catalog, String schema, String table, boolean unique, boolean approximate)
      throws SQLException {
    var metadata = session.getMetadata();
    if (!approximate) {
      metadata.getIndexManagerInternal().reload(session);
    }

    final var classIndexes =
        metadata.getIndexManagerInternal().getClassIndexes(session, table);

    final Set<Index> indexes = new HashSet<>();

    for (var index : classIndexes) {
      if (!unique || index.getType().equals(INDEX_TYPE.UNIQUE.name())) {
        indexes.add(index);
      }
    }

    final var resultSet = new InternalResultSet(session);
    for (var idx : indexes) {
      var notUniqueIndex = !(idx.getType().equals(INDEX_TYPE.UNIQUE.name()));

      final var fieldNames = idx.getDefinition().getFields().toString();

      var res = new ResultInternal(session);
      res.setProperty("TABLE_CAT", catalog);
      res.setProperty("TABLE_SCHEM", schema);
      res.setProperty("TABLE_NAME", table);
      res.setProperty("NON_UNIQUE", notUniqueIndex);
      res.setProperty("INDEX_QUALIFIER", null);
      res.setProperty("INDEX_NAME", idx.getName());
      res.setProperty("TYPE", idx.getType());
      res.setProperty("ORDINAL_POSITION", 0);
      res.setProperty("COLUMN_NAME", fieldNames.substring(1, fieldNames.length() - 1));
      res.setProperty("ASC_OR_DESC", "ASC");

      resultSet.add(res);
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public boolean supportsResultSetType(int type) throws SQLException {

    return false;
  }

  public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {

    return false;
  }

  public boolean ownUpdatesAreVisible(int type) throws SQLException {

    return false;
  }

  public boolean ownDeletesAreVisible(int type) throws SQLException {

    return false;
  }

  public boolean ownInsertsAreVisible(int type) throws SQLException {

    return false;
  }

  public boolean othersUpdatesAreVisible(int type) throws SQLException {

    return false;
  }

  public boolean othersDeletesAreVisible(int type) throws SQLException {

    return false;
  }

  public boolean othersInsertsAreVisible(int type) throws SQLException {

    return false;
  }

  public boolean updatesAreDetected(int type) throws SQLException {

    return false;
  }

  public boolean deletesAreDetected(int type) throws SQLException {
    return false;
  }

  public boolean insertsAreDetected(int type) throws SQLException {

    return false;
  }

  public boolean supportsBatchUpdates() throws SQLException {

    return false;
  }

  public ResultSet getUDTs(
      String catalog, String schemaPattern, String typeNamePattern, int[] types)
      throws SQLException {
    final var classes = session.getMetadata().getSchema().getClasses();

    var resultSet = new InternalResultSet(session);
    for (var cls : classes) {
      final var res = new ResultInternal(session);
      res.setProperty("TYPE_CAT", null);
      res.setProperty("TYPE_SCHEM", null);
      res.setProperty("TYPE_NAME", cls.getName());
      res.setProperty("CLASS_NAME", cls.getName());
      res.setProperty("DATA_TYPE", java.sql.Types.STRUCT);
      res.setProperty("REMARKS", null);
      resultSet.add(res);
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public Connection getConnection() throws SQLException {
    return connection;
  }

  public boolean supportsSavepoints() throws SQLException {

    return false;
  }

  public boolean supportsNamedParameters() throws SQLException {

    return true;
  }

  public boolean supportsMultipleOpenResults() throws SQLException {

    return false;
  }

  public boolean supportsGetGeneratedKeys() throws SQLException {

    return false;
  }

  public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
      throws SQLException {
    final var cls = session.getMetadata().getSchema().getClass(typeNamePattern);

    final var resultSet = new InternalResultSet(session);
    if (cls != null) {
      for (var superCls : cls.getSuperClassesNames()) {
        final var res = new ResultInternal(session);
        res.setProperty("TABLE_CAT", catalog);
        res.setProperty("TABLE_SCHEM", catalog);
        res.setProperty("TABLE_NAME", cls.getName());
        res.setProperty("SUPERTYPE_CAT", catalog);
        res.setProperty("SUPERTYPE_SCHEM", catalog);
        res.setProperty("SUPERTYPE_NAME", superCls);
        resultSet.add(res);
      }
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getSuperTables(
      final String catalog, final String schemaPattern, final String tableNamePattern)
      throws SQLException {
    final var cls = session.getMetadata().getSchema().getClass(tableNamePattern);
    final var resultSet = new InternalResultSet(session);

    if (cls != null) {
      for (var superCls : cls.getSuperClassesNames()) {
        final var res = new ResultInternal(session);
        res.setProperty("TABLE_CAT", catalog);
        res.setProperty("TABLE_SCHEM", catalog);
        res.setProperty("TABLE_NAME", cls.getName());
        res.setProperty("SUPERTYPE_CAT", catalog);
        res.setProperty("SUPERTYPE_SCHEM", catalog);
        res.setProperty("SUPERTYPE_NAME", superCls);
        resultSet.add(res);
      }
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getAttributes(
      String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
      throws SQLException {

    return getEmptyResultSet();
  }

  public boolean supportsResultSetHoldability(int holdability) throws SQLException {

    return false;
  }

  public int getResultSetHoldability() throws SQLException {

    return 0;
  }

  public int getDatabaseMajorVersion() throws SQLException {
    return YouTrackDBConstants.getVersionMajor();
  }

  public int getDatabaseMinorVersion() throws SQLException {
    return YouTrackDBConstants.getVersionMinor();
  }

  public int getJDBCMajorVersion() throws SQLException {

    return 0;
  }

  public int getJDBCMinorVersion() throws SQLException {

    return 0;
  }

  public int getSQLStateType() throws SQLException {

    return 0;
  }

  public boolean locatorsUpdateCopy() throws SQLException {

    return false;
  }

  public boolean supportsStatementPooling() throws SQLException {

    return false;
  }

  public RowIdLifetime getRowIdLifetime() throws SQLException {

    return null;
  }

  public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {

    return getEmptyResultSet();
  }

  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {

    return true;
  }

  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    return false;
  }

  public ResultSet getClientInfoProperties() throws SQLException {

    return getEmptyResultSet();
  }

  public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
      throws SQLException {
    var resultSet = new InternalResultSet(session);
    for (var fName : session.getMetadata().getFunctionLibrary().getFunctionNames()) {
      final var res = new ResultInternal(session);
      res.setProperty("FUNCTION_CAT", null);
      res.setProperty("FUNCTION_SCHEM", null);
      res.setProperty("FUNCTION_NAME", fName);
      res.setProperty("REMARKS", "");
      res.setProperty("FUNCTION_TYPE", procedureResultUnknown);
      res.setProperty("SPECIFIC_NAME", fName);

      resultSet.add(res);
    }

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getFunctionColumns(
      String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
      throws SQLException {
    final var resultSet = new InternalResultSet(session);

    final var f =
        session.getMetadata().getFunctionLibrary().getFunction(session, functionNamePattern);

    for (var p : f.getParameters()) {
      final var res = new ResultInternal(session);
      res.setProperty("FUNCTION_CAT", null);
      res.setProperty("FUNCTION_SCHEM", null);
      res.setProperty("FUNCTION_NAME", f.getName());
      res.setProperty("COLUMN_NAME", p);
      res.setProperty("COLUMN_TYPE", procedureColumnIn);
      res.setProperty("DATA_TYPE", java.sql.Types.OTHER);
      res.setProperty("SPECIFIC_NAME", f.getName());
      resultSet.add(res);
    }

    final var res = new ResultInternal(session);
    res.setProperty("FUNCTION_CAT", null);
    res.setProperty("FUNCTION_SCHEM", null);
    res.setProperty("FUNCTION_NAME", f.getName());
    res.setProperty("COLUMN_NAME", "return");
    res.setProperty("COLUMN_TYPE", procedureColumnReturn);
    res.setProperty("DATA_TYPE", java.sql.Types.OTHER);
    res.setProperty("SPECIFIC_NAME", f.getName());

    resultSet.add(res);

    return new YouTrackDbJdbcResultSet(
        new YouTrackDbJdbcStatement(connection),
        session, resultSet,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public ResultSet getPseudoColumns(String arg0, String arg1, String arg2, String arg3)
      throws SQLException {
    return getEmptyResultSet();
  }

  public boolean generatedKeyAlwaysReturned() throws SQLException {
    return false;
  }

  private ResultInternal getPropertyAsDocument(final SchemaClass clazz, final SchemaProperty prop) {
    final var type = prop.getType();
    var res = new ResultInternal(session);
    res.setProperty("TABLE_CAT", session.getDatabaseName());
    res.setProperty("TABLE_SCHEM", session.getDatabaseName());
    res.setProperty("TABLE_NAME", clazz.getName());
    res.setProperty("COLUMN_NAME", prop.getName());
    res.setProperty("DATA_TYPE", YouTrackDbJdbcResultSetMetaData.getSqlType(type));
    res.setProperty("TYPE_NAME", type.name());
    res.setProperty("COLUMN_SIZE", 1);
    res.setProperty("BUFFER_LENGTH", null);
    res.setProperty("DECIMAL_DIGITS", null);
    res.setProperty("NUM_PREC_RADIX", 10);
    res.setProperty("NULLABLE", !prop.isNotNull() ? columnNoNulls : columnNullable);
    res.setProperty("REMARKS", prop.getDescription());
    res.setProperty("COLUMN_DEF", prop.getDefaultValue());
    res.setProperty("SQL_DATA_TYPE", null);
    res.setProperty("SQL_DATETIME_SUB", null);
    res.setProperty("CHAR_OCTET_LENGTH", null);
    res.setProperty("ORDINAL_POSITION", prop.getId());
    res.setProperty("IS_NULLABLE", prop.isNotNull() ? "NO" : "YES");

    return res;
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {

    return null;
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {

    return false;
  }
}
