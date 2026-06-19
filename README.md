# OpenCode Mobile

Control OpenCode and Devin from your phone through a desktop relay.

<table>
  <tr>
    <td><img src="proof1.jpg" alt="Screenshot 1" width="200" /></td>
    <td><img src="proof2.jpg" alt="Screenshot 2" width="200" /></td>
  </tr>
  <tr>
    <td colspan="2" align="center"><em>OpenCode Mobile screenshots at work</em></td>
  </tr>
</table>

## How it works

```
Phone ──wss──> Cloud Relay Server <──wss── Desktop Relay
                                        ├── OpenCode local server
                                        └── Devin CLI
```

The cloud server is a WebSocket switchboard between your phone and your laptop. Your code, credentials, and API keys never leave your machine.

## Quick start

The fastest way to get running is to point an OpenCode or Devin agent at this repo and ask it to set everything up for you — deploy the backend, build the Android app, and install it on your phone.

If you prefer to do it manually, follow the steps below.

### 1. Deploy the relay server

```bash
git clone https://github.com/HOLYKEYZ/opencode-mobile.git
cd opencode-mobile/backend
npm install
npm run build
npm start
```

Deploy `backend/` on Render as a Node.js web service (port `3001`).

### 2. Install the Android app

```bash
cd AgentHub
./gradlew assembleDebug
```

Or on Windows with USB debugging enabled, install directly:

```powershell
.\scripts\install-debug-apk.ps1 -Rebuild
```

### 3. Start the laptop relay

```bash
cd backend
SERVER_URL=wss://your-server.onrender.com npm run relay
```

The relay checks for local OpenCode/Devin installs and prints a QR code.

### 4. Connect your phone

Open Agent Hub, scan the QR code, pick a chat, and send a prompt.

## Features

- **Relay-only execution** — the cloud server never calls model APIs and does not need API keys
- **QR or manual pairing** — keep the same server URL and swap only the session code/agent
- **OpenCode support** — starts or reuses the local OpenCode HTTP server, lists sessions, auto-selects the most recent chat
- **Devin support** — drives the local Devin CLI in single-turn mode, lists sessions, web search enabled by default
- **Live transcript** — shows prompts, responses, thinking events, shell activity, and file changes without stale terminal noise
- **Technical event toggle** — command output and tool/file counts stay hidden unless enabled
- **Running-turn composer** — progress indicator while a turn is active; typing redirects into the active turn
- **Chat controls** — refresh lists, collapse/show, copy transcript, jump to latest message
- **In-app updates** — Settings includes an update button for grabbing the latest APK
- **Model controls** — switch the current model from Settings or the top bar
- **Token usage** — Settings keeps the latest token usage summary
- **Relay persistence** — the relay keeps the same code across reconnects unless `backend/relay-state.json` is deleted
- **Background-friendly** — keeps screen awake, preserves chat/transcript, reconnects after drops
- **Voice input** — Google speech-to-text fills the prompt box directly
- **File upload** — attachments are copied to `.agenthub_uploads/` on the laptop and appended to the agent prompt
- **Multi-phone** — every phone on the same session gets relay status and stream updates
- **Offline handling** — the phone shows an offline error if the laptop relay is down

## Agents

| Agent | How it is driven |
|-------|------------------|
| OpenCode | Local `opencode serve` HTTP API on `127.0.0.1:4096` |
| Devin | Local `devin` CLI in single-turn mode with web search enabled |

## Environment

| Env | Default | Description |
|-----|---------|-------------|
| `PORT` | `3001` | Relay server port |
| `SERVER_URL` | `ws://localhost:3001` | Relay server URL |
| `AGENTHUB_CWD` | repo root | Working directory for local agents |
| `OPENCODE_PORT` | `4096` | Local OpenCode server port |
| `DEVIN_PATH` | `devin` | Path to the Devin CLI binary |
| `DEVIN_SESSION_DIR` | `~/.local/share/devin/sessions` | Devin session storage |
| `AGENTHUB_RELAY_CODE` | unset | Optional fixed relay code |

## Notes

- OpenCode/Devin credentials and files stay on the laptop. The cloud server is only a switchboard.
- The relay survives lid closing only if Windows stays awake. Use `scripts/start-relay-keepawake.ps1` or set your power plan manually.
- Uploads are stored under `.agenthub_uploads/` in the relay working directory.
