# Deekseep Open API

The upstream **Deekseep** LSPosed module for the official DeepSeek Android app, with its
**Local API** restored and the author's promotional layer removed.

Based on:
- [`lllucccian/Deekseep`](https://github.com/lllucccian/Deekseep) @ `v1.7.5.1` — the module itself
  (all features: chat, account, Agent + MCP, backup, Java plugins, navigation, diagnostics, …)
- The build recipe from [`Baidaofu/Deekseep`](https://github.com/Baidaofu/Deekseep), which makes
  the project buildable
- The open Local API implementation from `deekseep-onlyapi`

## Build

The upstream Gradle/Compose project (`compose-launcher`) is the path that produces the real
release APK. Four things stop it working out of the box; `ci/apply-local-api.py` plus the
workflow handle them:

1. `gradle.properties` hardcodes a Termux `aapt2` path.
2. `module/debug.keystore` is gitignored, so Gradle cannot sign.
3. `protectedBuild` defaults to `true` (Closed); the Open build needs `-PprotectedBuild=false`,
   and `build.gradle.kts` references an `editions/closed/` directory that does not exist here.
4. AGP 9.3.0's lint needs JDK 21, not the documented 17.

```bash
python3 ci/apply-local-api.py . .
cd compose-launcher
sed -i '/aapt2FromMavenOverride/d' gradle.properties
mkdir -p ../editions/closed/full-backup-src
cp ../ci/signing/deepseek-api.jks ../module/debug.keystore
gradle :app:assembleRelease -PprotectedBuild=false
```

## What this adds

**Local API.** Two components are missing from the public source and live only inside the
Closed edition's encrypted payload:

| Component | Role | Public source | Closed APK |
| --- | --- | --- | --- |
| `z1` | HTTP gateway (server + protocol conversion) | absent | absent from `classes.dex` |
| `z18` | execution engine (drives the native transport) | absent | absent from `classes.dex` |

Both are reimplemented here (`src/com/dsmod/probe/z1.java`,
`ci/engine/LocalApiEngine.java`). The engine builds the host completion request directly from
the host's own constructor, so no warm-up message is needed. `z14` maps the payload name
`com.dsmod.probe.z18` onto the open engine.

Endpoints: `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/responses`,
`POST /v1/messages`, `POST /v1/messages/count_tokens`. Streaming, reasoning, and both
OpenAI and Anthropic wire formats.

The gateway also publishes the same status files the Closed payload did
(`dq0.txt`, `deekseep_api.log`, `deekseep_api_status.json`, and a copy on shared storage),
and listens on the module's expected default port **8765**.

**Manifest.** `compose-launcher`'s manifest omits `z20`/`z21` — the Local API keep-alive
bridge activity and its foreground service. Without them the API logs
`ActivityNotFoundException` and dies when DeepSeek is backgrounded. The patch declares them.

**Removed.** The author's watermark, sponsor dialogs (Afdian / WeChat), community invite
links, update checker and splash letter. The GPL-3.0 licence notice is kept.

## Layout

```
src/com/dsmod/probe/        z1 gateway, z2 contract, z4 attachments, z14 resolver
ci/engine/                  LocalApiEngine (the z18 replacement)
ci/neutralized/             replacement sources for stripped upstream classes
ci/signing/                 pinned signing key (password: android)
ci/apply-local-api.py       the integration patches (idempotent)
tests/                      JVM gateway tests
run-tests.sh                runs them
module/, compose-launcher/  upstream source (unmodified; patched at build time)
```

## Testing

```bash
JSON_JAR=/path/to/json.jar ./run-tests.sh     # 32 assertions, no device needed
```

## Signing

`ci/signing/deepseek-api.jks` is committed so every build shares one identity and successive
builds install with `adb install -r`, preserving the LSPosed scope.

```
keystore: ci/signing/deepseek-api.jks
store/key password: android
alias: androiddebugkey
```

This is a **development key**. Replace it before any real distribution.

## Licence

GPL-3.0-only, matching upstream. Not affiliated with DeepSeek or the upstream author.
