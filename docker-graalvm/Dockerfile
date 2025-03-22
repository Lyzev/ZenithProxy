FROM alpine:latest

RUN apk add --no-cache libc6-compat

RUN mkdir -p /opt/app
WORKDIR /opt/app

COPY build/native/nativeCompile/ZenithProxy /opt/app/ZenithProxy
RUN chmod +x /opt/app/ZenithProxy

EXPOSE 25565
CMD ["/opt/app/ZenithProxy", "-Xmx100m", "-Dprofiling=false"]