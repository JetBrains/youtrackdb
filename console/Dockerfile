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

FROM eclipse-temurin:21

RUN apt-get update
RUN apt-get install -y dos2unix
RUN rm -rf /var/lib/apt/lists/*

ARG YTDB_CONSOLE_DIR

COPY docker/docker-entrypoint.sh /
COPY ${YTDB_CONSOLE_DIR} /opt/ytdb-console


RUN dos2unix /docker-entrypoint.sh
RUN dos2unix /opt/ytdb-console/bin/ytdb.sh

RUN groupadd -r ytdb
RUN useradd -m -r -g ytdb -s /bin/false ytdb
RUN chown -R ytdb:ytdb /opt/ytdb-console
USER ytdb

WORKDIR /opt/ytdb-console

VOLUME /opt/ytdb-console/conf
VOLUME /opt/ytdb-console/data

ENTRYPOINT ["/docker-entrypoint.sh"]