############################################################
# Dockerfile to run an YouTrackDB (Graph) Container
# for current developed version
############################################################

# usage example:
# 1. build the image:
# `docker build -t bin_compat_yt .`
# 2. run the tests:
# `docker run --rm -v path_to_config:/config -v path_to_local_db:/config/test-db bin_compat_yt`
# sample config could be found in `tests/src/test/resources/binary-compatibility-test-config.yaml`

FROM azul/zulu-openjdk:21.0.8-jdk

RUN apt-get update && apt-get install -y git
COPY target/youtrackdb-tests-*.jar /youtrackdb-tests.jar

ENV YTDB_BINARY_COMPAT_TEST_CONFIG=/config/binary-compatibility-test-config.yaml
CMD ["java", "-jar", "/youtrackdb-tests.jar"]
