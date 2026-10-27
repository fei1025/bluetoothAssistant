const sdk=window.btAssistant, transport="classic", bt=sdk[transport], $=id=>document.getElementById(id);
let connection=null; const devices=new Map(), endpoints=new Map(), rows=[], samples=[];
const log=value=>{rows.push(new Date().toLocaleTimeString()+' '+String(value).slice(0,1000));while(rows.length>80)rows.shift();$('log').textContent=rows.join('\n');};
const run=work=>Promise.resolve().then(work).catch(error=>{log((error.code||'ERROR')+': '+error.message);$('status').textContent=error.message;});
const hex=bytes=>Array.from(bytes.slice(0,256),byte=>byte.toString(16).padStart(2,'0')).join(' ');
function addDevice(device){if(device.transport!==transport)return;devices.set(device.address,device);let option=Array.from($('devices').options).find(item=>item.value===device.address);if(!option){option=document.createElement('option');option.value=device.address;$('devices').appendChild(option);}option.textContent=(device.name||'Unknown')+' '+device.address+(device.rssi?' '+device.rssi+' dBm':'');}
function params(extra={}){if(!connection?.sessionId)throw new Error('请先连接 / Connect first');return {sessionId:connection.sessionId,...extra};}
function payload(){const value=$('payload').value;if($('encoding').value==='hex'){const raw=value.replace(/\s/g,'');if(!raw||raw.length%2||!/^[0-9a-f]+$/i.test(raw))throw new Error('Invalid HEX');return Uint8Array.from(raw.match(/../g),x=>parseInt(x,16));}return new TextEncoder().encode(value+(transport==='classic'&&$('crlf').checked?'\r\n':''));}
sdk.on('device',addDevice);
sdk.on('state',state=>{if(state.transport!==transport)return;connection=state.connected?state:null;$('status').textContent=state.connected?'已连接 / Connected '+state.address:'未连接 / Disconnected';});
sdk.on('data',data=>{if(data.transport!==transport)return;log('RX '+hex(data.bytes));if(transport==='ble'&&data.bytes.length){samples.push(data.bytes[0]);while(samples.length>120)samples.shift();const canvas=$('chart'),ctx=canvas.getContext('2d');ctx.clearRect(0,0,canvas.width,canvas.height);ctx.beginPath();samples.forEach((value,i)=>{const x=i*canvas.width/119,y=canvas.height-value*canvas.height/255;if(i)ctx.lineTo(x,y);else ctx.moveTo(x,y);});ctx.strokeStyle='#385bd7';ctx.lineWidth=2;ctx.stroke();}});
sdk.on('dataLoss',()=>{connection=null;log('数据丢失，连接已停止 / Data loss: connection closed');});
$('scan').onclick=()=>run(()=>bt.scan(transport==='ble'?{namePrefix:$('prefix').value}:{}));
$('stop-scan').onclick=()=>run(()=>bt.stopScan());
$('connect').onclick=()=>run(async()=>{const address=$('devices').value;if(!address)throw new Error('请选择设备 / Select a device');connection=await bt.connect({address,...(transport==='classic'?{uuid:$('uuid').value}:{})});$('status').textContent='Connected '+address;if(transport==='ble')await services();});
$('disconnect').onclick=()=>run(()=>bt.disconnect());
$('send').onclick=()=>run(async()=>{const bytes=payload();const options=params({bytes});if(transport==='ble'){options.characteristic=selected();options.withoutResponse=$('without-response').checked;options.split=$('split').checked;}const result=await bt.write(options);log('TX '+result.status+' '+hex(bytes));});
$('save').onclick=()=>run(async()=>{await sdk.storage.set('input',{payload:$('payload').value,encoding:$('encoding').value});log('Saved');});
async function services(){const tree=await bt.services(params());endpoints.clear();$('characteristics').replaceChildren();for(const service of tree){for(const characteristic of service.characteristics){endpoints.set(characteristic.handle,characteristic);const option=document.createElement('option');option.value=characteristic.handle;option.textContent=service.uuid+' #'+service.instanceId+' / '+characteristic.uuid+' #'+characteristic.instanceId+' [0x'+characteristic.properties.toString(16)+']';$('characteristics').appendChild(option);}}log('Services '+tree.length);}
function selected(){const handle=$('characteristics').value;if(!endpoints.has(handle))throw new Error('请选择特征 / Select a characteristic');return handle;}
if(transport==='classic')$('paired').onclick=()=>run(async()=>{for(const device of await bt.paired())addDevice(device);});
else{
 $('services').onclick=()=>run(services);
 $('read').onclick=()=>run(async()=>{const value=await bt.read(params({characteristic:selected()}));log('READ '+hex(value.bytes));});
 for(const [id,mode] of [['notify',1],['indicate',2],['unsubscribe',0]])$(id).onclick=()=>run(async()=>{await bt.subscribe(params({characteristic:selected(),mode}));log('Subscription '+mode);});
 $('request-mtu').onclick=()=>run(async()=>log('MTU '+(await bt.requestMtu(params({mtu:Number($('mtu').value)}))).mtu));
 $('rssi').onclick=()=>run(async()=>log('RSSI '+(await bt.readRssi(params())).rssi+' dBm'));
}
run(async()=>{const host=await sdk.ready();$('status').textContent='Ready · SDK '+host.sdk;const saved=await sdk.storage.get('input');if(saved){$('payload').value=saved.payload||'';$('encoding').value=saved.encoding||'text';}});
