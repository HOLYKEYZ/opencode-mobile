const auth = 'Basic ' + Buffer.from('opencode:37e27954-9586-4226-89b5-bf063e7972ff').toString('base64');

async function test() {
  console.log('Testing OC API POST...');
  const start = Date.now();
  let success = true;
  
  // Try the async endpoint first
  try {
    const res = await fetch('http://127.0.0.1:4096/session/ses_12dbd95e7ffecIiTpoZcY9kf36/prompt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': auth },
      body: JSON.stringify({ parts: [{ type: 'text', text: 'Say exactly: phone test works!' }] }),
      signal: AbortSignal.timeout(30000),
    });
    console.log(`POST /prompt: ${res.status} ${res.statusText} in ${Date.now()-start}ms`);
    const text = await res.text();
    console.log('Response:', text.slice(0, 500));
  } catch (e) {
    console.log(`POST /prompt failed: ${e.message} in ${Date.now()-start}ms`);
    success = false;
  }

  // Try the original endpoint with abort
  const start2 = Date.now();
  try {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 60000);
    const res = await fetch('http://127.0.0.1:4096/session/ses_12dbd95e7ffecIiTpoZcY9kf36/message', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': auth },
      body: JSON.stringify({ parts: [{ type: 'text', text: 'Say exactly: phone test works!' }] }),
      signal: ac.signal,
    });
    clearTimeout(timer);
    console.log(`POST /message: ${res.status} ${res.statusText} in ${Date.now()-start2}ms`);
    const text = await res.text();
    console.log('Response:', text.slice(0, 500));
  } catch (e) {
    console.log(`POST /message failed: ${e.message} in ${Date.now()-start2}ms`);
    success = false;
  }
  if (!success) process.exit(1);
}

test();
