(() => {
  'use strict';
  if (window.btAssistant) return;
  // Install before author code. Navigation/CSP and native resource interception enforce the primary boundary.
  const lock = (target, key, value) => { try { Object.defineProperty(target, key, {value, writable:false, configurable:false}); } catch (_) {} };
  ['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','WebSocket','WebSocketStream','WebTransport',
   'EventSource','Worker','SharedWorker','open','indexedDB','localStorage','sessionStorage'].forEach(key => lock(window, key, undefined));
  for(const key of ['bluetooth','usb','serial','hid'])lock(navigator,key,undefined);
  lock(navigator, 'sendBeacon', () => false);
  lock(navigator, 'serviceWorker', undefined);
  const bridge = window.__btNative;
  const contextId = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2,'0')).join('');
  const pending = new Map(), listeners = new Map();
  let counter = 0, runId = '';
  const to64 = bytes => {
    if (!(bytes instanceof Uint8Array) || bytes.byteLength < 1 || bytes.byteLength > 65536) throw new TypeError('Expected Uint8Array (1..65536 bytes)');
    let result = ''; for (let i=0;i<bytes.length;i++) result += String.fromCharCode(bytes[i]); return btoa(result);
  };
  const from64 = text => { const raw = atob(text); return Uint8Array.from(raw, c => c.charCodeAt(0)); };
  const send = (method, params = {}) => new Promise((resolve,reject) => {
    const requestId = String(++counter);
    if (pending.size >= 64) { const error=new Error('Plugin request queue is full');error.code='QUEUE_FULL';error.requestId=requestId;reject(error);return; }
    let encoded;
    try { encoded=JSON.stringify({type:'request',contextId,requestId,runId,method,params});if(encoded.length>128*1024)throw new Error('Request exceeds 128 KiB bridge limit'); }
    catch(cause){const error=new Error(cause.message);error.code='INVALID_ARGUMENT';error.requestId=requestId;reject(error);return;}
    const timer = setTimeout(() => { if(pending.delete(requestId)){const error=new Error('Operation timed out; do not retry a write automatically');error.code='OUTCOME_UNKNOWN';error.requestId=requestId;reject(error);} },65000);
    pending.set(requestId,{resolve,reject,timer});
    try {bridge.postMessage(encoded);}catch(cause){pending.delete(requestId);clearTimeout(timer);const error=new Error(cause.message);error.code='BRIDGE_CLOSED';error.requestId=requestId;reject(error);}
  });
  bridge.onmessage = event => {
    let batch; try { batch=JSON.parse(event.data); } catch (_) { return; }
    if(batch.type !== 'batch') return;
    runId=batch.runId;
    try {
      for(const message of batch.messages){
        if(message.type==='response'){
          if(message.contextId!==contextId)continue;
          const request=pending.get(message.requestId);if(!request)continue;
          pending.delete(message.requestId);clearTimeout(request.timer);
          if(message.error){const error=new Error(message.error.message);Object.assign(error,message.error);request.reject(error);}
          else {const value=message.result;if(value&&typeof value.bytes==='string')value.bytes=from64(value.bytes);request.resolve(value);}
        } else if(message.type==='event'){
          const value=message.value;
          if(value&&typeof value.bytes==='string')value.bytes=from64(value.bytes);
          for(const handler of [...(listeners.get(message.name)||[])]){try{handler(value);}catch(error){console.error(error);}}
        }
      }
    } finally {bridge.postMessage(JSON.stringify({type:'ack',contextId,runId,deliveryId:batch.deliveryId}));}
  };
  const initialized=send('host.ready');
  const call=(method,params)=>initialized.then(()=>send(method,params));
  const api={
    ready:()=>initialized,
    host:Object.freeze({getInfo:()=>call('host.getInfo'),getState:()=>call('host.getState')}),
    storage:Object.freeze({get:key=>call('storage.get',{key}),set:(key,value)=>call('storage.set',{key,value}),remove:key=>call('storage.remove',{key})}),
    on:(name,handler)=>{if(typeof handler!=='function')throw new TypeError('Expected event handler');if(!listeners.has(name))listeners.set(name,new Set());listeners.get(name).add(handler);return()=>listeners.get(name).delete(handler);}
  };
  for(const transport of ['classic','ble']){
    const methods=transport==='classic'?['paired','scan','stopScan','connect','disconnect','write']:['scan','stopScan','connect','disconnect','services','read','write','subscribe','requestMtu','readRssi'];
    const group={};for(const method of methods)group[method]=(options={})=>Promise.resolve().then(()=>{const args={...options};if(method==='write'){try{args.bytes=to64(args.bytes);}catch(error){error.code='INVALID_ARGUMENT';error.requestId=String(++counter);throw error;}}return call(transport+'.'+method,args);});
    api[transport]=Object.freeze(group);
  }
  lock(window,'btAssistant',Object.freeze(api));
})();
