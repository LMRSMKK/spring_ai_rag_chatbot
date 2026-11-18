FROM alpine:latest
RUN sed -i 's|dl-cdn.alpinelinux.org|mirrors.tencent.com|g' /etc/apk/repositories && \
    apk add --no-cache openjdk17-jre bash curl && \
    update-alternatives --set java /usr/lib/jvm/default-jvm/bin/java 2>/dev/null || true
WORKDIR /app
COPY target/Spring_AI_Language-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
