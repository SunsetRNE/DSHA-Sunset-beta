'use strict';
// MCP 只桥接已存在的 Android 设备接口；不暴露 shell、凭据读取或任意文件访问。
const fs=require('node:fs/promises');
const fsSync=require('node:fs');
const path=require('node:path');
const MAX_SCREENSHOT_BYTES=16*1024*1024;
// 桥端点：端口文件与 .bridge_token 同数据根（DSH_HOME 优先），每次请求重读。
// 契约文件是 ≤16 字节的普通文件（同原生 BridgeEndpointFiles.READ_LIMIT），内容只有 ASCII
// 十进制 + 一个可选换行。只有真正缺目录项才回退旧默认 3090；0、坏格式、越界、非 ASCII、
// 带垃圾尾巴、目录、符号链接（含悬空链接）或读不动都算「端点未知」，必须 fail-closed ——
// 同机可能有第二个 DSHA 实例。
const LEGACY_BRIDGE_PORT=3090;
const BRIDGE_PORT_LIMIT=16;
const BRIDGE_ENDPOINT_UNAVAILABLE='BRIDGE_ENDPOINT_UNAVAILABLE';
function bridgeRoot(){const home=process.env.DSH_HOME;return typeof home==='string'&&home.trim()&&home.trim()!=='/'?home.trim().replace(/\/+$/,''):'/root/.dsh';}
function readBridgePortFile(file){
  // 'missing' 只有真正没有这个目录项；悬空符号链接的 ENOENT 也算已存在 → 'invalid'。
  let entry;try{entry=fsSync.lstatSync(file);}catch(error){return error&&error.code==='ENOENT'?'missing':'invalid';}
  if(!entry.isFile())return 'invalid';
  let descriptor;
  try{
    descriptor=fsSync.openSync(file,(fsSync.constants.O_RDONLY|(fsSync.constants.O_NOFOLLOW||0)));
    const stats=fsSync.fstatSync(descriptor);
    if(!stats.isFile()||stats.size<1||stats.size>BRIDGE_PORT_LIMIT)return 'invalid';
    const buffer=Buffer.alloc(stats.size);let filled=0;
    while(filled<stats.size){const count=fsSync.readSync(descriptor,buffer,filled,stats.size-filled,filled);if(count<=0)break;filled+=count;}
    if(filled!==stats.size)return 'invalid';
    // 整份内容必须就是端口：'3091\n' 后面再有空白/垃圾就不是契约文件；不做 Unicode trim。
    const text=buffer.toString('utf8');
    if(!/^[0-9]{1,5}\n?$/.test(text))return 'invalid';
    const port=Number(text.replace(/\n$/,''));
    return port>=1&&port<=65535?port:'invalid';
  }catch(ignored){return 'invalid';}
  finally{try{if(descriptor!==undefined)fsSync.closeSync(descriptor);}catch(ignored){}}
}
function bridgePort(){
  const value=readBridgePortFile(path.join(bridgeRoot(),'.bridge_port'));
  if(value==='missing')return LEGACY_BRIDGE_PORT;
  if(value==='invalid')throw Error(BRIDGE_ENDPOINT_UNAVAILABLE);
  return value;
}
function bridgeTokenPath(){return path.join(bridgeRoot(),'.bridge_token');}
const definitions=[
  ['android_get_state','读取当前 Android 页面结构。操作前读取，操作后再次验证。','/app/ui/dump',{}],
  ['android_screenshot','截取当前屏幕并返回图片。需要系统截屏能力和 DSHA 授权。','/app/ui/screenshot',{}],
  ['android_click','点击当前页面上的坐标；坐标必须来自最近一次观察。','/app/ui/tap',{x:{type:'integer',minimum:0},y:{type:'integer',minimum:0}}],
  ['android_type','向已聚焦输入框写入文字。涉及敏感操作时先取得用户确认。','/app/ui/input',{text:{type:'string',maxLength:2000}}],
  ['android_key','按下 Android 导航键。','/app/ui/key',{name:{type:'string',enum:['back','home','recents','notifications','quicksettings']}}],
  ['android_swipe','在观察过的页面上滑动。','/app/ui/swipe',{x1:{type:'integer',minimum:0},y1:{type:'integer',minimum:0},x2:{type:'integer',minimum:0},y2:{type:'integer',minimum:0},ms:{type:'integer',minimum:50,maximum:3000}}]
];
const tools=definitions.map(([name,description,,properties])=>({name,description,inputSchema:{type:'object',properties,required:Object.keys(properties),additionalProperties:false}}));
function validate(def,args){if(!args||typeof args!=='object'||Array.isArray(args))throw Error('INVALID_ARGUMENTS');const fields=def[3];if(Object.keys(args).some(k=>!Object.hasOwn(fields,k)))throw Error('UNKNOWN_ARGUMENT');for(const [key,rule]of Object.entries(fields)){const v=args[key];if(rule.type==='integer'&&(!Number.isInteger(v)||v<rule.minimum||rule.maximum!==undefined&&v>rule.maximum))throw Error('INVALID_'+key);if(rule.type==='string'&&(typeof v!=='string'||rule.maxLength&&v.length>rule.maxLength||rule.enum&&!rule.enum.includes(v)))throw Error('INVALID_'+key);}}
// 只识别桥返回的错误前缀；读屏正文可能包含终端日志里的 [ERR] 等普通文字。
function bridgeFailed(result){return /^\s*(?:\[ERR\]|\[?(?:POLICY_BLOCKED|EXECUTION_UNKNOWN|NO_PERMISSION|DISABLED|UNAUTHORIZED)\b)/.test(result);}
// Native capture returns the freshly generated PNG directly: the guest does not
// necessarily mount the current Android user's /storage/emulated/<id> directory.
function screenshotContent(result){let shot;try{shot=JSON.parse(result);}catch{throw Error('SCREENSHOT_RESPONSE_INVALID');}
  if(shot?.kind!=='dsha-screenshot-v1'||shot.mimeType!=='image/png'||typeof shot.data!=='string')throw Error('SCREENSHOT_RESPONSE_INVALID');
  if(shot.data.length>Math.ceil(MAX_SCREENSHOT_BYTES/3)*4)throw Error('SCREENSHOT_TOO_LARGE');
  const png=Buffer.from(shot.data,'base64');
  if(png.toString('base64')!==shot.data)throw Error('SCREENSHOT_RESPONSE_INVALID');
  if(png.length>MAX_SCREENSHOT_BYTES)throw Error('SCREENSHOT_TOO_LARGE');
  if(png.length<8||!png.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw Error('SCREENSHOT_RESPONSE_INVALID');
  const text=typeof shot.path==='string'?'Screenshot saved: '+shot.path:'Android screenshot';
  return [{type:'text',text},{type:'image',mimeType:'image/png',data:shot.data}];
}
async function call(name,args,signal,dependencies={fs,fetch:globalThis.fetch}){const def=definitions.find(t=>t[0]===name);if(!def)throw Error('UNKNOWN_TOOL');validate(def,args);
  const port=(dependencies.bridgePort||bridgePort)();
  const token=(await dependencies.fs.readFile(bridgeTokenPath(),'utf8')).trim();if(!token)throw Error('BRIDGE_NOT_READY');
  const url=new URL('http://127.0.0.1:'+port+def[2]);for(const [k,v]of Object.entries(args))url.searchParams.set(k,String(v));
  if(name==='android_screenshot')url.searchParams.set('format','mcp');
  const response=await dependencies.fetch(url,{signal,headers:{'X-Token':token}});if(!response.ok)throw Error('BRIDGE_HTTP_'+response.status);
  const raw=await readBridgeResponse(response,name==='android_screenshot'?24*1024*1024:2*1024*1024);let result=raw;try{const parsed=JSON.parse(raw);result=typeof parsed.result==='string'?parsed.result:raw;}catch{}
  const failed=bridgeFailed(result);
  const content=name==='android_screenshot'&&!failed?screenshotContent(result):[{type:'text',text:result}];
  return {content,isError:failed};
}
async function readBridgeResponse(response,limit){
  const advertised=Number(response.headers.get('content-length'));
  if(Number.isFinite(advertised)&&advertised>limit){await response.body?.cancel();throw Error('BRIDGE_RESPONSE_TOO_LARGE');}
  if(!response.body) return '';
  const reader=response.body.getReader(),parts=[];let size=0;
  try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit){await reader.cancel();throw Error('BRIDGE_RESPONSE_TOO_LARGE');}parts.push(value);}}
  finally{reader.releaseLock();}
  return Buffer.concat(parts,size).toString('utf8');
}
function startServer(){
const active=new Map(),cancelled=new Set();let buffer='',queue=Promise.resolve(),ended=false,pending=0;
const send=message=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...message})+'\n');
async function receive(message){const {id,method,params={}}=message;if(method==='notifications/cancelled'){if(active.has(params.requestId))active.get(params.requestId).abort();else if(cancelled.size<128)cancelled.add(params.requestId);return;}if(id===undefined)return;if(ended||cancelled.delete(id)){send({id,error:{code:-32800,message:'Request cancelled before execution'}});return;}
  try{let result;
    if(method==='initialize')result={protocolVersion:params.protocolVersion||'2025-03-26',capabilities:{tools:{listChanged:false}},serverInfo:{name:'DSHA Android Computer Use',version:'0.1.0'},instructions:'Use a fresh Android observation before actions and verify afterwards. Respect native permission and confirmation results. Never replay an action whose outcome is unknown.'};
    else if(method==='ping')result={};
    else if(method==='tools/list')result={tools};
    else if(method==='tools/call'){const controller=new AbortController();active.set(id,controller);try{result=await call(params.name,params.arguments||{},controller.signal);}finally{active.delete(id);}}
    else{send({id,error:{code:-32601,message:'Method not found'}});return;}
    send({id,result});
  }catch(error){send({id,result:{content:[{type:'text',text:'Android operation unavailable: '+String(error.code||(/^[A-Z][A-Za-z0-9_]{0,60}$/.test(error.message)?error.message:'OPERATION_UNAVAILABLE'))+'. Check DSHA device permissions. If execution may have started, verify the screen before retrying.'}],isError:true}});}
}
process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{buffer+=chunk;if(buffer.length>1024*1024){process.stderr.write('MCP_INPUT_LIMIT\n');process.exit(1);}let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;let value;try{value=JSON.parse(line);}catch{send({id:null,error:{code:-32700,message:'Parse error'}});continue;}if(value.method==='notifications/cancelled')void receive(value);else if(pending>=32)send({id:value.id??null,error:{code:-32000,message:'Request queue full'}});else{pending++;queue=queue.then(()=>receive(value)).finally(()=>pending--);}}});
process.stdin.on('end',()=>{ended=true;for(const controller of active.values())controller.abort();});
process.stdout.on('error',error=>{if(error.code==='EPIPE')process.exit(0);});
}
if(require.main===module)startServer();
module.exports={validate,definitions,tools,bridgeFailed,screenshotContent,readBridgeResponse,call,bridgePort,bridgeTokenPath};
