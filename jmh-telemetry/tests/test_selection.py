"""Offline contract checks for default job isolation and unpublished artifacts."""
import copy
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def reactor():
    root = ET.parse(ROOT / "pom.xml")
    modules = {}
    for entry in root.findall("m:modules/m:module", NS):
        pom = ET.parse(ROOT / entry.text / "pom.xml")
        modules[pom.findtext("m:artifactId", namespaces=NS)] = pom
    return modules


def upstream(modules):
    pending = ["youtrackdb-jmh-ldbc"]
    selected = set()
    while pending:
        artifact = pending.pop()
        if artifact in selected:
            continue
        selected.add(artifact)
        # Conservatively include every profile and plugin dependency. Inactive
        # profiles may over-select, but cannot hide a default-job dependency.
        for dep in modules[artifact].findall(".//m:dependency", NS):
            target = dep.findtext("m:artifactId", namespaces=NS)
            if target in modules:
                pending.append(target)
        for plugin in modules[artifact].findall(".//m:plugin", NS):
            target = plugin.findtext("m:artifactId", namespaces=NS)
            if target in modules:
                pending.append(target)
    return selected


def require_isolated(modules):
    if "youtrackdb-jmh-telemetry" in upstream(modules):
        raise AssertionError("Default LDBC selection reaches telemetry")


class SelectionTest(unittest.TestCase):
    def test_default_jobs_select_only_ldbc_upstream_closure(self):
        require_isolated(reactor())
        for job in ("ldbc-jmh-compare.yml", "ldbc-jmh-nightly.yml"):
            content = (ROOT / ".github/workflows" / job).read_text()
            commands = re.findall(r"./mvnw[^\n]+", content)
            self.assertTrue(commands)
            for command in commands:
                self.assertIn("-pl jmh-ldbc -am", command)
            self.assertNotIn("jmh-telemetry", content)

    def test_profile_and_plugin_dependency_canaries_reject_selection(self):
        # Inject each forbidden edge in memory, without changing repository POMs.
        for container in (
            "<profiles><profile><id>bench</id><dependencies>{}</dependencies></profile></profiles>",
            "<build><plugins><plugin><artifactId>example</artifactId>"
            "<dependencies>{}</dependencies></plugin></plugins></build>",
        ):
            with self.subTest(container=container):
                modules = copy.deepcopy(reactor())
                dependency = ("<dependency><groupId>io.youtrackdb</groupId>"
                              "<artifactId>youtrackdb-jmh-telemetry</artifactId></dependency>")
                fragment = ET.fromstring(
                    '<project xmlns="' + NS["m"] + '">' + container.format(dependency) + "</project>")
                modules["youtrackdb-jmh-ldbc"].getroot().extend(fragment)
                with self.assertRaises(AssertionError):
                    require_isolated(modules)

    def test_module_cannot_install_deploy_or_publish(self):
        pom = ET.parse(ROOT / "jmh-telemetry/pom.xml")
        for name, flag in (
            ("maven-install-plugin", "skip"),
            ("maven-deploy-plugin", "skip"),
            ("central-publishing-maven-plugin", "skipPublishing"),
        ):
            plugin = next(p for p in pom.findall("m:build/m:plugins/m:plugin", NS)
                          if p.findtext("m:artifactId", namespaces=NS) == name)
            self.assertEqual("true", plugin.findtext("m:configuration/m:" + flag, namespaces=NS))
