package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import org.junit.Test;

/**
 * {@link OrderByCollationResolver} pins the collation of each ORDER BY item from the schema, once
 * per plan build. The cases below cover the three answers it can give: the declared collation of the
 * ordered property, no collation because the item is not one plain property, and no collation
 * because two classes of one hierarchy disagree about the name.
 */
public class OrderByCollationResolverTest extends DbTestBase {

  /**
   * Scenario: translator-generated ordering passes through every AST copy used by planning.
   * Expected: the provenance marker remains present on the copied item.
   */
  @Test
  public void translatorMarker_survivesOrderByCopy() {
    var item = ProjectionExpressionFactories.orderByProperty("v", "value", true);
    var orderBy = ProjectionExpressionFactories.orderBy(List.of(item));

    assertThat(item.isGremlinToMatchTranslatorProduced()).isTrue();
    assertThat(orderBy.copy().getItems().getFirst().isGremlinToMatchTranslatorProduced()).isTrue();
    assertThat(orderBy.copy().getItems().getFirst().copy().isGremlinToMatchTranslatorProduced())
        .isTrue();
  }

  /**
   * Scenario: only translated property items need TinkerPop type ordering. Expected: the appended
   * record identifier remains unmarked, while the translated property item carries provenance.
   */
  @Test
  public void translatorMarker_marksPropertiesButNotRecordAttributes() {
    var property = ProjectionExpressionFactories.orderByProperty("v", "value", true);
    var recordId = ProjectionExpressionFactories.orderByRecordAttribute("v", "@rid", true);

    assertThat(property.isGremlinToMatchTranslatorProduced()).isTrue();
    assertThat(recordId.isGremlinToMatchTranslatorProduced()).isFalse();
  }

  /**
   * Scenario: a SELECT item naming a property declared case-insensitive on the target class.
   * Expected: the item carries that declaration, so the sort comparator follows it.
   */
  @Test
  public void resolveOnTargetClass_declaredCaseInsensitiveProperty_pinsThatCollation() {
    var doc = session.getMetadata().getSchema().createClass("Doc");
    doc.createProperty("name", PropertyType.STRING).setCollate("ci");

    var orderBy = selectOrderBy("name");
    OrderByCollationResolver.resolveOnTargetClass(orderBy, doc);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate())
        .isInstanceOf(CaseInsensitiveCollate.class);
  }

  /**
   * Scenario: a SELECT item naming a property that declares no collation. Expected: no declaration
   * is pinned, which leaves the item on the default collation — the case-sensitive rule that also
   * governs the native Gremlin pipeline.
   */
  @Test
  public void resolveOnTargetClass_propertyWithoutDeclaration_pinsNothing() {
    var doc = session.getMetadata().getSchema().createClass("Doc");
    doc.createProperty("name", PropertyType.STRING);

    var orderBy = selectOrderBy("name");
    OrderByCollationResolver.resolveOnTargetClass(orderBy, doc);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: a schema-less name, present on no property of the target class. Expected: no
   * declaration, because nothing in the schema speaks about the column.
   */
  @Test
  public void resolveOnTargetClass_undeclaredName_pinsNothing() {
    var doc = session.getMetadata().getSchema().createClass("Doc");

    var orderBy = selectOrderBy("name");
    OrderByCollationResolver.resolveOnTargetClass(orderBy, doc);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: two subclasses of one polymorphic target declare the same name with different
   * collations. Expected: no declaration, because a query over the base returns rows of both and no
   * single rule governs the column.
   */
  @Test
  public void resolveOnTargetClass_subclassesDisagree_fallsBackToTheDefault() {
    var base = session.getMetadata().getSchema().createClass("Base");
    var insensitive = session.getMetadata().getSchema().createClass("Insensitive", base);
    insensitive.createProperty("name", PropertyType.STRING).setCollate("ci");
    var sensitive = session.getMetadata().getSchema().createClass("Sensitive", base);
    sensitive.createProperty("name", PropertyType.STRING);

    var orderBy = selectOrderBy("name");
    OrderByCollationResolver.resolveOnTargetClass(orderBy, base);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: every subclass that carries the name declares the same case-insensitive collation, and
   * the base carries no such property at all. Expected: that shared declaration is pinned — a class
   * without the property stores the value schema-less, where the default collation applies either
   * way, so it is no disagreement.
   */
  @Test
  public void resolveOnTargetClass_subclassesAgree_pinsTheSharedCollation() {
    var base = session.getMetadata().getSchema().createClass("Base");
    var first = session.getMetadata().getSchema().createClass("First", base);
    first.createProperty("name", PropertyType.STRING).setCollate("ci");
    var second = session.getMetadata().getSchema().createClass("Second", base);
    second.createProperty("name", PropertyType.STRING).setCollate("ci");

    var orderBy = selectOrderBy("name");
    OrderByCollationResolver.resolveOnTargetClass(orderBy, base);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate())
        .isInstanceOf(CaseInsensitiveCollate.class);
  }

  /**
   * Scenario: an item whose target class is unknown, which is what a subquery target or an
   * expression target yields. Expected: no declaration, and no failure.
   */
  @Test
  public void resolveOnTargetClass_unknownTargetClass_pinsNothing() {
    var orderBy = selectOrderBy("name");
    OrderByCollationResolver.resolveOnTargetClass(orderBy, null);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: a record attribute sort item, {@code ORDER BY @rid}. Expected: no declaration, because
   * a record attribute is not a property the schema can collate.
   */
  @Test
  public void resolveOnTargetClass_recordAttributeItem_pinsNothing() {
    var doc = session.getMetadata().getSchema().createClass("Doc");
    doc.createProperty("name", PropertyType.STRING).setCollate("ci");

    var item = new SQLOrderByItem();
    item.setRecordAttr("@rid");
    var orderBy = ProjectionExpressionFactories.orderBy(List.of(item));
    OrderByCollationResolver.resolveOnTargetClass(orderBy, doc);

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: a MATCH item, {@code ORDER BY person.name}, where the alias is bound to a class that
   * declares the property case-insensitive. Expected: the declaration is pinned, read through the
   * alias-to-class map the MATCH planner supplies.
   */
  @Test
  public void resolveOnAliasClasses_aliasProperty_pinsTheDeclarationOfTheAliasClass() {
    var person = session.getMetadata().getSchema().createClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");

    var orderBy =
        ProjectionExpressionFactories.orderBy(
            List.of(ProjectionExpressionFactories.orderByProperty("person", "name", true)));
    OrderByCollationResolver.resolveOnAliasClasses(orderBy, alias -> resolveClass(alias, person));

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate())
        .isInstanceOf(CaseInsensitiveCollate.class);
  }

  /**
   * Scenario: a MATCH item whose alias carries no class constraint. Expected: no declaration,
   * because the collation of a property cannot be read without a class.
   */
  @Test
  public void resolveOnAliasClasses_unconstrainedAlias_pinsNothing() {
    var person = session.getMetadata().getSchema().createClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");

    var orderBy =
        ProjectionExpressionFactories.orderBy(
            List.of(ProjectionExpressionFactories.orderByProperty("other", "name", true)));
    OrderByCollationResolver.resolveOnAliasClasses(orderBy, alias -> resolveClass(alias, person));

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: a MATCH item reaching a record attribute, {@code ORDER BY person.@rid}. Expected: no
   * declaration, for the same reason the SELECT record-attribute case pins none.
   */
  @Test
  public void resolveOnAliasClasses_aliasRecordAttribute_pinsNothing() {
    var person = session.getMetadata().getSchema().createClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");

    var orderBy =
        ProjectionExpressionFactories.orderBy(
            List.of(ProjectionExpressionFactories.orderByRecordAttribute("person", "@rid", true)));
    OrderByCollationResolver.resolveOnAliasClasses(orderBy, alias -> resolveClass(alias, person));

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /**
   * Scenario: a two-segment link chain, {@code ORDER BY person.friend.name}. Expected: no
   * declaration, because the value comes from a linked record rather than from a property of the
   * alias class.
   */
  @Test
  public void resolveOnAliasClasses_linkChainItem_pinsNothing() {
    var person = session.getMetadata().getSchema().createClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");

    var item = ProjectionExpressionFactories.orderByProperty("person", "friend", true);
    item.getModifier().next = chainedPropertyModifier("name");
    var orderBy = ProjectionExpressionFactories.orderBy(List.of(item));
    OrderByCollationResolver.resolveOnAliasClasses(orderBy, alias -> resolveClass(alias, person));

    assertThat(orderBy.getItems().getFirst().getDeclaredCollate()).isNull();
  }

  /** An empty clause must resolve without touching anything. */
  @Test
  public void resolveOnTargetClass_nullClause_doesNothing() {
    OrderByCollationResolver.resolveOnTargetClass(null, null);
    OrderByCollationResolver.resolveOnAliasClasses(null, alias -> null);
  }

  /** {@code ORDER BY <property>} as the SELECT planner builds it: the alias is the property name. */
  private static SQLOrderBy selectOrderBy(String propertyName) {
    var item = new SQLOrderByItem();
    item.setAlias(propertyName);
    return ProjectionExpressionFactories.orderBy(List.of(item));
  }

  /** The one-entry alias-to-class map the MATCH cases need. */
  private static SchemaClass resolveClass(String alias, SchemaClass person) {
    return "person".equals(alias) ? person : null;
  }

  private static SQLModifier chainedPropertyModifier(String propertyName) {
    var modifier = new SQLModifier(-1);
    modifier.suffix = new SQLSuffixIdentifier(new SQLIdentifier(propertyName));
    return modifier;
  }
}
