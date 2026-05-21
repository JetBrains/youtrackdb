youtrackdb-embedded release stub

The youtrackdb-embedded module produces a shaded uber-jar with all third-party
dependencies relocated under com.jetbrains.youtrackdb.shade. The module has no
Java source files; every relocated class is built from a dependency declared
in pom.xml.

This stub exists so the release pipeline can attach -sources.jar and
-javadoc.jar artifacts to the published GAV. Maven Central refuses
deployments where any module is missing either classifier.

For sources and Javadocs of the relocated code, see the matching version of
youtrackdb-core, which exposes the same API without shading.
