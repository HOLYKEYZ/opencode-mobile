# Agent Hub

Remote control 4 AI coding agents (Codex, OpenCode, Windsurf, Kiro) from your phone.

```
Phone ──wss──► Render Server ←──wss── Laptop (relay + CLI agents)
                    │
                    └──► OpenAI / Bedrock / etc (cloud fallback)
```

- **Relay mode**: laptop runs agents locally (full CLI access, tools, approvals)
- **Cloud mode**: laptop offline — server calls APIs directly with stored keys
- **QR pairing**: scan a code from the laptop terminal, no config needed

## Quick Start

### 1. Deploy the server

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy)

Or manually:

```bash
git clone https://github.com/HOLYKEYZ/vibe-app-slop.git
cd vibe-app-slop/backend
npm install
node server.js
```

Deploy `backend/` on Render as a Node.js web service (or any cloud provider). Port `3001`.

### 2. Install Android app

Build from source:

```bash
cd AgentHub
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Or download from Releases.

### 3. Start the relay (laptop)

```bash
cd backend
npm install
SERVER_URL=wss://your-server.onrender.com node relay.js
```

The relay auto-discovers API keys from local CLI configs (`~/.codex/config.toml`, `~/.local/share/opencode/auth.json`, etc.) and prints a QR code.

### 4. Connect your phone

- Open the Agent Hub app → tap **Scan QR** → scan the code from your terminal
- Or tap **Enter session code** → paste the code shown in the terminal

### 5. Chat

Pick an agent, type a prompt. When your laptop is on, agents run locally with full tool access. When it's off, the server falls back to cloud APIs.

## Agents

| Agent | Session | Cloud API |
|-------|---------|-----------|
| Codex | OpenAI key or `access_token` | `api.openai.com` |
| OpenCode | Provider key (OpenAI, OpenRouter, Groq, Google, NVIDIA) | Multi-provider |
| Windsurf | Codeium token | `server.codeium.com` |
| Kiro | AWS credentials (JSON or `key:secret:region`) | Bedrock `converseStream` |

Models are configurable per-agent via the app settings or environment variables (`CODEX_MODEL`, `OPENCODE_MODEL`, etc.).

## Settings Reference

| Env | Default | Description |
|-----|---------|-------------|
| `PORT` | `3001` | Server port |
| `CODEX_MODEL` | `gpt-5.5` | Default Codex model |
| `OPENCODE_MODEL` | `gpt-5.5` | Default OpenCode model |
| `WINDSURF_MODEL` | `gpt-4o` | Default Windsurf model |
| `KIRO_MODEL` | `anthropic.claude-3-5-sonnet-20241022-v2:0` | Default Kiro/Bedrock model |
| `KIRO_REGION` | `us-east-1` | AWS region for Bedrock |

## Pairing Flow

1. `relay.js` generates a random code, sends API keys to the server, prints QR
2. Phone scans QR → connects to server with the code
3. Server links phone ↔ relay; stores keys for cloud fallback
4. Phone sends prompts → server routes to relay (or cloud if relay offline)
5. Session persists 5 minutes after relay disconnects, then expires
