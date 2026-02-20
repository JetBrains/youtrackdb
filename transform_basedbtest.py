#!/usr/bin/env python3
import re
import sys


def transform_test(input_file, output_file, class_name):
  # Read the original file
  with open(input_file, 'r') as f:
    content = f.read()

  lines = content.split('\n')

  # Check if class is @Ignore
  is_ignored = '@Ignore' in content and '@Ignore' in '\n'.join(lines[:40])

  # Find the ignore message if present
  ignore_message = ""
  for line in lines:
    match = re.search(r'@Ignore\("([^"]+)"\)', line)
    if match:
      ignore_message = match.group(1)
      break

  # Extract imports (non-testng ones)
  imports = []
  for line in lines:
    if line.startswith('import ') and 'testng' not in line.lower():
      imports.append(line)
    elif line.startswith('import static') and 'testng' not in line.lower():
      imports.append(line)

  # Find all test method names (public void methods that start with 'test')
  test_methods = []
  for i, line in enumerate(lines, 1):
    match = re.match(r'\s+public void (test\w+)\(', line)
    if match:
      test_methods.append((match.group(1), i))

  # Extract the class-level javadoc if present
  class_javadoc = ""
  in_javadoc = False
  javadoc_lines = []
  for i, line in enumerate(lines):
    if '/**' in line and not in_javadoc:
      in_javadoc = True
      javadoc_lines = [line]
    elif in_javadoc:
      javadoc_lines.append(line)
      if '*/' in line:
        in_javadoc = False
        # Check if next non-empty lines lead to class declaration
        for j in range(i + 1, min(i + 10, len(lines))):
          if 'public class' in lines[j] or '@Ignore' in lines[j] or '@Test' in \
              lines[j]:
            class_javadoc = '\n'.join(javadoc_lines)
            break
          elif lines[j].strip() and not lines[j].strip().startswith('@'):
            javadoc_lines = []
            break

  # Create the JUnit version header
  output = f'''/*
 * JUnit 4 version of {class_name}.
 * Original: {input_file}
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

'''

  # Add imports
  for imp in imports:
    # Convert static testng imports to junit
    if 'static org.testng.Assert' in imp:
      imp = imp.replace('org.testng.Assert', 'org.junit.Assert')
    output += imp + '\n'

  # Add JUnit imports
  output += '''import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

'''

  # Add class javadoc
  if class_javadoc:
    output += class_javadoc + '\n'
  else:
    output += f'''/**
 * JUnit 4 version of {class_name}.
 * Original: {input_file}
 */
'''

  # Add class annotations and declaration
  output += '@FixMethodOrder(MethodSorters.NAME_ASCENDING)\n'
  if is_ignored:
    if ignore_message:
      output += f'@Ignore("{ignore_message}")\n'
    else:
      output += '@Ignore("Migrated from TestNG - original test class was @Ignore")\n'

  output += f'''public class {class_name} extends BaseDBTest {{

  private static {class_name} instance;

  @BeforeClass
  public static void setUpClass() throws Exception {{
    instance = new {class_name}();
    instance.beforeClass();
    instance.setupSchema();
  }}

  @AfterClass
  public static void tearDownClass() throws Exception {{
    if (instance != null) {{
      instance.destroySchema();
    }}
  }}

'''

  # Find setupSchema method and extract its body
  in_setup = False
  setup_lines = []
  brace_count = 0
  setup_line_num = 0

  for i, line in enumerate(lines):
    if 'public void setupSchema()' in line or 'void setupSchema()' in line:
      in_setup = True
      setup_line_num = i + 1
      setup_lines = []
      brace_count = 1  # Opening brace is on the method signature line
      continue
    if in_setup:
      setup_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0:
        break

  output += f'''  /**
   * Original: setupSchema (line {setup_line_num})
   * Location: {input_file}
   */
  private void setupSchema() {{
'''
  output += '\n'.join(setup_lines[:-1])
  output += '\n  }\n\n'

  # Find destroySchema method and extract its body
  in_destroy = False
  destroy_lines = []
  brace_count = 0
  destroy_line_num = 0

  for i, line in enumerate(lines):
    if 'public void destroySchema()' in line or 'void destroySchema()' in line:
      in_destroy = True
      destroy_line_num = i + 1
      destroy_lines = []
      brace_count = 1  # Opening brace is on the method signature line
      continue
    if in_destroy:
      destroy_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0:
        break

  output += f'''  /**
   * Original: destroySchema (line {destroy_line_num})
   * Location: {input_file}
   */
  private void destroySchema() {{
'''
  output += '\n'.join(destroy_lines[:-1])
  output += '\n  }\n\n'

  # Find afterMethod and extract its body if it exists
  in_after = False
  after_lines = []
  brace_count = 0
  after_line_num = 0

  for i, line in enumerate(lines):
    if 'public void afterMethod()' in line:
      in_after = True
      after_line_num = i + 1
      after_lines = []
      brace_count = 1  # Opening brace is on the method signature line
      continue
    if in_after:
      after_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0:
        break

  if after_lines:
    output += f'''  /**
   * Original: afterMethod (line {after_line_num})
   * Location: {input_file}
   */
  @Override
  @After
  public void afterMethod() throws Exception {{
'''
    output += '\n'.join(after_lines[:-1])
    output += '\n  }\n\n'

  # Extract test methods (public void methods starting with test)
  all_test_methods_content = []
  in_method = False
  current_method_name = None
  current_method_lines = []
  brace_count = 0

  for i, line in enumerate(lines):
    match = re.match(r'\s+public void (test\w+)\(', line)
    if match and not in_method:
      current_method_name = match.group(1)
      in_method = True
      current_method_lines = [' {']
      brace_count = 1
      continue

    if in_method:
      current_method_lines.append(line)
      brace_count += line.count('{') - line.count('}')

      if brace_count == 0 and len(current_method_lines) > 1:
        orig_line = None
        for name, ln in test_methods:
          if name == current_method_name:
            orig_line = ln
            break

        method_content = '\n'.join(current_method_lines)
        all_test_methods_content.append(
          (current_method_name, orig_line, method_content))

        in_method = False
        current_method_name = None
        current_method_lines = []

  # Add numbered test methods
  for idx, (method_name, orig_line, method_body) in enumerate(
      all_test_methods_content, 1):
    num_prefix = f"test{idx:02d}_"
    # Remove 'test' prefix if present and add numbered one
    if method_name.startswith('test'):
      new_method_name = num_prefix + method_name[4:]
    else:
      new_method_name = num_prefix + method_name

    output += f'''  /**
   * Original: {method_name} (line {orig_line})
   * Location: {input_file}
   */
  @Test
  public void {new_method_name}(){method_body}

'''

  output += '}\n'

  # Write output
  with open(output_file, 'w') as f:
    f.write(output)

  print(
    f"Created JUnit test {class_name} with {len(all_test_methods_content)} test methods")


if __name__ == '__main__':
  if len(sys.argv) != 4:
    print(
      "Usage: python transform_basedbtest.py <input_file> <output_file> <class_name>")
    sys.exit(1)

  transform_test(sys.argv[1], sys.argv[2], sys.argv[3])
