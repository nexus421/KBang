# KBang with everything it needs to build: GraalVM, JBang and the musl toolchain (x86_64 only, see README, Docker).
# The steps mirror README, Deployment, so /opt/graalvm, /opt/jbang and /opt/musl are where config.example.json
# expects them.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# The cache mount keeps Gradle's downloads between builds, they do not end up in the image
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon buildFatJar

FROM debian:bookworm-slim
ARG GRAALVM_VERSION=25.0.2
ARG JBANG_URL=https://github.com/jbangdev/jbang/releases/latest/download/jbang.zip
ARG ZLIB_VERSION=1.3.1

# setsid comes with util-linux, procps has kill and pgrep, file is only for the smoke test
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip util-linux procps file gcc make zlib1g-dev musl-tools \
 && rm -rf /var/lib/apt/lists/*

# GraalVM CE (also runs KBang itself) and JBang
RUN curl -fsSLo /tmp/graalvm.tar.gz "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && curl -fsSLo /tmp/jbang.zip "${JBANG_URL}" \
 && unzip -q /tmp/jbang.zip -d /opt \
 && rm /tmp/graalvm.tar.gz /tmp/jbang.zip

# musl toolchain for static binaries: native-image expects x86_64-linux-musl-gcc and a zlib built against musl
RUN mkdir -p /opt/musl/bin \
 && ln -sf /usr/bin/musl-gcc /opt/musl/bin/x86_64-linux-musl-gcc \
 && curl -fsSLo /tmp/zlib.tar.gz "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" \
 && mkdir /tmp/zlib \
 && tar -xzf /tmp/zlib.tar.gz -C /tmp/zlib --strip-components=1 \
 && cd /tmp/zlib \
 && CC=musl-gcc ./configure --static --prefix=/tmp/zlib-musl \
 && make \
 && make install \
 && cp /tmp/zlib-musl/lib/libz.a /usr/lib/x86_64-linux-musl/ \
 && cp /tmp/zlib-musl/include/zlib.h /tmp/zlib-musl/include/zconf.h /usr/include/x86_64-linux-musl/ \
 && rm -rf /tmp/zlib /tmp/zlib-musl /tmp/zlib.tar.gz

# The home of the service user holds ~/.jbang, ~/.m2 and the workspaces: mount a volume there
RUN useradd --system --create-home --home-dir /var/lib/kbang --shell /usr/sbin/nologin kbang \
 && mkdir -p /opt/kbang /etc/kbang
COPY --from=build /src/build/libs/kbang.jar /opt/kbang/kbang.jar
COPY deploy/smoke-test.sh /opt/kbang/smoke-test.sh

USER kbang
ENV HOME=/var/lib/kbang
WORKDIR /var/lib/kbang
EXPOSE 8080
# The config is mounted at /etc/kbang/config.json. "docker run --rm kbang key name=laptop" prints a new API key.
ENTRYPOINT ["/opt/graalvm/bin/java", "-jar", "/opt/kbang/kbang.jar", "config=/etc/kbang/config.json"]
