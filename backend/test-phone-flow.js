const WebSocket = require('ws');
const ws = new WebSocket('ws://localhost:3001');

let msgId = 0;
function send(obj) {
  msgId++;
  const str = JSON.stringify(obj);
  console.log(`\n>>> SENT #${msgId}: ${str.slice(0, 250)}`);
  ws.send(str);
}

const received = {};

ws.on('open', () => {
  console.log('CONNECTED to server');
  
  // Step 1: Join session (what phone does on connect)
  setTimeout(() => send({ type: 'join_session', code: 'WATCHDOG1' }), 500);
  
  // Step 2: List sessions
  setTimeout(() => send({ type: 'session_list', agent: 'opencode' }), 2000);
  
  // Step 3: Get detail of test session
  setTimeout(() => {
    if (received.sessions?.length > 0) {
      const target = received.sessions.find(s => s.id?.includes('12956e060ffe')) || received.sessions[0];
      console.log(`\n>>> Opening session: ${target.title}`);
      send({ type: 'session_detail', agent: 'opencode', sessionId: target.id });
    }
  }, 4000);
  
  // Step 4: Send a prompt
  setTimeout(() => {
    if (received.detail) {
      send({ type: 'execute', agent: 'opencode', prompt: 'Say exactly: phone test works!' });
    }
  }, 6000);
  
  setTimeout(() => { console.log('\n\n=== TEST COMPLETE ==='); ws.close(); process.exit(0); }, 120000);
});

ws.on('message', (raw) => {
  const msg = JSON.parse(raw);
  const type = msg.type;
  
  if (type === 'session_joined') {
    console.log(`\n<<< SESSION JOINED: relay=${msg.relay_online} models=${(msg.available_models?.opencode || []).length} agents=${JSON.stringify(msg.available_agents)}`);
    received.models = msg.available_models?.opencode || [];
    console.log(`    First 5 models: ${received.models.slice(0,5).join(', ')}`);
  } else if (type === 'sessions') {
    received.sessions = msg.sessions || [];
    console.log(`\n<<< SESSIONS: ${received.sessions.length} total`);
    received.sessions.slice(0, 5).forEach(s => console.log(`    [${s.agent}] ${s.title}`));
  } else if (type === 'session_detail') {
    received.detail = msg.detail;
    const msgs = msg.detail?.messages || [];
    console.log(`\n<<< SESSION_DETAIL: ${msgs.length} messages`);
    msgs.forEach(m => {
      const preview = (m.text || '').slice(0, 100);
      console.log(`    [${m.role}] ${preview}`);
    });
  } else if (type === 'replace_stream') {
    console.log(`\n<<< STREAM: ${(msg.content || '').slice(0, 200)}`);
  } else if (type === 'status') {
    console.log(`\n<<< STATUS: ${(msg.content || '').slice(0, 150)}`);
  } else if (type === 'done') {
    console.log(`\n<<< DONE`);
  } else if (type === 'error') {
    console.log(`\n<<< ERROR: ${msg.content}`);
  } else {
    console.log(`\n<<< ${type}: ${JSON.stringify(msg).slice(0, 200)}`);
  }
});

ws.on('error', (err) => console.error('WS ERROR:', err.message));
ws.on('close', () => console.log('WS CLOSED'));
