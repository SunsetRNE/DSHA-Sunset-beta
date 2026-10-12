;(function(root){
  'use strict';
  // Same narrow migration for Node preload and an early browser script. No source/origin trust is inferred.
  //
  // 允许注入 X-Token 的端口是「每次请求」解析出来的本实例端口，与 .bridge_token 同数据根：
  //   Node preload    → 读 <DSH_HOME>/.bridge_port（原生原子写入的 ASCII 十进制端口 + 换行）
  //   正式 App 页      → 调 App 注入的 globalThis.__DSHA_BRIDGE_PORT_PROVIDER__() 现取（新 App）
  //   老 App / 外部页  → 只认静态 globalThis.__DSHA_BRIDGE_PORT__；连这一行都没有才是旧默认 3090
  // 契约文件必须是 ≤16 字节的普通文件（同原生 BridgeEndpointFiles.READ_LIMIT），只用 ASCII
  // 十进制 + 一个可选换行；只有真正「目录里没有这一项」才回退旧默认 3090。文件存在但为 0、
  // 坏格式、越界、非 ASCII、带垃圾尾巴、是目录/符号链接（含悬空链接）或读不动都算
  // 「端点未知」：一律 fail-closed，绝不改试别的回环端口 —— 同机可能有第二个 DSHA 实例，
  // 猜一个端口就等于把本实例 token 交给它的桥。
  //
  // 新 App 页同时声明 __DSHA_BRIDGE_PORT_REQUIRED__=true 并提供 provider：任何带 token 的
  // 已知桥请求在发出前先 await provider，拿到「本次请求自己的」当前端口（1..65535）才改写；
  // 拿不到（未就绪 / 0 / 坏值 / reject / 超时）就拒绝本次 fetch，绝不回退静态值和 3090。
  // 端口只作为本请求的局部参数传给改写函数，不写全局 —— 并发请求之间不会串台。
  var LEGACY_PORT=3090;
  var PORT_FILE='.bridge_port';
  var PORT_FILE_LIMIT=16;
  // 本实例桥的候选端口范围：原生在 3090–3109 里挑一个空闲端口。
  var BRIDGE_PORT_MIN=3090;
  var BRIDGE_PORT_MAX=3109;
  // 已知桥路由：候选端口 3090–3109 上的这些路径才算「桥请求」。
  // 范围外的回环请求（例如 3080 的 dsh Web 鉴权 ?token= 或 3081 的 LAN 鉴权）原样放行。
  var BRIDGE_ROUTE=/^\/(?:app\/|device\/|exec(?:$|\/))/;
  // provider 等待上限：provider 自身也应超时，这里只做兜底，避免 fetch 无限挂起。
  var PROVIDER_WAIT_MS=2000;
  var warnedPortUnavailable=false;
  function isRequest(input){return typeof root.Request==='function'&&input instanceof root.Request;}
  function portValue(raw){
    if(typeof raw==='number'&&Number.isInteger(raw))return raw;
    if(typeof raw==='string'&&/^[0-9]{1,5}$/.test(raw))return Number(raw);
    return null;
  }
  // 只接受契约的完整内容：ASCII 十进制 + 可选单个换行。不做 Unicode trim
  // （NBSP、行分隔符等都不是 ASCII 空白，不能被当成合法端点）。
  function contractPort(text){
    if(typeof text!=='string'||!/^[0-9]{1,5}\n?$/.test(text))return null;
    return portValue(text.slice(0,text.length-(text.charAt(text.length-1)==='\n'?1:0)));
  }
  // 返回端口号 / null（文件存在但不可用）/ undefined（这一层不存在：浏览器或没有 require）。
  function filePort(){
    if(typeof require!=='function'||typeof process==='undefined'||!process.env)return undefined;
    var fs,path;
    try{fs=require('node:fs');path=require('node:path');}catch(ignored){return undefined;}
    var home=process.env.DSH_HOME;
    home=typeof home==='string'&&home.trim()&&home.trim()!=='/'?home.trim().replace(/\/+$/,''):'/root/.dsh';
    var file=path.join(home,PORT_FILE),entry;
    try{entry=fs.lstatSync(file);}
    // 只有真正缺目录项才是旧回退；悬空符号链接的 ENOENT 也有目录项，必须 fail-closed。
    catch(error){return error&&error.code==='ENOENT'?LEGACY_PORT:null;}
    if(!entry.isFile())return null;
    var descriptor;
    try{
      var flags=(fs.constants&&fs.constants.O_RDONLY||0)|((fs.constants&&fs.constants.O_NOFOLLOW)||0);
      descriptor=fs.openSync(file,flags);
      var stats=fs.fstatSync(descriptor);
      if(!stats.isFile()||stats.size<1||stats.size>PORT_FILE_LIMIT)return null;
      var buffer=Buffer.alloc(stats.size),filled=0,count;
      while(filled<stats.size&&(count=fs.readSync(descriptor,buffer,filled,stats.size-filled,filled))>0)filled+=count;
      if(filled!==stats.size)return null;
      return contractPort(buffer.toString('utf8'));
    }catch(ignored){return null;}
    finally{try{if(descriptor!==undefined)fs.closeSync(descriptor);}catch(ignored){}}
  }
  // 浏览器上下文旧来源：App 没注入 provider 时的静态快照。注入过但读到 null / getter 抛错 /
  // 值非法 = 配置坏了，fail-closed（不注入），也不退回 3090。
  function browserPort(){
    var raw;
    try{raw=root.__DSHA_BRIDGE_PORT__;}catch(ignored){return null;}
    if(raw===undefined)return LEGACY_PORT;
    return portValue(raw);
  }
  // 非 provider 路径的端口：Node 先读 .bridge_port，其次浏览器静态快照。
  function allowedPort(){
    var port=filePort();
    if(port===undefined)port=browserPort();
    if(port!==null&&port>=1&&port<=65535)return port;
    if(!warnedPortUnavailable){
      warnedPortUnavailable=true;
      try{if(typeof console!=='undefined'&&console&&typeof console.warn==='function')console.warn('[dsha-bridge] bridge endpoint unavailable; X-Token injection disabled (no fallback port)');}catch(ignored){}
    }
    return null;
  }
  function unavailable(){return Error('BRIDGE_ENDPOINT_UNAVAILABLE');}
  // 新 App 页显式声明「端口必须现取」。老浏览器 / 老 App 没有这一行，保持旧兼容。
  function providerRequired(){
    try{return root.__DSHA_BRIDGE_PORT_REQUIRED__===true;}catch(ignored){return true;}
  }
  // 每次请求都调一次 provider，返回 Promise<port>；任何拿不到 1..65535 的情况都 reject。
  // 不缓存、不写全局；调用失败 / reject / 超时都算端点未知。
  function providerPort(){
    var provider;
    try{provider=root.__DSHA_BRIDGE_PORT_PROVIDER__;}catch(ignored){provider=null;}
    if(typeof provider!=='function'||typeof root.Promise!=='function')return null;
    var result;
    try{result=provider();}catch(ignored){return root.Promise.reject(unavailable());}
    return new root.Promise(function(resolve,reject){
      var settled=false;
      var timer=root.setTimeout(function(){finish(reject,unavailable());},PROVIDER_WAIT_MS);
      function finish(action,value){
        if(settled)return;
        settled=true;
        try{root.clearTimeout(timer);}catch(ignored){}
        action(value);
      }
      root.Promise.resolve(result).then(function(value){
        var port=portValue(value);
        if(port===null||port<1||port>65535)finish(reject,unavailable());else finish(resolve,port);
      },function(){finish(reject,unavailable());});
    });
  }
  function isLoopback(url){return url.hostname==='127.0.0.1'||url.hostname==='[::1]';}
  // 与端口无关的同步判定：只回答「这是不是一个可能由本 shim 负责的带 token GET 请求」。
  function classify(input,init){
    if(typeof root.URL!=='function'||typeof root.Headers!=='function')return null;
    var url;try{url=new root.URL(isRequest(input)?input.url:String(input));}catch(ignored){return null;}
    if(url.protocol!=='http:'||!isLoopback(url)||url.username||url.password)return null;
    if(!url.searchParams||typeof url.searchParams.forEach!=='function')return null;
    var entries=[];url.searchParams.forEach(function(value,name){if(name.toLowerCase()==='token')entries.push([name,value]);});if(!entries.length)return null;
    var method=String(init&&init.method!==undefined?init.method:isRequest(input)?input.method:'GET').toUpperCase();if(method!=='GET')return null;
    var numeric=Number(url.port)||0;
    var pathname=url.pathname,decoded=pathname;
    try{if(typeof decodeURIComponent==='function')decoded=decodeURIComponent(pathname);}catch(ignored){}
    var bridgeRoute=BRIDGE_ROUTE.test(pathname)||decoded!==pathname&&BRIDGE_ROUTE.test(decoded);
    return {url:url,entries:entries,numeric:numeric,bridgeRoute:bridgeRoute};
  }
  // 返回改写结果 / null（不是本 shim 负责的请求，原样放行）/ 抛错（已知桥请求但端点不可用
  // 或不匹配：拒绝本次 fetch，绝不把带 token 的 URL 交给别的端口，也不换端口重放）。
  // port 是显式传入的「本请求端口」：number 或 null（端点未知，fail-closed）。
  function rewriteWithPort(input,init,port){
    var request=classify(input,init);
    if(request===null)return null;
    var numeric=request.numeric;
    var addressedToUs=typeof port==='number'&&numeric===port;
    var inRange=numeric>=BRIDGE_PORT_MIN&&numeric<=BRIDGE_PORT_MAX;
    // 只有「打到本实例端口」或「候选端口上的桥路由」才算已知桥请求；范围外的回环请求
    // （3080 的 dsh Web 鉴权、3081 的 LAN 鉴权等）一个字节都不碰，维持原语义。
    if(!addressedToUs&&!(inRange&&request.bridgeRoute))return null;
    // 已知桥请求但端点不可用（读不到 / 为 0 / 坏文件 / provider 未就绪）或不是本实例端口：
    // 拒绝本次 fetch，绝不把带 token 的 URL 交给别的端口，也不扫描端口或重放。
    if(!addressedToUs)throw unavailable();
    var entries=request.entries;
    if(entries.length!==1||!entries[0][1]||!/^[A-Za-z0-9_-]{1,128}$/.test(entries[0][1]))throw Error('BRIDGE_CREDENTIAL_INVALID');
    var token=entries[0][1],headers=new root.Headers(init&&Object.prototype.hasOwnProperty.call(init,'headers')?init.headers:isRequest(input)?input.headers:undefined);
    if(headers.has('X-Token')&&headers.get('X-Token')!==token)throw Error('BRIDGE_CREDENTIAL_CONFLICT');
    headers.set('X-Token',token);request.url.searchParams.delete(entries[0][0]);
    var next={};if(init)Object.keys(init).forEach(function(key){next[key]=init[key];});next.headers=headers;
    return {input:isRequest(input)?new root.Request(request.url,input):request.url,init:next};
  }
  // 兼容入口（老 Node 调用 / 单测）：端口按 .bridge_port → 静态快照 → 旧默认解析。
  function rewrite(input,init){return rewriteWithPort(input,init,allowedPort());}
  // 有 token 的候选桥请求：provider 路径要为它现取端口。范围外端口 / 非桥路由不打扰原生通道。
  function candidate(request){
    return request!==null&&request.numeric>=BRIDGE_PORT_MIN&&request.numeric<=BRIDGE_PORT_MAX&&request.bridgeRoute;
  }
  if(typeof root.fetch==='function'&&!root.__dshaBridgeTokenCompatV1){
    var original=root.fetch;
    root.fetch=function(input,init){
      var self=this;
      try{
        if(providerRequired()){
          var request=classify(input,init);
          if(candidate(request)){
            var pending=providerPort();
            if(pending===null||typeof pending.then!=='function')throw unavailable();
            return pending.then(function(port){
              var changed=rewriteWithPort(input,init,port);
              return changed?original.call(self,changed.input,changed.init):original.call(self,input,init);
            });
          }
          // 非候选（范围外端口 / 非桥路由）：不读 provider、不换端口，只做端点未知时的拒绝判定。
          var untouched=rewriteWithPort(input,init,null);
          return untouched?original.call(self,untouched.input,untouched.init):original.call(self,input,init);
        }
        var changed=rewriteWithPort(input,init,allowedPort());
        return changed?original.call(self,changed.input,changed.init):original.call(self,input,init);
      }catch(error){if(typeof root.Promise==='function')return root.Promise.reject(error);throw error;}
    };
    root.__dshaBridgeTokenCompatV1=true;
  }
  if(typeof module!=='undefined'&&module.exports)module.exports={rewrite:rewrite,allowedPort:allowedPort};
})(typeof globalThis!=='undefined'?globalThis:typeof self!=='undefined'?self:this);
