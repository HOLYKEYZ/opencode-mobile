const WebSocket = require('ws');
const { spawn } = require('child_process');
const path = require('path');
const os = require('os');
const fs = require('fs');
const QRCode = require('qrcode');
let pty = null;
try { pty = require('node-pty'); } catch {}

const SERVER_URL = process.env.SERVER_URL || 'ws://localhost:3001';
const WORKSPACE_CWD = path.resolve(process.env.AGENTHUB_CWD || process.env.WORKSPACE_CWD || path.join(__dirname, '..'));
const isWin = os.platform() === 'win32';

const AGENTS = [
  { id: 'codex',    name: 'Codex',    modelKey: 'CODEX_MODEL',    cmd: process.env.CODEX_PATH || 'codex', args: p => ['exec', '--dangerously-bypass-approvals-and-sandbox', p], localPromptCli: true },
  { id: 'opencode', name: 'OpenCode', modelKey: 'OPENCODE_MODEL', cmd: null,                         args: p => ['run', '--dangerously-skip-permissions', '--format', 'json', p], localPromptCli: true },
  { id: 'windsurf', name: 'Windsurf', modelKey: 'WINDSURF_MODEL', cmd: null,                         args: p => [p], localPromptCli: false },
  { id: 'kiro',     name: 'Kiro',     modelKey: 'KIRO_MODEL',     cmd: null,                         args: p => [p], localPromptCli: false },
];

const PTY_AGENT_ARGS = {
  codex: (prompt) => ['--dangerously-bypass-approvals-and-sandbox', '--no-alt-screen', prompt],
  opencode: (prompt) => ['run', '--dangerously-skip-permissions', prompt],
  windsurf: (prompt) => [prompt],
  kiro: (prompt) => [prompt],
};

function quoteCmdArg(value) {
  const raw = String(value);
  const escaped = raw.replace(/([&|^<>()!])/g, '^$1').replace(/"/g, '""');
  return /[\s"]/u.test(raw) ? `"${escaped}"` : escaped;
}

function spawnAgentCommand(cmd, args) {
  if (!isWin) {
    return spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env } });
  }

  // Windows CLI tools installed through npm/cargo are often .cmd/.ps1 shims.
  // Running through cmd.exe avoids EPERM from child_process.spawn on those shims.
  const commandLine = [quoteCmdArg(cmd), ...args.map(quoteCmdArg)].join(' ');
  return spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', commandLine], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env },
    windowsHide: true,
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

function resolveCodexNodeLaunch(args) {
  if (!isWin) return null;
  const codexJs = path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'npm', 'node_modules', '@openai', 'codex', 'bin', 'codex.js');
  if (!fs.existsSync(codexJs)) return null;
  return { command: process.execPath, args: [codexJs, ...args] };
}

function buildPtyAgentLaunch(agent, cmd, args) {
  if (agent === 'codex') {
    const nodeLaunch = resolveCodexNodeLaunch(args);
    if (nodeLaunch) return nodeLaunch;
  }
  return buildPtyCommand(cmd, args);
}

function getCmd(a) {
  if (a.cmd) return a.cmd;
  if (a.id === 'opencode') {
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

function readCodexConfig() {
  try {
    const authPath = path.join(os.homedir(), '.codex', 'auth.json');
    if (!fs.existsSync(authPath)) return {};
    const cfg = {};
    const configPath = path.join(os.homedir(), '.codex', 'config.toml');
    if (fs.existsSync(configPath)) {
      const raw = fs.readFileSync(configPath, 'utf-8');
      const model = raw.match(/model\s*=\s*"([^"]+)"/)?.[1];
      if (model) cfg.CODEX_MODEL = model;
    }
    return cfg;
  } catch { return {}; }
}

function readOpenCodeConfig() {
  try {
    const authPath = path.join(os.homedir(), '.local', 'share', 'opencode', 'auth.json');
    if (!fs.existsSync(authPath)) return {};
    JSON.parse(fs.readFileSync(authPath, 'utf-8'));
    return {};
  } catch { return {}; }
}

function readWindsurfConfig() {
  try {
    const configPath = path.join(os.homedir(), '.codeium', 'windsurf.json');
    if (!fs.existsSync(configPath)) return {};
    JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    return {};
  } catch { return {}; }
}

function readKiroConfig() {
  try {
    const configPath = path.join(os.homedir(), '.kiro', 'config.json');
    if (!fs.existsSync(configPath)) return {};
    const raw = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    const cfg = {};
    if (raw.model) cfg.KIRO_MODEL = raw.model;
    return cfg;
  } catch { return {}; }
}

function readLocalConfig() {
  const cfg = { ...readCodexConfig(), ...readOpenCodeConfig(), ...readWindsurfConfig(), ...readKiroConfig() };
  cfg.LOCAL_AGENTS = getAvailableAgents().map(a => a.id);
  return cfg;
}

// ─── Detect available agents ──────────────────────────────────

function getAvailableAgents() {
  return AGENTS.filter((a) => {
    if (!a.localPromptCli) return false;
    const cmd = getCmd(a);
    if (!commandExists(cmd)) return false;
    if (a.id === 'codex') return fs.existsSync(path.join(os.homedir(), '.codex', 'auth.json'));
    if (a.id === 'opencode') return fs.existsSync(path.join(os.homedir(), '.local', 'share', 'opencode', 'auth.json'));
    return false;
  });
}

// ─── WebSocket ────────────────────────────────────────────────

let ws, reconnectTimer, heartbeatTimer, sessionCode;

function connect() {
  if (ws) { ws.close(); ws = null; }
  ws = new WebSocket(SERVER_URL);

  ws.on('open', () => {
    const config = readLocalConfig();
    ws.send(JSON.stringify({ type: 'register_relay', config }));
    clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => send({ type: 'ping' }), 25000);
  });

  ws.on('message', async (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    if (msg.type === 'relay_registered') {
      sessionCode = msg.code;
      console.log(`\n🔗 Relay session: ${sessionCode}\n`);
      printAgentQRCodes(sessionCode);
      return;
    }

    if (msg.type === 'execute') {
      const { agent, prompt, clientId } = msg;
      console.log(`\n📩 ${agent}: "${prompt.slice(0, 80)}..."`);
      await executeAgent(agent, prompt, clientId);
    } else if (msg.type === 'pong') {
      return;
    } else if (msg.type === 'system' || msg.type === 'phone_connected') {
      console.log(`ℹ️  ${msg.content || msg.type}`);
    }
  });

  ws.on('close', () => {
    clearInterval(heartbeatTimer);
    console.log('❌ Disconnected. Reconnecting in 5s...');
    ws = null;
    reconnectTimer = setTimeout(connect, 5000);
  });

  ws.on('error', (err) => {
    clearInterval(heartbeatTimer);
    console.error(`⚠️  ${err.message}`);
    ws = null;
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 5000);
  });
}

function send(obj) {
  if (ws?.readyState === 1) ws.send(JSON.stringify(obj));
}

function printAgentQRCodes(code) {
  const agents = getAvailableAgents();
  const qrFile = path.join(__dirname, '..', 'session_qr.txt');
  let fileContent = '';

  agents.forEach((a, i) => {
    const qrPayload = `${SERVER_URL}?code=${code}&agent=${a.id}`;
    if (i > 0) console.log('');
    QRCode.toString(qrPayload, { type: 'terminal', small: true }, (err, qr) => {
      if (err) return;
      console.log(`═══════════════════════════════════════`);
      console.log(`  ${a.name}`);
      console.log(`═══════════════════════════════════════`);
      console.log(qr);
      console.log(`   Code: ${code}`);
      console.log(`   Agent: ${a.name}`);
      console.log(`═══════════════════════════════════════\n`);
    });
    fileContent += `Agent: ${a.name} (${a.id})\nCode: ${code}\nURL: ${qrPayload}\n\n`;

    // Also print a compact one-liner
    console.log(`  [${a.id}] Code: ${code}  |  URL: ${qrPayload}\n`);
  });

  if (agents.length === 0) {
    console.log('⚠️  No agents detected (no API keys found).');
    console.log('   Install and configure codex, opencode, or others.\n');
  }

  try {
    if (fileContent) fs.writeFileSync(qrFile, fileContent);
  } catch {}
}

// ─── Agent execution ──────────────────────────────────────────

const ptySessions = new Map();

function sessionKey(agent) {
  return `${agent}:${WORKSPACE_CWD}`;
}

function startPtyAgent(agent, prompt, clientId) {
  if (!pty) throw new Error('node-pty is not installed. Run npm install in backend/.');
  const a = AGENTS.find(x => x.id === agent);
  if (!a) throw new Error(`Unknown agent: ${agent}`);
  if (!a.localPromptCli) throw new Error(`${a.name} is installed as an editor launcher, not a promptable local agent CLI.`);

  const cmd = getCmd(a);
  if (!commandExists(cmd)) throw new Error(`${a.name} CLI not found on PATH.`);
  const args = (PTY_AGENT_ARGS[agent] || ((p) => a.args(p)))(prompt);
  const launch = buildPtyAgentLaunch(agent, cmd, args);
  const id = sessionKey(agent);
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

function executePtyAgent(agent, prompt, clientId) {
  const id = sessionKey(agent);
  let session = ptySessions.get(id);
  if (!session || session.closed) {
    send({ type: 'status', clientId, content: `Starting ${agent} in ${WORKSPACE_CWD}` });
    startPtyAgent(agent, prompt, clientId);
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

function executeAgent(agent, prompt, clientId) {
  return new Promise((resolve) => {
    const a = AGENTS.find(x => x.id === agent);
    if (!a) { send({ type: 'error', clientId, content: `Unknown agent: ${agent}` }); resolve(); return; }
    if (!a.localPromptCli) { send({ type: 'error', clientId, content: `${a.name} is installed as an editor launcher, not a promptable local agent CLI.` }); resolve(); return; }

    if (process.env.AGENTHUB_ONE_SHOT !== '1') {
      try {
        executePtyAgent(agent, prompt, clientId);
      } catch (err) {
        send({ type: 'error', clientId, content: `PTY failed: ${err.message}` });
      }
      resolve();
      return;
    }

    const cmd = getCmd(a);
    const args = a.args(prompt);
    console.log(`  $ ${cmd} ${args.join(' ')}`);
    send({ type: 'status', clientId, content: `🔄 ${a.name}...` });

    let child;
    try {
      child = spawnAgentCommand(cmd, args);
    } catch (err) {
      send({ type: 'error', clientId, content: `Failed to start ${a.name}: ${err.message}` });
      resolve();
      return;
    }
    let doneSent = false, fullOutput = '', jsonBuffer = '';

    function handleData(data) {
      const text = data.toString();
      fullOutput += text;

      if (agent === 'opencode') {
        jsonBuffer += text;
        const lines = jsonBuffer.split('\n');
        jsonBuffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const ev = JSON.parse(line);
            if (ev.type === 'content' && ev.content) {
              send({ type: 'replace_stream', clientId, content: ev.content });
            }
          } catch {
            // Skip non-JSON lines (progress spinners, status, etc.)
          }
        }
      } else if (agent === 'codex') {
        // Codex output is mostly AI text. Forward cleanly, strip ANSI.
        send({ type: 'stream', clientId, content: text });
      } else {
        send({ type: 'stream', clientId, content: text });
      }
    }

    child.stdout.on('data', handleData);
    child.stderr.on('data', handleData);
    child.on('error', (err) => { if (!doneSent) { doneSent = true; send({ type: 'error', clientId, content: `Failed: ${err.message}` }); resolve(); } });
    child.on('close', (code) => {
      if (!doneSent) { doneSent = true; send({ type: 'done', clientId, content: code === 0 ? '' : `\n⚠️ Exit ${code}` }); }
      resolve();
    });
    setTimeout(() => { if (!doneSent) { doneSent = true; child.kill(); send({ type: 'done', clientId, content: '\n⏱️ Timeout' }); resolve(); } }, 10 * 60 * 1000);
  });
}

// ─── Start ────────────────────────────────────────────────────

console.log('═══════════════════════════════════════');
console.log('  Agent Hub — Desktop Relay');
console.log('═══════════════════════════════════════');

const agents = getAvailableAgents();
console.log(`  Agents:   ${agents.length ? agents.map(a => a.name).join(', ') : 'none'}`);
console.log(`  Server:   ${SERVER_URL}`);
console.log(`  Cwd:      ${WORKSPACE_CWD}`);
console.log(`  Mode:     ${pty ? 'persistent PTY' : 'one-shot fallback'}`);
console.log('═══════════════════════════════════════\n');

connect();

process.on('SIGINT', () => {
  clearTimeout(reconnectTimer);
  clearInterval(heartbeatTimer);
  for (const session of ptySessions.values()) {
    try { session.terminal.kill(); } catch {}
  }
  if (ws) ws.close();
  process.exit(0);
});
