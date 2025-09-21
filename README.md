# AI Agent (OpenRouter-powered) — Production Skeleton

**What this repo is:** Kotlin Android project skeleton for a mobile AI agent that:
- Uses OpenRouter API for reasoning (no heavy local model).
- Executes user-requested actions via Accessibility Service.
- Stores data locally and securely.
- Supports long-running, checkpointed tasks and resumability.
- Enforces strong user confirmations (biometric/PIN) for sensitive actions.

**Important**
- Replace `OPENROUTER_MODEL` placeholder and set API key in app settings.
- Do NOT publish without proper privacy policy and Accessibility justification.
- This is a skeleton: extend and test thoroughly on device before any production use.

## Build
This repo contains a GitHub Actions workflow (`.github/workflows/android-build.yml`) to build the APK automatically.

## Quick start
1. Open repository in GitHub mobile and commit.
2. On device: install the generated APK from Actions artifacts (or download via your repo).
3. Run app, go to Settings -> set OpenRouter API key (encrypted).
4. Follow onboarding to enable Accessibility & grant permissions.