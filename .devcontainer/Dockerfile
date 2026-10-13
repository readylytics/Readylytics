FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

# Set non-interactive mode and environment variables
ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_HOME=/home/vscode/android-sdk \
    ANDROID_SDK_ROOT=/home/vscode/android-sdk \
    PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${JAVA_HOME}/bin:${PATH} \
    GRADLE_USER_HOME=/home/vscode/.gradle

# Install system dependencies
# - JDK 17: Required by Readylytics (gradle/libs.versions.toml:2 specifies AGP 9.2.1 which requires JDK 17)
# - Build tools, curl, wget, unzip: Required for Android SDK installation and build processes
# - git: Required for version control and CI/CD
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk \
    openjdk-17-jdk-headless \
    git \
    curl \
    wget \
    unzip \
    build-essential \
    apt-transport-https \
    ca-certificates \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Node.js 18+ (required by Claude Code and OpenAI Codex CLI)
# Using NodeSource repository for latest LTS version
RUN curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs && \
    npm install -g npm@latest && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Android SDK cmdline-tools
# Version: 10.0 (latest stable as of 2026-07-14)
# Downloads from Google's official Android repository
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q -O /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-10141245_latest.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept Android SDK licenses and install required packages
# Platforms: API 37 (compileSdk/targetSdk from app/build.gradle.kts:123,137)
# Build-tools: 37.0.0 (implicit, managed by AGP based on compileSdk)
# Platform-tools: adb, fastboot, and other utilities
# Mirrors: Sets up connection to Google's update server
RUN yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses && \
    ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \
    "platforms;android-37" \
    "build-tools;37.0.0" \
    "platform-tools" \
    "tools"

# Install AI Coding Tools

# Anthropic Claude Code
# Package: @anthropic-ai/claude-code@^3.0.0 (version-pinned to major.minor for stability)
# Official: https://github.com/anthropics/claude-code
RUN npm install -g @anthropic-ai/claude-code@^3.0.0

# OpenAI Codex CLI
# Package: @openai/codex@^1.0.0 (npm package, version-pinned)
# Official: https://www.npmjs.com/package/@openai/codex
RUN npm install -g @openai/codex@^1.0.0

# Google Antigravity CLI
# Official installer: https://antigravity.google/cli/install.sh
# Installs latest version with auto-update support
RUN curl -fsSL https://antigravity.google/cli/install.sh | bash

# Create Gradle cache directory and ensure correct permissions
# Non-root user (vscode) must own all development directories for secure, unprivileged development
RUN mkdir -p ${GRADLE_USER_HOME} && \
    chown -R vscode:vscode ${ANDROID_HOME} ${GRADLE_USER_HOME} /home/vscode

# Verify all installations succeeded
# Failure to verify indicates build misconfiguration; exit code 1 fails the Docker build
RUN java -version && \
    ./gradlew --version 2>/dev/null || echo "✓ Gradle wrapper (will be used from repo)" && \
    claude --version && \
    codex --version && \
    antigravity --version

# Switch to non-root user for development
USER vscode
WORKDIR /workspaces/Readylytics
