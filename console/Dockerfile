# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

FROM alpine

ARG YTDB_CONSOLE_DIR

RUN apk add --no-cache --update \
    bash \
    openjdk21-jdk

COPY src/main/docker/docker-entrypoint.sh /
COPY ${YTDB_CONSOLE_DIR} /opt/ytdb-console

WORKDIR /opt/ytdb-console

RUN addgroup -S ytdb && adduser -S -s /bin/false -G ytdb ytdb
RUN chown -R ytdb:ytdb /opt/ytdb-console
USER ytdb

ENTRYPOINT ["/docker-entrypoint.sh"]