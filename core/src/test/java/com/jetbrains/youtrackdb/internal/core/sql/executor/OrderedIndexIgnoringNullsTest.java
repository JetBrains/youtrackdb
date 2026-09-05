package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests for the ordered-plan guard against an index that IGNORES NULL VALUES, recorded as BG501.
 *
 * <p>Such an index holds no entry for a record that lacks the indexed property, so a plan that
 * walks it to satisfy an ORDER BY drops that record from the result entirely. The project default
 * keeps null entries, which is why the defect is invisible under the default configuration and
 * only an explicitly configured index reaches it.
 *
 * <p>Three planner sites can pick an ordered index. Two of them lose the record and are guarded:
 * the sort-only index scan of {@code SelectExecutionPlanner.handleClassWithIndexForSortOnly} and
 * the index-ordered MATCH traversal of {@code IndexOrderedPlanner.detect}. The third, the
 * filtering index scan that sets the order-applied flag, is covered here by a CONTRACT test: a
 * record without the key is invisible to a filtering scan on that key whether or not the query
 * orders by it, so no ordering decision there can be blamed for a loss.
 */
public class OrderedIndexIgnoringNullsTest extends DbTestBase {

  /**
   * Fan-in fixture with one key-less target. {@code Ann} and {@code Eve} both know {@code Bea},
   * {@code Cid} and {@code Nemo}; only {@code Nemo} carries no {@code id}. The index on
   * {@code Person.id} is created with {@code ignoreNullValues: true}, so {@code Nemo} holds no
   * index entry.
   */
  private void seedPeopleWhereOneLacksTheKey() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    session.createEdgeClass("knows");
    session.execute(
        "CREATE INDEX Person.id ON Person (id) NOTUNIQUE METADATA {ignoreNullValues: true}")
        .close();

    session.begin();
    for (var spec : new String[][] {
        {"a", "Ann"}, {"e", "Eve"}, {"b", "Bea"}, {"c", "Cid"}}) {
      session.execute(
          "CREATE VERTEX Person SET id = '" + spec[0] + "', name = '" + spec[1] + "'").close();
    }
    session.execute("CREATE VERTEX Person SET name = 'Nemo'").close();
    for (var src : new String[] {"Ann", "Eve"}) {
      session.execute(
          "CREATE EDGE knows FROM (SELECT FROM Person WHERE name = '" + src + "')"
              + " TO (SELECT FROM Person WHERE name IN ['Bea', 'Cid', 'Nemo'])")
          .close();
    }
    session.commit();
  }

  private List<String> names(String query, String column) {
    var result = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> result.add(String.valueOf((Object) row.getProperty(column))));
    }
    return result;
  }

  private String plan(String query) {
    try (var rs = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) rs.next().getProperty("executionPlanAsString"));
    }
  }

  /**
   * SITE 1, the sort-only index scan. {@code SELECT ... ORDER BY id} must return all five people
   * including the one without an {@code id}. Before the guard the planner walked the null-ignoring
   * index and returned four.
   */
  @Test
  public void plainOrderByKeepsTheRecordLackingTheKey() {
    seedPeopleWhereOneLacksTheKey();
    var query = "SELECT name FROM Person ORDER BY id";
    assertThat(plan(query))
        .as("the guard must refuse the null-ignoring index and sort in memory instead")
        .doesNotContain("FETCH FROM INDEX VALUES");
    assertThat(names(query, "name"))
        .as("the key-less record sorts first as a null key and must not be dropped")
        .containsExactly("Nemo", "Ann", "Bea", "Cid", "Eve");
  }

  /**
   * SITE 1 descending. The direction does not change eligibility, and the key-less record sorts
   * last instead of first.
   */
  @Test
  public void plainOrderByDescendingKeepsTheRecordLackingTheKey() {
    seedPeopleWhereOneLacksTheKey();
    assertThat(names("SELECT name FROM Person ORDER BY id DESC", "name"))
        .containsExactly("Eve", "Cid", "Bea", "Ann", "Nemo");
  }

  /**
   * SITE 1 with a WHERE clause that no index serves. The sort-only path is reached after the
   * filtering attempts fail, so a query whose predicate SELECTS the key-less record used to
   * return nothing at all: the ordered index scan never produced the record the filter wanted.
   */
  @Test
  public void orderByWithAPredicateSelectingTheKeyLessRecordReturnsIt() {
    seedPeopleWhereOneLacksTheKey();
    assertThat(names("SELECT name FROM Person WHERE id IS NULL ORDER BY id", "name"))
        .as("the only record without an id must survive the ordered plan")
        .containsExactly("Nemo");
  }

  /**
   * SITE 2, the index-ordered MATCH traversal. The pattern binds six times, twice per target, and
   * the key-less target must contribute two of those rows. Before the guard the index-ordered
   * scan visited only the indexed targets and returned four rows.
   */
  @Test
  public void indexOrderedMatchKeepsTheTargetLackingTheKey() {
    seedPeopleWhereOneLacksTheKey();
    var query =
        "MATCH {as: src, class: Person}.out('knows'){as: dst, class: Person}"
            + " RETURN src.name, dst.name ORDER BY dst.id";
    assertThat(plan(query))
        .as("the guard must refuse the index-ordered traversal on a null-ignoring index")
        .doesNotContain("INDEX ORDERED MATCH");
    assertThat(names(query, "dst.name"))
        .as("two sources reach each of the three targets, including the key-less one")
        .containsExactly("Nemo", "Nemo", "Bea", "Bea", "Cid", "Cid");
  }

  /**
   * SITE 2 under a LIMIT. The bound does not re-admit the refused index, and the cut lands inside
   * the key-less block because a null key sorts first ascending.
   */
  @Test
  public void indexOrderedMatchUnderALimitKeepsTheTargetLackingTheKey() {
    seedPeopleWhereOneLacksTheKey();
    var query =
        "MATCH {as: src, class: Person}.out('knows'){as: dst, class: Person}"
            + " RETURN src.name, dst.name ORDER BY dst.id LIMIT 2";
    assertThat(names(query, "dst.name"))
        .as("both rows of the cut belong to the key-less target")
        .containsExactly("Nemo", "Nemo");
  }

  /**
   * SITE 3, the filtering index scan that sets the order-applied flag. CONTRACT, not a guard: a
   * record lacking the key cannot satisfy a predicate on that key, so the filtering scan omits it
   * for a reason the ORDER BY does not create. The two queries below differ only by the ORDER BY,
   * and they return the same rows, which is the evidence that no ordering decision loses a row at
   * this site.
   */
  @Test
  public void filteringIndexScanLosesNoRowBecauseOfTheOrdering() {
    seedPeopleWhereOneLacksTheKey();
    var ordered = "SELECT name FROM Person WHERE id > 'a' ORDER BY id";
    var unordered = "SELECT name FROM Person WHERE id > 'a'";
    assertThat(plan(ordered))
        .as("the predicate is served by the index, which is what this site plans")
        .contains("FETCH FROM INDEX Person.id");
    assertThat(names(ordered, "name"))
        .as("ordering the filtered scan changes the sequence, never the membership")
        .containsExactlyInAnyOrderElementsOf(names(unordered, "name"));
    assertThat(names(ordered, "name"))
        .as("the key-less record is excluded by the predicate, not by the ordering")
        .containsExactly("Bea", "Cid", "Eve");
  }

  /**
   * A guarded query still honours the configured null placement, so the guard restores rows
   * without changing where a null key sorts. Also demonstrates the accepted cost: the plan now
   * carries an in-memory ORDER BY step for a query that used to stream from the index.
   */
  @Test
  public void guardedPlanSortsInMemoryAndPlacesTheNullKeyFirst() {
    seedPeopleWhereOneLacksTheKey();
    var query = "SELECT name FROM Person ORDER BY id";
    assertThat(plan(query))
        .as("the accepted cost of the guard is an in-memory sort")
        .contains("ORDER BY");
    assertThat(names(query, "name").getFirst()).isEqualTo("Nemo");
  }

  /**
   * WHY THE GUARD IS NOT RELAXED FOR A PRESENCE CONDITION.
   *
   * <p>An ordered plan under the portable Gremlin opt-out carries {@code key IS DEFINED} on the
   * ordered alias, which looks like a licence to walk a null-ignoring index: a record without
   * the key is excluded by the predicate anyway. IT IS NOT. {@code IS DEFINED} is an
   * ENTITY-LAYER test that matches a property present with a LITERAL NULL VALUE, while an index
   * that ignores null values stores no entry for that record. The two disagree on exactly one
   * class of record, and that record would be lost.
   *
   * <p>The fixture below holds one of each: {@code Nully} carries {@code id} set to null and
   * {@code Absent} carries no {@code id} at all. The presence condition keeps {@code Nully} and
   * drops {@code Absent}, so a relaxed guard would return one row where two are correct.
   * {@code IS NOT NULL} would be a sound trigger, but it is not what the translator emits.
   */
  @Test
  public void aPresenceConditionDoesNotMakeTheNullIgnoringIndexSound() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    session.execute(
        "CREATE INDEX Person.id ON Person (id) NOTUNIQUE METADATA {ignoreNullValues: true}")
        .close();
    session.begin();
    session.execute("CREATE VERTEX Person SET id = 'a', name = 'Ann'").close();
    session.execute("CREATE VERTEX Person SET id = null, name = 'Nully'").close();
    session.execute("CREATE VERTEX Person SET name = 'Absent'").close();
    session.commit();

    assertThat(names("SELECT name FROM Person WHERE id IS DEFINED", "name"))
        .as("a property present with a null value satisfies IS DEFINED")
        .containsExactlyInAnyOrder("Ann", "Nully");
    assertThat(names("SELECT name FROM Person WHERE id IS NOT NULL", "name"))
        .as("IS NOT NULL excludes it, which is why only that form would be a sound trigger")
        .containsExactly("Ann");
    assertThat(names("SELECT name FROM Person WHERE id IS DEFINED ORDER BY id", "name"))
        .as("the guard keeps both rows; a relaxed guard would lose the null-valued one")
        .containsExactly("Nully", "Ann");
  }

  /**
   * The guard is scoped to the null-ignoring configuration. An index created with the project
   * default keeps an entry for a key-less record, so it stays eligible for the ordered scan and
   * the record is still returned.
   */
  @Test
  public void defaultIndexStaysEligibleForTheOrderedScan() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    session.execute("CREATE INDEX Person.id ON Person (id) NOTUNIQUE").close();
    session.begin();
    session.execute("CREATE VERTEX Person SET id = 'a', name = 'Ann'").close();
    session.execute("CREATE VERTEX Person SET name = 'Nemo'").close();
    session.commit();

    var query = "SELECT name FROM Person ORDER BY id";
    assertThat(plan(query))
        .as("a default index keeps the null bucket, so the ordered scan stays available")
        .contains("FETCH FROM INDEX VALUES");
    assertThat(names(query, "name")).containsExactly("Nemo", "Ann");
  }

  /**
   * BG503 — an empty indexed collection produces no index entry ({@code ClassIndexManager}
   * iterates list keys and stores nothing for {@code []}). A filtering lookup on that index
   * therefore omits the empty-list record.
   *
   * <p>ORDER BY must not walk a multi-value index for the sort (YTDB-1289): that shortcut is
   * not total over the class, so the planner sorts in memory and keeps {@code Empty}.
   */
  @Test
  public void emptyIndexedCollectionIsInvisibleToFilteringIndexLookup() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    session.execute("CREATE INDEX Person.tags ON Person (tags) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX Person SET name = 'Ann', tags = ['a']").close();
    session.execute("CREATE VERTEX Person SET name = 'Empty', tags = []").close();
    session.execute("CREATE VERTEX Person SET name = 'Bea', tags = ['b']").close();
    session.commit();

    var orderQuery = "SELECT name FROM Person ORDER BY tags";
    assertThat(plan(orderQuery))
        .as("a multi-value index must not satisfy ORDER BY (YTDB-1289)")
        .doesNotContain("FETCH FROM INDEX");
    assertThat(names(orderQuery, "name"))
        .as("in-memory ORDER BY sees every class record, including the empty list")
        .contains("Ann", "Bea", "Empty");

    var filterQuery = "SELECT name FROM Person WHERE tags CONTAINS 'a'";
    assertThat(plan(filterQuery))
        .as("a filtering lookup may still use the list index")
        .contains("FETCH FROM INDEX");
    assertThat(names(filterQuery, "name"))
        .as("the empty-list record has no index entry, so a key lookup omits it")
        .containsExactly("Ann")
        .doesNotContain("Empty");
  }

  /**
   * BG502 — a UNIQUE index admits only one record that lacks the indexed property. The first
   * missing-property insert stores under the null key; a second raises
   * {@link RecordDuplicatedException}. Pin only: do not change the write path here.
   */
  @Test
  public void secondMissingPropertyInsertAgainstUniqueIndexThrowsDuplicate() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    person.createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX Person.id ON Person (id) UNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX Person SET name = 'First'").close();
    session.commit();

    session.begin();
    try {
      session.execute("CREATE VERTEX Person SET name = 'Second'").close();
      session.commit();
      throw new AssertionError(
          "expected RecordDuplicatedException on the second missing-property insert");
    } catch (RecordDuplicatedException expected) {
      session.rollback();
      assertThat(expected.getMessage())
          .as("the duplicate is the null-key slot of the unique index")
          .isNotBlank();
    }
  }
}
