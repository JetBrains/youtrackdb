#!/usr/bin/env python3
import re
import sys


def transform_test(input_file, output_file, class_name):
  with open(input_file, 'r') as f:
    content = f.read()

  lines = content.split('\n')

  # Extract imports (non-testng ones)
  imports = []
  for line in lines:
    if line.startswith('import ') and 'testng' not in line.lower():
      imports.append(line)
    elif line.startswith('import static') and 'testng' not in line.lower():
      imports.append(line)

  # Create the JUnit version header
  output = f'''/*
 * JUnit 4 version of {class_name}.
 * Original: {input_file}
 */
package com.jetbrains.youtrackdb.auto.junit;

'''

  for imp in imports:
    if 'static org.testng.Assert' in imp:
      imp = imp.replace('org.testng.Assert', 'org.junit.Assert')
    output += imp + '\n'

  output += '''import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

'''

  output += f'''/**
 * JUnit 4 version of {class_name}.
 * Original: {input_file}
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class {class_name} extends BaseDBTest {{

  private static {class_name} instance;

  @BeforeClass
  public static void setUpClass() throws Exception {{
    instance = new {class_name}();
    instance.beforeClass();
  }}

'''

  # Find beforeClass method
  in_before = False
  before_lines = []
  brace_count = 0
  before_line_num = 0

  for i, line in enumerate(lines):
    if 'public void beforeClass()' in line:
      in_before = True
      before_line_num = i + 1
      before_lines = []
      brace_count = 1
      continue
    if in_before:
      before_lines.append(line)
      brace_count += line.count('{') - line.count('}')
      if brace_count == 0:
        break

  if before_lines:
    output += f'''  /**
   * Original: beforeClass (line {before_line_num})
   * Location: {input_file}
   */
  @Override
  public void beforeClass() throws Exception {{
'''
    output += '\n'.join(before_lines[:-1])
    output += '\n  }\n\n'

  # Find @Test annotated methods
  test_methods = []
  i = 0
  while i < len(lines):
    line = lines[i]
    if line.strip() == '@Test':
      # Next line should be method declaration
      i += 1
      if i < len(lines):
        match = re.match(r'\s+public void (\w+)\(', lines[i])
        if match:
          method_name = match.group(1)
          method_line = i + 1
          method_lines = [' {']
          brace_count = 1
          i += 1
          while i < len(lines) and brace_count > 0:
            method_lines.append(lines[i])
            brace_count += lines[i].count('{') - lines[i].count('}')
            i += 1
          test_methods.append(
            (method_name, method_line, '\n'.join(method_lines)))
          continue
    i += 1

  # Add numbered test methods
  for idx, (method_name, orig_line, method_body) in enumerate(test_methods, 1):
    new_method_name = f"test{idx:02d}_{method_name[0].upper()}{method_name[1:]}"

    output += f'''  /**
   * Original: {method_name} (line {orig_line})
   * Location: {input_file}
   */
  @Test
  public void {new_method_name}(){method_body}

'''

  # Extract helper methods and enum - find where test methods end
  # Look for methods that are not @Test annotated
  helper_content = []
  i = 0
  in_helper = False
  while i < len(lines):
    line = lines[i]
    # Skip @Test annotated methods and beforeClass
    if line.strip() == '@Test' or line.strip() == '@BeforeClass' or line.strip() == '@Override':
      i += 1
      continue
    # Look for private/protected methods or enum
    if re.match(r'\s+(private|protected)\s+.*\(',
                line) or 'enum FunctionDefinition' in line:
      in_helper = True
    if in_helper:
      helper_content.append(line)
    i += 1

  if helper_content:
    # Remove trailing class closing brace
    while helper_content and helper_content[-1].strip() == '}':
      helper_content.pop()
    if helper_content:
      output += '  // Helper methods from original\n'
      output += '\n'.join(helper_content)
      output += '\n'

  output += '}\n'

  with open(output_file, 'w') as f:
    f.write(output)

  print(
    f"Created JUnit test {class_name} with {len(test_methods)} test methods")


if __name__ == '__main__':
  transform_test(sys.argv[1], sys.argv[2], sys.argv[3])
