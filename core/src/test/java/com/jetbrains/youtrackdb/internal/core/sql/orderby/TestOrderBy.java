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
