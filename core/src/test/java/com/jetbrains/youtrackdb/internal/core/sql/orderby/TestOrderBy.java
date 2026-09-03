package com.jetbrains.youtrackdb.internal.core.sql.orderby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * {@code ORDER BY} over text compares through the collation the property declares, and a property
 * that declares nothing takes the default collation, which is plain code-point comparison.
 *
 * <p>Both tests below set the session locale to Germany and then assert an order that ignores it.
 * The locale is kept in the fixture on purpose: it is the input that used to select a
 * {@code java.text.Collator} for the in-memory sort, and the assertion is what pins that it no
 * longer does. Under German collation rules {@code Ähhhh} sorts next to {@code Ahhhh}; under the
 * default collation it sorts after {@code Zebra}, because {@code Ä} is code point 196 and {@code Z}
 * is 90.
 *
 * <p>A stated {@code COLLATE} clause carries one further duty, pinned by the binary case below: it
 * must order a value whose class is not {@code Comparable} as well, through the comparator registry
 * that knows such classes.
 */
public class TestOrderBy extends DbTestBase {

  /**
   * Scenario: three names, one of them accented, ordered by an unindexed property while the session
   * declares the German locale. Expected: the in-memory sort answers plain code-point order,
   * ascending and then descending, rather than German collation order.
   */
  @Test
  public void testGermanOrderBy() {
    session.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMANY.getCountry());
    session.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE, Locale.GERMANY.getLanguage());
    session.getMetadata().getSchema().createClass("test");

    session.begin();
    var accented = session.newEntity("test");
    accented.setProperty("name", "Ähhhh");
    var plain = session.newEntity("test");
    plain.setProperty("name", "Ahhhh");
    var last = session.newEntity("test");
    last.setProperty("name", "Zebra");
    session.commit();

    session.begin();
    var queryRes =
        session.query("select from test order by name").stream().collect(Collectors.toList());
    assertEquals(plain.getIdentity(), queryRes.get(0).getIdentity());
    assertEquals(last.getIdentity(), queryRes.get(1).getIdentity());
    assertEquals(accented.getIdentity(), queryRes.get(2).getIdentity());

    queryRes =
        session.query("select from test order by name desc ").stream().collect(Collectors.toList());
    assertEquals(accented.getIdentity(), queryRes.get(0).getIdentity());
    assertEquals(last.getIdentity(), queryRes.get(1).getIdentity());
    assertEquals(plain.getIdentity(), queryRes.get(2).getIdentity());
    session.commit();
  }

  /**
   * Scenario: a projection alias shadows a case-insensitive schema property with the same name.
   * Expected: ORDER BY name uses the default collation for the projected surname values.
   */
  @Test
  public void projectionAlias_doesNotInheritTheShadowedPropertyCollation() {
    var person = session.getMetadata().getSchema().createClass("projectionAliasOrder");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    person.createProperty("surname", PropertyType.STRING);

    session.begin();
    var zebra = session.newEntity("projectionAliasOrder");
    zebra.setProperty("surname", "Zebra");
    var ada = session.newEntity("projectionAliasOrder");
    ada.setProperty("surname", "ada");
    session.commit();

    session.begin();
    var rows = session.query("select surname as name from projectionAliasOrder order by name")
        .stream().collect(Collectors.toList());
    assertThat(rows).extracting(row -> row.getProperty("name")).containsExactly("Zebra", "ada");
    session.commit();
  }

  /**
   * Scenario: an alias repeats the name of its case-insensitive source property. Expected: the
   * alias does not shadow a different expression, so the property's declaration still governs.
   */
  @Test
  public void sameNameProjectionAliasKeepsPropertyCollation() {
    var person = session.getMetadata().getSchema().createClass("sameNameProjectionAliasOrder");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");

    session.begin();
    session.newEntity("sameNameProjectionAliasOrder").setProperty("name", "Zebra");
    session.newEntity("sameNameProjectionAliasOrder").setProperty("name", "ada");
    session.newEntity("sameNameProjectionAliasOrder").setProperty("name", "Ada");
    session.commit();

    session.begin();
    var rows = session.query(
        "select name as name from sameNameProjectionAliasOrder order by name")
        .stream().collect(Collectors.toList());
    assertThat(rows).extracting(row -> row.getProperty("name"))
        .containsExactly("Ada", "ada", "Zebra");
    session.commit();
  }

  /**
   * Scenario: a plain projection orders the declared surname property directly while another
   * property named name declares case-insensitive collation. Expected: surname uses its default.
   */
  @Test
  public void plainProjection_ordersTheNamedPropertyWithItsDeclaration() {
    var person = session.getMetadata().getSchema().createClass("plainProjectionOrder");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    person.createProperty("surname", PropertyType.STRING);

    session.begin();
    session.newEntity("plainProjectionOrder").setProperty("surname", "Zebra");
    session.newEntity("plainProjectionOrder").setProperty("surname", "ada");
    session.commit();

    session.begin();
    var rows = session.query("select surname from plainProjectionOrder order by surname")
        .stream().collect(Collectors.toList());
    assertThat(rows).extracting(row -> row.getProperty("surname")).containsExactly("Zebra", "ada");
    session.commit();
  }

  /**
   * Scenario: three binary values ordered by a stated {@code COLLATE} clause. Expected: byte-wise
   * ascending order, and its exact reverse descending.
   *
   * <p>A byte array is not {@code Comparable}, so only the comparator registry behind the collation
   * can order it. A comparison that first demanded {@code Comparable} reported every pair equal, and
   * a stated ordering then answered whatever sequence the scan happened to produce.
   */
  @Test
  public void statedCollateOrdersValuesThatAreNotComparable() {
    var clazz = session.getMetadata().getSchema().createClass("binaryTest");
    clazz.createProperty("data", PropertyType.BINARY);

    session.begin();
    var middle = session.newEntity("binaryTest");
    middle.setProperty("data", new byte[] {2, 0});
    var last = session.newEntity("binaryTest");
    last.setProperty("data", new byte[] {3, 0});
    var first = session.newEntity("binaryTest");
    first.setProperty("data", new byte[] {1, 0});
    session.commit();

    session.begin();
    assertThat(identitiesOf("select from binaryTest order by data collate default"))
        .as("a stated collation must order a binary value, not tie it")
        .containsExactly(first.getIdentity(), middle.getIdentity(), last.getIdentity());
    assertThat(identitiesOf("select from binaryTest order by data desc collate default"))
        .as("the descending direction must reverse that same order")
        .containsExactly(last.getIdentity(), middle.getIdentity(), first.getIdentity());
    session.commit();
  }

  /**
   * Scenario: a plain SELECT over a case-insensitive property uses its index, while an explicit
   * default collation forces the buffered comparison. Expected: both plans return the same first row
   * after the planner refuses the index whose collation differs from the sort comparison.
   */
  @Test
  public void declaredCollationDoesNotUseMismatchedIndexForSelectOrder() {
    var clazz = session.getMetadata().getSchema().createClass("collatedSelect");
    clazz.createProperty("name", PropertyType.STRING)
        .setCollate("ci")
        .createIndex(INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var zebra = session.newEntity("collatedSelect");
    zebra.setProperty("name", "Zebra");
    var ada = session.newEntity("collatedSelect");
    ada.setProperty("name", "ada");
    var first = session.newEntity("collatedSelect");
    first.setProperty("name", "Ada");
    session.commit();

    session.begin();
    assertThat(identitiesOf("select from collatedSelect order by name"))
        .as("the plain SELECT must agree with its buffered default-collation control")
        .startsWith(first.getIdentity());
    assertThat(identitiesOf("select from collatedSelect order by name collate default"))
        .as("explicit default collation is the buffered reference order")
        .startsWith(first.getIdentity());
    session.commit();
  }

  /**
   * Scenario: a WHERE range uses a case-insensitive index before ordering that declared property.
   * Expected: the fully-sorted shortcut is refused and the declared comparison determines output.
   */
  @Test
  public void declaredCollationWithWhereDoesNotClaimIndexOrder() {
    var clazz = session.getMetadata().getSchema().createClass("collatedWhereSelect");
    clazz.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.execute(
        "create index collatedWhereSelect.nameDefault on collatedWhereSelect"
            + " (name collate default) NOTUNIQUE")
        .close();

    session.begin();
    session.newEntity("collatedWhereSelect").setProperty("name", "Zebra");
    session.newEntity("collatedWhereSelect").setProperty("name", "ada");
    session.newEntity("collatedWhereSelect").setProperty("name", "Ada");
    session.commit();

    session.begin();
    var rows = session.query(
        "select from collatedWhereSelect where name > 'A' order by name").toList();
    assertThat(rows).extracting(row -> row.<String>getProperty("name"))
        .containsExactly("Ada", "ada", "Zebra");
    session.commit();
  }

  /**
   * Scenario: a plain SELECT orders a collection-valued property backed by a multi-value index.
   * Expected: refusing that index preserves one result row per record, rather than one row per
   * indexed element.
   */
  @Test
  public void collectionIndexDoesNotSupplySelectOrder() {
    var clazz = session.getMetadata().getSchema().createClass("collectionSelect");
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING)
        .createIndex(INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var first = session.newEntity("collectionSelect");
    first.<String>getOrCreateEmbeddedList("tags").addAll(List.of("a", "b"));
    var second = session.newEntity("collectionSelect");
    second.<String>getOrCreateEmbeddedList("tags").add("c");
    session.commit();

    session.begin();
    assertThat(identitiesOf("select from collectionSelect order by tags"))
        .as("a multi-value index must not duplicate rows for each indexed element")
        .containsExactlyInAnyOrder(first.getIdentity(), second.getIdentity());
    session.commit();
  }

  /**
   * Scenario: a composite index starts with a collection property. Expected: the index cannot
   * supply ORDER BY because its entries represent collection elements, not result rows.
   */
  @Test
  public void compositeCollectionIndexDoesNotSupplySelectOrder() {
    var clazz = session.getMetadata().getSchema().createClass("compositeCollectionSelect");
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(
        "compositeCollectionSelect.tags_name", INDEX_TYPE.NOTUNIQUE, "tags", "name");

    session.begin();
    var first = session.newEntity("compositeCollectionSelect");
    first.setProperty("name", "first");
    first.<String>getOrCreateEmbeddedList("tags").addAll(List.of("a", "b"));
    var second = session.newEntity("compositeCollectionSelect");
    second.setProperty("name", "second");
    second.<String>getOrCreateEmbeddedList("tags").add("c");
    session.commit();

    session.begin();
    assertThat(identitiesOf("select from compositeCollectionSelect order by tags, name"))
        .as("a composite multi-value index must not emit one row per collection element")
        .containsExactlyInAnyOrder(first.getIdentity(), second.getIdentity());
    session.commit();
  }

  /** The identities of every row of {@code query}, in arrival order. */
  private List<RID> identitiesOf(String query) {
    try (var result = session.query(query)) {
      return result.stream().map(row -> row.getIdentity()).collect(Collectors.toList());
    }
  }

  /**
   * Scenario: the same three names ordered by an indexed property, so the plan may serve the order
   * from the index instead of sorting in memory. Expected: the same plain code-point order as the
   * unindexed case.
   *
   * <p>This test was disabled because the two mechanisms disagreed. The index has always ordered
   * text by the declared collation, while the in-memory sort used a locale collator, so whichever
   * one the planner picked decided the answer. Both now follow the declaration, so the case is live
   * again and pins that agreement.
   */
  @Test
  public void testGermanOrderByIndex() {
    session.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMANY.getCountry());
    session.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE, Locale.GERMANY.getLanguage());

    var clazz = session.getMetadata().getSchema().createClass("test");
    clazz.createProperty("name", PropertyType.STRING)
        .createIndex(INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var accented = session.newEntity("test");
    accented.setProperty("name", "Ähhhh");
    var plain = session.newEntity("test");
    plain.setProperty("name", "Ahhhh");
    var last = session.newEntity("test");
    last.setProperty("name", "Zebra");
    session.commit();

    session.begin();
    var queryRes =
        session.query("select from test order by name").stream().collect(Collectors.toList());
    assertEquals(plain.getIdentity(), queryRes.get(0).getIdentity());
    assertEquals(last.getIdentity(), queryRes.get(1).getIdentity());
    assertEquals(accented.getIdentity(), queryRes.get(2).getIdentity());

    queryRes =
        session.query("select from test order by name desc ").stream().collect(Collectors.toList());
    assertEquals(accented.getIdentity(), queryRes.get(0).getIdentity());
    assertEquals(last.getIdentity(), queryRes.get(1).getIdentity());
    assertEquals(plain.getIdentity(), queryRes.get(2).getIdentity());
    session.commit();
  }
}
