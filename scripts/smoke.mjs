import assert from 'node:assert/strict';
import {request} from 'node:http';
const base = 'http://127.0.0.1:8080';
let cookie = '';
async function post(path, data = {}) {
  // No generation deadline, including the implicit header timeout of Node fetch.
  const response = await new Promise((resolve, reject) => {
    const req = request(base + path, {method:'POST', headers:{'Content-Type':'application/json', ...(cookie ? {cookie} : {})}}, resolve);
    req.on('error', reject);
    req.end(JSON.stringify(data));
  });
  cookie = response.headers['set-cookie']?.[0]?.split(';')[0] || cookie;
  const chunks = [];
  for await (const chunk of response) chunks.push(chunk);
  return {status:response.statusCode, data:JSON.parse(Buffer.concat(chunks).toString('utf8'))};
}
assert.equal((await fetch(base)).status, 200);
assert.equal((await fetch(base + '/composeApp.js')).status, 200);
assert.equal((await post('/api/connect',{url:'http://localhost/private'})).status,400);
const missing=await post('/api/connect',{url:'https://github.com/release-pulse-inaccessible-test-2026/no-such-repo'});
assert.equal(missing.status,404);
const connected=await post('/api/connect',{url:'https://github.com/ktorio/ktor'});
assert.equal(connected.status,200,connected.data.error);
const s=connected.data;
assert.equal(s.repository.fullName,'ktorio/ktor');
assert.ok(s.sources.every(x=>x.url.startsWith('https://github.com/ktorio/ktor/')));
assert.ok(s.fetchedAt && s.metrics.openPr>=0);
assert.ok(s.statusSignals.every(x=>x.sourceIds.every(id=>s.sources.some(source=>source.id===id))));
assert.equal(s.ai.retrieval.indexedSources,s.sources.length);
assert.ok(s.ai.retrieval.selectedChars<=s.ai.retrieval.budgetChars);
console.log(JSON.stringify({publicRepo:s.repository.fullName,status:s.status,metrics:s.metrics,sources:s.sources.length,docs:s.sources.filter(x=>x.kind==='doc').length,rag:{indexedChunks:s.ai.retrieval.indexedChunks,selected:s.ai.retrieval.selectedChunks,chars:s.ai.retrieval.selectedChars},fetchedAt:s.fetchedAt}));
if(process.argv.includes('--ai')) {
  const ai=await post('/api/analyze');
  assert.equal(ai.status,200);
  assert.ok(ai.data.analysis || ai.data.error);
  if(ai.data.analysis) {
    const a=ai.data.analysis;
    const ids=new Set(ai.data.retrieval.excerpts.map(x=>x.sourceId));
    assert.ok([...a.blocks,...a.risks,...a.next_actions].every(x=>x.source_ids.every(id=>ids.has(id))));
    assert.ok(a.next_actions.length<=3);
    console.log(JSON.stringify({aiModel:ai.data.model,fallback:ai.data.fallback,status:a.status,headline:a.headline,risks:a.risks.length,actions:a.next_actions.length}));
  } else console.log(JSON.stringify({aiError:ai.data.error}));
}
if(process.argv.includes('--refresh')) {
  const refreshed=await post('/api/refresh');
  assert.equal(refreshed.status,200,refreshed.data.error);
  assert.ok(new Date(refreshed.data.fetchedAt)>new Date(s.fetchedAt));
  assert.equal(refreshed.data.ai.retrieval.indexedSources,refreshed.data.sources.length);
  console.log('Manual refresh and RAG index rebuild passed.');
}
assert.equal((await post('/api/disconnect')).status,200);
assert.equal((await post('/api/refresh')).status,401);
console.log('Smoke checks passed.');
