package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.YTDBGraphFeatureTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.YTDBProcessTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.YTDBStructureTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites.YTDBGremlinProcessTests;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites.YTDBStructureSuite;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Guards against classes under {@code gremlintest/**} silently running nowhere.
 *
 * <p>{@code core/pom.xml}'s {@code sequential-tests} surefire execution excludes {@code
 * gremlintest/**} entirely, on the assumption that every concrete, {@code @Test}-bearing class
 * living there is reachable only by explicit registration in one of the two TinkerPop
 * suite-array registries -- {@link YTDBGremlinProcessTests} (feeding the {@code YTDBProcessTest}
 * wrapper) or {@link YTDBStructureSuite} (feeding the {@code YTDBStructureTest} wrapper). Before
 * that exclude existed, a class under {@code gremlintest/**} that was forgotten from both
 * registries would still have run standalone under {@code sequential-tests} (loudly, as an
 * unplanned duplicate -- see the removed 2x-duplicate-execution bug). After the exclude, the same
 * mistake produces no test execution and no build signal at all: not under {@code
 * sequential-tests} (file-pattern excluded) and not under any compliance execution (never
 * referenced by a suite array). This test turns that silent gap back into a loud, specific
 * failure naming the exact class that needs to be added to a registry.
 *
 * <p>It walks the compiled {@code gremlintest/**} test-classes tree directly rather than using a
 * classpath-scanning library, since none is a test-scoped dependency of this module. It lives
 * outside {@code gremlintest/**} on purpose: that whole package tree is excluded from {@code
 * sequential-tests}, so a copy of this guard placed inside it would never run either.
 *
 * <p>Tagged {@link SequentialTest} not because it mutates shared state, but because it must load
 * {@link YTDBGremlinProcessTests} and {@link YTDBStructureSuite}, both of which reference
 * TinkerPop suite base classes from the {@code gremlin-test} dependency; {@code default-test}
 * excludes that dependency from its classpath entirely (see {@code core/pom.xml}), so this test
 * can only run under {@code sequential-tests}, which does not.
 */
@Category(SequentialTest.class)
public class GremlinComplianceSuiteRegistrationTest {

  private static final String GREMLINTEST_PACKAGE =
      "com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest";

  /**
   * The three suite-wrapper classes are entry points that surefire runs directly (each gets its
   * own {@code gremlin-*-compliance-tests} execution); they are not themselves registered inside
   * a suite array, so they are exempt from the "must be registered" check below. In practice none
   * of them declare a {@code @Test} method of their own either, so the scan would skip them
   * anyway -- this set just makes that exemption explicit and future-proof.
   */
  private static final Set<String> WRAPPER_CLASS_NAMES = Set.of(
      YTDBProcessTest.class.getName(),
      YTDBStructureTest.class.getName(),
      YTDBGraphFeatureTest.class.getName());

  /**
   * Scenario: a hand-written, concrete class under {@code gremlintest/**} declares (directly or
   * by inheritance, e.g. a nested subclass of an abstract scenario base) at least one JUnit4
   * {@code @Test} method. Expected outcome: that exact class -- top-level or nested -- appears in
   * one of the {@code Class<?>[]} registry fields on {@link YTDBGremlinProcessTests} or {@link
   * YTDBStructureSuite}, or is one of the three suite-wrapper classes. Any class satisfying the
   * first half without the second is named in the failure message, since it would otherwise run
   * nowhere in a normal build.
   */
  @Test
  public void everyGremlintestTestClassIsRegisteredInASuite() throws Exception {
    var registered = collectRegisteredClassNames();

    var unregistered = new ArrayList<String>();
    for (var testClass : findConcreteJUnit4TestClasses()) {
      var name = testClass.getName();
      if (!WRAPPER_CLASS_NAMES.contains(name) && !registered.contains(name)) {
        unregistered.add(name);
      }
    }

    assertThat(unregistered)
        .as(
            "these gremlintest/** classes declare (or inherit) a @Test method but are not "
                + "registered in YTDBGremlinProcessTests or YTDBStructureSuite, so they would "
                + "run nowhere in a normal build -- sequential-tests excludes gremlintest/** "
                + "entirely and no compliance execution references them; add each one to the "
                + "appropriate suite array")
        .isEmpty();
  }

  /**
   * Collects the fully qualified names of every class referenced from any {@code Class<?>[]}
   * static field declared on {@code declaringClass}. Both {@link YTDBGremlinProcessTests} and
   * {@link YTDBStructureSuite} declare their registries with package-private or private
   * visibility, so reflection (with {@code setAccessible}) is required from this package.
   */
  private static Set<String> collectRegisteredClassNames() throws IllegalAccessException {
    var registered = new HashSet<String>();

    for (Class<?> declaringClass : List.of(YTDBGremlinProcessTests.class,
        YTDBStructureSuite.class)) {
      for (Field field : declaringClass.getDeclaredFields()) {
        if (!Class[].class.equals(field.getType())) {
          continue;
        }
        field.setAccessible(true);
        var classes = (Class<?>[]) field.get(null);
        for (var registeredClass : classes) {
          registered.add(registeredClass.getName());
        }
      }
    }

    return registered;
  }

  /**
   * Walks the compiled {@code gremlintest/**} test-classes tree on disk and returns every
   * concrete (non-abstract, non-anonymous, non-local) class -- top-level or nested -- that
   * declares or inherits at least one {@code @org.junit.Test}-annotated method. Anonymous/local
   * classes are excluded because they cannot be named from a suite array in the first place (e.g.
   * inner listener implementations compiled as {@code Foo$1}), so they can never legitimately
   * satisfy the registration check.
   */
  private static List<Class<?>> findConcreteJUnit4TestClasses() throws Exception {
    var classLoader = Thread.currentThread().getContextClassLoader();
    var packagePath = GREMLINTEST_PACKAGE.replace('.', '/');
    var root = classLoader.getResource(packagePath);
    assertThat(root).as("gremlintest test-classes package not found on classpath: %s",
        packagePath).isNotNull();

    var rootDir = new File(root.toURI());
    var result = new ArrayList<Class<?>>();
    Deque<File> pending = new ArrayDeque<>();
    pending.add(rootDir);

    while (!pending.isEmpty()) {
      var dir = pending.remove();
      var children = dir.listFiles();
      if (children == null) {
        continue;
      }
      for (var child : children) {
        if (child.isDirectory()) {
          pending.add(child);
          continue;
        }

        var fileName = child.getName();
        if (!fileName.endsWith(".class")) {
          continue;
        }

        var relativePath = rootDir.toPath().relativize(child.toPath()).toString();
        var binaryTail = relativePath.substring(0, relativePath.length() - ".class".length())
            .replace(File.separatorChar, '.');
        var className = GREMLINTEST_PACKAGE + "." + binaryTail;

        var loaded = Class.forName(className, false, classLoader);
        if (Modifier.isAbstract(loaded.getModifiers())
            || loaded.isAnonymousClass()
            || loaded.isLocalClass()) {
          continue;
        }
        if (hasOwnOrInheritedTestMethod(loaded)) {
          result.add(loaded);
        }
      }
    }

    return result;
  }

  /** {@code getMethods()} includes inherited public methods, covering nested subclasses (like
   * a {@code Traversals} scenario) whose {@code @Test} methods live only on an abstract base. */
  private static boolean hasOwnOrInheritedTestMethod(Class<?> clazz) {
    for (Method method : clazz.getMethods()) {
      if (method.isAnnotationPresent(Test.class)) {
        return true;
      }
    }
    return false;
  }
}
