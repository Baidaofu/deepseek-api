# Deekseep 1.7.5

Deekseep 1.7.5 is the open-source release of Deekseep.

## Downloads

- `Open.apk` — Official Open edition release build with unobfuscated R8 dead-code stripping (8.8MB).
- `Open-debug.apk` — Official Open edition debug build (32MB).

Due to rampant unauthorized resale and malicious abuse, the Closed edition is no longer published or updated on GitHub. Legitimate users can join the official Telegram community (@Deekseepapp) to obtain Closed builds.

## Important Notice

Public open-source repository updates are suspended indefinitely following this release. Please refer to the Letter to Users displayed on startup and the Letter to Developers in the repository for detailed context.

## Highlights & What's New

### 1. Host Version Compatibility
- Full compatibility adaptation for Mainland China DeepSeek app v2.4.1 (versionCode 257).
- Retained backward compatibility paths for 2.3.6, 2.3.4, 2.3.0, and 2.2.x.

### 2. Chat & Message Management
- Multi-select chat batch deletion: One-tap deletion of selected sessions in sidebar.
- Dual chat mode: Run two independent native chat sessions in split-screen resizable panes.
- Local quota unlock: Removed local chat count modification limits.
- Thinking chain code copy: Added one-click copy button for reasoning and thinking code blocks.
- Anti-recall reliability: Fixed edge cases where recalled messages could disappear and preserved conversation context.

### 3. Security & Anti-Risk Bypass
- Risk control SDK bypass: Added runtime inline bypass for SMSDK / Shumei device risk-control detection routines.
- Anti-ban defensive protections: Implemented defensive heuristics to mitigate automatic bans.

### 4. UI Polish & Navigation
- Theme color synchronization: Dynamic host theme color palette synchronization with custom HEX overrides.
- Entry setting renamed: '使用原生入口' renamed to '使用旧版入口' for clarity.
- UI Navigation Manager: Integrated Activity and Compose route manager.

### 5. Diagnostic Tools & System Logs
- High-frequency unmasked raw hook trace logging with rotating logs.
- System diagnostic log and crash trace export (.zip).
- Application user data backup support.

### 6. Architecture & Extensions
- Java Plugin Framework v3 with dedicated hook catalogs and lifecycle management.
- Remote Feature Flags and gray-release configuration extensions.
- Built-in Termux runtime support for Agent workflows.
- Agent Model Context Protocol (MCP) server integration.
- Segmented multi-round prompt injection.



