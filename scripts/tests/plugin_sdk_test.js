const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),crypto=require('node:crypto');
const sent=[],window={},navigator={},bridge={postMessage:value=>sent.push(JSON.parse(value))};window.__btNative=bridge;
const context=vm.createContext({window,navigator,crypto:crypto.webcrypto,Uint8Array,setTimeout,clearTimeout,
  console,btoa:s=>Buffer.from(s,'binary').toString('base64'),atob:s=>Buffer.from(s,'base64').toString('binary')});
vm.runInContext(fs.readFileSync('app/src/main/assets/plugins/sdk.js','utf8'),context);
let delivery=0;
function response(request,result,error){bridge.onmessage({data:JSON.stringify({type:'batch',runId:'run',deliveryId:++delivery,
  messages:[{type:'response',contextId:request.contextId,requestId:request.requestId,result,error}]})});}
async function tick(){await new Promise(resolve=>setImmediate(resolve));}
(async()=>{
 const sdk=window.btAssistant,ready=sent.shift();assert.equal(ready.method,'host.ready');response(ready,{sdk:1});await sdk.ready();
 assert.equal(sent.pop().type,'ack');assert.equal(window.WebSocket,undefined);assert.equal(navigator.bluetooth,undefined);
 const payload=new Uint8Array([0,255,13,10]);let done=false;
 const write=sdk.classic.write({sessionId:'session',bytes:payload}).then(v=>{done=true;return v;});await tick();
 const request=sent.shift();assert.equal(request.params.bytes,'AP8NCg==');assert.equal(request.runId,'run');assert.equal(done,false);
 response(request,{status:'SENT'});assert.equal((await write).status,'SENT');sent.length=0;
 await assert.rejects(sdk.classic.write({bytes:[1]}),e=>e.code==='INVALID_ARGUMENT'&&Boolean(e.requestId));
 const cyclic={};cyclic.self=cyclic;await assert.rejects(sdk.storage.set('cycle',cyclic),e=>e.code==='INVALID_ARGUMENT');
 await assert.rejects(sdk.storage.set('huge','x'.repeat(128*1024)),e=>e.code==='INVALID_ARGUMENT');
 let data;const off=sdk.on('data',v=>{data=v;});bridge.onmessage({data:JSON.stringify({type:'batch',runId:'run',deliveryId:++delivery,messages:[{type:'event',name:'data',value:{bytes:'AP8NCg=='}}]})});
 assert.deepEqual([...data.bytes],[0,255,13,10]);off();sent.length=0;
 const read=sdk.ble.read({sessionId:'s',characteristic:'opaque'});await tick();response(sent.shift(),{bytes:'AP8NCg==',status:'OK'});assert.deepEqual([...(await read).bytes],[0,255,13,10]);sent.length=0;
 const pending=[];for(let i=0;i<64;i++)pending.push(sdk.host.getState());await tick();
 await assert.rejects(sdk.host.getState(),e=>e.code==='QUEUE_FULL'&&Boolean(e.requestId));
 const requests=sent.splice(0);for(const r of requests)response(r,{});await Promise.all(pending);
 console.log('SDK boundary checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
