#!/usr/bin/env node
const path = require('path');

let WebSocket;
try {
  WebSocket = require(path.join(__dirname, '..', 'backend', 'node_modules', 'ws'));
} catch {
  WebSocket = require('ws');
}

const serverUrl = process.env.SERVER_URL || process.argv[2] || 'wss://agent-hub-backend-wk48.onrender.com';
const relayCode = process.env.RELAY_CODE || process.argv[3] || '';
const timeoutMs = Number(process.env.SMOKE_TIMEOUT_MS || 45000);
const executeOpenCode = process.env.SMOKE_EXECUTE_OPENCODE === '1' || process.argv.includes('--execute-opencode');
const executeDevin = process.env.SMOKE_EXECUTE_DEVIN === '1' || process.argv.includes('--execute-devin');
const openCodeExpected = process.env.SMOKE_OPENCODE_EXPECT || 'AGENTHUB_SMOKE_OK';
const openCodePrompt = process.env.SMOKE_OPENCODE_PROMPT || `Agent Hub relay smoke test: reply with exactly ${openCodeExpected}.`;
const devinExpected = process.env.SMOKE_DEVIN_EXPECT || 'AGENTHUB_SMOKE_OK';
const devinPrompt = process.env.SMOKE_DEVIN_PROMPT || `Agent Hub relay smoke test: reply with exactly ${devinExpected}.`;
const attachSmokeFile = process.env.SMOKE_ATTACH_FILE === '1' || process.argv.includes('--attach-file');
const attachmentText = process.env.SMOKE_ATTACHMENT_TEXT || `Agent Hub smoke attachment ${openCodeExpected}`;

if (!relayCode) {
  console.error('Usage: node scripts/relay-smoke.js <server-url> <relay-code>');
  console.error('Or set SERVER_URL and RELAY_CODE.');
  process.exit(2);
}

function withTimeout(label, work) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} timed out after ${timeoutMs}ms`)), timeoutMs);
    work().then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      },
    );
  });
}

function connectPhone(label) {
  return withTimeout(label, () => new Promise((resolve, reject) => {
    const ws = new WebSocket(serverUrl);
    const state = {
      joined: null,
      sessions: null,
      selectedOpenCode: null,
      openCodeDetail: null,
      selectedDevin: null,
      devinDetail: null,
      openCodeExecution: executeOpenCode ? { selected: null, statuses: [], output: '', done: false, attachmentSaved: false } : null,
      devinExecution: executeDevin ? { selected: null, statuses: [], output: '', done: false } : null,
    };

    function send(payload) {
      ws.send(JSON.stringify(payload));
    }

    ws.on('open', () => send({ type: 'join_session', code: relayCode }));
    ws.on('error', reject);
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw);
      if (msg.type === 'error') {
        reject(new Error(msg.content || 'Relay returned error'));
        try { ws.close(); } catch {}
        return;
      }

      if (msg.type === 'session_joined') {
        if (state.joined) return;
        state.joined = msg;
        if (!msg.relay_online) {
          reject(new Error('Relay is offline for this code'));
          try { ws.close(); } catch {}
          return;
        }
        send({ type: 'session_list' });
        return;
      }

      if (msg.type === 'sessions' && !state.sessions) {
        const sessions = msg.sessions || [];
        state.sessions = sessions;

        const openCode = sessions
          .filter((session) => session.agent === 'opencode' && session.id)
          .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0];
        if (openCode) {
          state.selectedOpenCode = { id: openCode.id, title: openCode.title };
          send({ type: 'session_detail', agent: 'opencode', sessionId: openCode.id });
        }

        const devin = sessions
          .filter((session) => session.agent === 'devin' && session.id)
          .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0];
        if (devin) {
          state.selectedDevin = { id: devin.id, title: devin.title };
          if (!openCode) send({ type: 'session_detail', agent: 'devin', sessionId: devin.id });
        }

        if (!openCode && !devin) {
          try { ws.close(1000, 'smoke done'); } catch {}
          resolve(state);
        }
        return;
      }

      if (msg.type === 'session_detail' && msg.detail?.agent === 'opencode' && !state.openCodeDetail) {
        const detail = msg.detail;
        if (detail.sessionId !== state.selectedOpenCode?.id) return;
        const hasUsableDetail = Array.isArray(detail.messages);
        if (!hasUsableDetail) return;
        state.openCodeDetail = detail;
        if (!executeOpenCode && !executeDevin) {
          try { ws.close(1000, 'smoke done'); } catch {}
          resolve(state);
          return;
        }
        if (executeOpenCode) {
          state.openCodeExecution.selected = state.selectedOpenCode;
          const payload = { agent: 'opencode', sessionId: state.selectedOpenCode.id, prompt: openCodePrompt };
          if (attachSmokeFile) {
            const data = Buffer.from(attachmentText, 'utf8');
            payload.attachments = [{
              name: 'agenthub-smoke.txt',
              mime: 'text/plain',
              base64: data.toString('base64'),
              size: data.length,
            }];
          }
          send(payload);
        } else if (executeDevin && state.selectedDevin) {
          send({ type: 'session_detail', agent: 'devin', sessionId: state.selectedDevin.id });
        }
        return;
      }

      if (msg.type === 'session_detail' && msg.detail?.agent === 'devin' && !state.devinDetail) {
        state.devinDetail = msg.detail;
        if (executeDevin && !executeOpenCode) {
          state.devinExecution.selected = state.selectedDevin;
          send({ agent: 'devin', sessionId: state.selectedDevin.id, prompt: devinPrompt });
        }
        return;
      }

      if (executeOpenCode && msg.type === 'status') {
        state.openCodeExecution?.statuses.push(msg.content || '');
        if (msg.content?.startsWith('file: saved upload ')) state.openCodeExecution.attachmentSaved = true;
        return;
      }

      if (executeOpenCode && (msg.type === 'stream' || msg.type === 'replace_stream')) {
        if (msg.content) state.openCodeExecution.output = msg.content;
        return;
      }

      if (executeOpenCode && msg.type === 'done') {
        state.openCodeExecution.done = true;
        if (!executeDevin) {
          try { ws.close(1000, 'smoke done'); } catch {}
          resolve(state);
        } else if (state.selectedDevin) {
          send({ type: 'session_detail', agent: 'devin', sessionId: state.selectedDevin.id });
        }
        return;
      }

      if (executeDevin && msg.type === 'status') {
        state.devinExecution?.statuses.push(msg.content || '');
        return;
      }

      if (executeDevin && (msg.type === 'stream' || msg.type === 'replace_stream')) {
        if (msg.content) state.devinExecution.output = msg.content;
        return;
      }

      if (executeDevin && msg.type === 'done') {
        state.devinExecution.done = true;
        try { ws.close(1000, 'smoke done'); } catch {}
        resolve(state);
      }
    });
  }));
}

function summarize(result) {
  const sessions = result.sessions || [];
  const counts = sessions.reduce((acc, session) => {
    acc[session.agent] = (acc[session.agent] || 0) + 1;
    return acc;
  }, {});
  const openCodeDetail = result.openCodeDetail || {};
  const devinDetail = result.devinDetail || {};
  return {
    relayOnline: !!result.joined?.relay_online,
    agents: result.joined?.available_agents || [],
    totalSessions: sessions.length,
    counts,
    selectedOpenCode: result.selectedOpenCode,
    selectedDevin: result.selectedDevin,
    openCodeDetail: {
      messages: Array.isArray(openCodeDetail.messages) ? openCodeDetail.messages.length : 0,
    },
    devinDetail: {
      sessionId: devinDetail.sessionId || '',
    },
    openCodeExecution: result.openCodeExecution ? {
      selected: result.openCodeExecution.selected,
      statusCount: result.openCodeExecution.statuses.length,
      done: result.openCodeExecution.done,
      output: result.openCodeExecution.output,
      attachmentSaved: result.openCodeExecution.attachmentSaved,
    } : null,
    devinExecution: result.devinExecution ? {
      selected: result.devinExecution.selected,
      statusCount: result.devinExecution.statuses.length,
      done: result.devinExecution.done,
      output: result.devinExecution.output,
    } : null,
  };
}

(async () => {
  const first = await connectPhone('first phone connection');
  const second = await connectPhone('reconnected phone connection');
  const firstSummary = summarize(first);
  const secondSummary = summarize(second);

  const failures = [];
  for (const [label, summary] of [['first', firstSummary], ['reconnect', secondSummary]]) {
    if (!summary.relayOnline) failures.push(`${label}: relay not online`);
    if (!summary.agents.includes('opencode')) failures.push(`${label}: opencode missing`);
    if (!summary.agents.includes('devin')) failures.push(`${label}: devin missing`);
    if (summary.totalSessions < 1) failures.push(`${label}: no sessions returned`);
    if ((summary.counts.opencode || 0) < 1) failures.push(`${label}: no OpenCode sessions`);
    if ((summary.counts.devin || 0) < 1) failures.push(`${label}: no Devin sessions`);
    if (!summary.selectedOpenCode?.id) failures.push(`${label}: no recent OpenCode chat selected`);
    if (summary.openCodeDetail.messages < 1) failures.push(`${label}: OpenCode detail has no messages`);
    if (executeOpenCode) {
      if (!summary.openCodeExecution?.selected?.id) failures.push(`${label}: no OpenCode execution target`);
      if (!summary.openCodeExecution?.done) failures.push(`${label}: OpenCode execution did not finish`);
      if (!summary.openCodeExecution?.output?.includes(openCodeExpected)) {
        failures.push(`${label}: OpenCode output did not include ${openCodeExpected}`);
      }
      if (attachSmokeFile && !summary.openCodeExecution?.attachmentSaved) {
        failures.push(`${label}: relay did not report saved attachment`);
      }
    }
    if (executeDevin) {
      if (!summary.devinExecution?.selected?.id) failures.push(`${label}: no Devin execution target`);
      if (!summary.devinExecution?.done) failures.push(`${label}: Devin execution did not finish`);
      if (!summary.devinExecution?.output?.includes(devinExpected)) {
        failures.push(`${label}: Devin output did not include ${devinExpected}`);
      }
    }
  }

  console.log(JSON.stringify({ serverUrl, relayCode, executeOpenCode, executeDevin, attachSmokeFile, first: firstSummary, reconnect: secondSummary }, null, 2));
  if (failures.length) {
    console.error(`Relay smoke failed:\n- ${failures.join('\n- ')}`);
    process.exit(1);
  }
})().catch((err) => {
  console.error(err.stack || err.message);
  process.exit(1);
});
