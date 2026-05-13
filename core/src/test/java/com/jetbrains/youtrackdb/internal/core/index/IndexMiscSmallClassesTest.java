package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Standalone (no-DB) tests for the small, pure classes in the {@code core/index} package:
 * {@link IndexUpdateAction}, {@link IndexMetadata}, {@link IndexException}, and
 * {@link IndexEngineException}.
 *
 * <p>These classes require no database session and are covered entirely with plain unit tests.
 */
public class IndexMiscSmallClassesTest {

  // ═══════════════════════════════════════════════════════════════════════
  //  IndexUpdateAction — three singletons + changed factory
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * {@code IndexUpdateAction.nothing()} returns an action whose {@code isNothing()} is true
   * and whose other predicates are false. Calling {@code getValue()} must throw
   * {@link UnsupportedOperationException}.
   */
  @Test
  public void indexUpdateAction_nothing_predicatesAndExceptionOnGetValue() {
    IndexUpdateAction<?> action = IndexUpdateAction.nothing();

    assertTrue("isNothing() must be true for nothing()", action.isNothing());
    assertFalse("isChange() must be false for nothing()", action.isChange());
    assertFalse("isRemove() must be false for nothing()", action.isRemove());

    try {
      action.getValue();
      fail("nothing().getValue() must throw UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  /**
   * {@code IndexUpdateAction.remove()} returns an action whose {@code isRemove()} is true
   * and whose other predicates are false. Calling {@code getValue()} must throw
   * {@link UnsupportedOperationException}.
   */
  @Test
  public void indexUpdateAction_remove_predicatesAndExceptionOnGetValue() {
    IndexUpdateAction<?> action = IndexUpdateAction.remove();

    assertTrue("isRemove() must be true for remove()", action.isRemove());
    assertFalse("isChange() must be false for remove()", action.isChange());
    assertFalse("isNothing() must be false for remove()", action.isNothing());

    try {
      action.getValue();
      fail("remove().getValue() must throw UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  /**
   * {@code IndexUpdateAction.changed(value)} returns an action whose {@code isChange()} is
   * true and whose {@code getValue()} returns the provided value.
   */
  @Test
  public void indexUpdateAction_changed_predicatesAndValueAccess() {
    var newValue = "updated_value";
    @SuppressWarnings("unchecked")
    IndexUpdateAction<String> action =
        (IndexUpdateAction<String>) IndexUpdateAction.changed(newValue);

    assertTrue("isChange() must be true for changed()", action.isChange());
    assertFalse("isRemove() must be false for changed()", action.isRemove());
    assertFalse("isNothing() must be false for changed()", action.isNothing());
    assertEquals("getValue() must return the provided value", newValue, action.getValue());
  }

  /**
   * {@code nothing()} is a singleton — two calls return the same instance.
   */
  @Test
  public void indexUpdateAction_nothing_isSingleton() {
    assertSame("nothing() must return the same singleton instance",
        IndexUpdateAction.nothing(), IndexUpdateAction.nothing());
  }

  /**
   * {@code remove()} is a singleton — two calls return the same instance.
   */
  @Test
  public void indexUpdateAction_remove_isSingleton() {
    assertSame("remove() must return the same singleton instance",
        IndexUpdateAction.remove(), IndexUpdateAction.remove());
  }

  /**
   * {@code changed()} creates a new instance each time (not a singleton). Two calls with
   * the same value return different objects.
   */
  @Test
  public void indexUpdateAction_changed_createsNewInstanceEachTime() {
    var a1 = IndexUpdateAction.changed("v");
    var a2 = IndexUpdateAction.changed("v");
    // Different objects, but same value. assertNotSame is the canonical reference-inequality
    // idiom; assertFalse on `a1 == a2` works but is non-idiomatic.
    assertNotSame("changed() must allocate a fresh instance per call", a1, a2);
    assertEquals("both instances must report the same value", a1.getValue(), a2.getValue());
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  IndexMetadata
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Constructor round-trip: all supplied values are accessible via getters.
   */
  @Test
  public void indexMetadata_constructor_allFieldsAccessibleViaGetters() {
    Set<String> collections = new HashSet<>();
    collections.add("MyClass");
    Map<String, Object> meta = Map.of("key", "value");

    var im = new IndexMetadata("myIndex", null, collections, "UNIQUE", "BTREE", 1, meta);

    assertEquals("name must match", "myIndex", im.getName());
    assertNull("indexDefinition must be null", im.getIndexDefinition());
    assertEquals("collections must match", collections, im.getCollectionsToIndex());
    assertEquals("type must match", "UNIQUE", im.getType());
    assertEquals("algorithm must match", "BTREE", im.getAlgorithm());
    assertEquals("version must match", 1, im.getVersion());
    assertEquals("metadata must match", meta, im.getMetadata());
  }

  /**
   * {@code setVersion} / {@code setMetadata} mutators update the stored value.
   */
  @Test
  public void indexMetadata_mutators_updateStoredValues() {
    var im = new IndexMetadata("idx", null, new HashSet<>(), "NOTUNIQUE", "BTREE", 1, null);

    im.setVersion(42);
    assertEquals("setVersion must update the stored version", 42, im.getVersion());

    im.setMetadata(Map.of("updated", true));
    assertEquals("setMetadata must update the stored metadata map", Map.of("updated", true),
        im.getMetadata());
  }

  /**
   * {@code isMultivalue} returns true when the index type is NOTUNIQUE and false otherwise.
   */
  @Test
  public void indexMetadata_isMultivalue_trueForNotUnique_falseForUnique() {
    var notUnique = new IndexMetadata("a", null, new HashSet<>(), "NOTUNIQUE", "BTREE", 1, null);
    var unique = new IndexMetadata("b", null, new HashSet<>(), "UNIQUE", "BTREE", 1, null);

    assertTrue("NOTUNIQUE index must be multivalue", notUnique.isMultivalue());
    assertFalse("UNIQUE index must not be multivalue", unique.isMultivalue());
  }

  /**
   * {@code equals} and {@code hashCode} are consistent: two metadata objects with the same
   * name, type, algorithm, collections, and definition are equal with the same hash.
   */
  @Test
  public void indexMetadata_equalsAndHashCode_consistentForEqualObjects() {
    Set<String> cols = new HashSet<>();
    cols.add("MyClass");

    var im1 = new IndexMetadata("idx", null, cols, "UNIQUE", "BTREE", 1, null);
    var im2 = new IndexMetadata("idx", null, cols, "UNIQUE", "BTREE", 1, null);

    assertEquals("equal IndexMetadata objects must be ==equals", im1, im2);
    assertEquals("equal IndexMetadata objects must have the same hashCode",
        im1.hashCode(), im2.hashCode());
  }

  /**
   * {@code equals} returns false when names differ.
   */
  @Test
  public void indexMetadata_equals_differentName_returnsFalse() {
    var im1 = new IndexMetadata("idx1", null, new HashSet<>(), "UNIQUE", "BTREE", 1, null);
    var im2 = new IndexMetadata("idx2", null, new HashSet<>(), "UNIQUE", "BTREE", 1, null);

    assertFalse("metadata with different names must not be equal", im1.equals(im2));
  }

  /**
   * {@code equals} returns false when comparing to a non-IndexMetadata object.
   */
  @Test
  public void indexMetadata_equals_nonIndexMetadataObject_returnsFalse() {
    var im = new IndexMetadata("idx", null, new HashSet<>(), "UNIQUE", "BTREE", 1, null);
    //noinspection SimplifiableJUnitAssertion
    assertFalse("IndexMetadata must not equal a plain String", im.equals("not metadata"));
  }

  /**
   * {@code equals} is reflexive: an object is equal to itself.
   */
  @Test
  public void indexMetadata_equals_reflexive_returnsTrue() {
    var im = new IndexMetadata("idx", null, new HashSet<>(), "UNIQUE", "BTREE", 1, null);
    assertEquals("IndexMetadata must equal itself", im, im);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  IndexException — constructors
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * {@code IndexException(String message)} constructor constructs without a DB session.
   * The exception must be throwable and have the expected message.
   */
  @Test
  public void indexException_stringMessage_constructorWorks() {
    var ex = new IndexException("test error message");
    assertNotNull("exception must not be null", ex);
    assertTrue("message must contain the provided text",
        ex.getMessage().contains("test error message"));
  }

  /**
   * {@code IndexException(String dbName, String message)} constructor stores both values.
   * The message should be accessible via {@code getMessage()}.
   */
  @Test
  public void indexException_dbNameAndMessage_constructorWorks() {
    var ex = new IndexException("mydb", "index write failed");
    assertNotNull(ex);
    assertTrue("message must contain the error text",
        ex.getMessage().contains("index write failed"));
  }

  /**
   * {@code IndexException(IndexException)} copy constructor propagates the original exception
   * message — the copy is a faithful clone of the original.
   */
  @Test
  public void indexException_copyConstructor_propagatesMessage() {
    var original = new IndexException("original error");
    var copy = new IndexException(original);
    assertEquals("copy must preserve message", original.getMessage(), copy.getMessage());
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  IndexEngineException — constructors
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * {@code IndexEngineException(String dbName, String message)} constructor.
   */
  @Test
  public void indexEngineException_dbNameAndMessage_constructorWorks() {
    var ex = new IndexEngineException("mydb", "engine failure");
    assertNotNull("IndexEngineException must not be null", ex);
    assertTrue("message must contain the error text",
        ex.getMessage().contains("engine failure"));
  }

  /**
   * {@code IndexEngineException(String dbName, String message, String componentName)}
   * three-argument constructor.
   */
  @Test
  public void indexEngineException_threeArgConstructor_constructsSuccessfully() {
    var ex = new IndexEngineException("mydb", "write error", "BTreeEngine");
    assertNotNull("IndexEngineException with componentName must not be null", ex);
    assertTrue("message must contain the error text",
        ex.getMessage().contains("write error"));
  }

  /**
   * {@code IndexEngineException(IndexEngineException)} copy constructor propagates the
   * original error text and the dbName field. Note: the copy chain through CoreException
   * currently re-appends {@code DB Name="…"} a second time because the super-call passes
   * the already-formatted {@code exception.getMessage()} into BaseException, which then
   * re-formats it via the overridden {@code CoreException.getMessage()}. We assert the
   * original error text and the dbName are present rather than full-string equality, so
   * this test pins observable behaviour without locking in the double-append. WHEN-FIXED:
   * if the CoreException copy chain is hardened to avoid the re-format, tighten this to
   * assertEquals(original.getMessage(), copy.getMessage()).
   */
  @Test
  public void indexEngineException_copyConstructor_propagatesMessage() {
    var original = new IndexEngineException("db", "original engine error");
    var copy = new IndexEngineException(original);
    var copyMessage = copy.getMessage();
    assertTrue("copy must contain the original error text",
        copyMessage.contains("original engine error"));
    assertTrue("copy must preserve the dbName tag",
        copyMessage.contains("DB Name=\"db\""));
  }
}
