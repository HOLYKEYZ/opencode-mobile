// @ts-nocheck
const WebSocket = require('ws');
const { spawn } = require('child_process');
const path = require('path');
const os = require('os');
const fs = require('fs');
const QRCode = require('qrcode');
let pty = null;
try { pty = require('node-pty'); } catch {}

const SERVER_URL = process.env.SERVER_URL || 'ws://localhost:3001';
const RELAY_DIR = fs.existsSync(path.join(__dirname, 'package.json')) ? __dirname : path.join(__dirname, '..');
const WORKSPACE_CWD = path.resolve(process.env.AGENTHUB_CWD || process.env.WORKSPACE_CWD || path.join(RELAY_DIR, '..'));
const isWin = os.platform() === 'win32';
const RELAY_STATE_FILE = path.join(RELAY_DIR, 'relay-state.json');

const OPENCODE_DESKTOP_PATH = 'C:\\Users\\USER\\AppData\\Local\\Programs\\@opencode-aidesktop\\OpenCode.exe';
const DEVIN_DESKTOP_PATH = 'C:\\Users\\USER\\AppData\\Local\\Programs\\devin\\Devin.exe';
const OPENCODE_CLI_PATH = path.join(RELAY_DIR, 'node_modules', 'opencode-ai', 'bin', 'opencode.exe');

const AGENTS = [
  { id: 'opencode', name: 'OpenCode', modelKey: 'OPENCODE_MODEL', cmd: null, args: p => ['run', '--dangerously-skip-permissions', '--format', 'json', p], localPromptCli: true, serverBacked: true, desktopPath: OPENCODE_DESKTOP_PATH },
  { id: 'devin',    name: 'Devin',    modelKey: 'DEVIN_MODEL',    cmd: process.env.DEVIN_PATH || 'devin', args: p => ['--permission-mode', 'bypass', '-p', '--', p], localPromptCli: true, jsonExec: false, desktopPath: DEVIN_DESKTOP_PATH },
];
const MODEL_KEY_BY_AGENT = Object.fromEntries(AGENTS.map(a => [a.id, a.modelKey]));
let relayConfig = {};

function quoteCmdArg(value) {
  const raw = String(value);
  const escaped = raw.replace(/([&|^<>()!])/g, '^$1').replace(/"/g, '""');
  return /[\s"]/u.test(raw) ? `"${escaped}"` : escaped;
}

function spawnAgentCommand(cmd, args, cwd = process.cwd()) {
  const env = { ...process.env, NO_COLOR: '1', FORCE_COLOR: '0' };
  if (!isWin) {
    return spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], env, cwd });
  }
  const commandLine = [quoteCmdArg(cmd), ...args.map(quoteCmdArg)].join(' ');
  return spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', commandLine], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env,
    windowsHide: true,
    cwd,
  });
}

function buildWindowsCommandLine(cmd, args) {
  return [quoteCmdArg(cmd), ...args.map(quoteCmdArg)].join(' ');
}

function buildPtyCommand(cmd, args) {
  if (!isWin) return { command: cmd, args };
  return {
    command: process.env.ComSpec || 'cmd.exe',
    args: ['/d', '/s', '/c', buildWindowsCommandLine(cmd, args)],
  };
}

function getCmd(a) {
  if (a.cmd) return a.cmd;
  if (a.id === 'opencode') {
    if (fs.existsSync(OPENCODE_CLI_PATH)) return OPENCODE_CLI_PATH;
    if (fs.existsSync(OPENCODE_DESKTOP_PATH)) return OPENCODE_DESKTOP_PATH;
    if (isWin) {
      const fp = path.join(process.env.LOCALAPPDATA || path.join(process.env.USERPROFILE, 'AppData', 'Local'), 'OpenCode', 'opencode-cli.exe');
      try { fs.accessSync(fp); return fp; } catch {}
    }
    return 'opencode';
  }
  return a.id;
}

function commandExists(command) {
  if (!command) return false;
  if (path.isAbsolute(command)) {
    try { fs.accessSync(command); return true; } catch { return false; }
  }
  const pathExt = isWin ? (process.env.PATHEXT || '.EXE;.CMD;.BAT;.COM').split(';') : [''];
  const names = isWin && !path.extname(command) ? pathExt.map(ext => command + ext.toLowerCase()).concat(pathExt.map(ext => command + ext)) : [command];
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    for (const name of names) {
      try { fs.accessSync(path.join(dir, name)); return true; } catch {}
    }
  }
  return false;
}

function readOpenCodeConfig() {
  try {
    const authPath = path.join(os.homedir(), '.local', 'share', 'opencode', 'auth.json');
    if (!fs.existsSync(authPath)) return {};
    JSON.parse(fs.readFileSync(authPath, 'utf-8'));
    return {};
  } catch { return {}; }
}

function devinAuthPaths() {
  return [
    process.env.DEVIN_AUTH_PATH,
    path.join(os.homedir(), '.config', 'devin', 'auth.json'),
    path.join(os.homedir(), '.local', 'share', 'devin', 'auth.json'),
    path.join(os.homedir(), '.devin', 'auth.json'),
  ].filter(Boolean);
}

function readDevinConfig() {
  try {
    const authPath = devinAuthPaths().find((p) => fs.existsSync(p));
    if (!authPath) return {};
    JSON.parse(fs.readFileSync(authPath, 'utf-8'));
    return {};
  } catch { return {}; }
}

function readLocalConfig() {
  const cfg = { ...readOpenCodeConfig(), ...readDevinConfig() };
  cfg.LOCAL_AGENTS = getAvailableAgents().map(a => a.id);
  return cfg;
}

function getSelectedModel(agent) {
  const key = MODEL_KEY_BY_AGENT[agent];
  if (!key) return '';
  return String(relayConfig[key] || process.env[key] || '').trim();
}

function setSelectedModel(agent, model) {
  const key = MODEL_KEY_BY_AGENT[agent];
  if (!key) return false;
  const clean = String(model || '').trim();
  if (!clean) return false;
  relayConfig[key] = clean;
  process.env[key] = clean;
  return true;
}

function readPersistedRelayCode() {
  try {
    if (!fs.existsSync(RELAY_STATE_FILE)) return '';
    const state = JSON.parse(fs.readFileSync(RELAY_STATE_FILE, 'utf-8'));
    return typeof state.code === 'string' ? state.code : '';
  } catch {
    return '';
  }
}

function writePersistedRelayCode(code) {
  try {
    fs.writeFileSync(RELAY_STATE_FILE, JSON.stringify({ code, serverUrl: SERVER_URL, updatedAt: new Date().toISOString() }, null, 2));
  } catch {}
}

function getAvailableAgents() {
  return AGENTS.filter((a) => {
    if (!a.localPromptCli) return false;
    if (a.id === 'opencode') {
      if (fs.existsSync(OPENCODE_DESKTOP_PATH)) return true;
      const cmd = getCmd(a);
      if (!commandExists(cmd)) return false;
      const dataDir = path.join(os.homedir(), '.local', 'share', 'opencode');
      return fs.existsSync(path.join(dataDir, 'auth.json')) || fs.existsSync(path.join(dataDir, 'opencode.db'));
    }
    if (a.id === 'devin') {
      const cmd = getCmd(a);
      if (!commandExists(cmd)) return false;
      return devinAuthPaths().some((p) => fs.existsSync(p));
    }
    return false;
  });
}

// WebSocket

let ws, reconnectTimer, heartbeatTimer, registrationTimer, publicSessionTimer, sessionCode;

function serverHttpUrl() {
  try {
    const url = new URL(SERVER_URL);
    if (url.protocol === 'wss:') url.protocol = 'https:';
    else if (url.protocol === 'ws:') url.protocol = 'http:';
    else return null;
    url.pathname = '';
    url.search = '';
    url.hash = '';
    return url.toString().replace(/\/+$/, '');
  } catch {
    return null;
  }
}

async function relayIsRegisteredPublicly(code) {
  const base = serverHttpUrl();
  if (!base || !code) return true;
  try {
    const res = await fetch(`${base}/connect?code=${encodeURIComponent(code)}`, { cache: 'no-store' });
    if (!res.ok) return false;
    const json = await res.json().catch(() => null);
    return json?.relayOnline === true;
  } catch {
    return true;
  }
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
    const config = readLocalConfig();
    relayConfig = { ...relayConfig, ...config };
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
          console.error(`Relay code ${sessionCode} is missing from the server. Re-registering...`);
          try { socket.terminate?.(); } catch {}
          scheduleReconnect();
        }
      }, 30000);
      return;
    }

    if (msg.type === 'execute') {
      const { agent, prompt, clientId, sessionId, attachments } = msg;
      console.log(`\nIncoming ${agent}: "${prompt.slice(0, 80)}..."`);
      executeAgent(agent, prompt, clientId, sessionId, attachments || []).catch((err) => {
        send({ type: 'error', clientId, content: `${agent} failed: ${err.message}` });
      });
    } else if (msg.type === 'select_model') {
      if (setSelectedModel(msg.agent, msg.model)) {
        console.log(`Model selected for ${msg.agent}: ${msg.model}`);
        send({ type: 'relay_update_config', config: relayConfig });
      }
    } else if (msg.type === 'session_list') {
      const sessions = await listLocalSessions(msg.agent);
      console.log(`  [sessions] ${sessions.length} ${msg.agent || 'all'} -> ${msg.clientId || 'unknown'}`);
      send({ type: 'sessions', clientId: msg.clientId, sessions });
    } else if (msg.type === 'session_detail') {
      try {
        const detail = msg.agent === 'opencode'
          ? await getOpenCodeSessionDetail(msg.sessionId)
          : await getDevinSessionDetail(msg.sessionId);
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

  socket.on('close', () => {
    console.log('Disconnected. Reconnecting in 5s...');
    scheduleReconnect();
  });

  socket.on('error', (err) => {
    console.error(`Warning: ${err.message}`);
    scheduleReconnect();
  });
}

function send(obj) {
  if (ws?.readyState === 1) ws.send(JSON.stringify(obj));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const OPENCODE_PORT = Number(process.env.OPENCODE_PORT || 4096);
const OPENCODE_DEFAULT_BASE_URL = process.env.OPENCODE_BASE_URL || `http://127.0.0.1:${OPENCODE_PORT}`;
let opencodeBaseUrl = OPENCODE_DEFAULT_BASE_URL;
let opencodeServerProcess = null;
let opencodeDesktopLaunchedByRelay = false;

function openCodeLogDir() {
  return path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'ai.opencode.desktop', 'logs');
}

function latestOpenCodeLogFolder() {
  const root = openCodeLogDir();
  if (!fs.existsSync(root)) return null;
  let latest = null;
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (!latest || entry.name > latest.name) latest = entry;
  }
  return latest ? path.join(root, latest.name) : null;
}

function findOpenCodeServerUrlInLog(logFolder) {
  if (!logFolder) return null;
  const mainLog = path.join(logFolder, 'main.log');
  if (!fs.existsSync(mainLog)) return null;
  try {
    const text = fs.readFileSync(mainLog, 'utf-8');
    const matches = [...text.matchAll(/server ready\s*\{\s*url:\s*['"]([^'"]+)['"]/gi)];
    return matches.length ? matches[matches.length - 1][1] : null;
  } catch {
    return null;
  }
}

function getOpenCodeLogFolders() {
  const root = openCodeLogDir();
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => path.join(root, e.name))
    .sort((a, b) => path.basename(b).localeCompare(path.basename(a)));
}

async function discoverOpenCodeServerUrl() {
  for (const folder of getOpenCodeLogFolders()) {
    const url = findOpenCodeServerUrlInLog(folder);
    if (!url) continue;
    try {
      const health = await requestJson(`${url.replace(/\/$/, '')}/global/health`);
      if (health) return url;
    } catch {}
  }
  return null;
}

async function requestJson(url, init = {}) {
  const res = await fetch(url, {
    ...init,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(init.headers || {}),
    },
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text.trim()) return null;
  return JSON.parse(text);
}

async function isOpenCodeServerRunning() {
  try {
    const health = await requestJson(`${opencodeBaseUrl}/global/health`);
    return !!health;
  } catch {
    return false;
  }
}

async function waitForOpenCodeDesktopServer(timeoutMs = 60000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const url = await discoverOpenCodeServerUrl();
    if (url) return url;
    await sleep(500);
  }
  return null;
}

function openCodeDesktopIsInstalled() {
  return fs.existsSync(OPENCODE_DESKTOP_PATH);
}

function openCodeCliIsInstalled() {
  if (fs.existsSync(OPENCODE_CLI_PATH)) return true;
  const cmd = AGENTS.find((a) => a.id === 'opencode') ? getCmd({ id: 'opencode' }) : 'opencode';
  return commandExists(cmd) && cmd !== OPENCODE_DESKTOP_PATH;
}

function runOpenCodeCli(args, timeoutMs = 10 * 60 * 1000) {
  return new Promise((resolve, reject) => {
    const cmd = getCmd({ id: 'opencode' });
    if (!cmd || !commandExists(cmd)) { reject(new Error('OpenCode CLI not found')); return; }
    const child = spawnAgentCommand(cmd, args, WORKSPACE_CWD);
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });
    child.on('error', reject);
    const timer = setTimeout(() => { try { child.kill(); } catch {} reject(new Error('OpenCode CLI timed out')); }, timeoutMs);
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) { resolve(stdout); return; }
      const tail = stripTerminalNoise(stderr || stdout).slice(-800);
      reject(new Error(tail || `OpenCode exited ${code}`));
    });
  });
}

function parseOpenCodeCliJsonLines(raw) {
  const lines = String(raw || '').split('\n').filter((l) => l.trim());
  const items = [];
  for (const line of lines) {
    try { items.push(JSON.parse(line)); } catch {}
  }
  return items;
}

async function ensureOpenCodeServer() {
  if (await isOpenCodeServerRunning()) return;

  const discovered = await discoverOpenCodeServerUrl();
  if (discovered) {
    opencodeBaseUrl = discovered.replace(/\/$/, '');
    if (await isOpenCodeServerRunning()) return;
  }

  if (openCodeCliIsInstalled()) {
    const cmd = getCmd({ id: 'opencode' });
    if (!cmd || !commandExists(cmd)) throw new Error('OpenCode CLI not found.');
    console.log(`Starting OpenCode CLI server on ${opencodeBaseUrl}...`);
    opencodeServerProcess = spawn(cmd, ['serve', '--hostname', '127.0.0.1', '--port', String(OPENCODE_PORT), '--log-level', 'ERROR'], {
      stdio: ['ignore', 'ignore', 'ignore'],
      cwd: WORKSPACE_CWD,
      env: { ...process.env },
      windowsHide: true,
    });
    for (let i = 0; i < 60; i++) {
      if (await isOpenCodeServerRunning()) return;
      await sleep(500);
    }
    throw new Error(`OpenCode server did not start on ${opencodeBaseUrl}.`);
  }

  if (openCodeDesktopIsInstalled()) {
    console.log('Launching OpenCode Desktop to expose local API...');
    opencodeServerProcess = spawn(OPENCODE_DESKTOP_PATH, [], {
      stdio: ['ignore', 'ignore', 'ignore'],
      cwd: WORKSPACE_CWD,
      env: { ...process.env },
      windowsHide: false,
      detached: true,
    });
    opencodeDesktopLaunchedByRelay = true;
    const url = await waitForOpenCodeDesktopServer(60000);
    if (url) {
      opencodeBaseUrl = url.replace(/\/$/, '');
      console.log(`OpenCode Desktop API ready at ${opencodeBaseUrl}`);
      return;
    }
    throw new Error('OpenCode Desktop started but its local API URL could not be discovered from logs.');
  }

  throw new Error('OpenCode Desktop or CLI not found. Install OpenCode to use this agent.');
}

function responseItems(json) {
  if (Array.isArray(json)) return json;
  if (Array.isArray(json?.value)) return json.value;
  if (Array.isArray(json?.data)) return json.data;
  if (Array.isArray(json?.items)) return json.items;
  return [];
}

function stripTerminalNoise(value) {
  return String(value || '')
    .replace(/\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1B\\))/gu, '')
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/gu, '')
    .replace(/\r/g, '')
    .trim();
}

function truncateText(value, max = 140) {
  const text = stripTerminalNoise(value).replace(/\s+/g, ' ').trim();
  if (text.length <= max) return text;
  return `${text.slice(0, max - 1)}...`;
}

const DETAIL_MESSAGE_LIMIT = 80;
const DETAIL_MESSAGE_CHARS = 6000;

function capMessageText(value, max = DETAIL_MESSAGE_CHARS) {
  const text = stripTerminalNoise(value);
  if (text.length <= max) return text;
  return `${text.slice(0, max)}\n\n[message truncated for phone]`;
}

function capDetailMessages(messages, limit = DETAIL_MESSAGE_LIMIT) {
  return (messages || []).slice(-limit).map((message) => ({
    ...message,
    text: capMessageText(message.text || ''),
  }));
}

function toOpenCodeSession(item) {
  return {
    agent: 'opencode',
    id: item.id,
    title: item.title || item.slug || item.id,
    subtitle: item.directory || item.path || 'OpenCode',
    directory: item.directory || '',
    updatedAt: item.time?.updated || item.time_updated || 0,
    createdAt: item.time?.created || item.time_created || 0,
    status: '',
    summary: item.summary || null,
  };
}

async function listOpenCodeSessions() {
  try {
    const raw = await runOpenCodeCli(['session', 'list', '--format', 'json'], 20000);
    const parsed = JSON.parse(raw);
    const items = Array.isArray(parsed) ? parsed : [];
    return items.map((item) => ({
      agent: 'opencode',
      id: item.id || '',
      title: item.title || item.slug || item.id || 'OpenCode chat',
      subtitle: item.directory || item.path || WORKSPACE_CWD,
      directory: item.directory || '',
      updatedAt: item.updated || item.time?.updated || item.time_updated || 0,
      createdAt: item.created || item.time?.created || item.time_created || 0,
      status: '',
      summary: item.summary || null,
    })).filter((s) => s.id);
  } catch (err) {
    console.error('  [opencode] session list error:', err.message);
    return [{ agent: 'opencode', id: '', title: `OpenCode unavailable: ${err.message}`, subtitle: '', updatedAt: 0, error: true }];
  }
}

async function getMostRecentOpenCodeSession() {
  const sessions = await listOpenCodeSessions();
  return sessions.filter((s) => s.id && !s.error).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0] || null;
}

function formatOpenCodePart(part) {
  if (!part) return '';
  if (part.type === 'text') return part.text || '';
  if (part.type === 'reasoning' || part.type === 'step-start' || part.type === 'step-finish') return '';
  if (part.type === 'tool') {
    const status = part.state?.status || '';
    const title = part.state?.title || part.tool || 'tool';
    return `[tool:${title}${status ? ` ${status}` : ''}]`;
  }
  if (part.type === 'file') return `[file:${part.filename || part.url || 'attachment'}]`;
  return `[${part.type || 'part'}]`;
}

function formatOpenCodeMessage(message) {
  const info = message.info || message;
  const role = info.role || message.role || 'message';
  const parts = message.parts || message.content || [];
  const text = Array.isArray(parts) ? parts.map(formatOpenCodePart).filter(Boolean).join('\n') : '';
  return {
    id: info.id || message.id || '',
    role,
    text,
    time: info.time || message.time || null,
    tokens: info.tokens || message.tokens || null,
    cost: info.cost ?? message.cost ?? null,
  };
}

async function getOpenCodeSessionDetail(sessionId) {
  return {
    agent: 'opencode',
    sessionId,
    title: sessionId,
    directory: WORKSPACE_CWD,
    messages: [],
    diff: [],
    todo: [],
  };
}

async function createOpenCodeSession() {
  return { id: '' };
}

function sanitizeAttachmentName(name, index) {
  const cleaned = String(name || `upload-${index}`).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').slice(0, 120);
  return cleaned || `upload-${index}`;
}

function saveAttachments(attachments = [], clientId = '') {
  if (!Array.isArray(attachments) || attachments.length === 0) return [];
  const dir = path.join(WORKSPACE_CWD, '.agenthub_uploads');
  fs.mkdirSync(dir, { recursive: true });
  return attachments.slice(0, 10).map((file, index) => {
    const name = sanitizeAttachmentName(file.name, index);
    const out = path.join(dir, `${Date.now()}-${index}-${name}`);
    const data = Buffer.from(String(file.base64 || ''), 'base64');
    fs.writeFileSync(out, data);
    send({ type: 'status', clientId, content: `file: saved upload ${out}` });
    return { path: out, name, mime: file.mime || '', size: data.length };
  });
}

function promptWithAttachments(prompt, attachments, clientId) {
  const saved = saveAttachments(attachments, clientId);
  if (!saved.length) return prompt;
  const note = saved.map((file) => `- ${file.path}${file.mime ? ` (${file.mime})` : ''}`).join('\n');
  return `${prompt}\n\nAttached files from phone saved on this laptop:\n${note}`;
}

async function sendOpenCodePrompt(prompt, clientId, sessionId, attachments = []) {
  prompt = promptWithAttachments(prompt, attachments, clientId);
  let target = sessionId;
  if (target === undefined || target === null) {
    const recent = await getMostRecentOpenCodeSession();
    if (recent) {
      target = recent.id;
      send({ type: 'status', clientId, content: `Using most recent OpenCode chat: ${recent.title || target}` });
    }
  }
  if (!target) {
    send({ type: 'status', clientId, content: 'Starting new OpenCode session' });
  }

  const args = ['run', '--dangerously-skip-permissions', '--format', 'json', '--dir', WORKSPACE_CWD];
  const model = getSelectedModel('opencode');
  if (model) args.push('--model', model);
  if (target) {
    args.push('--continue', '--session', target);
  } else {
    args.push('--title', 'OC-mob prompt');
  }
  args.push('--', prompt);

  send({ type: 'status', clientId, content: target ? `Sending to OpenCode session ${target}` : 'Sending to new OpenCode session' });
  console.log(`  [opencode] $ opencode ${args.join(' ')}`);

  let finalText = '';
  let usage = null;
  await new Promise((resolve, reject) => {
    const cmd = getCmd({ id: 'opencode' });
    const child = spawnAgentCommand(cmd, args, WORKSPACE_CWD);
    let buffer = '';
    child.stdout.on('data', (data) => {
      buffer += data.toString();
      const lines = buffer.split('\n');
      buffer = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const event = JSON.parse(line);
          if (event.type === 'text' && event.part?.text) {
            finalText = event.part.text;
            send({ type: 'replace_stream', clientId, content: finalText });
          }
          if (event.type === 'step_finish' && event.part?.tokens) {
            usage = event.part.tokens;
          }
        } catch {}
      }
    });
    child.stderr.on('data', (data) => {
      const text = stripTerminalNoise(data.toString()).slice(0, 500);
      if (text) send({ type: 'status', clientId, content: text });
    });
    child.on('error', reject);
    const timer = setTimeout(() => { try { child.kill(); } catch {} reject(new Error('OpenCode prompt timed out')); }, 10 * 60 * 1000);
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(stripTerminalNoise(buffer).slice(-500) || `OpenCode exited ${code}`));
    });
  });

  if (finalText) send({ type: 'replace_stream', clientId, content: finalText });
  if (usage) {
    const summary = usageSummary({ tokens: usage });
    if (summary) send({ type: 'status', clientId, content: summary });
  }
  const detail = target ? await getOpenCodeSessionDetail(target).catch(() => null) : null;
  if (detail) send({ type: 'session_detail', clientId, detail });
  send({ type: 'done', clientId, content: '' });
}

// Devin CLI support

function devinSessionDir() {
  if (process.env.DEVIN_SESSION_DIR) return process.env.DEVIN_SESSION_DIR;
  return path.join(os.homedir(), '.local', 'share', 'devin', 'sessions');
}

function parseDevinListJson(raw) {
  try {
    const parsed = JSON.parse(raw);
    const items = responseItems(parsed);
    return items.map((item) => {
      const id = String(item.id || item.session_id || item.sessionId || item.sessionId || '');
      const title = String(item.title || item.name || item.display_name || item.slug || id || 'Devin chat');
      const directory = String(item.directory || item.path || item.cwd || item.workspace || item.working_directory || WORKSPACE_CWD);
      const updatedAt = Number(item.updated_at || item.updatedAt || item.updated || item.time?.updated || item.last_active_at || 0);
      const createdAt = Number(item.created_at || item.createdAt || item.created || item.time?.created || 0);
      return {
        agent: 'devin',
        id,
        title,
        subtitle: directory,
        directory,
        updatedAt: updatedAt ? updatedAt * 1000 : Date.now(),
        createdAt: createdAt ? createdAt * 1000 : Date.now(),
      };
    }).filter((s) => s.id);
  } catch {
    return [];
  }
}

async function listDevinSessionsViaCli() {
  const devin = AGENTS.find((a) => a.id === 'devin');
  const cmd = devin ? getCmd(devin) : 'devin';
  if (!commandExists(cmd)) return [];
  return new Promise((resolve) => {
    const child = spawnAgentCommand(cmd, ['list', '--format', 'json']);
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });
    child.on('error', () => resolve([]));
    child.on('close', () => {
      const sessions = parseDevinListJson(stdout);
      resolve(sessions);
    });
    setTimeout(() => { try { child.kill(); } catch {} resolve([]); }, 15000);
  });
}

function scanDevinSessionDir() {
  const dir = devinSessionDir();
  if (!fs.existsSync(dir)) return [];
  const sessions = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const full = path.join(dir, entry.name);
    try {
      const stat = fs.statSync(full);
      sessions.push({
        agent: 'devin',
        id: entry.name,
        title: entry.name,
        subtitle: full,
        directory: full,
        updatedAt: stat.mtimeMs,
        createdAt: stat.birthtimeMs || stat.ctimeMs,
      });
    } catch {}
  }
  return sessions.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

async function listDevinSessions() {
  try {
    const viaCli = await listDevinSessionsViaCli();
    if (viaCli.length) return viaCli;
  } catch {}
  return scanDevinSessionDir();
}

async function getMostRecentDevinSession() {
  const sessions = await listDevinSessions();
  const inCwd = sessions.filter((s) => !s.directory || s.directory === WORKSPACE_CWD || WORKSPACE_CWD.startsWith(s.directory));
  return (inCwd.length ? inCwd : sessions).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0] || null;
}

async function getDevinSessionDetail(sessionId) {
  const sessions = await listDevinSessions();
  const session = sessions.find((s) => s.id === sessionId);
  return {
    agent: 'devin',
    sessionId,
    title: session?.title || sessionId,
    directory: session?.directory || WORKSPACE_CWD,
    messages: [],
    files: [],
    commands: [],
    tools: [],
  };
}

function devinArgs(prompt, sessionId, model) {
  const args = [];
  if (model) args.push('--model', model);
  args.push('--permission-mode', 'bypass');
  if (sessionId) args.push('-r', sessionId);
  args.push('-p', '--', prompt);
  return args;
}

function numberFromUsage(obj, keys) {
  for (const key of keys) {
    const value = obj?.[key];
    if (typeof value === 'number' && Number.isFinite(value)) return value;
  }
  return 0;
}

function usageSummary(value) {
  if (!value || typeof value !== 'object') return '';
  const usage = value.usage || value.token_usage || value.tokens || value;
  const input = numberFromUsage(usage, ['input_tokens', 'inputTokens', 'prompt_tokens', 'promptTokens']);
  const output = numberFromUsage(usage, ['output_tokens', 'outputTokens', 'completion_tokens', 'completionTokens']);
  const cached = numberFromUsage(usage, ['cached_input_tokens', 'cachedInputTokens', 'cached_tokens', 'cachedTokens']);
  const reasoning = numberFromUsage(usage, ['reasoning_tokens', 'reasoningTokens']);
  const total = numberFromUsage(usage, ['total_tokens', 'totalTokens']) || input + output;
  const parts = [];
  if (total) parts.push(`total ${total}`);
  if (input) parts.push(`in ${input}`);
  if (output) parts.push(`out ${output}`);
  if (cached) parts.push(`cached ${cached}`);
  if (reasoning) parts.push(`reasoning ${reasoning}`);
  return parts.length ? `tokens: ${parts.join(' | ')}` : '';
}

async function sendDevinPrompt(prompt, clientId, sessionId, attachments = []) {
  prompt = promptWithAttachments(prompt, attachments, clientId);
  prompt = `Use web search when helpful. ${prompt}`;
  const devin = AGENTS.find((a) => a.id === 'devin');
  const cmd = devin ? getCmd(devin) : 'devin';
  if (!commandExists(cmd)) throw new Error('Devin CLI not found on PATH.');

  let target = sessionId;
  if (!target) {
    const recent = await getMostRecentDevinSession();
    if (recent) {
      target = recent.id;
      send({ type: 'status', clientId, content: `Using most recent Devin chat: ${recent.title || target}` });
    }
  }
  if (target) {
    send({ type: 'status', clientId, content: `Sending to Devin session ${target}` });
  } else {
    send({ type: 'status', clientId, content: 'Starting new Devin session' });
  }

  const model = getSelectedModel('devin');
  const args = devinArgs(prompt, target, model);
  console.log(`  [devin] $ ${cmd} ${args.join(' ')}`);

  await new Promise((resolve, reject) => {
    let child;
    try {
      child = spawnAgentCommand(cmd, args);
    } catch (err) {
      reject(err);
      return;
    }
    let fullOutput = '';
    child.stdout.on('data', (data) => {
      const text = data.toString();
      fullOutput += text;
      send({ type: 'replace_stream', clientId, content: stripTerminalNoise(fullOutput) });
    });
    child.stderr.on('data', (data) => {
      const text = data.toString();
      fullOutput += text;
      send({ type: 'status', clientId, content: stripTerminalNoise(text).slice(0, 500) });
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) resolve();
      else reject(new Error(stripTerminalNoise(fullOutput).slice(-1200) || `Devin exited ${code}`));
    });
    setTimeout(() => {
      try { child.kill(); } catch {}
      reject(new Error('Devin timed out after 10 minutes.'));
    }, 10 * 60 * 1000);
  });

  const detail = target ? await getDevinSessionDetail(target).catch(() => null) : null;
  if (detail) send({ type: 'session_detail', clientId, detail });
  send({ type: 'done', clientId, content: '' });
}

async function listLocalSessions(agent) {
  const opencodeSessions = agent && agent !== 'opencode' ? [] : await listOpenCodeSessions();
  const devinSessions = agent && agent !== 'devin' ? [] : await listDevinSessions();
  return [...opencodeSessions, ...devinSessions].sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

function printAgentQRCodes(code) {
  const agents = getAvailableAgents();
  const qrFile = path.join(WORKSPACE_CWD, 'session_qr.txt');
  const shouldPrintQr = process.stdout.isTTY && process.env.AGENTHUB_PRINT_QR !== '0';
  let fileContent = '';

  agents.forEach((a, i) => {
    const qrPayload = `${SERVER_URL}?code=${code}&agent=${a.id}`;
    if (i > 0) console.log('');
    if (shouldPrintQr) {
      QRCode.toString(qrPayload, { type: 'terminal', small: true }, (err, qr) => {
        if (err) return;
        console.log('---------------------------------------');
        console.log(`  ${a.name}`);
        console.log('---------------------------------------');
        console.log(qr);
        console.log(`   Code: ${code}`);
        console.log(`   Agent: ${a.name}`);
        console.log('---------------------------------------\n');
      });
    }
    fileContent += `Agent: ${a.name} (${a.id})\nCode: ${code}\nURL: ${qrPayload}\n\n`;

    console.log(`  [${a.id}] Code: ${code}  |  URL: ${qrPayload}\n`);
  });

  if (agents.length === 0) {
    console.log('Warning: No agents detected.');
    console.log('   Install and sign in to OpenCode or Devin.\n');
  }

  try {
    if (fileContent) fs.writeFileSync(qrFile, fileContent);
  } catch {}
}

// Agent execution

const ptySessions = new Map();

function sessionKey(agent, sessionId = '') {
  return `${agent}:${WORKSPACE_CWD}:${sessionId || 'default'}`;
}

function startPtyAgent(agent, prompt, clientId, sessionId = '') {
  if (!pty) throw new Error('node-pty is not installed. Run npm install in backend/.');
  const a = AGENTS.find(x => x.id === agent);
  if (!a) throw new Error(`Unknown agent: ${agent}`);
  if (!a.localPromptCli) throw new Error(`${a.name} is installed as an editor launcher, not a promptable local agent CLI.`);
  if (a.serverBacked) throw new Error(`${a.name} uses its local HTTP server, not PTY.`);

  const cmd = getCmd(a);
  if (!commandExists(cmd)) throw new Error(`${a.name} CLI not found on PATH.`);
  const args = a.args(prompt, sessionId);
  const launch = buildPtyCommand(cmd, args);
  const id = sessionKey(agent, sessionId);
  console.log(`  [pty] ${a.name} cwd=${WORKSPACE_CWD}`);
  console.log(`  [pty] $ ${launch.command} ${launch.args.join(' ')}`);

  const terminal = pty.spawn(launch.command, launch.args, {
    name: 'xterm-256color',
    cols: 100,
    rows: 30,
    cwd: WORKSPACE_CWD,
    env: {
      ...process.env,
      TERM: 'xterm-256color',
      NO_COLOR: '0',
    },
  });

  const session = {
    id,
    agent,
    name: a.name,
    terminal,
    clients: new Set([clientId]),
    output: '',
    closed: false,
  };
  ptySessions.set(id, session);

  terminal.onData((data) => {
    session.output = (session.output + data).slice(-256 * 1024);
    process.stdout.write(data);
    for (const target of session.clients) {
      send({ type: 'stream', clientId: target, content: data });
    }
  });

  terminal.onExit(({ exitCode }) => {
    session.closed = true;
    ptySessions.delete(id);
    for (const target of session.clients) {
      send({ type: 'done', clientId: target, content: `\n[${a.name} exited ${exitCode}]\n` });
    }
  });

  return session;
}

function executePtyAgent(agent, prompt, clientId, sessionId = '') {
  const id = sessionKey(agent, sessionId);
  let session = ptySessions.get(id);
  if (!session || session.closed) {
    const target = sessionId ? ` session ${sessionId}` : '';
    send({ type: 'status', clientId, content: `Starting ${agent}${target} in ${WORKSPACE_CWD}` });
    startPtyAgent(agent, prompt, clientId, sessionId);
    return;
  }

  session.clients.add(clientId);
  send({ type: 'status', clientId, content: `Sending to existing ${agent} session in ${WORKSPACE_CWD}` });
  if (session.output) {
    send({ type: 'stream', clientId, content: session.output.slice(-12000) });
  }
  session.terminal.write(prompt);
  setTimeout(() => {
    try { session.terminal.write('\r'); } catch {}
  }, 250);
}

function executeAgent(agent, prompt, clientId, sessionId = '', attachments = []) {
  return new Promise(async (resolve) => {
    const a = AGENTS.find(x => x.id === agent);
    if (!a) { send({ type: 'error', clientId, content: `Unknown agent: ${agent}` }); resolve(); return; }
    if (!a.localPromptCli) { send({ type: 'error', clientId, content: `${a.name} is installed as an editor launcher, not a promptable local agent CLI.` }); resolve(); return; }

    if (a.serverBacked) {
      try {
        await sendOpenCodePrompt(prompt, clientId, sessionId, attachments);
      } catch (err) {
        send({ type: 'error', clientId, content: `OpenCode failed: ${err.message}` });
      }
      resolve();
      return;
    }

    if (agent === 'devin') {
      try {
        await sendDevinPrompt(prompt, clientId, sessionId, attachments);
      } catch (err) {
        send({ type: 'error', clientId, content: `Devin failed: ${err.message}` });
      }
      resolve();
      return;
    }

    if (process.env.AGENTHUB_ONE_SHOT !== '1') {
      try {
        executePtyAgent(agent, prompt, clientId, sessionId);
      } catch (err) {
        send({ type: 'error', clientId, content: `PTY failed: ${err.message}` });
      }
      resolve();
      return;
    }

    const cmd = getCmd(a);
    const args = a.args(prompt, sessionId);
    console.log(`  $ ${cmd} ${args.join(' ')}`);
    send({ type: 'status', clientId, content: ` ${a.name} running...`.trim() });

    let child;
    try {
      child = spawnAgentCommand(cmd, args);
    } catch (err) {
      send({ type: 'error', clientId, content: `Failed to start ${a.name}: ${err.message}` });
      resolve();
      return;
    }
    let doneSent = false, fullOutput = '';

    function handleData(data) {
      const text = data.toString();
      fullOutput += text;
      send({ type: 'stream', clientId, content: text });
    }

    child.stdout.on('data', handleData);
    child.stderr.on('data', handleData);
    child.on('error', (err) => { if (!doneSent) { doneSent = true; send({ type: 'error', clientId, content: `Failed: ${err.message}` }); resolve(); } });
    child.on('close', (code) => {
      if (!doneSent) { doneSent = true; send({ type: 'done', clientId, content: code === 0 ? '' : `\nExit ${code}` }); }
      resolve();
    });
    setTimeout(() => { if (!doneSent) { doneSent = true; child.kill(); send({ type: 'done', clientId, content: '\nTimeout' }); resolve(); } }, 10 * 60 * 1000);
  });
}

// Start

console.log('=======================================');
console.log('  Agent Hub - Desktop Relay');
console.log('=======================================');

const agents = getAvailableAgents();
console.log(`  Agents:   ${agents.length ? agents.map(a => a.name).join(', ') : 'none'}`);
console.log(`  Server:   ${SERVER_URL}`);
console.log(`  Cwd:      ${WORKSPACE_CWD}`);
console.log(`  Mode:     OpenCode session server + Devin CLI${pty ? ' + PTY fallback' : ''}`);
console.log('=======================================\n');

connect();

process.on('SIGINT', () => {
  clearTimeout(reconnectTimer);
  clearInterval(heartbeatTimer);
  for (const session of ptySessions.values()) {
    try { session.terminal.kill(); } catch {}
  }
  try { opencodeServerProcess?.kill(); } catch {}
  if (ws) ws.close();
  process.exit(0);
});
