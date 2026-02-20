#!/usr/bin/env python3
import re
import sys


def transform_test(input_file, output_file, class_name):
  # Read the original file
  with open(input_file, 'r') as f:
    content = f.read()

  lines = content.split('\n')

  # Check if class is @Ignore
  is_ignored = '@Ignore' in content and '@Ignore' in '\n'.join(lines[:35])

  # Extract imports (non-testng ones)
  imports = []
  for line in lines:
    if line.startswith('import ') and 'testng' not in line.lower():
      imports.append(line)
    if line.startswith('import static') and 'testng' not in line.lower():
      imports.append(line)

  # Find the ignore message if present
  ignore_message = ""
  for line in lines:
    match = re.search(r'@Ignore\("([^"]+)"\)', line)
    if match:
      ignore_message = match.group(1)
      break

  # Extract test method names and their line numbers
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
              lines[j] or '@SuppressWarnings' in lines[j]:
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
  output += '''import org.junit.AfterClass;
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

  output += f'''public class {class_name} extends AbstractIndexReuseTest {{

  private static {class_name} instance;

  @BeforeClass
  public static void setUpClass() throws Exception {{
    instance = new {class_name}();
    instance.beforeClass();
  }}

  @AfterClass
  public static void tearDownClass() throws Exception {{
    if (instance != null) {{
      instance.afterClass();
    }}
  }}

'''

  # Find beforeClass method and extract its body
  in_before_class = False
  before_class_lines = []
  brace_count = 0
  before_class_line_num = 0

  for i, line in enumerate(lines):
    if 'public void beforeClass()' in line:
      in_before_class = True
      before_class_line_num = i + 1
      before_class_lines = []
      brace_count = 0
      continue
    if in_before_class:
      before_class_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0 and len(before_class_lines) > 1:
        break

  output += f'''  /**
   * Original: beforeClass (line {before_class_line_num})
   * Location: {input_file}
   */
  @Override
  public void beforeClass() throws Exception {{
'''
  output += '\n'.join(before_class_lines[:-1])  # Exclude the closing brace
  output += '\n  }\n\n'

  # Find afterClass method and extract its body
  in_after_class = False
  after_class_lines = []
  brace_count = 0
  after_class_line_num = 0

  for i, line in enumerate(lines):
    if 'public void afterClass()' in line:
      in_after_class = True
      after_class_line_num = i + 1
      after_class_lines = []
      brace_count = 0
      continue
    if in_after_class:
      after_class_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0 and len(after_class_lines) > 1:
        break

  output += f'''  /**
   * Original: afterClass (line {after_class_line_num})
   * Location: {input_file}
   */
  @Override
  public void afterClass() throws Exception {{
'''
  output += '\n'.join(after_class_lines[:-1])
  output += '\n  }\n\n'

  # Extract helper methods (non-test methods that are not beforeClass/afterClass)
  helper_methods = []
  in_method = False
  method_lines = []
  method_name = None
  method_line_num = 0
  brace_count = 0

  for i, line in enumerate(lines):
    # Skip test methods, beforeClass, afterClass
    if '@Test' in line or '@BeforeClass' in line or '@AfterClass' in line or '@Override' in line:
      continue

    # Look for private/protected methods that aren't tests
    match = re.match(r'\s+(private|protected|public)\s+\w+\s+(\w+)\s*\(', line)
    if match and not in_method:
      method_name = match.group(2)
      if method_name not in ['beforeClass', 'afterClass', 'beforeMethod',
                             'afterMethod'] and not method_name.startswith(
        'test'):
        in_method = True
        method_line_num = i + 1
        method_lines = [line]
        brace_count = line.count('{') - line.count('}')
        continue

    if in_method:
      method_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0 and len(method_lines) > 1:
        helper_methods.append(
          (method_name, method_line_num, '\n'.join(method_lines)))
        in_method = False
        method_lines = []
        method_name = None

  # Add helper methods
  for method_name, line_num, method_body in helper_methods:
    output += f'''  /**
   * Original: {method_name} (line {line_num})
   * Location: {input_file}
   */
{method_body}

'''

  # Extract test methods
  in_test_method = False
  current_method_name = None
  current_method_lines = []
  brace_count = 0
  all_test_methods_content = []

  for i, line in enumerate(lines):
    if line.strip() == '@Test':
      in_test_method = True
      current_method_lines = []
      continue

    if in_test_method and not current_method_name:
      match = re.match(r'\s+public void (test\w+)\(', line)
      if match:
        current_method_name = match.group(1)
        current_method_lines = [' {']
        brace_count = 1
        continue

    if current_method_name:
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

        current_method_name = None
        current_method_lines = []
        in_test_method = False

  # Add numbered test methods
  for idx, (method_name, orig_line, method_body) in enumerate(
      all_test_methods_content, 1):
    num_prefix = f"test{idx:02d}_"
    new_method_name = num_prefix + method_name[4:]

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
      "Usage: python transform_test_generic.py <input_file> <output_file> <class_name>")
    sys.exit(1)

  transform_test(sys.argv[1], sys.argv[2], sys.argv[3])
