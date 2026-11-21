FROM maven:3.9.11-eclipse-temurin-11 AS maven

FROM isovista:local

COPY --from=maven /usr/share/maven /opt/maven

ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
    PATH=/opt/maven/bin:$PATH \
    LD_LIBRARY_PATH=/lib/x86_64-linux-gnu \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Djava.library.path=/lib/x86_64-linux-gnu -Dfile.encoding=UTF-8"

WORKDIR /workspace
