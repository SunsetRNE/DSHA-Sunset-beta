'use strict';
const fsSync=require('node:fs');
const path=require('node:path');
// 桥端点：端口文件与 .bridge_token 同数据根（DSH_HOME 优先），每次请求重读。
// 契约文件是 ≤16 字节的普通文件（同原生 BridgeEndpointFiles.READ_LIMIT），内容只有 ASCII
// 十进制 + 一个可选换行。只有真正缺目录项才回退旧默认 3090；0、坏格式、越界、非 ASCII、
// 带垃圾尾巴、目录、符号链接（含悬空链接）或读不动一律 fail-closed。
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
 ['android_vscreen_create','创建或复用虚拟屏。',{orientation:{type:'string',enum:['portrait','landscape']}}],
 ['android_vscreen_status','读取显示尺寸、方向、通道和最新 frameSeq。',{}],
 ['android_vscreen_launch','把已安装应用启动到虚拟屏。',{package:{type:'string',maxLength:160}}],
 ['android_vscreen_see','读取最新画面；成功后用 generation/frameSeq 驱动下一步。',{}],
 ['android_vscreen_tap','单次点击。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},x:{type:'number',minimum:0},y:{type:'number',minimum:0}}],
 ['android_vscreen_swipe','连续滑动；手势会在虚拟屏上实时注入。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},x1:{type:'number',minimum:0},y1:{type:'number',minimum:0},x2:{type:'number',minimum:0},y2:{type:'number',minimum:0},ms:{type:'integer',minimum:50,maximum:3000}}],
 ['android_vscreen_touch','发送一个触控阶段：0 按下、2 移动、1 抬起、3 取消。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},stroke:{type:'string',pattern:'^[a-f0-9-]{16,64}$'},action:{type:'integer',enum:[0,1,2,3]},x:{type:'number',minimum:0},y:{type:'number',minimum:0}}],
 ['android_vscreen_type','通过虚拟屏输入目标输入文字；中文走无障碍。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},text:{type:'string',maxLength:2000}}],
 ['android_vscreen_key','发送返回、主页、确认或退格键。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},keycode:{type:'integer',enum:[3,4,66,67]}}],
 ['android_vscreen_tree','读取虚拟屏无障碍控件树；返回 nodeId 供后续操作。',{}],
 ['android_vscreen_node','通过无障碍点击、滚动、聚焦或设置文本。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},nodeId:{type:'string',maxLength:80},action:{type:'string',enum:['click','long_click','scroll_forward','scroll_backward','focus','set_text']},text:{type:'string',maxLength:16000}}],
 ['android_vscreen_editor','读取或修改虚拟屏当前输入框；op 为 get/edit/submit。',{generation:{type:'string',maxLength:80},op:{type:'string',enum:['get','edit','submit']},editorId:{type:'string',maxLength:160},text:{type:'string',maxLength:16000},start:{type:'integer',minimum:0},end:{type:'integer',minimum:0}}],
 ['android_vscreen_close','关闭并回收虚拟屏。',{}]
];
const required={
 android_vscreen_create:[],android_vscreen_status:[],android_vscreen_launch:['package'],android_vscreen_see:[],
 android_vscreen_tap:['generation','frameSeq','x','y'],android_vscreen_swipe:['generation','frameSeq','x1','y1','x2','y2'],
 android_vscreen_touch:['generation','frameSeq','stroke','action','x','y'],android_vscreen_type:['generation','frameSeq','text'],
 android_vscreen_key:['generation','frameSeq','keycode'],android_vscreen_tree:[],
 android_vscreen_node:['generation','frameSeq','nodeId','action'],android_vscreen_editor:['generation','op'],android_vscreen_close:[]
};
const tools=definitions.map(([name,description,properties])=>{
 const inputSchema={type:'object',properties,required:required[name],additionalProperties:false};
 if(name==='android_vscreen_node')inputSchema.allOf=[{if:{properties:{action:{const:'set_text'}},required:['action']},then:{required:['text']}}];
 if(name==='android_vscreen_editor')inputSchema.allOf=[
  {if:{properties:{op:{const:'edit'}},required:['op']},then:{required:['editorId','text']}},
  {if:{properties:{op:{const:'submit'}},required:['op']},then:{required:['editorId']}}
 ];
 return{name,description,inputSchema};
});
function validate(def,args){
 if(!args||typeof args!=='object'||Array.isArray(args))throw Error('INVALID_ARGUMENTS');
 const fields=def[2],name=def[0];if(Object.keys(args).some(k=>!Object.hasOwn(fields,k)))throw Error('UNKNOWN_ARGUMENT');
 const needed=[...required[name]];
 if(name==='android_vscreen_node'&&args.action==='set_text')needed.push('text');
 if(name==='android_vscreen_editor'&&args.op!=='get')needed.push('editorId');
 if(name==='android_vscreen_editor'&&args.op==='edit')needed.push('text');
 for(const key of needed)if(!Object.hasOwn(args,key))throw Error('INVALID_'+key);
 for(const [key,rule]of Object.entries(fields)){
  if(!Object.hasOwn(args,key))continue;const v=args[key];
  if(rule.enum&&!rule.enum.includes(v))throw Error('INVALID_'+key);
  if(rule.type==='integer'&&(!Number.isInteger(v)||(rule.minimum!==undefined&&v<rule.minimum)||(rule.maximum!==undefined&&v>rule.maximum)))throw Error('INVALID_'+key);
  if(rule.type==='number'&&(typeof v!=='number'||!Number.isFinite(v)||v<rule.minimum))throw Error('INVALID_'+key);
  if(rule.type==='string'&&(typeof v!=='string'||(rule.maxLength!==undefined&&v.length>rule.maxLength)))throw Error('INVALID_'+key);
  if(rule.pattern&&typeof v==='string'&&!new RegExp(rule.pattern).test(v))throw Error('INVALID_'+key);
 }
 for(const key of ['generation','package','nodeId'])if(needed.includes(key)&&!args[key])throw Error('INVALID_'+key);
 if(name==='android_vscreen_editor'&&args.op!=='get'&&!args.editorId)throw Error('INVALID_editorId');
 if(name==='android_vscreen_editor'&&args.op==='edit')for(const key of ['start','end'])if(args[key]!==undefined&&args[key]>args.text.length)throw Error('INVALID_'+key);
}
function bridgeFailed(value){return !value||value.ok!==true;}
function requestFor(name,args,port){
 const def=definitions.find(x=>x[0]===name);if(!def)throw Error('UNKNOWN_TOOL');validate(def,args);
 let route={android_vscreen_create:'create',android_vscreen_status:'status',android_vscreen_launch:'launch',android_vscreen_see:'see',android_vscreen_tap:'tap',android_vscreen_swipe:'swipe',android_vscreen_touch:'touch',android_vscreen_type:'type',android_vscreen_key:'key',android_vscreen_tree:'tree',android_vscreen_node:'node',android_vscreen_close:'close'}[name];
 const parameters={...args};
 if(name==='android_vscreen_editor'){route={get:'editor',edit:'edit',submit:'submit'}[args.op];delete parameters.op;}
 const url='http://127.0.0.1:'+(port===undefined?bridgePort():port)+'/app/vscreen/'+route;
 if(['edit','submit','node','type'].includes(route)){
  const body=JSON.stringify(parameters);if(Buffer.byteLength(body,'utf8')>128*1024)throw Error('BRIDGE_BODY_LIMIT');
  return{url,method:'POST',body,headers:{'Content-Type':'application/json; charset=utf-8'}};
 }
 const query=new URLSearchParams();for(const[k,v]of Object.entries(parameters))query.set(k,String(v));
 return{url:url+(query.size?'?'+query:''),method:'GET',headers:{}};
}
function decodeBridge(raw){
 let value;try{value=JSON.parse(raw);}catch{throw Error('INVALID_BRIDGE_RESPONSE');}
 if(value&&typeof value.result==='string'){
  try{value=JSON.parse(value.result);}catch{return{ok:false,error:'BRIDGE_ERROR',detail:value.result};}
 }
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('INVALID_BRIDGE_RESPONSE');return value;
}
async function call(name,args,signal){
 const port=bridgePort();const request=requestFor(name,args,port);const token=(await require('node:fs/promises').readFile(bridgeTokenPath(),'utf8')).trim();
 const response=await fetch(request.url,{...request,signal,headers:{...request.headers,'X-Token':token}});
 const value=decodeBridge(await response.text());const{previewB64,...metadata}=value;const content=[{type:'text',text:JSON.stringify(metadata)}];
 if(name==='android_vscreen_see'&&previewB64)content.push({type:'image',mimeType:'image/jpeg',data:previewB64});
 return{content,isError:!response.ok||bridgeFailed(value)};
}
function start(){const active=new Map(),cancelled=new Set();let buffer='',queue=Promise.resolve(),ended=false,pending=0;const send=m=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...m})+'\n');async function receive(m){const{id,method,params={}}=m;if(method==='notifications/cancelled'){if(active.has(params.requestId))active.get(params.requestId).abort();else cancelled.add(params.requestId);return;}if(id===undefined)return;if(ended||cancelled.delete(id)){send({id,error:{code:-32800,message:'Request cancelled'}});return;}try{let result;if(method==='initialize')result={protocolVersion:params.protocolVersion||'2025-03-26',capabilities:{tools:{listChanged:false}},serverInfo:{name:'DSHA Virtual Screen',version:'0.2.0'},instructions:'Observe before input. Use logical display coordinates. Touch supports down/move/up. Use accessibility tree/editor for app controls and Unicode text. Reobserve after every action; never replay an unknown result.'};else if(method==='ping')result={};else if(method==='tools/list')result={tools};else if(method==='tools/call'){const ac=new AbortController();active.set(id,ac);try{result=await call(params.name,params.arguments||{},ac.signal);}finally{active.delete(id);}}else{send({id,error:{code:-32601,message:'Method not found'}});return;}send({id,result});}catch(error){send({id,result:{content:[{type:'text',text:'Virtual screen unavailable: '+(error.message||'OPERATION_UNAVAILABLE')}],isError:true}});}}
process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{buffer+=chunk;if(buffer.length>1024*1024)process.exit(1);let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;let value;try{value=JSON.parse(line);}catch{send({id:null,error:{code:-32700,message:'Parse error'}});continue;}if(pending>=32){send({id:value.id??null,error:{code:-32000,message:'Request queue full'}});continue;}pending++;queue=queue.then(()=>receive(value)).finally(()=>pending--);}});process.stdin.on('end',()=>{ended=true;for(const ac of active.values())ac.abort();});process.stdout.on('error',e=>{if(e.code==='EPIPE')process.exit(0);});}
if(require.main===module)start();module.exports={definitions,tools,validate,call,requestFor,decodeBridge,bridgeFailed,bridgePort,bridgeTokenPath};

