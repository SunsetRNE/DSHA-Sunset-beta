/*#dsha-bridge-port-provider:start*/
/* 桥端口 provider：正式 App 页专用（Standard WebView 与 Low/Gecko 页面世界共用同一份脚本）。
   每次带 token 的已知桥请求由 compat shim 调一次 provider，现取当前 activePort()：
     Standard → WebView 注入的 DshaBridgePort 对象（WebMessageListener，原生按来源/主框架/页校验）
     Low/Gecko → startup-relay.js 内容脚本转发的 window.postMessage（原生同样按页面代次校验）
   拿不到（未就绪 / 0 / 超限 / 超时 / 页面已卸载）一律 reject，绝不回退 3090；结果只归本次请求。
   这不是通用设备接口：通道只回一个端口数字，不带 token、不带文件路径；应急页与外部页不装。 */
(function () {
  if (typeof window === 'undefined' || typeof Promise !== 'function') return;
  // 应急 HTML 也复用后半段启动观察；仅正式页面的原生/Gecko prologue 声明此能力。
  if (window.top !== window || window.__DSHA_BRIDGE_PORT_REQUIRED__ !== true) return;
  if (typeof window.__DSHA_BRIDGE_PORT_PROVIDER__ === 'function') return;
  var PENDING_LIMIT = 8, DEFAULT_TIMEOUT_MS = 1500, MAX_ID = 2147483000;
  var timeoutMs = (function () {
    try {
      var override = window.__DSHA_BRIDGE_PORT_TIMEOUT_MS__;
      if (typeof override === 'number' && override >= 50 && override <= 5000) return override;
    } catch (_) {}
    return DEFAULT_TIMEOUT_MS;
  })();
  var dead = false, pending = Object.create(null), pendingCount = 0, nextSequence = randomBase();
  function randomBase() {
    // 随机起点：新文档的 id 与上一文档错开，迟到的旧响应不会命中新页的 pending。
    try {
      if (window.crypto && typeof window.crypto.getRandomValues === 'function') {
        var buffer = new Uint32Array(1); window.crypto.getRandomValues(buffer);
        return (buffer[0] % MAX_ID) + 1;
      }
    } catch (_) {}
    return Math.floor(Math.random() * MAX_ID) + 1;
  }
  function nextId() { nextSequence++; if (nextSequence > MAX_ID) nextSequence = randomBase(); return nextSequence; }
  function clear(id) {
    var entry = pending[id];
    if (!entry) return null;
    delete pending[id]; pendingCount--;
    try { clearTimeout(entry.timer); } catch (_) {}
    return entry;
  }
  function settle(id, port) { var entry = clear(id); if (entry) entry.resolve(port); }
  function fail(id) { var entry = clear(id); if (entry) entry.reject(Error('BRIDGE_ENDPOINT_UNAVAILABLE')); }
  // 原生读通道对象可能晚于本脚本注入：每次请求时都确保 onmessage 已挂上。
  function bindHost(host) {
    if (host && !host.__dshaBridgePortBound) {
      host.onmessage = function (event) { onReply(event && event.data); };
      host.__dshaBridgePortBound = true;
    }
  }
  function onReply(data) {
    if (dead) return;
    // 两条通道都回 JSON 文本；字符串解析失败即丢弃（请求方向是纯十进制文本，不会命中这里）。
    if (typeof data === 'string') { try { data = JSON.parse(data); } catch (_) { return; } }
    if (!data || typeof data !== 'object') return;
    var id = data.__dshaBridgePortReply;
    if (typeof id !== 'number' || !isFinite(id) || Math.floor(id) !== id || id < 1 || id > MAX_ID) return;
    if (!pending[id]) return;
    // 端口原样交给 shim 校验 1..65535：这里的 0 / 非法值同样按端点未知处理。
    settle(id, data.port);
  }
  function request() {
    return new Promise(function (resolve, reject) {
      if (dead || pendingCount >= PENDING_LIMIT) { reject(Error('BRIDGE_ENDPOINT_UNAVAILABLE')); return; }
      var id = nextId();
      var timer = setTimeout(function () { fail(id); }, timeoutMs);
      pending[id] = {resolve: resolve, reject: reject, timer: timer}; pendingCount++;
      var host = null;
      try { host = window.DshaBridgePort; } catch (_) { host = null; }
      if (host && typeof host.postMessage === 'function') {
        bindHost(host);
        try { host.postMessage(String(id)); return; } catch (_) { fail(id); return; }
      }
      // Low/Gecko：内容脚本（startup-relay.js）只答这一种请求，native 再按页面校验。
      try {
        window.postMessage(String(id), window.location && window.location.origin ? window.location.origin : '*');
      } catch (_) { fail(id); }
    });
  }
  // pagehide：旧页尚未回来的响应全部作废，之后请求也直接拒绝（迟到响应不跨页生效）。
  function release() {
    dead = true;
    var ids = Object.keys(pending);
    for (var index = 0; index < ids.length; index++) fail(Number(ids[index]));
  }
  try {
    bindHost(window.DshaBridgePort);
    window.addEventListener('message', function (event) {
      if (event && event.origin && window.location && event.origin !== window.location.origin) return;
      onReply(event && event.data);
    });
    window.addEventListener('pagehide', release);
  } catch (_) {}
  // REQUIRED 给 shim 看：端口必须现取，拿不到就拒绝本次桥请求；老 App/外部页没有这一行。
  window.__DSHA_BRIDGE_PORT_REQUIRED__ = true;
  window.__DSHA_BRIDGE_PORT_PROVIDER__ = request;
})();
/*#dsha-bridge-port-provider:end*/
/* 页面启动观察：模块实际加载/应用错误和官方启动失败屏；不吞异常、不改插件开关。 */
(function () {
  'use strict';
  if (window.top !== window) return;
  var binding=window.__DSHA_PAGE_BINDING__||{};
  if(window.__dshaStartupObserved&&window.__dshaStartupObservationNonce===String(binding.nonce||''))return;
  window.__dshaStartupObserved = true;
  window.__dshaStartupObservationNonce=String(binding.nonce||'');
  var documentId=String(Date.now())+'-'+Math.random().toString(36).slice(2);
  window.__dshaStartupDocumentId=documentId;
  var ready = false, seenBoot = false, lastFailure = '', count = 0;
  // dsh-client 可在页面重渲染时再次调用同一个插件的 apply。成功结果
  // 对启动诊断没有新增信息；只记录首次成功，错误/下一次错误前的加载仍保留。
  var reportedActive = Object.create(null), reportedLoading = Object.create(null);
  function uiText(zh, en) { return window.__DSHA_LANGUAGE__ === 'en' ? en : zh; }
  function report(type, id, message, fatal) {
    if (count++ > 500) return;
    var text = JSON.stringify({type:type, id:String(id || '').slice(0,214), message:String(message || '').slice(0,6000), fatal:!!fatal && !ready,
      nonce:String(binding.nonce||''),documentId:documentId,sequence:count-1,page:location.origin+location.pathname});
    console.info('[DSHA_PAGE] ' + text);
    window.dispatchEvent(new CustomEvent('dsha-startup', {detail:text}));
  }
  function reportPlugin(type, id, message, fatal) {
    var key = String(id || '');
    if (type === 'loading') {
      if (reportedActive[key] || reportedLoading[key]) return;
      reportedLoading[key] = true;
    } else if (type === 'active') {
      reportedLoading[key] = false;
      if (reportedActive[key]) return;
      reportedActive[key] = true;
    } else if (type === 'issue') {
      reportedLoading[key] = false;
      reportedActive[key] = false;
    }
    report(type, key, message, fatal);
  }
  function detail(error) { return error && (error.stack || error.message) || String(error); }
  function wrapExports(value, id) {
    if (!value || typeof value.apply !== 'function') return value;
    var descriptor = Object.getOwnPropertyDescriptor(value, 'apply');
    if (!descriptor || !descriptor.writable) return value;
    var original = value.apply;
    value.apply = function () {
      reportPlugin('loading', id, uiText('正在初始化网页插件：', 'Initializing web plugin: ') + id);
      try {
        var result = original.apply(this, arguments);
        if (result && typeof result.then === 'function') return result.then(function (v) {
          reportPlugin('active', id, uiText('网页插件初始化返回：', 'Web plugin initialization returned: ') + id); return v;
        }, function (error) { reportPlugin('issue', id, detail(error)); throw error; });
        reportPlugin('active', id, uiText('网页插件初始化返回：', 'Web plugin initialization returned: ') + id); return result;
      } catch (error) { reportPlugin('issue', id, detail(error)); throw error; }
    };
    return value;
  }
  function wrap(loader) {
    if (!loader || loader.__dshaObserved || typeof loader.load !== 'function') return loader;
    try {
      var original = loader.load;
      loader.load = function (definition) {
        if (!definition || typeof definition.factory !== 'function') return original.apply(this, arguments);
        var args = Array.prototype.slice.call(arguments), factory = definition.factory, id = definition.id;
        args[0] = Object.assign({}, definition, {factory:function () {
          try { return wrapExports(factory.apply(this, arguments), id); }
          catch (error) { report('issue', id, detail(error)); throw error; }
        }});
        return original.apply(this, args);
      };
      Object.defineProperty(loader, '__dshaObserved', {value:true});
    } catch (_) { /* 观察不可用时保持原加载行为。 */ }
    return loader;
  }
  try {
    var descriptor = Object.getOwnPropertyDescriptor(window, '__ModuleLoader__');
    if (!descriptor) {
      var facade;
      Object.defineProperty(window, '__ModuleLoader__', {configurable:true, enumerable:true,
        get:function () { return facade; }, set:function (v) { facade = wrap(v); }});
    } else wrap(window.__ModuleLoader__);
  } catch (_) {}
  window.addEventListener('error', function (event) {
    if (event.error || event.message) report('issue', '', detail(event.error || event.message) + '\n' + (event.filename || ''));
  });
  window.addEventListener('unhandledrejection', function (event) { report('issue', '', detail(event.reason)); });
  function inspect() {
    wrap(window.__ModuleLoader__);
    var boot = document.querySelector('[data-dsh-boot]');
    if (boot) {
      seenBoot = true;
      var text = boot.textContent || '';
      if (/Failed to load plugins|did not activate/.test(text) && text !== lastFailure) {
        lastFailure = text; report('issue', '', text, true);
      }
    }
    var root = document.getElementById('root');
    if (document.querySelector('[data-composer-input]') || (seenBoot && !boot && root && root.children.length)) {
      ready = true; report('ready', '', uiText('网页已就绪', 'Web page ready')); observer.disconnect();
    }
  }
  var observer = new MutationObserver(inspect);
  observer.observe(document, {childList:true, subtree:true, characterData:true});
  // Old WebView has no document-start hook: inspect a failure/ready screen
  // that already exists when the fallback observer is injected.
  inspect();
})();
