FROM eclipse-temurin:21-alpine AS jre-build

RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.naming,java.net.http,jdk.crypto.ec,jdk.unsupported \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime

FROM alpine:latest
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME
RUN mkdir /opt/app
WORKDIR /opt/app
COPY build/libs/ZenithProxy.jar /opt/app/ZenithProxy.jar

EXPOSE 25565

CMD ["java", "-Xmx100m", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=13", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1", "-jar", "/opt/app/ZenithProxy.jar"]