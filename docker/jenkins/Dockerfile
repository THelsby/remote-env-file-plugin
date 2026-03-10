# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-17 AS builder

WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM jenkins/jenkins:2.528.3-lts-jdk17

USER root

COPY docker/fixture/certs/env-fixture.crt /usr/local/share/ca-certificates/env-fixture.crt
RUN update-ca-certificates \
    && keytool -importcert -noprompt -storepass changeit -alias env-fixture-dev \
        -keystore "${JAVA_HOME}/lib/security/cacerts" \
        -file /usr/local/share/ca-certificates/env-fixture.crt

COPY docker/jenkins/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY docker/jenkins/init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/
COPY docker/jenkins/jenkins-dev.sh /usr/local/bin/jenkins-dev.sh
COPY --from=builder /workspace/target/remote-env-file.hpi /usr/share/jenkins/ref/plugins/remote-env-file.jpi

RUN chmod 755 /usr/local/bin/jenkins-dev.sh \
    && chown -R jenkins:jenkins \
        /usr/share/jenkins/ref/init.groovy.d \
        /usr/share/jenkins/ref/plugins/remote-env-file.jpi \
        /usr/local/share/ca-certificates/env-fixture.crt

USER jenkins

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/jenkins-dev.sh"]
