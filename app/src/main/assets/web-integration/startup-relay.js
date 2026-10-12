// Gecko 页面世界报告只传入现有同源只读诊断通道。
if (window.top === window) {
  window.__dshaStartupPort = browser.runtime.connectNative('dsha');
  let observationNonce='',pendingStartup=[];
  // 桥端口转发：页面世界（startup.js 的 provider）每次已知桥请求都要一次当前 activePort()。
  // 只转发纯十进制请求 id、只回一个端口数字；native 侧还会按 sameService / 主框架 / 页面代次
  // 再校验。pending 有界（8）且 1.5s 超时，native 不回就当没这回事，绝不猜端口。
  const bridgePortPending=new Map(),BRIDGE_PORT_LIMIT=8,BRIDGE_PORT_TIMEOUT=1500;
  const dropBridgePort=id=>{
    const timer=bridgePortPending.get(id);
    if(timer===undefined)return false;
    bridgePortPending.delete(id);clearTimeout(timer);return true;
  };
  const sendStartup=report=>{
    report.nonce=observationNonce;report.page=location.origin+location.pathname;
    window.__dshaStartupPort.postMessage({type:'startup',report});
  };
  window.__dshaStartupPort.onMessage.addListener(message => {
    if (message?.type === 'bridge-port-reply') {
      // 迟到/重复/越界回包一律丢弃；只有仍挂在 pending 里的 id 才回页面世界。
      const id=message.id;
      if(typeof id==='number'&&Number.isInteger(id)&&id>0&&id<=2147483000&&dropBridgePort(id)
          &&typeof message.port==='number'&&Number.isInteger(message.port)&&message.port>=0&&message.port<=65535){
        window.postMessage(JSON.stringify({__dshaBridgePortReply:id,port:message.port}),location.origin);
      }
      return;
    }
    if (message?.type === 'browser-resume') {
      const host = document.head || document.documentElement;
      if (host) {
        const script = document.createElement('script');
        script.textContent = "window.dispatchEvent(new Event('dsha-browser-resume'));";
        host.appendChild(script); script.remove();
      }
      return;
    }
    if (message?.type !== 'language' || !['zh','en'].includes(message.language)) return;
    if(typeof message.observationNonce==='string'&&/^[a-f0-9]{32}$/.test(message.observationNonce)){
      observationNonce=message.observationNonce;const buffered=pendingStartup;pendingStartup=[];
      buffered.forEach(sendStartup);
    }
    const inject = () => {
      const host = document.head || document.documentElement;
      if (!host) return false;
      const script = document.createElement('script');
      script.textContent = "window.__DSHA_LANGUAGE__=" + JSON.stringify(message.language) + ";window.dispatchEvent(new CustomEvent('dsha-language'));";
      host.appendChild(script);script.remove();return true;
    };
    if (!inject()) {
      const observer = new MutationObserver(() => { if (inject()) observer.disconnect(); });
      observer.observe(document, {childList:true,subtree:true});
    }
  });
  window.addEventListener('dsha-language-selected', event => {
    if (event.detail === 'zh' || event.detail === 'en') window.__dshaStartupPort.postMessage({type:'language-selected',language:event.detail});
  });
  window.addEventListener('dsha-startup', event => {
    if (typeof event.detail !== 'string' || event.detail.length > 9000) return;
    try { const report=JSON.parse(event.detail);if(observationNonce)sendStartup(report);else if(pendingStartup.length<501)pendingStartup.push(report); } catch (_) {}
  });
  window.addEventListener('message', event => {
    // 请求方向只认同源、纯十进制文本（provider 发的是 String(id)）；其它 window 消息不碰。
    if (event?.origin !== location.origin) return;
    if (typeof event.data !== 'string' || !/^[1-9][0-9]{0,9}$/.test(event.data)) return;
    const id = Number(event.data);
    if (bridgePortPending.size >= BRIDGE_PORT_LIMIT || bridgePortPending.has(id)) return;
    bridgePortPending.set(id, setTimeout(() => bridgePortPending.delete(id), BRIDGE_PORT_TIMEOUT));
    try { window.__dshaStartupPort.postMessage({type:'bridge-port-request',id}); }
    catch (_) { dropBridgePort(id); }
  });
}
