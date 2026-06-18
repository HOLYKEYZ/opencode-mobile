const WebSocket = require('ws');
const ws = new WebSocket('ws://localhost:3001');

let msgId = 0;
function send(obj) {
  msgId++;
  const str = JSON.stringify(obj);
  console.log(`>>> SENT #${msgId}: ${str.slice(0, 200)}`);
  ws.send(str);
}

ws.on('open', () => {
  console.log('CONNECTED');

  // Join
  setTimeout(() => send({ type: 'join_session', code: 'WATCHDOG1' }), 500);

  // Execute - wait 3s for join to complete, then send prompt
  setTimeout(() => {
    send({ type: 'execute', agent: 'opencode', prompt: 'Say exactly: phone test works!', sessionId: 'ses_12dbd95e7ffecIiTpoZcY9kf36' });
  }, 2000);
});

ws.on('message', (raw) => {
  const msg = JSON.parse(raw);
  const t = msg.type;
  if (t === 'replace_stream') {
    console.log(`\n<<< STREAM: ${(msg.content || '').slice(0, 500)}`);
  } else if (t === 'session_detail') {
    const msgs = msg.detail?.messages || [];
    console.log(`\n<<< DETAIL: ${msgs.length} messages`);
    msgs.slice(-3).forEach(m => console.log(`    [${m.role}] ${(m.text||'').slice(0, 150)}`));
  } else if (t === 'done') {
    console.log(`\n<<< DONE - TEST PASSED`);
    ws.close();
    process.exit(0);
  } else if (t === 'error') {
    console.log(`\n<<< ERROR: ${msg.content}`);
  } else if (t === 'status') {
    console.log(`<<< STATUS: ${(msg.content || '').slice(0, 150)}`);
  } else {
    console.log(`<<< ${t}`);
  }
});

ws.on('error', (e) => console.error('ERR:', e.message));
ws.on('close', () => { console.log('CLOSED'); process.exit(0); });

// Safety exit after 5 minutes
setTimeout(() => { console.log('TIMEOUT after 5min'); process.exit(1); }, 300000);
