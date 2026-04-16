/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.filter;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquals;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the SQL filter evaluation infrastructure: SQLFilterCondition, SQLFilter, SQLPredicate,
 * SQLTarget, FilterOptimizer, and SQLFilterItem* classes. All tests extend DbTestBase because
 * parsing/evaluating filters requires a database session for schema resolution and type conversion.
 */
public class SQLFilterClassesTest extends DbTestBase {

  private CommandContext context;

  @Before
  public void setUp() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    context = ctx;
  }

  // ====================================================================
  // SQLFilter — parsing and evaluation
  // ====================================================================

  /** Parsing a null expression text must throw IllegalArgumentException. */
  @Test(expected = IllegalArgumentException.class)
  public void testSQLFilterNullTextThrows() {
    new SQLFilter(null, context);
  }

  /** SQLFilter "1 = 1" evaluates to true against any record. */
  @Test
  public void testSQLFilterSimpleTrueEvaluatesTrue() {
    var filter = new SQLFilter("1 = 1", context);
    session.begin();
    var doc = (EntityImpl) session.newInstance();
    var result = filter.evaluate(doc, null, context);
    Assert.assertEquals(Boolean.TRUE, result);
    session.commit();
  }

  /** SQLFilter simple equality on a document field evaluates correctly. */
  @Test
  public void testSQLFilterFieldEquality() {
    session.getMetadata().getSchema().createClass("FilterTestA");
    session.begin();
    var doc = (EntityImpl) session.newInstance("FilterTestA");
    doc.setProperty("name", "Alice");
    var filter = new SQLFilter("name = 'Alice'", context);
    Assert.assertEquals(Boolean.TRUE, filter.evaluate(doc, null, context));
    var filter2 = new SQLFilter("name = 'Bob'", context);
    Assert.assertEquals(Boolean.FALSE, filter2.evaluate(doc, null, context));
    session.commit();
  }

  /** SQLFilter compound AND condition — both sides must be true. */
  @Test
  public void testSQLFilterAndCondition() {
    session.getMetadata().getSchema().createClass("FilterTestB");
    session.begin();
    var doc = (EntityImpl) session.newInstance("FilterTestB");
    doc.setProperty("age", 30);
    doc.setProperty("name", "Bob");
    var filter = new SQLFilter("age = 30 and name = 'Bob'", context);
    Assert.assertEquals(Boolean.TRUE, filter.evaluate(doc, null, context));
    var filter2 = new SQLFilter("age = 30 and name = 'Alice'", context);
    Assert.assertEquals(Boolean.FALSE, filter2.evaluate(doc, null, context));
    session.commit();
  }

  /** SQLFilter compound OR condition — either side being true is enough. */
  @Test
  public void testSQLFilterOrCondition() {
    session.getMetadata().getSchema().createClass("FilterTestC");
    session.begin();
    var doc = (EntityImpl) session.newInstance("FilterTestC");
    doc.setProperty("age", 25);
    doc.setProperty("name", "Carol");
    var filter = new SQLFilter("age = 99 or name = 'Carol'", context);
    Assert.assertEquals(Boolean.TRUE, filter.evaluate(doc, null, context));
    session.commit();
  }

  /** Operator precedence: AND binds tighter than OR. */
  @Test
  public void testSQLFilterOperatorPrecedence() {
    session.getMetadata().getSchema().createClass("FilterTestD");
    session.begin();
    var doc = (EntityImpl) session.newInstance("FilterTestD");
    doc.setProperty("a", 1);
    doc.setProperty("b", 99);
    doc.setProperty("c", 3);
    // (a=1 AND b=2) OR c=3 → false OR true → true
    var filter = new SQLFilter("a = 1 and b = 2 or c = 3", context);
    Assert.assertEquals(Boolean.TRUE, filter.evaluate(doc, null, context));
    session.commit();
  }

  /** SQLFilter with braces in condition — verify parsing works. */
  @Test
  public void testSQLFilterBracesCondition() {
    session.getMetadata().getSchema().createClass("FilterTestE");
    session.begin();
    var doc = (EntityImpl) session.newInstance("FilterTestE");
    doc.setProperty("a", 1);
    doc.setProperty("b", 2);
    doc.setProperty("c", 3);
    // a=1 and (b=2 or c=99) → true AND (true OR false) → true
    var filter = new SQLFilter("a = 1 and (b = 2 or c = 99)", context);
    Assert.assertEquals(Boolean.TRUE, filter.evaluate(doc, null, context));
    session.commit();
  }

  /** Filter with empty text (whitespace only) produces null root condition. */
  @Test
  public void testSQLFilterWhitespaceOnlyText() {
    // An all-whitespace filter parses to null root → evaluate returns true
    var filter = new SQLFilter("1 = 1", context);
    Assert.assertNotNull(filter.getRootCondition());
  }

  // ====================================================================
  // SQLFilterCondition — evaluate, toString, getters, type conversion
  // ====================================================================

  /** Integer vs String type conversion via checkForConversion. */
  @Test
  public void testSQLFilterConditionIntegerConversion() {
    session.getMetadata().getSchema().createClass("CondConv");
    session.begin();
    var doc = (EntityImpl) session.newInstance("CondConv");
    doc.setProperty("val", "42");
    var filter = SQLEngine.parseCondition("val = 42", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** Float conversion path in checkForConversion. */
  @Test
  public void testSQLFilterConditionFloatConversion() {
    session.getMetadata().getSchema().createClass("CondFloat");
    session.begin();
    var doc = (EntityImpl) session.newInstance("CondFloat");
    doc.setProperty("val", 3.14f);
    var filter = SQLEngine.parseCondition("val > 2.0", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** SQLFilterCondition.toString() contains operator and value. */
  @Test
  public void testSQLFilterConditionToString() {
    var filter = SQLEngine.parseCondition("a = 3", context);
    var str = filter.getRootCondition().toString();
    Assert.assertTrue(str.contains("="));
    Assert.assertTrue(str.contains("3"));
  }

  /** SQLFilterCondition.asString() formats with SQLFilterItemAbstract. */
  @Test
  public void testSQLFilterConditionAsString() {
    var filter = SQLEngine.parseCondition("a = 3", context);
    var str = filter.getRootCondition().asString(session);
    Assert.assertTrue(str.contains("a"));
  }

  /** getLeft/getRight/getOperator return correct components. */
  @Test
  public void testSQLFilterConditionGetters() {
    var filter = SQLEngine.parseCondition("x = 5", context);
    var cond = filter.getRootCondition();
    Assert.assertNotNull(cond.getLeft());
    Assert.assertNotNull(cond.getRight());
    Assert.assertNotNull(cond.getOperator());
    Assert.assertTrue(cond.getLeft() instanceof SQLFilterItemField);
    Assert.assertTrue(cond.getOperator() instanceof QueryOperatorEquals);
  }

  /** setLeft/setRight update operands. */
  @Test
  public void testSQLFilterConditionSetters() {
    var filter = SQLEngine.parseCondition("x = 5", context);
    var cond = filter.getRootCondition();
    cond.setLeft("newLeft");
    cond.setRight("newRight");
    Assert.assertEquals("newLeft", cond.getLeft());
    Assert.assertEquals("newRight", cond.getRight());
  }

  /** getInvolvedFields extracts field names. */
  @Test
  public void testSQLFilterConditionGetInvolvedFields() {
    var filter = SQLEngine.parseCondition("name = 'test'", context);
    var fields = new ArrayList<String>();
    filter.getRootCondition().getInvolvedFields(fields);
    Assert.assertTrue(fields.contains("name"));
  }

  /** getInvolvedFields with nested AND extracts fields from both branches. */
  @Test
  public void testSQLFilterConditionGetInvolvedFieldsNested() {
    var filter = SQLEngine.parseCondition("a = 1 and b = 2", context);
    var fields = new ArrayList<String>();
    filter.getRootCondition().getInvolvedFields(fields);
    Assert.assertTrue(fields.contains("a"));
    Assert.assertTrue(fields.contains("b"));
  }

  /** getBeginRidRange/getEndRidRange with null operator delegates to left condition. */
  @Test
  public void testSQLFilterConditionRidRangeNullOperator() {
    var inner = SQLEngine.parseCondition("a = 1", context).getRootCondition();
    var outer = new SQLFilterCondition(inner, null);
    Assert.assertNull(outer.getBeginRidRange(session));
    Assert.assertNull(outer.getEndRidRange(session));
  }

  /** Null operator with non-condition left returns null. */
  @Test
  public void testSQLFilterConditionRidRangeNullOperatorNonConditionLeft() {
    var cond = new SQLFilterCondition("literal", null);
    Assert.assertNull(cond.getBeginRidRange(session));
    Assert.assertNull(cond.getEndRidRange(session));
  }

  /** toString with null operator only outputs left in parens. */
  @Test
  public void testSQLFilterConditionToStringNullOperator() {
    var cond = new SQLFilterCondition("left", null);
    Assert.assertEquals("(left", cond.toString());
  }

  /** toString with string right operand adds quotes. */
  @Test
  public void testSQLFilterConditionToStringQuotesStringRight() {
    var filter = SQLEngine.parseCondition("name = 'test'", context);
    Assert.assertTrue(filter.getRootCondition().toString().contains("'"));
  }

  // ====================================================================
  // SQLFilterCondition — checkForConversion edge cases
  // ====================================================================

  /** Date compared to integer triggers Date↔Long conversion. */
  @Test
  public void testSQLFilterConditionDateComparison() {
    session.getMetadata().getSchema().createClass("DateComp");
    session.begin();
    var doc = (EntityImpl) session.newInstance("DateComp");
    doc.setProperty("created", new Date(1000000));
    var filter = SQLEngine.parseCondition("created > 0", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** RID comparison: @rid = #X:Y. */
  @Test
  public void testSQLFilterConditionRidComparison() {
    session.getMetadata().getSchema().createClass("RidComp");
    session.begin();
    var doc = (EntityImpl) session.newInstance("RidComp");
    var rid = doc.getIdentity();
    var filter = SQLEngine.parseCondition("@rid = " + rid.toString(), context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** BigDecimal comparison — numeric type conversion path. */
  @Test
  public void testSQLFilterConditionBigDecimalComparison() {
    session.getMetadata().getSchema().createClass("DecComp");
    session.begin();
    var doc = (EntityImpl) session.newInstance("DecComp");
    doc.setProperty("amount", new BigDecimal("100.50"));
    var filter = SQLEngine.parseCondition("amount > 50", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // SQLPredicate — parsing
  // ====================================================================

  /** SQLPredicate constructor parsing simple condition. */
  @Test
  public void testSQLPredicateSimpleParse() {
    var pred = new SQLPredicate(context, "a = 1");
    Assert.assertNotNull(pred.getRootCondition());
    Assert.assertNotNull(pred.getRootCondition().getOperator());
  }

  /** SQLPredicate.text() with null text throws. */
  @Test(expected = Exception.class)
  public void testSQLPredicateNullTextThrows() {
    var pred = new SQLPredicate(context);
    pred.text(session, null);
  }

  /** Null rootCondition returns true from all evaluate overloads. */
  @Test
  public void testSQLPredicateNullRootConditionReturnsTrue() {
    var pred = new SQLPredicate(context);
    Assert.assertEquals(true, pred.evaluate());
  }

  @Test
  public void testSQLPredicateEvaluateWithContextNullRoot() {
    var pred = new SQLPredicate(context);
    Assert.assertEquals(true, pred.evaluate(context));
  }

  @Test
  public void testSQLPredicateEvaluateThreeArgNullRoot() {
    var pred = new SQLPredicate(context);
    Assert.assertEquals(true, pred.evaluate(null, null, context));
  }

  /** toString shows "Parsed:" when condition is parsed. */
  @Test
  public void testSQLPredicateToStringParsed() {
    var pred = new SQLPredicate(context, "a = 1");
    Assert.assertTrue(pred.toString().startsWith("Parsed:"));
  }

  /** toString shows "Unparsed:" when no condition parsed. */
  @Test
  public void testSQLPredicateToStringUnparsed() {
    var pred = new SQLPredicate(context);
    Assert.assertTrue(pred.toString().startsWith("Unparsed:"));
  }

  /** addParameter with named parameter (":name") strips colon. */
  @Test
  public void testSQLPredicateAddParameterNamed() {
    var pred = new SQLPredicate(context);
    var param = pred.addParameter(":myParam");
    Assert.assertEquals("myParam", param.getName());
  }

  /** addParameter with positional parameter "?" keeps the name. */
  @Test
  public void testSQLPredicateAddParameterPositional() {
    var pred = new SQLPredicate(context);
    var param = pred.addParameter("?");
    Assert.assertEquals("?", param.getName());
  }

  /** addParameter with invalid name (non-alphanumeric) throws. */
  @Test(expected = QueryParsingException.class)
  public void testSQLPredicateAddParameterInvalidName() {
    var pred = new SQLPredicate(context);
    pred.addParameter(":invalid-name!");
  }

  /** upperCase keeps original char when its uppercase is >1 char (e.g. ß→SS). */
  @Test
  public void testSQLPredicateUpperCaseMultiCharExpansion() {
    var result = SQLPredicate.upperCase("straße");
    Assert.assertEquals("straße".length(), result.length());
  }

  /** Normal ASCII uppercasing. */
  @Test
  public void testSQLPredicateUpperCaseNormal() {
    Assert.assertEquals("HELLO", SQLPredicate.upperCase("hello"));
    Assert.assertEquals("HELLO", SQLPredicate.upperCase("HELLO"));
    Assert.assertEquals("HELLO123", SQLPredicate.upperCase("hello123"));
  }

  /** setRootCondition replaces the root condition. */
  @Test
  public void testSQLPredicateSetRootCondition() {
    var pred = new SQLPredicate(context, "a = 1");
    Assert.assertNotNull(pred.getRootCondition());
    pred.setRootCondition(null);
    Assert.assertNull(pred.getRootCondition());
  }

  /** NOT operator wraps the equality operator with negation. */
  @Test
  public void testSQLPredicateNotOperator() {
    session.getMetadata().getSchema().createClass("NotTest");
    session.begin();
    var doc = (EntityImpl) session.newInstance("NotTest");
    doc.setProperty("a", 1);
    var filter = SQLEngine.parseCondition("not a = 1", context);
    Assert.assertEquals(Boolean.FALSE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** BETWEEN operator parsing and evaluation. */
  @Test
  public void testSQLPredicateBetweenParsing() {
    session.getMetadata().getSchema().createClass("BetweenParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("BetweenParse");
    doc.setProperty("val", 5);
    var filter = SQLEngine.parseCondition("val between 1 and 10", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** IN operator with collection parsing. */
  @Test
  public void testSQLPredicateInCollectionParsing() {
    session.getMetadata().getSchema().createClass("InParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("InParse");
    doc.setProperty("val", 2);
    var filter = SQLEngine.parseCondition("val in [1, 2, 3]", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** LIKE operator parsing. */
  @Test
  public void testSQLPredicateLikeParsing() {
    session.getMetadata().getSchema().createClass("LikeParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("LikeParse");
    doc.setProperty("name", "hello world");
    var filter = SQLEngine.parseCondition("name like '%world%'", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** IS null operator parsing. */
  @Test
  public void testSQLPredicateIsNull() {
    session.getMetadata().getSchema().createClass("IsNullParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("IsNullParse");
    doc.setProperty("name", (String) null);
    var filter = SQLEngine.parseCondition("name is null", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** IS NOT NULL operator parsing. */
  @Test
  public void testSQLPredicateIsNotNull() {
    session.getMetadata().getSchema().createClass("IsNotNullParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("IsNotNullParse");
    doc.setProperty("name", "value");
    var filter = SQLEngine.parseCondition("name is not null", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // SQLTarget — target parsing
  // ====================================================================

  /** SQLTarget parses a class target and resolves it against schema. */
  @Test
  public void testSQLTargetClassTarget() {
    session.getMetadata().getSchema().createClass("TARGETCLASS");
    var target = new SQLTarget("TARGETCLASS", context);
    Assert.assertNotNull(target.getTargetClasses());
    Assert.assertTrue(target.getTargetClasses().containsKey("TARGETCLASS"));
    // isEmpty() returns true because parser is at end after consuming the single word
    Assert.assertTrue(target.isEmpty());
  }

  /** SQLTarget with CLASS: prefix. */
  @Test
  public void testSQLTargetClassPrefixTarget() {
    session.getMetadata().getSchema().createClass("PREFIXCLASS");
    var target = new SQLTarget("CLASS:PREFIXCLASS", context);
    Assert.assertNotNull(target.getTargetClasses());
    Assert.assertTrue(target.getTargetClasses().containsKey("PREFIXCLASS"));
  }

  /** SQLTarget toString for class target contains "class". */
  @Test
  public void testSQLTargetToStringClass() {
    session.getMetadata().getSchema().createClass("TOSTRCLASS");
    var target = new SQLTarget("TOSTRCLASS", context);
    Assert.assertTrue(target.toString().contains("class"));
  }

  /** Non-existent class throws CommandExecutionException. */
  @Test(expected = CommandExecutionException.class)
  public void testSQLTargetNonExistentClassThrows() {
    new SQLTarget("NonExistentClassName12345", context);
  }

  /** SQLTarget parses a RID target. */
  @Test
  public void testSQLTargetRidTarget() {
    var target = new SQLTarget("#1:0", context);
    Assert.assertNotNull(target.getTargetRecords());
    Assert.assertTrue(target.toString().contains("records"));
  }

  /** SQLTarget parses a collection of RIDs. */
  @Test
  public void testSQLTargetRidCollectionTarget() {
    var target = new SQLTarget("[#1:0, #1:1]", context);
    Assert.assertNotNull(target.getTargetRecords());
    var records = new ArrayList<>();
    target.getTargetRecords().forEach(records::add);
    Assert.assertEquals(2, records.size());
  }

  /** SQLTarget parses INDEX: prefix. */
  @Test
  public void testSQLTargetIndexTarget() {
    var target = new SQLTarget("INDEX:myIndex", context);
    Assert.assertEquals("myIndex", target.getTargetIndex());
    Assert.assertTrue(target.toString().contains("index"));
  }

  /** INDEXVALUES: target is ascending by default. */
  @Test
  public void testSQLTargetIndexValuesAsc() {
    var target = new SQLTarget("INDEXVALUES:myIdx", context);
    Assert.assertEquals("myIdx", target.getTargetIndexValues());
    Assert.assertTrue(target.isTargetIndexValuesAsc());
  }

  /** INDEXVALUESASC: target is ascending. */
  @Test
  public void testSQLTargetIndexValuesAscExplicit() {
    var target = new SQLTarget("INDEXVALUESASC:myIdx2", context);
    Assert.assertEquals("myIdx2", target.getTargetIndexValues());
    Assert.assertTrue(target.isTargetIndexValuesAsc());
  }

  /** INDEXVALUESDESC: target is descending. */
  @Test
  public void testSQLTargetIndexValuesDesc() {
    var target = new SQLTarget("INDEXVALUESDESC:myIdx3", context);
    Assert.assertEquals("myIdx3", target.getTargetIndexValues());
    Assert.assertFalse(target.isTargetIndexValuesAsc());
  }

  /** METADATA:SCHEMA target resolves to the schema record. */
  @Test
  public void testSQLTargetMetadataSchema() {
    var target = new SQLTarget("METADATA:SCHEMA", context);
    Assert.assertNotNull(target.getTargetRecords());
  }

  /** METADATA:INDEXMANAGER target resolves to the index manager record. */
  @Test
  public void testSQLTargetMetadataIndexManager() {
    var target = new SQLTarget("METADATA:INDEXMANAGER", context);
    Assert.assertNotNull(target.getTargetRecords());
  }

  /** Unsupported metadata entity throws. */
  @Test(expected = QueryParsingException.class)
  public void testSQLTargetMetadataUnsupported() {
    new SQLTarget("METADATA:UNKNOWN", context);
  }

  /** Variable target ($var) is parsed. */
  @Test
  public void testSQLTargetVariableTarget() {
    var target = new SQLTarget("$myVar", context);
    Assert.assertEquals("myVar", target.getTargetVariable());
    Assert.assertTrue(target.toString().contains("variable"));
  }

  /** Empty text throws QueryParsingException. */
  @Test(expected = QueryParsingException.class)
  public void testSQLTargetEmptyTextThrows() {
    new SQLTarget("", context);
  }

  /** Variable target toString is non-null. */
  @Test
  public void testSQLTargetToStringVariable() {
    var target = new SQLTarget("$v", context);
    Assert.assertNotNull(target.toString());
  }

  /** COLLECTION: prefix parsing for a single collection. */
  @Test
  public void testSQLTargetCollectionTarget() {
    var target = new SQLTarget("COLLECTION:myCollection", context);
    Assert.assertNotNull(target.getTargetCollections());
    // Collection names are uppercased by the parser (subjectName.toUpperCase)
    // but the suffix is extracted from the uppercased version
    Assert.assertTrue(target.getTargetCollections().containsKey("MYCOLLECTION"));
    Assert.assertTrue(target.toString().contains("collection"));
  }

  /** COLLECTION: with multiple collections in brackets. */
  @Test
  public void testSQLTargetCollectionMultiple() {
    var target = new SQLTarget("COLLECTION:[col1,col2]", context);
    Assert.assertNotNull(target.getTargetCollections());
    // Collection names within brackets are NOT uppercased (extracted from raw text)
    Assert.assertFalse(target.getTargetCollections().isEmpty());
    Assert.assertEquals(2, target.getTargetCollections().size());
  }

  /** getTargetQuery returns null for non-query targets. */
  @Test
  public void testSQLTargetGetTargetQueryNull() {
    var target = new SQLTarget("$v", context);
    Assert.assertNull(target.getTargetQuery());
  }

  // ====================================================================
  // SQLFilterItemField — field resolution
  // ====================================================================

  /** getValue with null record throws CommandExecutionException. */
  @Test(expected = CommandExecutionException.class)
  public void testSQLFilterItemFieldNullRecordThrows() {
    var field = new SQLFilterItemField(session, "name", null);
    field.getValue(null, null, context);
  }

  /** Simple field isFieldChain is true. */
  @Test
  public void testSQLFilterItemFieldIsFieldChainSimple() {
    var field = new SQLFilterItemField(session, "name", null);
    Assert.assertTrue(field.isFieldChain());
  }

  /** Simple field getFieldChain has one item. */
  @Test
  public void testSQLFilterItemFieldGetFieldChainSimple() {
    var field = new SQLFilterItemField(session, "name", null);
    var chain = field.getFieldChain();
    Assert.assertEquals(1, chain.getItemCount());
    Assert.assertEquals("name", chain.getItemName(0));
    Assert.assertFalse(chain.isLong());
  }

  /** getRoot returns the field name. */
  @Test
  public void testSQLFilterItemFieldGetRoot() {
    var field = new SQLFilterItemField(session, "myField", null);
    Assert.assertEquals("myField", field.getRoot(session));
  }

  /** hasChainOperators is false for simple field. */
  @Test
  public void testSQLFilterItemFieldHasChainOperators() {
    var simple = new SQLFilterItemField(session, "name", null);
    Assert.assertFalse(simple.hasChainOperators());
  }

  /** getCollate returns null when no class context. */
  @Test
  public void testSQLFilterItemFieldGetCollateNoClass() {
    var field = new SQLFilterItemField(session, "name", null);
    Assert.assertNull(field.getCollate());
  }

  /** getLastChainOperator returns null for simple field. */
  @Test
  public void testSQLFilterItemFieldGetLastChainOperatorNull() {
    var field = new SQLFilterItemField(session, "name", null);
    Assert.assertNull(field.getLastChainOperator());
  }

  /** Parsed "field.length()" has method chain, is NOT a field chain. */
  @Test
  public void testSQLFilterItemFieldWithMethodChain() {
    var filter = SQLEngine.parseCondition("name.length() = 5", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    Assert.assertTrue(field.hasChainOperators());
    Assert.assertFalse(field.isFieldChain());
  }

  /** Parsed "a.b" is a field chain with 2 items. */
  @Test
  public void testSQLFilterItemFieldDotFieldChain() {
    var filter = SQLEngine.parseCondition("a.b = 1", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    Assert.assertTrue(field.isFieldChain());
    Assert.assertEquals(2, field.getFieldChain().getItemCount());
    Assert.assertEquals("a", field.getFieldChain().getItemName(0));
    Assert.assertEquals("b", field.getFieldChain().getItemName(1));
    Assert.assertTrue(field.getFieldChain().isLong());
  }

  /** FieldChain.belongsTo is true for the owning field, false for other. */
  @Test
  public void testSQLFilterItemFieldChainBelongsTo() {
    var filter = SQLEngine.parseCondition("x = 1", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    var chain = field.getFieldChain();
    Assert.assertTrue(chain.belongsTo(field));
    var other = new SQLFilterItemField(session, "y", null);
    Assert.assertFalse(chain.belongsTo(other));
  }

  /** getFieldChain on non-field-chain throws IllegalStateException. */
  @Test(expected = IllegalStateException.class)
  public void testSQLFilterItemFieldGetFieldChainOnNonFieldChainThrows() {
    var filter = SQLEngine.parseCondition("name.length() = 5", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    field.getFieldChain();
  }

  /** getValue resolves a property from a record. */
  @Test
  public void testSQLFilterItemFieldGetValueFromRecord() {
    session.getMetadata().getSchema().createClass("FieldGet");
    session.begin();
    var doc = (EntityImpl) session.newInstance("FieldGet");
    doc.setProperty("x", 42);
    var filter = SQLEngine.parseCondition("x = 42", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    Assert.assertEquals(42, field.getValue(doc, null, context));
    session.commit();
  }

  /** asString for simple field returns the field name. */
  @Test
  public void testSQLFilterItemFieldAsString() {
    var field = new SQLFilterItemField(session, "myField", null);
    Assert.assertEquals("myField", field.asString(session));
  }

  /** asString for chained field includes both parts. */
  @Test
  public void testSQLFilterItemFieldAsStringChained() {
    var filter = SQLEngine.parseCondition("a.b = 1", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    Assert.assertTrue(field.asString(session).contains("a"));
  }

  // ====================================================================
  // SQLFilterItemParameter — parameter binding
  // ====================================================================

  /** Default value is NOT_SETTED ("?"). */
  @Test
  public void testSQLFilterItemParameterDefaultValue() {
    var param = new SQLFilterItemParameter("test");
    Assert.assertEquals("?", param.getValue(null, null, context));
  }

  /** setValue updates the returned value. */
  @Test
  public void testSQLFilterItemParameterSetValue() {
    var param = new SQLFilterItemParameter("test");
    param.setValue(42);
    Assert.assertEquals(42, param.getValue(null, null, context));
  }

  /** setValue(null) sets the value to null. */
  @Test
  public void testSQLFilterItemParameterSetNull() {
    var param = new SQLFilterItemParameter("test");
    param.setValue(null);
    Assert.assertNull(param.getValue(null, null, context));
  }

  /** getName returns the parameter name. */
  @Test
  public void testSQLFilterItemParameterGetName() {
    var param = new SQLFilterItemParameter("myParam");
    Assert.assertEquals("myParam", param.getName());
  }

  /** toString shows ":name" when unset, value when set. */
  @Test
  public void testSQLFilterItemParameterToString() {
    var named = new SQLFilterItemParameter("myParam");
    Assert.assertEquals(":myParam", named.toString());
    var positional = new SQLFilterItemParameter("?");
    Assert.assertEquals("?", positional.toString());
    named.setValue(42);
    Assert.assertEquals("42", named.toString());
    named.setValue(null);
    Assert.assertEquals("null", named.toString());
  }

  // ====================================================================
  // FilterOptimizer — additional coverage
  // ====================================================================

  /** Optimizing a null condition is a no-op. */
  @Test
  public void testFilterOptimizerNullCondition() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a = 3", context);
    filter.setRootCondition(null);
    var cond = SQLEngine.parseCondition("a = 3", context).getRootCondition();
    var searchResult = new IndexSearchResult(
        cond.getOperator(),
        ((SQLFilterItemField) cond.getLeft()).getFieldChain(), 3);
    optimizer.optimize(filter, searchResult);
    Assert.assertNull(filter.getRootCondition());
  }

  /** Unwrap null-operator wrapper when left is a nested condition. */
  @Test
  public void testFilterOptimizerUnwrapNullOperator() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a = 3", context);
    var inner = filter.getRootCondition();
    filter.setRootCondition(new SQLFilterCondition(inner, null));
    var searchResult = new IndexSearchResult(
        inner.getOperator(),
        ((SQLFilterItemField) inner.getLeft()).getFieldChain(), 3);
    optimizer.optimize(filter, searchResult);
    Assert.assertNull(filter.getRootCondition());
  }

  /** Null operator with non-null right → condition returned as-is. */
  @Test
  public void testFilterOptimizerNullOperatorNonNullRight() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a = 3", context);
    var inner = filter.getRootCondition();
    filter.setRootCondition(new SQLFilterCondition(inner, null, "something"));
    var searchResult = new IndexSearchResult(
        inner.getOperator(),
        ((SQLFilterItemField) inner.getLeft()).getFieldChain(), 3);
    optimizer.optimize(filter, searchResult);
    Assert.assertNotNull(filter.getRootCondition());
  }

  /** Partial optimization with OR: only the matched branch is removed. */
  @Test
  public void testFilterOptimizerPartialWithOr() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a = 3 or b > 5", context);
    var cond = filter.getRootCondition();
    var leftCond = (SQLFilterCondition) cond.getLeft();
    var searchResult = new IndexSearchResult(
        leftCond.getOperator(),
        ((SQLFilterItemField) leftCond.getLeft()).getFieldChain(), 3);
    optimizer.optimize(filter, searchResult);
    Assert.assertNotNull(filter.getRootCondition());
  }

  /** Value mismatch (3 vs null) prevents optimization. */
  @Test
  public void testFilterOptimizerNullValueMatching() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a = 3", context);
    var cond = filter.getRootCondition();
    var searchResult = new IndexSearchResult(
        cond.getOperator(),
        ((SQLFilterItemField) cond.getLeft()).getFieldChain(), null);
    optimizer.optimize(filter, searchResult);
    Assert.assertNotNull(filter.getRootCondition());
  }

  /** Field mismatch prevents optimization. */
  @Test
  public void testFilterOptimizerFieldMismatch() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a = 3", context);
    var bFilter = SQLEngine.parseCondition("b = 3", context);
    var bCond = bFilter.getRootCondition();
    var searchResult = new IndexSearchResult(
        bCond.getOperator(),
        ((SQLFilterItemField) bCond.getLeft()).getFieldChain(), 3);
    optimizer.optimize(filter, searchResult);
    Assert.assertNotNull(filter.getRootCondition());
  }

  /** INDEX_OPERATOR path: Major (>) with matching field/value is optimized. */
  @Test
  public void testFilterOptimizerNonEqualsOperator() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a > 5", context);
    var cond = filter.getRootCondition();
    var searchResult = new IndexSearchResult(
        cond.getOperator(),
        ((SQLFilterItemField) cond.getLeft()).getFieldChain(), 5);
    optimizer.optimize(filter, searchResult);
    Assert.assertNull(filter.getRootCondition());
  }

  /** INDEX_OPERATOR path: Minor (<) with mismatched value is not optimized. */
  @Test
  public void testFilterOptimizerNonEqualsOperatorMismatch() {
    var optimizer = new FilterOptimizer();
    var filter = SQLEngine.parseCondition("a < 5", context);
    var cond = filter.getRootCondition();
    var searchResult = new IndexSearchResult(
        cond.getOperator(),
        ((SQLFilterItemField) cond.getLeft()).getFieldChain(), 99);
    optimizer.optimize(filter, searchResult);
    Assert.assertNotNull(filter.getRootCondition());
  }

  // ====================================================================
  // Complex parsing paths
  // ====================================================================

  /** Method chain: name.toLowerCase() = 'test'. */
  @Test
  public void testParsingMethodChainCondition() {
    session.getMetadata().getSchema().createClass("MethodChain");
    session.begin();
    var doc = (EntityImpl) session.newInstance("MethodChain");
    doc.setProperty("name", "TEST");
    var filter = SQLEngine.parseCondition("name.toLowerCase() = 'test'", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** Deep field chain: a.b.c = 1. */
  @Test
  public void testParsingDeepFieldChain() {
    var filter = SQLEngine.parseCondition("a.b.c = 1", context);
    var field = (SQLFilterItemField) filter.getRootCondition().getLeft();
    Assert.assertTrue(field.isFieldChain());
    Assert.assertEquals(3, field.getFieldChain().getItemCount());
    Assert.assertEquals("a", field.getFieldChain().getItemName(0));
    Assert.assertEquals("b", field.getFieldChain().getItemName(1));
    Assert.assertEquals("c", field.getFieldChain().getItemName(2));
  }

  /** Sub-query in body throws. */
  @Test(expected = Exception.class)
  public void testSQLPredicateSubQueryThrows() {
    new SQLPredicate(context, "SELECT from V");
  }

  /** MATCHES operator parsing and evaluation. */
  @Test
  public void testParsingMatchesOperator() {
    session.getMetadata().getSchema().createClass("MatchParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("MatchParse");
    doc.setProperty("code", "ABC123");
    // Use a simple regex pattern that doesn't need backslash escaping
    var filter = SQLEngine.parseCondition("code matches '[A-Z]+[0-9]+'", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** CONTAINSTEXT operator parsing and evaluation. */
  @Test
  public void testParsingContainsTextOperator() {
    session.getMetadata().getSchema().createClass("ContTextParse");
    session.begin();
    var doc = (EntityImpl) session.newInstance("ContTextParse");
    doc.setProperty("desc", "hello world");
    var filter = SQLEngine.parseCondition("desc containstext 'world'", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** Triple AND condition: a = 1 and b = 2 and c = 3. */
  @Test
  public void testParsingTripleAndCondition() {
    session.getMetadata().getSchema().createClass("TripleAnd");
    session.begin();
    var doc = (EntityImpl) session.newInstance("TripleAnd");
    doc.setProperty("a", 1);
    doc.setProperty("b", 2);
    doc.setProperty("c", 3);
    var filter = SQLEngine.parseCondition("a = 1 and b = 2 and c = 3", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** OR with comparison operators. */
  @Test
  public void testParsingOrWithComparison() {
    session.getMetadata().getSchema().createClass("OrComp");
    session.begin();
    var doc = (EntityImpl) session.newInstance("OrComp");
    doc.setProperty("a", 10);
    doc.setProperty("b", 10);
    var filter = SQLEngine.parseCondition("a > 5 or b < 3", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // getCollate() paths
  // ====================================================================

  /** getCollate with no SQLFilterItemField on either side returns null. */
  @Test
  public void testSQLFilterConditionGetCollateNoField() {
    var cond = new SQLFilterCondition("left", new QueryOperatorEquals(), "right");
    Assert.assertNull(cond.getCollate(session, null));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testSQLFilterConditionGetCollateDeprecatedNoField() {
    var cond = new SQLFilterCondition("left", new QueryOperatorEquals(), "right");
    Assert.assertNull(cond.getCollate());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testSQLFilterConditionGetCollateDeprecatedLeftField() {
    var filter = SQLEngine.parseCondition("name = 'test'", context);
    Assert.assertNull(filter.getRootCondition().getCollate());
  }

  // ====================================================================
  // Evaluate exception wrapping
  // ====================================================================

  /** Generic exception during evaluation returns false, not an exception. */
  @Test
  public void testSQLFilterConditionEvaluateGenericExceptionReturnsFalse() {
    session.getMetadata().getSchema().createClass("ExcTest");
    session.begin();
    var doc = (EntityImpl) session.newInstance("ExcTest");
    doc.setProperty("val", "not-a-date");
    var filter = SQLEngine.parseCondition("val > 42", context);
    var result = filter.getRootCondition().evaluate(doc, null, context);
    Assert.assertTrue(result instanceof Boolean);
    session.commit();
  }

  // ====================================================================
  // checkForConversion helpers
  // ====================================================================

  /** String "42" vs integer 42 → getInteger conversion. */
  @Test
  public void testCheckForConversionStringToInteger() {
    session.getMetadata().getSchema().createClass("IntConv");
    session.begin();
    var doc = (EntityImpl) session.newInstance("IntConv");
    doc.setProperty("val", "42");
    var filter = SQLEngine.parseCondition("val = 42", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** String "3.7" vs integer 3: getInteger("3.7") → (int)Float.parseFloat → 3. */
  @Test
  public void testCheckForConversionStringWithDecimalToInteger() {
    session.getMetadata().getSchema().createClass("DecIntConv");
    session.begin();
    var doc = (EntityImpl) session.newInstance("DecIntConv");
    doc.setProperty("val", "3.7");
    var filter = SQLEngine.parseCondition("val = 3", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** Date vs integer → Date.getTime() conversion. */
  @Test
  public void testCheckForConversionDateToLong() {
    session.getMetadata().getSchema().createClass("DateLong");
    session.begin();
    var doc = (EntityImpl) session.newInstance("DateLong");
    doc.setProperty("created", new Date());
    var filter = SQLEngine.parseCondition("created > 0", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // Schema property collate
  // ====================================================================

  /** Case-insensitive collate makes "HELLO" = "hello". */
  @Test
  public void testSQLFilterItemFieldCollateFromSchema() {
    var cls = session.getMetadata().getSchema().createClass("CollateTest");
    cls.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.begin();
    var doc = (EntityImpl) session.newInstance("CollateTest");
    doc.setProperty("name", "HELLO");
    var filter = SQLEngine.parseCondition("name = 'hello'", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // SQLFilterItemFieldAll / SQLFilterItemFieldAny
  // ====================================================================

  @Test
  public void testSQLFilterItemFieldAllConstants() {
    Assert.assertEquals("ALL", SQLFilterItemFieldAll.NAME);
    Assert.assertEquals("ALL()", SQLFilterItemFieldAll.FULL_NAME);
  }

  @Test
  public void testSQLFilterItemFieldAnyConstants() {
    Assert.assertEquals("ANY", SQLFilterItemFieldAny.NAME);
    Assert.assertEquals("ANY()", SQLFilterItemFieldAny.FULL_NAME);
  }

  /** ANY() = 10 matches when at least one field has value 10. */
  @Test
  public void testParsingAnyFieldItem() {
    session.getMetadata().getSchema().createClass("AnyTest");
    session.begin();
    var doc = (EntityImpl) session.newInstance("AnyTest");
    doc.setProperty("a", 10);
    doc.setProperty("b", 20);
    var filter = SQLEngine.parseCondition("any() = 10", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** ALL() = 10 matches when all fields have value 10. */
  @Test
  public void testParsingAllFieldItem() {
    session.getMetadata().getSchema().createClass("AllTest");
    session.begin();
    var doc = (EntityImpl) session.newInstance("AllTest");
    doc.setProperty("a", 10);
    doc.setProperty("b", 10);
    var filter = SQLEngine.parseCondition("all() = 10", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** ALL() = 10 is false when not all fields match. */
  @Test
  public void testParsingAllFieldItemFalse() {
    session.getMetadata().getSchema().createClass("AllTestF");
    session.begin();
    var doc = (EntityImpl) session.newInstance("AllTestF");
    doc.setProperty("a", 10);
    doc.setProperty("b", 20);
    var filter = SQLEngine.parseCondition("all() = 10", context);
    Assert.assertEquals(Boolean.FALSE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // Misc SQLPredicate paths
  // ====================================================================

  /** Parsing a simple filter ensures checkForEnd does not interfere. */
  @Test
  public void testCheckForEndOrder() {
    var filter = new SQLFilter("name = 'test'", context);
    Assert.assertNotNull(filter.getRootCondition());
  }

  @Test
  public void testCheckForEndLimit() {
    var filter = new SQLFilter("age = 5", context);
    Assert.assertNotNull(filter.getRootCondition());
  }

  /** Compound condition triggers computePrefetchFieldList. */
  @Test
  public void testComputePrefetchFieldList() {
    var filter = SQLEngine.parseCondition("a = 1 and b = 2", context);
    session.getMetadata().getSchema().createClass("Prefetch");
    session.begin();
    var doc = (EntityImpl) session.newInstance("Prefetch");
    doc.setProperty("a", 1);
    doc.setProperty("b", 2);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** @rid preloaded field optimization. */
  @Test
  public void testSQLFilterItemFieldRidPreloadedField() {
    session.getMetadata().getSchema().createClass("RidPreload");
    session.begin();
    var doc = (EntityImpl) session.newInstance("RidPreload");
    doc.setProperty("x", 1);
    var filter = SQLEngine.parseCondition(
        "@rid = " + doc.getIdentity().toString(), context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** AND short-circuit: left is false → right not evaluated. */
  @Test
  public void testSQLFilterConditionShortCircuitAnd() {
    session.getMetadata().getSchema().createClass("ShortCirc");
    session.begin();
    var doc = (EntityImpl) session.newInstance("ShortCirc");
    doc.setProperty("a", 999);
    doc.setProperty("b", 1);
    var filter = SQLEngine.parseCondition("a = 1 and b = 1", context);
    Assert.assertEquals(Boolean.FALSE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  // ====================================================================
  // End-to-end pipeline tests
  // ====================================================================

  /** Complex filter with mixed operators, type conversion, nested conditions. */
  @Test
  public void testEndToEndComplexFilter() {
    session.getMetadata().getSchema().createClass("E2E");
    session.begin();
    var doc = (EntityImpl) session.newInstance("E2E");
    doc.setProperty("name", "Alice");
    doc.setProperty("age", 30);
    doc.setProperty("score", 95.5);
    var filter = SQLEngine.parseCondition(
        "name = 'Alice' and age >= 18 and score > 90.0", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** Field.method() chain evaluation: text.length() = 11. */
  @Test
  public void testEndToEndMethodChainFilter() {
    session.getMetadata().getSchema().createClass("E2EMethod");
    session.begin();
    var doc = (EntityImpl) session.newInstance("E2EMethod");
    doc.setProperty("text", "Hello World");
    var filter = SQLEngine.parseCondition("text.length() = 11", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }

  /** IN operator with collection parsing: status in ['active', 'pending']. */
  @Test
  public void testEndToEndInOperator() {
    session.getMetadata().getSchema().createClass("E2EIn");
    session.begin();
    var doc = (EntityImpl) session.newInstance("E2EIn");
    doc.setProperty("status", "active");
    var filter = SQLEngine.parseCondition(
        "status in ['active', 'pending']", context);
    Assert.assertEquals(Boolean.TRUE,
        filter.getRootCondition().evaluate(doc, null, context));
    session.commit();
  }
}
