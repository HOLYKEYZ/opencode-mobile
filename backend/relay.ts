// @ts-nocheck
const WebSocket = require('ws');
const path = require('path');
const os = require('os');
const fs = require('fs');
const QRCode = require('qrcode');

const SERVER_URL = process.env.SERVER_URL || 'ws://localhost:3001';
const RELAY_DIR = fs.existsSync(path.join(__dirname, 'package.json')) ? __dirname : path.join(__dirname, '..');
const WORKSPACE_CWD = path.resolve(process.env.AGENTHUB_CWD || process.env.WORKSPACE_CWD || path.join(RELAY_DIR, '..'));
const RELAY_STATE_FILE = path.join(RELAY_DIR, 'relay-state.json');

const OPENCODE_SERVER_PORT = Number(process.env.OPENCODE_SERVER_PORT || 4096);
const OPENCODE_SERVER_PASSWORD = process.env.OPENCODE_SERVER_PASSWORD || '37e27954-9586-4226-89b5-bf063e7972ff';
const OPENCODE_SERVER_USERNAME = process.env.OPENCODE_SERVER_USERNAME || 'opencode';
const OPENCODE_CLI_PATH = path.join(RELAY_DIR, 'node_modules', 'opencode-ai', 'bin', 'opencode.exe');
const OPENCODE_BASE_URL = process.env.OPENCODE_BASE_URL || `http://127.0.0.1:${OPENCODE_SERVER_PORT}`;

let opencodeServerProcess = null;
let ocServerVerified = false;
let executeLock = null; // Promise that resolves when current execute finishes

function openCodeAuthHeader() {
  const cred = Buffer.from(`${OPENCODE_SERVER_USERNAME}:${OPENCODE_SERVER_PASSWORD}`).toString('base64');
  return `Basic ${cred}`;
}

function openCodeAuthToken() {
  return Buffer.from(`${OPENCODE_SERVER_USERNAME}:${OPENCODE_SERVER_PASSWORD}`).toString('base64');
}

async function ocFetch(urlPath, init = {}) {
  const url = `${OPENCODE_BASE_URL}${urlPath}`;
  const method = (init.method || 'GET').toUpperCase();
  const headers = {
    'Accept': 'application/json',
    'Authorization': openCodeAuthHeader(),
    ...(init.headers || {}),
  };
  if (method !== 'GET' && method !== 'HEAD') {
    headers['Content-Type'] = 'application/json';
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5 * 60 * 1000);
  try {
    const res = await fetch(url, { ...init, headers, signal: controller.signal });
    clearTimeout(timeout);
    if (res.status === 204) return null;
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`${res.status} ${res.statusText}: ${text.slice(0, 200)}`);
    }
    const text = await res.text();
    if (!text.trim()) return null;
    return JSON.parse(text);
  } catch (err) {
    clearTimeout(timeout);
    throw err;
  }
}

async function isOpenCodeServerRunning() {
  for (let i = 0; i < 3; i++) {
    try {
      console.log(`  [oc-check] attempt ${i+1} to ${OPENCODE_BASE_URL}/global/health`);
      const health = await ocFetch('/global/health');
      console.log(`  [oc-check] result:`, JSON.stringify(health));
      if (health) return true;
    } catch (e) {
      console.log(`  [oc-check] attempt ${i+1} failed:`, e.message);
    }
    await sleep(500);
  }
  return false;
}

async function ensureOpenCodeServer() {
  if (ocServerVerified) return true;
  if (await isOpenCodeServerRunning()) { ocServerVerified = true; return true; }

  if (!fs.existsSync(OPENCODE_CLI_PATH)) {
    console.error('OpenCode CLI not found at', OPENCODE_CLI_PATH);
    return false;
  }

  console.log(`Starting OpenCode server on port ${OPENCODE_SERVER_PORT}...`);
  const env = { ...process.env, OPENCODE_SERVER_PASSWORD, OPENCODE_SERVER_USERNAME };
  opencodeServerProcess = require('child_process').spawn(OPENCODE_CLI_PATH, [
    'serve', '--hostname', '0.0.0.0', '--port', String(OPENCODE_SERVER_PORT)
  ], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env,
    cwd: WORKSPACE_CWD,
    windowsHide: true,
  });

  opencodeServerProcess.stdout?.on('data', (d) => {
    const msg = d.toString().trim();
    if (msg) console.log(`  [oc-serve] ${msg}`);
  });
  opencodeServerProcess.stderr?.on('data', (d) => {
    const msg = d.toString().trim();
    if (msg) console.error(`  [oc-serve] ${msg}`);
  });
  opencodeServerProcess.on('error', (err) => console.error('  [oc-serve] error:', err.message));
  opencodeServerProcess.on('close', (code) => console.log(`  [oc-serve] exited ${code}`));

  for (let i = 0; i < 40; i++) {
    await new Promise(r => setTimeout(r, 500));
    if (await isOpenCodeServerRunning()) {
      console.log('OpenCode server ready.');
      return true;
    }
  }
  console.error('OpenCode server failed to start.');
  return false;
}

function truncateText(value, max = 140) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  return text.length <= max ? text : `${text.slice(0, max - 1)}...`;
}

function stripAnsi(value) {
  return String(value || '')
    .replace(/\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1B\\))/gu, '')
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/gu, '')
    .replace(/\r/g, '')
    .trim();
}

const DETAIL_MESSAGE_LIMIT = 80;
const DETAIL_MESSAGE_CHARS = 6000;

function formatPart(part) {
  if (!part) return '';
  switch (part.type) {
    case 'text': return part.text || '';
    case 'reasoning': return part.text ? `[thinking] ${part.text}` : '';
    case 'tool': {
      const name = part.tool || part.state?.title || 'tool';
      const status = part.state?.status || '';
      const input = part.state?.input?.filePath || part.state?.input?.command || '';
      const summary = input ? ` ${truncateText(input, 60)}` : '';
      return `[tool:${name}${status ? ` (${status})` : ''}${summary}]`;
    }
    case 'step-start': return '';
    case 'step-finish': return '';
    case 'file': return `[file] ${part.filename || part.url || 'attachment'}`;
    default: return `[${part.type}]`;
  }
}

function formatMessageParts(message) {
  const info = message.info || message;
  const role = info.role || 'message';
  const parts = message.parts || [];
  const textParts = parts.map(formatPart).filter(Boolean);
  return {
    id: info.id || message.id || '',
    role,
    text: textParts.join('\n'),
    time: info.time || null,
    tokens: info.tokens || null,
    cost: info.cost ?? null,
    model: info.model || null,
  };
}

function formatMessageForPhone(message) {
  const info = message.info || message;
  const role = info.role || 'message';
  const parts = message.parts || [];
  const result = [];

  for (const part of parts) {
    switch (part.type) {
      case 'text':
        if (part.text) result.push({ role, text: part.text });
        break;
      case 'reasoning':
        if (part.text) result.push({ role: 'thinking', text: part.text });
        break;
      case 'tool': {
        const name = part.tool || part.state?.title || 'tool';
        const status = part.state?.status || '';
        const input = part.state?.input?.filePath || part.state?.input?.command || '';
        const display = input ? `${name} — ${truncateText(input, 80)}` : name;
        result.push({ role: 'tool', text: `[tool] ${display}${status ? ` (${status})` : ''}` });
        break;
      }
      case 'file':
        result.push({ role: 'file', text: `[file] ${part.filename || part.url || 'attachment'}` });
        break;
      case 'step-start':
      case 'step-finish':
        break;
      default:
        break;
    }
  }
  return result;
}

// WebSocket

let ws, reconnectTimer, heartbeatTimer, registrationTimer, publicSessionTimer, sessionCode;
let relayConfig = {};

function readPersistedRelayCode() {
  try {
    if (!fs.existsSync(RELAY_STATE_FILE)) return '';
    const state = JSON.parse(fs.readFileSync(RELAY_STATE_FILE, 'utf-8'));
    return typeof state.code === 'string' ? state.code : '';
  } catch { return ''; }
}

function writePersistedRelayCode(code) {
  try {
    fs.writeFileSync(RELAY_STATE_FILE, JSON.stringify({ code, serverUrl: SERVER_URL, updatedAt: new Date().toISOString() }, null, 2));
  } catch {}
}

function serverHttpUrl() {
  try {
    const url = new URL(SERVER_URL);
    if (url.protocol === 'wss:') url.protocol = 'https:';
    else if (url.protocol === 'ws:') url.protocol = 'http:';
    else return null;
    url.pathname = ''; url.search = ''; url.hash = '';
    return url.toString().replace(/\/+$/, '');
  } catch { return null; }
}

async function relayIsRegisteredPublicly(code) {
  const base = serverHttpUrl();
  if (!base || !code) return true;
  try {
    const res = await fetch(`${base}/connect?code=${encodeURIComponent(code)}`, { cache: 'no-store' });
    if (!res.ok) return false;
    const json = await res.json().catch(() => null);
    return json?.relayOnline === true;
  } catch { return true; }
}

function connect() {
  if (ws) { ws.close(); ws = null; }
  const socket = new WebSocket(SERVER_URL);
  ws = socket;
  let scheduledReconnect = false;
  let registered = false;

  function scheduleReconnect() {
    if (scheduledReconnect) return;
    scheduledReconnect = true;
    clearInterval(heartbeatTimer);
    clearTimeout(registrationTimer);
    clearInterval(publicSessionTimer);
    if (ws === socket) ws = null;
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 5000);
  }

  socket.on('open', () => {
    if (ws !== socket) return;
    const preferredCode = process.env.AGENTHUB_RELAY_CODE || readPersistedRelayCode();
    socket.send(JSON.stringify({ type: 'register_relay', config: relayConfig, preferredCode }));
    clearTimeout(registrationTimer);
    registrationTimer = setTimeout(() => {
      if (ws === socket && !registered) {
        console.error('Relay registration timed out. Reconnecting...');
        try { socket.terminate?.(); } catch {}
        scheduleReconnect();
      }
    }, 15000);
    clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => send({ type: 'ping' }), 25000);
  });

  socket.on('ping', () => {
    try { socket.pong(); } catch {}
  });

  socket.on('message', async (raw) => {
    if (ws !== socket) return;
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    if (msg.type === 'relay_registered') {
      registered = true;
      sessionCode = msg.code;
      clearTimeout(registrationTimer);
      writePersistedRelayCode(sessionCode);
      console.log(`\nRelay session: ${sessionCode}${msg.reused ? ' (reused)' : ''}\n`);
      printAgentQRCodes(sessionCode);
      clearInterval(publicSessionTimer);
      publicSessionTimer = setInterval(async () => {
        if (ws !== socket || !sessionCode) return;
        const ok = await relayIsRegisteredPublicly(sessionCode);
        if (!ok && ws === socket) {
          console.error(`Relay code ${sessionCode} missing from server. Re-registering...`);
          try { socket.terminate?.(); } catch {}
          scheduleReconnect();
        }
      }, 30000);
      return;
    }

    if (msg.type === 'execute') {
      const { agent, prompt, clientId, sessionId, attachments } = msg;
      console.log(`\nIncoming ${agent}: "${(prompt || '').slice(0, 80)}..."`);
      executeAgent(agent, prompt, clientId, sessionId, attachments || []).catch((err) => {
        send({ type: 'error', clientId, content: `${agent} failed: ${err.message}` });
      });
    } else if (msg.type === 'select_model') {
      console.log(`Model selected for ${msg.agent}: ${msg.model}`);
      relayConfig[`${(msg.agent || 'opencode').toUpperCase()}_MODEL`] = msg.model;
      send({ type: 'relay_update_config', config: relayConfig });
    } else if (msg.type === 'session_list') {
      console.log(`  [sessions] requested by ${msg.clientId || 'unknown'}`);
      const sessions = await listSessions(msg.agent);
      console.log(`  [sessions] ${sessions.length} ${msg.agent || 'all'} -> ${msg.clientId || 'unknown'}`);
      send({ type: 'sessions', clientId: msg.clientId, sessions });
    } else if (msg.type === 'session_detail') {
      try {
        const detail = await getSessionDetail(msg.sessionId);
        send({ type: 'session_detail', clientId: msg.clientId, detail });
      } catch (err) {
        send({ type: 'error', clientId: msg.clientId, content: err.message });
      }
    } else if (msg.type === 'pong') {
      return;
    } else if (msg.type === 'system' || msg.type === 'phone_connected') {
      console.log(`Info: ${msg.content || msg.type}`);
    }
  });

  socket.on('close', () => { console.log('Disconnected. Reconnecting in 5s...'); ocServerVerified = false; scheduleReconnect(); });
  socket.on('error', (err) => { console.error(`Warning: ${err.message}`); scheduleReconnect(); });
}

function send(obj) {
  if (ws?.readyState === 1) ws.send(JSON.stringify(obj));
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// Session management via HTTP API

async function listSessions(agent) {
  if (agent && agent !== 'opencode') return [];
  try {
    console.log('  [sessions] calling OC API /session...');
    const raw = await ocFetch('/session');
    console.log(`  [sessions] OC API returned: ${Array.isArray(raw) ? raw.length + ' items' : typeof raw}`);
    const items = Array.isArray(raw) ? raw : (raw?.data || raw?.value || []);
    return items.map(s => ({
      agent: 'opencode',
      id: s.id || '',
      title: s.title || s.slug || s.id || 'OpenCode chat',
      subtitle: s.directory || s.path || WORKSPACE_CWD,
      directory: s.directory || '',
      updatedAt: s.time?.updated || s.time_updated || 0,
      createdAt: s.time?.created || s.time_created || 0,
      status: s.status || '',
    })).filter(s => s.id);
  } catch (err) {
    console.error('  [opencode] session list error:', err.message);
    return [];
  }
}

async function getSessionDetail(sessionId) {
  if (!sessionId) throw new Error('No session ID');
  const raw = await ocFetch(`/session/${sessionId}/message`);
  const messages = Array.isArray(raw) ? raw : (raw?.data || raw?.value || []);

  const phoneMessages = [];
  for (const msg of messages) {
    const formatted = formatMessageForPhone(msg);
    phoneMessages.push(...formatted);
  }

  return {
    agent: 'opencode',
    sessionId,
    title: sessionId,
    directory: WORKSPACE_CWD,
    messages: phoneMessages.length ? phoneMessages : [{ role: 'assistant', text: 'No messages yet.' }],
    diff: [],
    todo: [],
  };
}

async function createSession() {
  const result = await ocFetch('/session', { method: 'POST', body: '{}' });
  return result;
}

async function sendPrompt(prompt, clientId, sessionId, model) {
  let target = sessionId;

  if (!target) {
    const sessions = await listSessions('opencode');
    if (sessions.length) {
      target = sessions.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0].id;
      send({ type: 'status', clientId, content: `Using session: ${sessions[0].title || target}` });
    }
  }

  if (!target) {
    send({ type: 'status', clientId, content: 'Creating new session...' });
    try {
      const newSession = await createSession();
      target = newSession?.id || newSession?.data?.id;
    } catch (err) {
      send({ type: 'error', clientId, content: `Failed to create session: ${err.message}` });
      return;
    }
  }

  send({ type: 'status', clientId, content: `Sending to session ${target}` });

  // Snapshot current message count before prompt
  let msgCountBefore = 0;
  try {
    const before = await ocFetch(`/session/${target}/message`);
    const arr = Array.isArray(before) ? before : (before?.data || before?.value || []);
    msgCountBefore = arr.length;
  } catch {}

  // Fire prompt_async (returns 204 immediately, no blocking)
  const body = {
    parts: [{ type: 'text', text: prompt }],
  };
  if (model) {
    body.model = { providerID: 'opencode', modelID: model };
  }

  try {
    await ocFetch(`/session/${target}/prompt_async`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
    send({ type: 'status', clientId, content: `Prompt sent, waiting for response...` });
  } catch (err) {
    send({ type: 'error', clientId, content: `Failed to send prompt: ${err.message}` });
    return;
  }

  // Poll for new messages (max 5 minutes)
  const startTime = Date.now();
  const maxWait = 5 * 60 * 1000;
  const pollInterval = 2000;
  let lastPoll = 0;

  while (Date.now() - startTime < maxWait) {
    await sleep(pollInterval);
    lastPoll += pollInterval;

    try {
      const messages = await ocFetch(`/session/${target}/message`);
      const arr = Array.isArray(messages) ? messages : (messages?.data || messages?.value || []);

      if (arr.length > msgCountBefore) {
        // New messages arrived — stream them to phone
        const newMsgs = arr.slice(msgCountBefore);
        for (const msg of newMsgs) {
          const formatted = formatMessageForPhone(msg);
          for (const fm of formatted) {
            if (fm.role === 'assistant' && fm.text) {
              send({ type: 'replace_stream', clientId, content: fm.text });
            } else {
              send({ type: 'status', clientId, content: `[${fm.role}] ${(fm.text || '').slice(0, 200)}` });
            }
          }
        }

        // Check if last message is from assistant (done)
        const lastMsg = arr[arr.length - 1];
        const lastRole = lastMsg?.info?.role || lastMsg?.role || '';
        if (lastRole === 'assistant') {
          // Send full session detail
          try {
            const detail = await getSessionDetail(target);
            send({ type: 'session_detail', clientId, detail });
          } catch {}
          send({ type: 'done', clientId, content: '' });
          return;
        }

        // Reset poll counter — we got data, keep checking
        msgCountBefore = arr.length;
      }
    } catch (err) {
      console.error('  [poll] error:', err.message);
    }
  }

  // Timeout — send what we have
  try {
    const detail = await getSessionDetail(target);
    send({ type: 'session_detail', clientId, detail });
  } catch {}
  send({ type: 'done', clientId, content: '' });
}

function executeAgent(agent, prompt, clientId, sessionId = '', attachments = []) {
  if (agent !== 'opencode') {
    send({ type: 'error', clientId, content: 'Only OpenCode agent is supported.' });
    return Promise.resolve();
  }

  // Queue behind any in-flight execute (prevents duplicate prompts on reconnect)
  const prev = executeLock;
  let release;
  const myLock = new Promise(r => { release = r; });
  executeLock = myLock;

  return (prev || Promise.resolve()).then(async () => {
    try {
      if (!ocServerVerified) {
        try {
          await ensureOpenCodeServer();
          ocServerVerified = true;
        } catch (err) {
          send({ type: 'error', clientId, content: `OpenCode server error: ${err.message}` });
          return;
        }
      }

      const model = relayConfig.OPENCODE_MODEL || '';

      if (attachments?.length) {
        const saved = [];
        const dir = path.join(WORKSPACE_CWD, '.agenthub_uploads');
        fs.mkdirSync(dir, { recursive: true });
        for (let i = 0; i < attachments.length && i < 10; i++) {
          const f = attachments[i];
          const name = String(f.name || `upload-${i}`).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').slice(0, 120);
          const out = path.join(dir, `${Date.now()}-${i}-${name}`);
          fs.writeFileSync(out, Buffer.from(String(f.base64 || ''), 'base64'));
          saved.push(`- ${out}${f.mime ? ` (${f.mime})` : ''}`);
          send({ type: 'status', clientId, content: `file: saved ${name}` });
        }
        if (saved.length) prompt = `${prompt}\n\nAttached files from phone:\n${saved.join('\n')}`;
      }

      try {
        await sendPrompt(prompt, clientId, sessionId || undefined, model || undefined);
      } catch (err) {
        if (err.message?.includes('ECONNREFUSED') || err.message?.includes('fetch failed')) {
          ocServerVerified = false;
        }
        send({ type: 'error', clientId, content: `OpenCode failed: ${err.message}` });
      }
    } finally {
      release();
    }
  });
}

function printAgentQRCodes(code) {
  const qrPayload = `${SERVER_URL}?code=${code}&agent=opencode`;
  const qrFile = path.join(WORKSPACE_CWD, 'session_qr.txt');
  const shouldPrintQr = process.stdout.isTTY && process.env.AGENTHUB_PRINT_QR !== '0';

  if (shouldPrintQr) {
    QRCode.toString(qrPayload, { type: 'terminal', small: true }, (err, qr) => {
      if (err) return;
      console.log('---------------------------------------');
      console.log('  OpenCode');
      console.log('---------------------------------------');
      console.log(qr);
      console.log(`   Code: ${code}`);
      console.log('---------------------------------------\n');
    });
  }

  console.log(`  [opencode] Code: ${code}  |  URL: ${qrPayload}\n`);

  try { fs.writeFileSync(qrFile, `Agent: OpenCode (opencode)\nCode: ${code}\nURL: ${qrPayload}\n`); } catch {}
}

// Start

console.log('=======================================');
console.log('  OC-mob Relay');
console.log('=======================================');
console.log(`  Server:   ${SERVER_URL}`);
console.log(`  Cwd:      ${WORKSPACE_CWD}`);
console.log(`  OC Port:  ${OPENCODE_SERVER_PORT}`);
console.log('=======================================\n');

ensureOpenCodeServer().then(ok => {
  if (ok) ocServerVerified = true;
  else console.error('Warning: OpenCode server not ready. Will retry on first prompt.');
  connect();
});

process.on('SIGINT', () => {
  clearTimeout(reconnectTimer);
  clearInterval(heartbeatTimer);
  try { opencodeServerProcess?.kill(); } catch {}
  if (ws) ws.close();
  process.exit(0);
});
