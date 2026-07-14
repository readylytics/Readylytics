# Readylytics Dev Container & GitHub Codespaces

This directory contains configuration for a reproducible Android development environment using VS Code Dev Containers and GitHub Codespaces.

## Quick Start

### GitHub Codespaces
1. Navigate to the Readylytics repository on GitHub
2. Click **Code** → **Codespaces** → **Create Codespace on claude/devcontainer-codespaces-plan-iwibxv** (or current branch)
3. VS Code opens in the browser (or desktop app)
4. Container builds automatically (~3-5 min on first run, cached thereafter)
5. Post-create setup runs automatically (gradle cache warm-up, ~2 min)
6. Ready to develop!

### Local Dev Container (VS Code Desktop)
1. Clone the repository: `git clone https://github.com/readylytics/readylytics.git`
2. Open the folder in VS Code: `code readylytics`
3. VS Code prompts: **"Folder contains a Dev Container configuration. Reopen in Container?"**
4. Click **Reopen in Container**
5. Container builds and initializes (first run: ~5-10 min)
6. Ready to develop!

## What Works in the Container

✅ **Build & Compilation**
- `./gradlew assembleDebug` — Build debug APK
- `./gradlew bundleRelease` — Build App Bundle (requires signing keys)
- `./gradlew compileDebugKotlin` — Compile Kotlin code

✅ **Testing**
- `./gradlew testDebugUnitTest` — Unit tests (JUnit, Robolectric, MockK)
- `./gradlew jacocoTestReport` — Generate coverage reports
- `./gradlew jacocoCoverageVerification` — Verify coverage gates (≥30%)

✅ **Code Quality**
- `./gradlew ktlintCheck` — Verify Kotlin formatting
- `./gradlew ktlintFormat` — Auto-format Kotlin code
- `./gradlew lint` — Android lint checks
- `./gradlew lintRelease` — Release lint checks

✅ **Development Tools**
- Git workflows and version control
- Terminal-based development with AI coding agents
- All three coding CLIs (Claude Code, OpenAI Codex, Google Antigravity)

## What Requires External Tools

❌ **Instrumented Tests**
- Requires Android emulator (API 36, x86_64, pixel_6)
- Run from Android Studio or configure external emulator with `adb connect`
- Command: `./gradlew connectedDebugAndroidTest -x :benchmark:connectedDebugAndroidTest`

❌ **Health Connect Integration**
- Health Connect APIs available on device or emulator only
- Container can build and test data mappers using mock data
- Real Health Connect sync requires physical device or emulator with Health Connect installed

❌ **Real-Time Debugging**
- Android Studio debugger attachment required
- Container can build debug APKs but not debug interactively
- Deploy to external device/emulator and attach debugger from Android Studio

❌ **Release Builds & Play Store Publishing**
- Requires signing keystore (not in container image, for security)
- Requires Google Play Console access and Workload Identity Federation setup
- See `internal-docs/RELEASE_SIGNING.md` for full release workflow

## Environment

| Component | Version | Source |
|-----------|---------|--------|
| OS | Ubuntu 24.04 LTS | Base image: `mcr.microsoft.com/devcontainers/base:ubuntu-24.04` |
| Java | JDK 17 | `openjdk-17-jdk` (openjdk-17-jdk-headless for remote) |
| Gradle | 9.6.1 | Wrapper from repo (`gradle/wrapper/gradle-wrapper.properties`) |
| Android SDK | API 37 | Installed via `cmdline-tools` |
| Build Tools | 37.0.0 | Installed via `sdkmanager` |
| Kotlin | 2.3.21 | Managed by Android Gradle Plugin (gradle/libs.versions.toml) |
| Android Gradle Plugin | 9.2.1 | gradle/libs.versions.toml |
| Node.js | 18.x LTS | Required for Claude Code and OpenAI Codex CLI |

## Persistent Volumes

The container uses named Docker volumes to persist data across restarts and rebuilds:

| Volume | Mount Point | Purpose |
|--------|-------------|---------|
| `readylytics-gradle-cache` | `~/.gradle/` | Gradle dependency cache (speeds up builds) |
| `readylytics-android-sdk` | `/home/vscode/android-sdk` | Android SDK and tools (2 GB+, cached) |
| `readylytics-claude-config` | `~/.claude/` | Claude Code configuration and cache |
| `readylytics-npm-cache` | `~/.npm/` | npm package cache for coding CLIs |

**Clean up volumes if needed:**
```bash
docker volume rm readylytics-gradle-cache
docker volume rm readylytics-android-sdk
docker volume rm readylytics-claude-config
docker volume rm readylytics-npm-cache
```

Next container start will rebuild the volumes (takes a few minutes the first time).

## AI Coding Tools

All three tools are preinstalled and available immediately:

### Anthropic Claude Code
```bash
claude --version              # Display version
claude login                  # Interactive browser OAuth (recommended)
export ANTHROPIC_API_KEY='sk-ant-...'  # Or set API key via env var
claude --help                 # Show available commands
```

**Configuration:** `~/.claude/` (persisted in volume)  
**Official:** https://github.com/anthropics/claude-code  
**Authentication:** Browser-based OAuth or `ANTHROPIC_API_KEY` env var  
**Codespaces:** Set `ANTHROPIC_API_KEY` as a repository secret

### OpenAI Codex CLI
```bash
codex --version               # Display version
export OPENAI_API_KEY='sk-...'  # Set API key
codex --help                  # Show available commands
```

**Package:** `@openai/codex` (npm)  
**Official:** https://www.npmjs.com/package/@openai/codex  
**Authentication:** `OPENAI_API_KEY` env var (required for API calls)  
**Codespaces:** Set `OPENAI_API_KEY` as a repository secret

### Google Antigravity CLI
```bash
antigravity --version         # Display version
export ANTIGRAVITY_API_KEY='...'  # Set API key if needed
antigravity --help            # Show available commands
```

**Installer:** https://antigravity.google/cli/install.sh  
**Authentication:** Browser-based OAuth or API key (TBD from official docs)  
**Codespaces:** Set `ANTIGRAVITY_API_KEY` as a repository secret if required

## Codespaces Secrets Configuration

To enable authentication in GitHub Codespaces:

1. Navigate to repository → **Settings** → **Secrets and variables** → **Codespaces**
2. Add secrets:
   - `ANTHROPIC_API_KEY` (optional for Claude Code; supports browser OAuth)
   - `OPENAI_API_KEY` (required for OpenAI Codex if using programmatically)
   - `ANTIGRAVITY_API_KEY` (required for Google Antigravity if using programmatically)

3. Secrets are automatically injected as environment variables in the container
4. Run `echo $ANTHROPIC_API_KEY` to verify injection
5. Coding tools read these env vars automatically on startup

**Security Note:** Secrets are not visible in logs or container images. They are injected at runtime only.

## Build & Test Examples

### Debug APK
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Unit Tests
```bash
./gradlew testDebugUnitTest
# Runs: JUnit 4, Robolectric, MockK tests (no emulator required)
```

### Pre-Commit Checks (Mandatory)
```bash
./gradlew ktlintFormat        # Auto-format Kotlin code
./gradlew testDebugUnitTest   # Run unit tests
./gradlew lintRelease         # Android lint checks
```

### Coverage Report
```bash
./gradlew jacocoTestReport
# Output: app/build/reports/jacoco/jacocoTestReport/index.html
# Minimum coverage: 30% (60% for workers package)
```

### Full CI Simulation (GitHub Actions)
```bash
./gradlew ktlintCheck          # Verify formatting (no auto-fix)
./gradlew lint lintRelease     # Lint checks
./gradlew testDebugUnitTest    # Unit tests
./gradlew jacocoTestReport     # Coverage report
./gradlew jacocoCoverageVerification  # Verify coverage gates
```

## Troubleshooting

### Gradle Build Fails or Hangs
**Symptom:** `./gradlew assembleDebug` hangs or times out

**Solution:**
1. Kill any stuck Gradle daemons: `./gradlew --stop`
2. Clear Gradle cache: `docker volume rm readylytics-gradle-cache`
3. Restart container: Close and reopen VS Code Dev Container
4. Retry build: `./gradlew clean assembleDebug --info`

### Android SDK Issues
**Symptom:** `ANDROID_HOME not set` or `sdkmanager not found`

**Solution:**
1. Verify ANDROID_HOME: `echo $ANDROID_HOME`
2. Verify cmdline-tools: `ls -la $ANDROID_HOME/cmdline-tools/latest/bin/`
3. Clear Android SDK volume: `docker volume rm readylytics-android-sdk`
4. Rebuild container (next restart rebuilds SDK)

### Out of Memory Errors
**Symptom:** `java.lang.OutOfMemoryError: GC overhead limit exceeded`

**Cause:** Codespaces machine size too small (2-core/8GB default) for large builds

**Solution:**
1. Increase machine size: Codespaces settings → Machine type → 4-core or larger
2. OR reduce Gradle heap: Edit `gradle.properties` and change `-Xmx4096m` to `-Xmx2048m`
3. Retry build with smaller heap: `./gradlew assembleDebug -Xmx2048m`

### Claude Code Authentication Issues
**Symptom:** `claude --version` works but `claude login` fails or times out

**Cause:** Browser OAuth may not work in headless environment (Codespaces web terminal)

**Solution:**
1. Use API key instead: Set `ANTHROPIC_API_KEY` in Codespaces secrets
2. Or use local Dev Container with browser support for interactive OAuth
3. Or authenticate on local machine and copy `~/.claude/` config to Codespaces

### Instrumented Tests Not Available
**Symptom:** `./gradlew connectedDebugAndroidTest` fails with "No connected devices"

**Solution:** Instrumented tests require Android emulator or physical device
1. **Option A:** Use Android Studio emulator
   - Open Android Studio on your machine
   - Start Android Virtual Device (API 36, x86_64, pixel_6)
   - Run from Codespaces: `adb connect <your-machine-ip>:5555`
   - Retry: `./gradlew connectedDebugAndroidTest`

2. **Option B:** Use physical device
   - Enable USB debugging on device
   - Connect via USB or wireless ADB
   - Retry: `./gradlew connectedDebugAndroidTest`

3. **Option C:** Skip instrumented tests for now
   - Container supports unit tests and lint only
   - Instrumented tests run in GitHub Actions CI

## Local Dev Container Secrets

For local development with sensitive credentials:

1. Create `.env` file in repository root (add to `.gitignore`):
   ```bash
   export ANTHROPIC_API_KEY='sk-ant-...'
   export OPENAI_API_KEY='sk-...'
   export ANTIGRAVITY_API_KEY='...'
   ```

2. Load before starting container:
   ```bash
   source .env
   code .
   ```

3. Or export individually:
   ```bash
   export ANTHROPIC_API_KEY='sk-ant-...'
   code .
   ```

**Security:** `.env` is in `.gitignore` and never committed. Never share `.env` files.

## Resource Requirements

### Minimum (Recommended for Codespaces)
- **CPU:** 2 cores
- **RAM:** 8 GB
- **Disk:** 32 GB
- **Build time:** ~10-15 min (full clean build), ~2-5 min (cache hits)

### Comfortable (for heavier workflows)
- **CPU:** 4 cores
- **RAM:** 16 GB
- **Disk:** 64 GB
- **Build time:** ~3-5 min (full clean), ~30 sec (cache hits)

### Premium (for intensive development)
- **CPU:** 8 cores
- **RAM:** 32 GB
- **Disk:** 128 GB
- **Build time:** ~1-2 min (full clean), ~5 sec (cache hits)

**Codespaces Cost:** Machine size affects hourly charges. See https://github.com/pricing/copilot for details.

## Documentation

- **Dockerfile:** Inline comments explain each build step, versions, and source URLs
- **devcontainer.json:** Full configuration for VS Code and Codespaces
- **post-create.sh:** Runs once on container creation; idempotent setup
- **post-start.sh:** Runs each container start; lightweight verification
- **Repository Docs:**
  - `README.md` — Project overview and quick start
  - `ABOUT.md` — App features and scoring algorithms
  - `internal-docs/DATA_FLOW.md` — Architecture and data pipeline
  - `internal-docs/RELEASE_SIGNING.md` — Release build and Play Store workflow

## Known Limitations

1. **No Android Emulator:** Container is intentionally lightweight; emulator requires significant resources
2. **No Android Studio IDE:** Container provides CLI tools only; use Android Studio on host machine for advanced debugging
3. **Health Connect:** Read-only in tests (mock data only); real sync requires device
4. **Release Signing:** Requires external keystore and Play Console setup
5. **Git LFS:** Not installed (project uses standard Git)

## Getting Help

- **Build errors:** Check `.gradle/` volume and Dockerfile version pins
- **Android SDK issues:** Verify `sdkmanager` in `$ANDROID_HOME/cmdline-tools/latest/bin/`
- **AI tool problems:** Run `claude --version`, `codex --version`, `antigravity --version`
- **Codespaces issues:** Check GitHub status and increase machine size if needed
- **Local container issues:** Ensure Docker Desktop is running and volumes are accessible

## Contributing Dev Container Updates

When updating the dev container:

1. Edit `.devcontainer/Dockerfile`, `devcontainer.json`, scripts as needed
2. Test in a fresh container (full rebuild)
3. Verify all verification commands pass
4. Commit with clear message: `Add/update dev container configuration`
5. Push to branch and open PR for review

**Before committing, test:**
```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lint lintRelease
claude --version
codex --version
antigravity --version
```

---

**Last Updated:** 2026-07-14  
**Container Base:** Ubuntu 24.04 LTS (Microsoft-maintained)  
**Gradle:** 9.6.1  
**Java:** JDK 17  
**Android SDK:** API 37
