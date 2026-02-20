#!/usr/bin/env python3
import re

# Read the original file
with open(
    'tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java',
    'r') as f:
  content = f.read()

# Extract test method names and their line numbers
test_methods = []
lines = content.split('\n')
for i, line in enumerate(lines, 1):
  match = re.match(r'\s+public void (test\w+)\(\)', line)
  if match:
    test_methods.append((match.group(1), i))

# Create the JUnit version
output = '''/*
 * JUnit 4 version of SQLSelectIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLSelectIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
 * 
 * This test class is @Ignored because the original TestNG version was also @Ignored.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Ignore("Migrated from TestNG - original test class was @Ignore")
public class SQLSelectIndexReuseTest extends AbstractIndexReuseTest {

  private static SQLSelectIndexReuseTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLSelectIndexReuseTest();
    instance.beforeClass();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (instance != null) {
      instance.afterClass();
    }
  }

'''

# Find and extract the beforeClass method body (from line 25 to 164)
before_class_start = None
before_class_end = None
for i, line in enumerate(lines):
  if '@BeforeClass' in line or 'public void beforeClass()' in line:
    if before_class_start is None:
      before_class_start = i
  if before_class_start is not None and before_class_end is None:
    if i > before_class_start + 1 and line.strip() == '}' and lines[
      i - 1].strip() != '}':
      # Check if this is the end of beforeClass by counting braces
      pass

# For beforeClass, we need to extract lines 25-164
output += '''  /**
   * Original: beforeClass (line 25)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("sqlSelectIndexReuseTestClass");

    oClass.createProperty("prop1", PropertyType.INTEGER);
    oClass.createProperty("prop2", PropertyType.INTEGER);
    oClass.createProperty("prop3", PropertyType.INTEGER);
    oClass.createProperty("prop4", PropertyType.INTEGER);
    oClass.createProperty("prop5", PropertyType.INTEGER);
    oClass.createProperty("prop6", PropertyType.INTEGER);
    oClass.createProperty("prop7", PropertyType.STRING);
    oClass.createProperty("prop8", PropertyType.INTEGER);
    oClass.createProperty("prop9", PropertyType.INTEGER);

    oClass.createProperty("fEmbeddedMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedMapTwo", PropertyType.EMBEDDEDMAP,
        PropertyType.INTEGER);

    oClass.createProperty("fLinkMap", PropertyType.LINKMAP);

    oClass.createProperty("fEmbeddedList", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedListTwo", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);

    oClass.createProperty("fLinkList", PropertyType.LINKLIST);

    oClass.createProperty("fEmbeddedSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedSetTwo", PropertyType.EMBEDDEDSET,
        PropertyType.INTEGER);

    oClass.createIndex("indexone", SchemaClass.INDEX_TYPE.UNIQUE, "prop1", "prop2");
    oClass.createIndex("indextwo", SchemaClass.INDEX_TYPE.UNIQUE, "prop3");
    oClass.createIndex("indexthree", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop1", "prop2",
        "prop4");
    oClass.createIndex("indexfour", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop4", "prop1",
        "prop3");
    oClass.createIndex("indexfive", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop6", "prop1",
        "prop3");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMap");
    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByValue",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedMap by value");
    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedList", SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedList");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByKeyProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMapTwo", "prop8");
    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByValueProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMapTwo by value", "prop8");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedSetProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedSetTwo", "prop8");
    oClass.createIndex(
        "sqlSelectIndexReuseTestProp9EmbeddedSetProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop9",
        "fEmbeddedSetTwo", "prop8");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedListTwoProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedListTwo", "prop8");

    final var fullTextIndexStrings = new String[]{
        "Alice : What is the use of a book, without pictures or conversations?",
        "Rabbit : Oh my ears and whiskers, how late it's getting!",
        "Alice : If it had grown up, it would have made a dreadfully ugly child; but it makes rather"
            + " a handsome pig, I think",
        "The Cat : We're all mad here.",
        "The Hatter : Why is a raven like a writing desk?",
        "The Hatter : Twinkle, twinkle, little bat! How I wonder what you're at.",
        "The Queen : Off with her head!",
        "The Duchess : Tut, tut, child! Everything's got a moral, if only you can find it.",
        "The Duchess : Take care of the sense, and the sounds will take care of themselves.",
        "The King : Begin at the beginning and go on till you come to the end: then stop."
    };

    for (var i = 0; i < 10; i++) {
      final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

      embeddedMap.put("key" + (i * 10 + 1), i * 10 + 1);
      embeddedMap.put("key" + (i * 10 + 2), i * 10 + 2);
      embeddedMap.put("key" + (i * 10 + 3), i * 10 + 3);
      embeddedMap.put("key" + (i * 10 + 4), i * 10 + 1);

      final List<Integer> embeddedList = new ArrayList<Integer>(3);
      embeddedList.add(i * 3);
      embeddedList.add(i * 3 + 1);
      embeddedList.add(i * 3 + 2);

      final Set<Integer> embeddedSet = new HashSet<Integer>();
      embeddedSet.add(i * 10);
      embeddedSet.add(i * 10 + 1);
      embeddedSet.add(i * 10 + 2);

      for (var j = 0; j < 10; j++) {
        session.begin();
        final var document = ((EntityImpl) session.newEntity("sqlSelectIndexReuseTestClass"));
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);
        document.setProperty("prop3", i * 10 + j);

        document.setProperty("prop4", i);
        document.setProperty("prop5", i);

        document.setProperty("prop6", j);

        document.setProperty("prop7", fullTextIndexStrings[i]);

        document.setProperty("prop8", j);

        document.setProperty("prop9", j % 2);

        document.newEmbeddedMap("fEmbeddedMap", embeddedMap);
        document.newEmbeddedMap("fEmbeddedMapTwo", embeddedMap);

        document.newEmbeddedList("fEmbeddedList", embeddedList);
        document.newEmbeddedList("fEmbeddedListTwo", embeddedList);

        document.newEmbeddedSet("fEmbeddedSet", embeddedSet);
        document.newEmbeddedSet("fEmbeddedSetTwo", embeddedSet);

        session.commit();
      }
    }
  }

  /**
   * Original: afterClass (line 166)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Override
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.execute("drop class sqlSelectIndexReuseTestClass").close();

    super.afterClass();
  }

  /**
   * Helper method to check if entity is contained in result list.
   * Original: containsEntity (line 2868)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  private static int containsEntity(final List<Result> resultList, final Entity entity) {
    var count = 0;
    for (final var result : resultList) {
      var containsAllFields = true;
      for (final var fieldName : entity.getPropertyNames()) {
        if (!entity.getProperty(fieldName).equals(result.getProperty(fieldName))) {
          containsAllFields = false;
          break;
        }
      }
      if (containsAllFields) {
        count++;
      }
    }
    return count;
  }

'''

# Now extract each test method from the original and add with numbering and comments
# Parse the original to find test method bodies
in_test_method = False
current_method_name = None
current_method_lines = []
brace_count = 0
method_counter = 1

all_test_methods_content = []

for i, line in enumerate(lines):
  # Check if this is a @Test annotation
  if line.strip() == '@Test':
    in_test_method = True
    current_method_lines = []
    continue

  if in_test_method and not current_method_name:
    match = re.match(r'\s+public void (test\w+)\(\)\s*\{?', line)
    if match:
      current_method_name = match.group(1)
      # Start with opening brace
      current_method_lines = [' {']
      brace_count = 1  # We've seen the opening brace
      continue

  if current_method_name:
    current_method_lines.append(line)
    brace_count += line.count('{') - line.count('}')

    if brace_count == 0 and len(current_method_lines) > 1:
      # Method complete
      # Find the original line number for this method
      orig_line = None
      for name, ln in test_methods:
        if name == current_method_name:
          orig_line = ln
          break

      method_content = '\n'.join(current_method_lines)
      all_test_methods_content.append(
        (current_method_name, orig_line, method_content))

      current_method_name = None
      current_method_lines = []
      in_test_method = False

# Add numbered test methods to output
for idx, (method_name, orig_line, method_body) in enumerate(
    all_test_methods_content, 1):
  # Create numbered method name (test01_, test02_, etc.)
  num_prefix = f"test{idx:02d}_"
  new_method_name = num_prefix + method_name[
    4:]  # Remove 'test' prefix and add numbered one

  output += f'''  /**
   * Original: {method_name} (line {orig_line})
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void {new_method_name}(){method_body}

'''

output += '}\n'

# Write the output
with open(
    'tests/src/test/java/com/jetbrains/youtrackdb/auto/junit/SQLSelectIndexReuseTest.java',
    'w') as f:
  f.write(output)

print(f"Created JUnit test with {len(all_test_methods_content)} test methods")
