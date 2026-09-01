package com.jetbrains.youtrackdb.internal.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests the key-change notification of {@link ContextConfiguration}.
 *
 * <p>An owner registers an observer to drop state it derived from the previous value of a key. A
 * storage does that for the raw text of a stored value it could not read. One configuration object
 * can have several owners, because an embedded instance hands its own configuration to every storage
 * it opens at startup. Every owner therefore has to be notified, and an owner that goes away has to
 * be able to deregister.
 */
public class ContextConfigurationKeyChangeTest {

  private static final GlobalConfiguration KEY =
      GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT;

  /**
   * Two observers registered on one configuration both receive every notification. A single
   * observer slot would displace the first registration, and that owner would then miss the change.
   */
  @Test
  public void twoObserversBothReceiveNotifications() {
    var configuration = new ContextConfiguration();
    var firstSeen = new ArrayList<String>();
    var secondSeen = new ArrayList<String>();
    configuration.addKeyChangeObserver(firstSeen::add);
    configuration.addKeyChangeObserver(secondSeen::add);

    configuration.setValue(KEY, OrderByNullsDefault.NULLS_LARGEST);

    assertEquals(2, configuration.getKeyChangeObserverCount());
    assertEquals(List.of(KEY.getKey()), firstSeen);
    assertEquals(List.of(KEY.getKey()), secondSeen);
  }

  /** A removal notifies as well, so an owner learns that a key was cleared. */
  @Test
  public void removalOfAValueAlsoNotifies() {
    var configuration = new ContextConfiguration();
    var seen = new ArrayList<String>();
    configuration.addKeyChangeObserver(seen::add);

    configuration.setValue(KEY, OrderByNullsDefault.NULLS_LARGEST);
    configuration.setValue(KEY, null);

    assertEquals(List.of(KEY.getKey(), KEY.getKey()), seen);
  }

  /**
   * A deregistered observer receives nothing further, while the other observer keeps receiving. This
   * is the property a closing storage configuration relies on.
   */
  @Test
  public void removedObserverStopsReceivingNotifications() {
    var configuration = new ContextConfiguration();
    var goneSeen = new ArrayList<String>();
    var liveSeen = new ArrayList<String>();
    ContextConfiguration.KeyChangeObserver gone = goneSeen::add;
    configuration.addKeyChangeObserver(gone);
    configuration.addKeyChangeObserver(liveSeen::add);

    configuration.removeKeyChangeObserver(gone);
    configuration.setValue(KEY, OrderByNullsDefault.NULLS_LARGEST);

    assertEquals(1, configuration.getKeyChangeObserverCount());
    assertTrue("a removed observer must receive nothing", goneSeen.isEmpty());
    assertEquals(List.of(KEY.getKey()), liveSeen);
  }

  /** Registering the same instance twice leaves one registration, so notifications stay single. */
  @Test
  public void repeatedRegistrationOfOneInstanceIsIdempotent() {
    var configuration = new ContextConfiguration();
    var seen = new ArrayList<String>();
    ContextConfiguration.KeyChangeObserver observer = seen::add;
    configuration.addKeyChangeObserver(observer);
    configuration.addKeyChangeObserver(observer);

    configuration.setValue(KEY, OrderByNullsDefault.NULLS_LARGEST);

    assertEquals(1, configuration.getKeyChangeObserverCount());
    assertEquals(List.of(KEY.getKey()), seen);
  }

  /**
   * Reproduces the sequence that used to resurrect a cleared setting, with two owners sharing one
   * configuration object.
   *
   * <p>Each owner stands for a storage that loaded an unreadable value and kept its raw text. The
   * operator sets the key, which is an explicit decision, so both owners have to forget their text.
   * With a single observer slot the first owner kept it and wrote it back at its next clean close.
   */
  @Test
  public void sharedConfigurationPurgesEveryOwnersPreservedValue() {
    var shared = new ContextConfiguration();
    Map<String, String> firstOwnerPreserved = new HashMap<>();
    Map<String, String> secondOwnerPreserved = new HashMap<>();
    firstOwnerPreserved.put(KEY.getKey(), "NOT_A_CONSTANT");
    secondOwnerPreserved.put(KEY.getKey(), "NOT_A_CONSTANT");
    shared.addKeyChangeObserver(firstOwnerPreserved::remove);
    shared.addKeyChangeObserver(secondOwnerPreserved::remove);

    shared.setValue(KEY, OrderByNullsDefault.NULLS_LARGEST);

    assertFalse(
        "the first owner must forget the stored text", firstOwnerPreserved.containsKey(
            KEY.getKey()));
    assertFalse(
        "the second owner must forget the stored text", secondOwnerPreserved.containsKey(
            KEY.getKey()));
  }

  /** A configuration nobody observes reports no observer and still accepts writes. */
  @Test
  public void configurationWithoutObserverAcceptsWrites() {
    var configuration = new ContextConfiguration();

    configuration.setValue(KEY, OrderByNullsDefault.NULLS_LARGEST);

    assertEquals(0, configuration.getKeyChangeObserverCount());
    assertEquals(OrderByNullsDefault.NULLS_LARGEST, configuration.getValue(KEY));
  }
}
