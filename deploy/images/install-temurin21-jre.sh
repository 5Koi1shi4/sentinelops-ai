#!/bin/sh
set -eu

# Versions and checksums follow Adoptium's 21/jre/alpine/3.24 Dockerfile.
apk add --no-cache \
  ca-certificates \
  coreutils \
  fontconfig \
  musl-locales \
  musl-locales-lang \
  openssl \
  p11-kit-trust \
  ttf-dejavu \
  tzdata
apk upgrade --no-cache

case "$(apk --print-arch)" in
  x86_64)
    checksum='7bc7c2f9ba5ffea5f727e14581964bd54294b8edc2be9a79ea6393f8d0799200'
    archive='OpenJDK21U-jre_x64_alpine-linux_hotspot_21.0.12.1_1.tar.gz'
    ;;
  aarch64)
    checksum='98e15ae359e1f87160ee4c0abb43336513e41712d439fd4a805a5474af32b2c7'
    archive='OpenJDK21U-jre_aarch64_alpine-linux_hotspot_21.0.12.1_1.tar.gz'
    ;;
  *)
    echo 'Unsupported Java runtime architecture.' >&2
    exit 1
    ;;
esac

url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/$archive"
wget -q -O /tmp/temurin-jre.tar.gz "$url"
printf '%s  %s\n' "$checksum" /tmp/temurin-jre.tar.gz | sha256sum -c -
mkdir -p /opt/java/openjdk
tar -xzf /tmp/temurin-jre.tar.gz -C /opt/java/openjdk --strip-components=1 --no-same-owner
rm -f /tmp/temurin-jre.tar.gz
/opt/java/openjdk/bin/java -version 2>&1 | grep -F '21.0.12.1'
