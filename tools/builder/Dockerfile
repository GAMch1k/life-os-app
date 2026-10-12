# Builder image for the Life OS Android apps.
#
# JDK 21 plus the Android command-line tools, one SDK platform and one
# build-tools release, all pinned. Nothing secret is ever baked in: the
# signing directory is mounted read-only at run time by tools/build.
#
# Built and run by tools/build; the image tag is derived from this file's
# hash, so editing it makes the next build rebuild the image.

# Temurin 21.0.12.1+1 on Ubuntu 24.04 (noble), pinned by digest.
FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87

# Android command-line tools 23.0 (build 16111833). The SHA-1 is the one
# published in https://dl.google.com/android/repository/repository2-3.xml.
ARG CMDLINE_TOOLS_BUILD=16111833
ARG CMDLINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
# compileSdk 37 (current AndroidX and Compose need it; targetSdk stays 36)
# and the build-tools that AGP and the verification steps use.
ARG SDK_PLATFORM=android-37.0
ARG SDK_PLATFORM_REVISION=2
ARG SDK_BUILD_TOOLS=36.1.0
# AGP wants platform-tools present (it tries to install it otherwise). The
# SDK manager always installs the newest one, so the build checks the version.
ARG SDK_PLATFORM_TOOLS=37.0.1

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/build-tools/${SDK_BUILD_TOOLS}:${PATH}

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"; \
    echo "${CMDLINE_TOOLS_SHA1}  /tmp/cmdline-tools.zip" | sha1sum -c -; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools; \
    mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip

# Accept the SDK licenses non-interactively, install the pinned packages, and
# fail if the SDK manager delivered other revisions than the pinned ones. The
# SDK manager unpacks executables as 0744, so open them up for the build user.
RUN set -eux; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install "platforms;${SDK_PLATFORM}" "build-tools;${SDK_BUILD_TOOLS}" platform-tools; \
    grep -qx "Pkg.Revision=${SDK_PLATFORM_REVISION}" "${ANDROID_HOME}/platforms/${SDK_PLATFORM}/source.properties"; \
    grep -qx "Pkg.Revision=${SDK_BUILD_TOOLS}" "${ANDROID_HOME}/build-tools/${SDK_BUILD_TOOLS}/source.properties"; \
    grep -qx "Pkg.Revision=${SDK_PLATFORM_TOOLS}" "${ANDROID_HOME}/platform-tools/source.properties"; \
    chmod -R a+rX "${ANDROID_HOME}"; \
    sdkmanager --list_installed; \
    rm -rf /root/.android /root/.cache /tmp/*

# The Gradle cache lives in a named volume mounted at /gradle. A new named
# volume copies this directory's ownership, so the build user (uid 1000,
# "ubuntu" in this base image) can write to it.
RUN mkdir -p /gradle /workspace && chown 1000:1000 /gradle /workspace

WORKDIR /workspace
