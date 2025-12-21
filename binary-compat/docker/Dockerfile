############################################################
# Dockerfile to run an YouTrackDB (Graph) Container
# for current developed version
############################################################

FROM azul/zulu-openjdk:21.0.8-jdk

RUN apt-get update && apt-get install -y git
COPY target/youtrackdb-binary-compat-*.jar /youtrackdb-binary-compat.jar

ENV YTDB_BINARY_COMPAT_TEST_CONFIG=/config/binary-compatibility-test-config.yaml
CMD ["java", "-jar", "/youtrackdb-binary-compat.jar"]
