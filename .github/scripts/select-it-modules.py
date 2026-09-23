#!/usr/bin/env python3
"""Select PR integration modules from a NUL-delimited Git path list."""

import argparse
import pathlib
import re
import xml.etree.ElementTree as ET

MODULE_NAME = re.compile(r"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\Z")
IT_NAMES = ("IT*.java", "*IT.java", "*ITCase.java")
NAMESPACE = {"pom": "http://maven.apache.org/POM/4.0.0"}


def reactor_modules(root):
    """Read only the root reactor's module paths, not standalone sibling POMs."""
    tree = ET.parse(root / "pom.xml")
    modules = []
    for element in tree.findall("./pom:modules/pom:module", NAMESPACE):
        name = (element.text or "").strip()
        if not MODULE_NAME.fullmatch(name) or not (root / name / "pom.xml").is_file():
            raise ValueError(f"Invalid reactor module path: {name!r}")
        modules.append(name)
    if not modules or len(modules) != len(set(modules)):
        raise ValueError("Root reactor must declare unique modules")
    return sorted(modules, key=lambda name: (-len(name), name))


def changed_paths(data):
    """Decode Git's -z output without splitting paths on whitespace or newlines."""
    if data and not data.endswith(b"\0"):
        raise ValueError("Git path list is not NUL-terminated")
    return [path.decode("utf-8", errors="surrogateescape") for path in data.split(b"\0")[:-1]]


def has_it_source(root, module):
    """Use Failsafe's default Java source names in the checked-out tree."""
    source_root = root / module / "src/test/java"
    return any(
        path.is_file()
        for pattern in IT_NAMES
        for path in source_root.glob(f"**/{pattern}")
    )


def select(root, paths):
    """Return whether Java changed and the changed reactor modules that own ITs."""
    modules = reactor_modules(root)
    java_paths = [path for path in paths if path.endswith(".java")]
    selected = set()
    for path in java_paths:
        # A module name comes from the trusted root POM, never from a diff path.
        module = next((name for name in modules if path.startswith(name + "/")), None)
        if module is not None and has_it_source(root, module):
            selected.add(module)
    return bool(java_paths), sorted(selected)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--changes", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)
    if not args.root.is_dir() or not args.changes.is_file() or not args.output.is_file():
        parser.error("root must be a directory and changes and output must be files")
    try:
        has_java, selected = select(args.root, changed_paths(args.changes.read_bytes()))
    except (ET.ParseError, OSError, ValueError) as error:
        parser.error(str(error))
    # Only names from the validated reactor list enter the Actions output or Maven -pl.
    with args.output.open("a", encoding="utf-8") as output:
        output.write(f"has_java_changes={str(has_java).lower()}\n")
        output.write(f"integration_modules={','.join(selected)}\n")


if __name__ == "__main__":
    main()
