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
package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.YouTrackDB.DatabaseConfigurationParameters;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.security.DefaultSecurityConfig;
import com.jetbrains.youtrackdb.internal.core.security.SecurityConfig;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Test;

/**
 * Pins {@link YouTrackDBConfigBuilderImpl} and {@link YouTrackDBConfigImpl} surface:
 * the seven {@code fromApacheConfiguration} dispatch arms, the listener / global-user /
 * security-config wiring, the protected ctor's null-listener fallback, the
 * {@code toApacheConfiguration} round-trip for every {@link ATTRIBUTES} case, and the
 * eight branches of {@link YouTrackDBConfigImpl#setParent}.
 */
public class YouTrackDBConfigImplTest {

  // --------------------------------------------------------------------------------------------
  // Build-from-* dispatcher tests
  // --------------------------------------------------------------------------------------------

  @Test
  public void testBuildSettings() {
    var settings =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 20)
            .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US")
            .build();

    assertEquals(20, ((YouTrackDBConfigImpl) settings).getConfiguration()
        .getValue(GlobalConfiguration.DB_POOL_MAX));
    assertEquals("US", settings.getAttributes().get(ATTRIBUTES.LOCALE_COUNTRY));
  }

  @Test
  public void testBuildSettingsFromMap() {
    Map<String, Object> configs = new HashMap<>();
    configs.put(GlobalConfiguration.DB_POOL_MAX.getKey(), 20);
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .fromMap(configs).build();
    assertEquals(20, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  @Test
  public void testBuildSettingsFromGlobalMap() {
    Map<GlobalConfiguration, Object> configs = new HashMap<>();
    configs.put(GlobalConfiguration.DB_POOL_MAX, 20);
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .fromGlobalConfigurationParameters(configs).build();
    assertEquals(20, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  /**
   * Apache-Configuration import: each of the six date-format / locale / charset / time-zone
   * keys must land in the matching ATTRIBUTES entry, the password key must be silently
   * skipped, and any other key falls through to the underlying {@link ContextConfiguration}.
   */
  @Test
  public void fromApacheConfigurationDispatchesEveryKnownKeyAndSkipsPassword() {
    var apache = new BaseConfiguration();
    apache.setProperty(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_COUNTRY, "US");
    apache.setProperty(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_LANGUAGE, "en");
    apache.setProperty(DatabaseConfigurationParameters.CONFIG_DB_TIME_ZONE, "UTC");
    apache.setProperty(DatabaseConfigurationParameters.CONFIG_DB_CHARSET, "UTF-8");
    apache.setProperty(DatabaseConfigurationParameters.CONFIG_DB_DATE_FORMAT, "yyyy-MM-dd");
    apache.setProperty(
        DatabaseConfigurationParameters.CONFIG_DB_DATE_TIME_FORMAT, "yyyy-MM-dd HH:mm:ss");
    apache.setProperty(YTDBGraphFactory.CONFIG_USER_PWD, "do-not-leak");
    apache.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 99);

    var settings =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder().fromApacheConfiguration(apache).build();

    var attrs = settings.getAttributes();
    assertEquals("US", attrs.get(ATTRIBUTES.LOCALE_COUNTRY));
    assertEquals("en", attrs.get(ATTRIBUTES.LOCALE_LANGUAGE));
    assertEquals("UTC", attrs.get(ATTRIBUTES.TIMEZONE));
    assertEquals("UTF-8", attrs.get(ATTRIBUTES.CHARSET));
    assertEquals("yyyy-MM-dd", attrs.get(ATTRIBUTES.DATEFORMAT));
    assertEquals("yyyy-MM-dd HH:mm:ss", attrs.get(ATTRIBUTES.DATE_TIME_FORMAT));

    // Password is intentionally not propagated — pinned so a future refactor that lets the
    // password leak into the ContextConfiguration breaks loudly here.
    assertNull(
        settings.getConfiguration().getValueAsString(YTDBGraphFactory.CONFIG_USER_PWD, null));

    // Non-attribute, non-password key flows into the configuration's general key/value store.
    assertEquals(99, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  @Test
  public void fromApacheConfigurationEmptyConfigYieldsEmptySettings() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .fromApacheConfiguration(new BaseConfiguration()).build();

    assertTrue("attributes empty after empty Apache import",
        settings.getAttributes().isEmpty());
    assertTrue("listeners empty after empty Apache import",
        settings.getListeners().isEmpty());
  }

  // --------------------------------------------------------------------------------------------
  // toApacheConfiguration round-trip — pins every ATTRIBUTES → CONFIG_DB_* mapping
  // --------------------------------------------------------------------------------------------

  @Test
  public void toApacheConfigurationCarriesEveryAttribute() {
    var settings =
        YouTrackDBConfig.builder()
            .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US")
            .addAttribute(ATTRIBUTES.LOCALE_LANGUAGE, "en")
            .addAttribute(ATTRIBUTES.TIMEZONE, "UTC")
            .addAttribute(ATTRIBUTES.CHARSET, "UTF-8")
            .addAttribute(ATTRIBUTES.DATEFORMAT, "yyyy-MM-dd")
            .addAttribute(ATTRIBUTES.DATE_TIME_FORMAT, "yyyy-MM-dd HH:mm:ss")
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 7)
            .build();

    var apache = settings.toApacheConfiguration();

    assertEquals("US", apache.getString(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_COUNTRY));
    assertEquals("en", apache.getString(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_LANGUAGE));
    assertEquals("UTC", apache.getString(DatabaseConfigurationParameters.CONFIG_DB_TIME_ZONE));
    assertEquals("UTF-8", apache.getString(DatabaseConfigurationParameters.CONFIG_DB_CHARSET));
    assertEquals("yyyy-MM-dd",
        apache.getString(DatabaseConfigurationParameters.CONFIG_DB_DATE_FORMAT));
    assertEquals("yyyy-MM-dd HH:mm:ss",
        apache.getString(DatabaseConfigurationParameters.CONFIG_DB_DATE_TIME_FORMAT));

    // Generic configuration keys also flow into the Apache configuration via merge().
    assertEquals(7, apache.getInt(GlobalConfiguration.DB_POOL_MAX.getKey()));
  }

  @Test
  public void toApacheConfigurationOnEmptySettingsHasNoKnownAttributeKeys() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder().build();
    var apache = settings.toApacheConfiguration();

    // Untouched attributes must not surface — guards against an accidental default-property
    // assignment in future toApacheConfiguration switch cases.
    assertNull(apache.getString(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_COUNTRY));
    assertNull(apache.getString(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_LANGUAGE));
    assertNull(apache.getString(DatabaseConfigurationParameters.CONFIG_DB_TIME_ZONE));
    assertNull(apache.getString(DatabaseConfigurationParameters.CONFIG_DB_CHARSET));
    assertNull(apache.getString(DatabaseConfigurationParameters.CONFIG_DB_DATE_FORMAT));
    assertNull(apache.getString(DatabaseConfigurationParameters.CONFIG_DB_DATE_TIME_FORMAT));
  }

  // --------------------------------------------------------------------------------------------
  // Listener wiring
  // --------------------------------------------------------------------------------------------

  @Test
  public void addSessionListenerStoresListenerInBuiltConfig() {
    SessionListener listener = new SessionListener() {
    };
    var settings =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder().addSessionListener(listener).build();

    assertEquals(1, settings.getListeners().size());
    assertTrue("registered listener must be present",
        settings.getListeners().contains(listener));
  }

  @Test
  public void addSessionListenerDeduplicatesByEquality() {
    SessionListener listener = new SessionListener() {
    };
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addSessionListener(listener)
        .addSessionListener(listener)
        .build();

    // Builder uses a HashSet — adding the same instance twice still yields one entry.
    assertEquals(1, settings.getListeners().size());
  }

  // --------------------------------------------------------------------------------------------
  // Builder fluent contract — every fluent setter must return the same builder instance
  // --------------------------------------------------------------------------------------------

  @Test
  public void builderFluentMethodsReturnSameInstance() {
    var builder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();

    SessionListener listener = new SessionListener() {
    };
    SecurityConfig securityConfig = new DefaultSecurityConfig();

    assertSame(builder, builder.fromApacheConfiguration(new BaseConfiguration()));
    assertSame(builder, builder.fromMap(new HashMap<>()));
    assertSame(builder, builder.fromGlobalConfigurationParameters(new HashMap<>()));
    assertSame(builder, builder.addSessionListener(listener));
    assertSame(builder, builder.addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US"));
    assertSame(builder, builder.addGlobalConfigurationParameter(
        GlobalConfiguration.DB_POOL_MAX, 1));
    assertSame(builder, builder.setSecurityConfig(securityConfig));
    assertSame(builder, builder.fromContext(new ContextConfiguration()));
  }

  // --------------------------------------------------------------------------------------------
  // Internal builder methods — not on the public interface but reachable from production code
  // --------------------------------------------------------------------------------------------

  @Test
  public void setSecurityConfigPropagatesIntoBuiltImpl() {
    SecurityConfig expected = new DefaultSecurityConfig();
    var builder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();
    builder.setSecurityConfig(expected);

    assertSame(expected, builder.build().getSecurityConfig());
  }

  @Test
  public void setSecurityConfigDefaultsToNullWhenNotSet() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder().build();

    // The builder leaves security config unset by default; the impl exposes whatever the
    // caller wired (or null). Pinned so a silent default switch would surface here.
    assertNull(settings.getSecurityConfig());
  }

  @Test
  public void fromContextReplacesEntireContextConfiguration() {
    var seedContext = new ContextConfiguration();
    seedContext.setValue(GlobalConfiguration.DB_POOL_MAX, 42);
    seedContext.setValue(GlobalConfiguration.DB_POOL_MIN, 3);

    var builder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();
    // Pre-existing builder state must be replaced wholesale — fromContext is a setter, not a
    // merge. We probe with raw key lookup so the GlobalConfiguration default fallback in
    // ContextConfiguration#getValue(GlobalConfiguration) does not mask the absence of the
    // pre-fromContext key.
    builder.addGlobalConfigurationParameter(GlobalConfiguration.CACHE_LOCAL_IMPL, "should-go");
    builder.fromContext(seedContext);

    var settings = builder.build();
    var ctx = settings.getConfiguration();

    // The new context's seeded values are present.
    assertEquals(42, ctx.getValue(GlobalConfiguration.DB_POOL_MAX));
    assertEquals(3, ctx.getValue(GlobalConfiguration.DB_POOL_MIN));

    // The pre-existing setting from before fromContext is no longer in the local map — pinned
    // by reading the raw key via the (String, default) overload, which does not fall back to
    // GlobalConfiguration.getValue when the local map is empty.
    assertNull(ctx.getValue(GlobalConfiguration.CACHE_LOCAL_IMPL.getKey(), null));

    // Identity sanity: the active configuration must be the seed instance, not a copy.
    assertSame(seedContext, ctx);
  }

  @Test
  public void addGlobalUserAccumulatesIntoUsersList() {
    var builder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();
    builder.addGlobalUser("alice", "secret-a", "*");
    builder.addGlobalUser("bob", "secret-b", "database.*");

    var users = builder.build().getUsers();
    assertEquals(2, users.size());

    var first = users.getFirst();
    assertEquals("alice", first.getName());
    assertEquals("secret-a", first.getPassword());
    assertEquals("*", first.getResources());

    var second = users.get(1);
    assertEquals("bob", second.getName());
    assertEquals("secret-b", second.getPassword());
    assertEquals("database.*", second.getResources());
  }

  // --------------------------------------------------------------------------------------------
  // YouTrackDBConfigImpl — public no-arg ctor surface
  // --------------------------------------------------------------------------------------------

  @Test
  public void publicNoArgCtorYieldsEmptyDefaults() {
    var settings = new YouTrackDBConfigImpl();

    assertNotNull(settings.getConfiguration());
    assertTrue("default attributes must be empty", settings.getAttributes().isEmpty());
    assertTrue("default listeners must be empty", settings.getListeners().isEmpty());
    assertTrue("default users must be empty", settings.getUsers().isEmpty());
    assertNotNull("default class loader must be wired", settings.getClassLoader());
    // Default security config is initialised to a DefaultSecurityConfig — pinned so a
    // future refactor that switches to null surfaces here.
    assertNotNull(settings.getSecurityConfig());
    assertEquals(DefaultSecurityConfig.class, settings.getSecurityConfig().getClass());
  }

  @Test
  public void publicNoArgCtorClassLoaderIsImplOwnLoader() {
    var settings = new YouTrackDBConfigImpl();
    // The ctor reads ClassLoader off this.getClass(), so a YouTrackDBConfigImpl built in
    // any context must report YouTrackDBConfigImpl's own class loader rather than the
    // thread-context one (which can drift between Maven Surefire and IDE runs).
    assertSame(YouTrackDBConfigImpl.class.getClassLoader(), settings.getClassLoader());
  }

  // --------------------------------------------------------------------------------------------
  // setParent — eight branches
  // --------------------------------------------------------------------------------------------

  @Test
  public void testParentConfig() {
    var parent =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 20)
            .addAttribute(ATTRIBUTES.LOCALE_LANGUAGE, "en")
            .build();

    var settings =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CLIENT_CONNECTION_STRATEGY,
                "ROUND_ROBIN_CONNECT")
            .addAttribute(ATTRIBUTES.LOCALE_LANGUAGE, "en")
            .build();

    settings.setParent((YouTrackDBConfigImpl) parent);

    assertEquals(20, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
    assertEquals(
        "ROUND_ROBIN_CONNECT",
        settings.getConfiguration().getValue(GlobalConfiguration.CLIENT_CONNECTION_STRATEGY));
    assertEquals("en", settings.getAttributes().get(ATTRIBUTES.LOCALE_LANGUAGE));
  }

  @Test
  public void setParentNullIsNoOp() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 11)
        .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "DE")
        .addSessionListener(new SessionListener() {
        })
        .build();

    var attrsBefore = settings.getAttributes();
    var listenersBefore = settings.getListeners();
    var configBefore = settings.getConfiguration();

    settings.setParent(null);

    // Same map / list / configuration object identity preserved on null-parent fast path.
    assertSame(attrsBefore, settings.getAttributes());
    assertSame(listenersBefore, settings.getListeners());
    assertSame(configBefore, settings.getConfiguration());
  }

  @Test
  public void setParentChildOverridesParentAttribute() {
    var parent =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US")
            .build();

    var child = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "DE")
        .build();

    child.setParent(parent);

    // putAll(parent) then putAll(child) — child must win.
    assertEquals("DE", child.getAttributes().get(ATTRIBUTES.LOCALE_COUNTRY));
  }

  @Test
  public void setParentMergesDistinctAttributesFromBoth() {
    var parent =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US")
            .build();

    var child = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addAttribute(ATTRIBUTES.TIMEZONE, "UTC")
        .build();

    child.setParent(parent);

    var attrs = child.getAttributes();
    assertEquals("US", attrs.get(ATTRIBUTES.LOCALE_COUNTRY));
    assertEquals("UTC", attrs.get(ATTRIBUTES.TIMEZONE));
  }

  @Test
  public void setParentChildOverridesParentConfigurationValue() {
    var parent =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 50)
            .build();

    var child = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 10)
        .build();

    child.setParent(parent);

    // merge(parent) then merge(child) — child must win on the same key.
    assertEquals(10, child.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  @Test
  public void setParentMergesListenersFromParentAndChild() {
    SessionListener parentListener = new SessionListener() {
    };
    SessionListener childListener = new SessionListener() {
    };

    var parent =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addSessionListener(parentListener)
            .build();

    var child = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addSessionListener(childListener)
        .build();

    child.setParent(parent);

    var listeners = child.getListeners();
    assertEquals(2, listeners.size());
    assertTrue("parent listener must propagate", listeners.contains(parentListener));
    assertTrue("child listener must remain", listeners.contains(childListener));
  }

  /**
   * The protected ctor coerces a null listener-set argument into
   * {@link java.util.Collections#emptySet()}, so by the time {@code setParent} runs there is
   * no production-reachable parent with {@code listeners == null}. We pin that ctor-side
   * coercion here: setParent always takes the merge branch, even with a "null-listener"
   * parent, and rebuilds the child's listener set into a fresh HashSet — preserving the
   * child's own listeners but no longer reusing the same backing collection identity.
   */
  @Test
  public void setParentMergesEvenWhenParentBuiltWithNullListeners() {
    var parentBuiltWithNull = parentWithNullListenersAndAttrs();

    SessionListener childListener = new SessionListener() {
    };
    var child = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addSessionListener(childListener)
        .build();

    var listenersBefore = child.getListeners();

    child.setParent(parentBuiltWithNull);

    var listenersAfter = child.getListeners();
    assertEquals(1, listenersAfter.size());
    assertTrue("child listener must remain after merge",
        listenersAfter.contains(childListener));
    // The merge always allocates a new HashSet, so the backing collection identity differs.
    org.junit.Assert.assertNotSame(listenersBefore, listenersAfter);
  }

  /**
   * Constructs a {@link YouTrackDBConfigImpl} that is intentionally shaped to exercise
   * setParent's null-side branches: null attributes <em>and</em> null listeners.
   */
  private static YouTrackDBConfigImpl parentWithNullListenersAndAttrs() {
    return new YouTrackDBConfigImpl(
        new ContextConfiguration(),
        /* attributes= */ null,
        /* listeners=  */ null,
        /* securityConfig= */ null,
        /* users= */ new java.util.ArrayList<>()) {
      // anonymous subclass to call the protected ctor
    };
  }

  /**
   * Pins the protected ctor's null-listener fallback: when null is passed as the listener set,
   * the impl must substitute an empty Collections.emptySet() rather than store null.
   */
  @Test
  public void protectedCtorFallsBackToEmptyListenersOnNull() {
    var settings = parentWithNullListenersAndAttrs();

    assertNotNull(settings.getListeners());
    assertTrue("null listeners must collapse to empty, not stay null",
        settings.getListeners().isEmpty());
    // Trying to mutate the fallback set must throw — Collections.emptySet() is unmodifiable.
    assertThrows(UnsupportedOperationException.class,
        () -> settings.getListeners().add(new SessionListener() {
        }));
  }

  /**
   * setParent's child-side `if (attributes != null)` arm: when the child's attributes map
   * is null, the merge takes only the parent's attributes — the inner null-guard at
   * {@code if (attributes != null) attrs.putAll(attributes)} skips the child contribution
   * and the parent's pairs survive intact. Child built via the anonymous subclass with
   * null attributes (the no-arg ctor coerces to an empty EnumMap; the protected ctor does
   * NOT coerce attributes).
   */
  @Test
  public void setParentWhenChildAttributesNullAdoptsParentAttributes() {
    var parent =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US")
            .addAttribute(ATTRIBUTES.TIMEZONE, "UTC")
            .build();

    var child = childWithNullAttrsAndConfig();
    child.setParent(parent);

    var attrs = child.getAttributes();
    assertNotNull(attrs);
    assertEquals("US", attrs.get(ATTRIBUTES.LOCALE_COUNTRY));
    assertEquals("UTC", attrs.get(ATTRIBUTES.TIMEZONE));
  }

  /**
   * setParent's child-side `if (this.configuration != null)` arm: when the child's
   * configuration is null, the merge yields a fresh ContextConfiguration carrying only the
   * parent's values. Pinned because the protected ctor does not coerce configuration to a
   * non-null default.
   */
  @Test
  public void setParentWhenChildConfigurationNullAdoptsParentConfiguration() {
    var parent =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 50)
            .build();

    var child = childWithNullAttrsAndConfig();
    child.setParent(parent);

    var conf = child.getConfiguration();
    assertNotNull(conf);
    assertEquals(50, conf.getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  /**
   * Helper: anonymous subclass that exercises the protected ctor with null attributes and
   * null configuration. The protected ctor does not coerce these to defaults (only the
   * listeners argument is coerced to {@link java.util.Collections#emptySet()} on null).
   */
  private static YouTrackDBConfigImpl childWithNullAttrsAndConfig() {
    return new YouTrackDBConfigImpl(
        /* configuration= */ null,
        /* attributes= */ null,
        /* listeners=  */ null,
        /* securityConfig= */ null,
        /* users= */ new java.util.ArrayList<>()) {
      // anonymous subclass to call the protected ctor
    };
  }

  /**
   * setParent's `if (parent.attributes != null)` arm: when the parent's attributes map is
   * null, the merge is skipped and the child's attribute map identity must remain.
   */
  @Test
  public void setParentSkipsAttributeMergeWhenParentAttributesNull() {
    var parent = parentWithNullListenersAndAttrs();

    var child = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "FR")
        .build();
    var beforeAttrs = child.getAttributes();

    child.setParent(parent);

    assertSame(beforeAttrs, child.getAttributes());
    assertEquals("FR", child.getAttributes().get(ATTRIBUTES.LOCALE_COUNTRY));
  }

  // --------------------------------------------------------------------------------------------
  // Sanity guards on the builder-built impl shape
  // --------------------------------------------------------------------------------------------

  @Test
  public void builderProducesEmptyUsersByDefault() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder().build();
    assertNotNull(settings.getUsers());
    assertTrue(settings.getUsers().isEmpty());
  }

  @Test
  public void builderProducesNonNullClassLoader() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder().build();
    assertNotNull(settings.getClassLoader());
  }

  @Test
  public void emptyApacheConfigurationDoesNotInjectDefaultsIntoAttributes() {
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .fromApacheConfiguration(new BaseConfiguration()).build();
    var apache = settings.toApacheConfiguration();
    // No attributes set => no CONFIG_DB_* keys present in the returned Apache config either.
    assertFalse(apache.containsKey(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_COUNTRY));
    assertFalse(apache.containsKey(DatabaseConfigurationParameters.CONFIG_DB_LOCALE_LANGUAGE));
  }
}
