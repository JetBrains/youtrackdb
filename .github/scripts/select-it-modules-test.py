#!/usr/bin/env python3
"""Tests for NUL-safe, reactor-scoped pull request integration selection."""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile

SCRIPT = pathlib.Path(__file__).with_name("select-it-modules.py")
spec = importlib.util.spec_from_file_location("select_it_modules", SCRIPT)
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


def check(actual, expected):
    """Check outcomes even when Python is started with -O."""
    if actual != expected:
        raise AssertionError(f"Expected {expected!r}, got {actual!r}")


def fixture(root):
    (root / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>'
        '<module>core</module><module>nested/inner</module><module>nested</module>'
        '<module>other</module></modules></project>', encoding="utf-8"
    )
    for module in ("core", "core-extra", "nested/inner", "nested", "other", "lucene"):
        directory = root / module
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "pom.xml").write_text("<project/>", encoding="utf-8")
    for module, test_name in (("core", "ITPrefix.java"),
                              ("nested/inner", "SuffixIT.java"),
                              ("nested", "CaseITCase.java"),
                              ("lucene", "LuceneIT.java")):
        source = root / module / "src/test/java/p" / test_name
        source.parent.mkdir(parents=True)
        source.touch()


def test_selection():
    """Java changes select owning IT modules, not dependents or a non-reactor POM."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        check(selector.select(root, ["core/src/main/java/A.java"]), (True, ["core"]))
        check(selector.select(root, ["other/src/main/java/B.java"]), (True, []))
        check(selector.select(root, ["lucene/src/main/java/L.java"]), (True, []))
        check(selector.select(root, ["unrelated/src/main/java/U.java"]), (True, []))
        check(selector.select(root, ["core/pom.xml", "nested/readme.md"]), (False, []))
        check(selector.select(root, ["nested/inner/src/test/java/A.java",
                                     "nested/src/main/java/B.java"]),
              (True, ["nested", "nested/inner"]))
        check(selector.select(root, ["nested/inner/src/test/java/A.java"]),
              (True, ["nested/inner"]))


def test_mixed_java_and_non_java():
    """A Java edit still selects core alongside a POM and Markdown edit."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        check(selector.select(root, ["core/src/main/java/A.java", "core/pom.xml",
                                     "README.md"]), (True, ["core"]))


def test_module_prefix_boundary():
    """A non-reactor core-extra edit must not select its IT-owning sibling core."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        check(selector.select(root, ["core-extra/src/main/java/A.java"]), (True, []))


def test_java_suffix_boundary():
    """A .javax or uppercase .JAVA path does not count as a Java change."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        check(selector.select(root, ["core/src/main/java/Foo.javax",
                                     "core/src/main/java/Foo.JAVA"]), (False, []))


def test_deleted_and_renamed():
    """Deleted or renamed Java paths count, but removal of the last IT source selects nothing."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        check(selector.select(root, ["core/src/main/java/Deleted.java"]), (True, ["core"]))
        check(selector.select(root, ["other/src/main/java/Old.java",
                                     "core/src/main/java/New.java"]), (True, ["core"]))
        (root / "core/src/test/java/p/ITPrefix.java").unlink()
        check(selector.select(root, ["core/src/test/java/p/ITPrefix.java"]), (True, []))


def test_nul_and_unusual_names():
    """Newlines, spaces, tabs and non-UTF-8 bytes in paths cannot split module names."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        data = b"core/src/main/java/line\nwith space\tand\xff.java\0other/X.java\0"
        check(selector.select(root, selector.changed_paths(data)), (True, ["core"]))
        check(selector.changed_paths(b""), [])
        try:
            selector.changed_paths(b"core/A.java")
        except ValueError:
            pass
        else:
            raise AssertionError("unterminated diff was accepted")


def test_cli():
    """CLI outputs only trusted module names and rejects empty or malformed arguments."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        changes = root / "changes"
        output = root / "output"
        changes.write_bytes(b"core/A.java\0lucene/L.java\0")
        output.touch()
        args = [sys.executable, str(SCRIPT), "--root", str(root),
                "--changes", str(changes), "--output", str(output)]
        result = subprocess.run(args, capture_output=True, text=True, check=False)
        check(result.returncode, 0)
        check(output.read_text(), "has_java_changes=true\nintegration_modules=core\n")
        for invalid in (("--changes", ""), ("--output", "")):
            bad = args.copy()
            bad[bad.index(invalid[0]) + 1] = invalid[1]
            check(subprocess.run(bad, capture_output=True, check=False).returncode != 0,
                  True)
        check(subprocess.run(args[:-1], capture_output=True, check=False).returncode != 0,
              True)
        changes.write_bytes(b"unterminated.java")
        check(subprocess.run(args, capture_output=True, check=False).returncode != 0, True)


def test_invalid_reactor():
    """Unsafe or duplicate POM module paths cannot enter the Maven argument list."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        fixture(root)
        for invalid in ("../outside", "core,other", "core"):
            pom = root / "pom.xml"
            old = pom.read_text()
            pom.write_text(old.replace("<module>other</module>",
                                       f"<module>{invalid}</module>"))
            try:
                selector.reactor_modules(root)
            except ValueError:
                pass
            else:
                raise AssertionError(f"accepted invalid reactor {invalid}")
            pom.write_text(old)


if __name__ == "__main__":
    for name, test in sorted(globals().copy().items()):
        if name.startswith("test_"):
            test()
            print(f"PASS {name}")
