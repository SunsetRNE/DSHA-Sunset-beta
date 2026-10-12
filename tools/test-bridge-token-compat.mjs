// bridge-token-compat.cjs 的独立回归：只测仓库里的这一份字节，不发真实网络请求、不用真实凭据。
//
// 跑法：NODE_OPTIONS= node tools/test-bridge-token-compat.mjs
//   —— 必须清空 NODE_OPTIONS（本机会预载一份部署 shim）并清掉 __dshaBridgeTokenCompatV1，
//      否则测到的是部署副本而不是仓库字节。
//
// 不变的契约：
//   * .bridge_port 是 ≤16 字节的普通文件，内容只有 ASCII 十进制 + 一个可选换行；只有真正
//     缺目录项才回退旧默认 3090。0 / 坏格式 / 越界 / 非 ASCII / 带垃圾尾巴 / 目录 / 符号链接
//     （含悬空链接）都是「端点未知」。
//   * 已知桥请求（候选端口 3090–3109 上的 /app、/device、/exec 路由，或本实例端口）在端点
//     不可用或端口不匹配时 Promise reject，底层 fetch 零调用；token 不出现在 URL / 日志 / 异常。
//   * 新 App 页声明 __DSHA_BRIDGE_PORT_REQUIRED__ 并提供 __DSHA_BRIDGE_PORT_PROVIDER__ 后，
//     每个带 token 的候选桥请求都要现取一次端口：现取结果只作为本请求局部参数（并发不串台），
//     0 / 坏值 / reject / 超时 / 缺失一律拒绝且零 fetch，绝不回退静态值或 3090；
//     老浏览器（没有 REQUIRED）保持静态注入 / 3090 旧语义。
//   * provider 与 Gecko 内容脚本用仓库真实字节在 vm 里验证：就绪前、每次 fresh、桥 3091→0→3092、
//     超时、并发乱序、pending 有界 8、pagehide 释放、迟到回包不生效。
//   * 范围外的外部请求（https、非回环主机、3080 dsh Web 鉴权、3081 LAN 鉴权、POST）原样放行。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import vm from 'node:vm';
import {createRequire} from 'node:module';
const SHIM = '../app/src/main/assets/bridge-token-compat.cjs';
const SOURCE = fs.readFileSync(new URL(SHIM, import.meta.url), 'utf8');
const STARTUP_SOURCE = fs.readFileSync(new URL('../app/src/main/assets/web-integration/startup.js', import.meta.url), 'utf8');
const RELAY_SOURCE = fs.readFileSync(new URL('../app/src/main/assets/web-integration/startup-relay.js', import.meta.url), 'utf8');
const PROVIDER_START = '/*#dsha-bridge-port-provider:start*/';
const PROVIDER_END = '/*#dsha-bridge-port-provider:end*/';
const providerStart = STARTUP_SOURCE.indexOf(PROVIDER_START), providerEnd = STARTUP_SOURCE.indexOf(PROVIDER_END);
assert.ok(providerStart >= 0 && providerEnd > providerStart, 'startup.js 必须带可提取的 provider 段');
const PROVIDER_SOURCE = STARTUP_SOURCE.slice(providerStart, providerEnd);
const token = 'fixture-review-credential';
const warnings = [];
const originalWarn = console.warn;
console.warn = (...args) => { warnings.push(args.map(String).join(' ')); };

// 计数器只统计「底层 fetch」：wrapper 在端点不可用时必须一个字节都不发。
const sent = [];
globalThis.fetch = async (input, init) => { sent.push({input: String(input), init}); return new Response('OK'); };
// 本地 NODE_OPTIONS 可能预载一份部署副本并置位这个标记；被测的必须是仓库里这一份。
delete globalThis.__dshaBridgeTokenCompatV1;
const {rewrite, allowedPort} = createRequire(import.meta.url)(SHIM);
assert.equal(globalThis.__dshaBridgeTokenCompatV1, true, '仓库 shim 必须在本进程安装（NODE_OPTIONS 要清空）');

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-bridge-token-'));
const portFile = path.join(home, '.bridge_port');
const previousHome = process.env.DSH_HOME;
process.env.DSH_HOME = home;
const setPort = value => fs.writeFileSync(portFile, value);
const removePort = () => { fs.rmSync(portFile, {force: true}); };
const sendCount = () => sent.length;
const lastSent = () => sent[sent.length - 1];
const reset = () => { sent.length = 0; };

function member(archive, name) {
  const raw = zlib.gunzipSync(fs.readFileSync(archive));
  for (let offset = 0; offset + 512 <= raw.length;) {
    const header = raw.subarray(offset, offset + 512), name0 = header.subarray(0, 100).toString().split('\0')[0];
    if (!name0) break;
    const size = parseInt(header.subarray(124, 136).toString().replace(/\0/g, '').trim(), 8);
    if (name0 === name) return raw.subarray(offset + 512, offset + 512 + size).toString();
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  throw Error('MISSING_ARCHIVE_MEMBER');
}

try {
  // ── 0. 真实 marketplace tgz 里的老 consumer：缺文件 = 老 App，仍按旧默认 3090 迁移 ──────
  removePort();
  const peak = member(new URL('../website/src/packages/dsh-peak-chip-4.1.8.tgz', import.meta.url), 'package/lib/index.js');
  const start = peak.indexOf('async function openTopUpPage(ctx) {'), end = peak.indexOf('/**', start + 1);
  assert(start >= 0 && end > start);
  const peakToken = 'fixture-legacy-token-12345678901234567890', peakWarnings = [];
  reset();
  await vm.runInNewContext(
    peak.slice(start, end) + '\nopenTopUpPage(ctx)',
    {readBridgeToken: async () => peakToken, BRIDGE_OPEN_URL: 'http://127.0.0.1:3090/app/open', TOPUP_URL: 'https://fixture.example.invalid/topup',
      TIMEOUT_MS: 5000, AbortSignal, fetch: globalThis.fetch, ctx: {logger: {warn: text => peakWarnings.push(text)}}});
  assert.deepEqual(peakWarnings, []);
  assert.equal(sendCount(), 1);
  const peakSent = new URL(lastSent().input);
  assert.equal(peakSent.searchParams.has('token'), false, 'query 里的 token 必须被搬走');
  assert.equal(peakSent.searchParams.get('url'), 'https://fixture.example.invalid/topup');
  assert.equal(lastSent().init.headers.get('X-Token'), peakToken);

  // ── 1. 选定端口：Request / Headers / AbortSignal / init 原样保留 ─────────────────────
  setPort('3090\n');
  const abort = new AbortController();
  const request = new Request('http://127.0.0.1:3090/app/ui/dump?token=' + token, {headers: {'X-Custom': 'kept'}, signal: abort.signal});
  const rewritten = rewrite(request, {cache: 'no-store'});
  assert(rewritten.input instanceof Request);
  assert.equal(rewritten.input.headers.get('X-Custom'), 'kept');
  assert.equal(rewritten.init.headers.get('X-Custom'), 'kept');
  assert.equal(rewritten.init.cache, 'no-store');
  assert.equal(rewritten.init.headers.get('X-Token'), token);
  abort.abort('fixture-cancel');
  assert.equal(rewritten.input.signal.aborted, true);
  assert.equal(rewritten.input.signal.reason, 'fixture-cancel');
  assert.equal(rewrite('http://[::1]:3090/app/open?token=' + token,
    {headers: new Headers({'X-Token': token, 'Keep': 'yes'}), signal: new AbortController().signal}).init.headers.get('Keep'), 'yes',
  'IPv6 回环语义必须保留');

  // ── 2. 重复 / 非法 / 冲突凭据：拒绝且不发请求 ────────────────────────────────────────
  for (const query of ['token=', 'token=a&token=b', 'token=a&TOKEN=a']) {
    const before = sendCount();
    await assert.rejects(fetch('http://127.0.0.1:3090/app/open?' + query), /BRIDGE_CREDENTIAL_INVALID/);
    assert.equal(sendCount(), before, '非法凭据不得发出请求');
  }
  const beforeConflict = sendCount();
  await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token=' + token, {headers: new Headers({'X-Token': 'different'})}), /BRIDGE_CREDENTIAL_CONFLICT/);
  assert.equal(sendCount(), beforeConflict);

  // ── 3. 非桥请求维持原语义（不改写、不拦、token 仍在 URL 里是调用方自己的事）────────────
  setPort('3091\n'); reset();
  const passThrough = [
    'https://example.invalid/?token=' + token,          // 非回环
    'http://localhost:3090/app/open?token=' + token,    // 主机名不是 127.0.0.1/[::1]
    'http://127.0.0.1:3080/?token=' + token,            // dsh Web 鉴权（范围外端口 + 非桥路由）
    'http://127.0.0.1:3081/auth?token=' + token,        // LAN 鉴权
    'http://127.0.0.1:3095/api/status?token=' + token,  // 候选端口但不是桥路由
    'http://127.0.0.1:3095/?token=' + token,            // 用户把 Web 端口配到候选范围内时的鉴权 URL
  ];
  for (const url of passThrough) assert.equal(rewrite(url, {}), null, '必须原样放行: ' + url);
  assert.equal(rewrite('http://127.0.0.1:3090/app/open?token=' + token, {method: 'POST'}), null, 'shim 只迁移老式 GET，POST 维持原语义');
  for (const url of passThrough) await fetch(url);
  assert.equal(sendCount(), passThrough.length, '范围外的请求必须照常发出');
  assert.ok(lastSent().input.includes(token), '原样放行时 URL 不变');
  reset();
  await fetch('https://fixture.example.invalid/?token=' + token);
  assert.equal(sendCount(), 1);
  // 桥路由的百分号编码写法不能被当成「无关请求」放过去。
  assert.throws(() => rewrite('http://127.0.0.1:3090/%61pp/open?token=' + token, {}), /BRIDGE_ENDPOINT_UNAVAILABLE/);
  assert.equal(rewrite('http://127.0.0.1:3091/%61pp/open?token=' + token, {}).init.headers.get('X-Token'), token);

  // ── 4. 已知桥请求打到别的候选端口：Promise reject、底层 fetch 零调用、token 不出现在异常 ──
  //     这段就是 P1：.bridge_port 为 0 或 3091 时，旧实现在 3090 上把带 token 的 URL 发了出去。
  const mismatchCases = ['0\n', '3091\n', '3095\n', '3109\n'];
  for (const value of mismatchCases) {
    setPort(value); reset();
    await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token=' + token), error => {
      assert.equal(String(error.message), 'BRIDGE_ENDPOINT_UNAVAILABLE');
      assert.equal(String(error.stack).includes(token), false, '异常里不能带 token');
      assert.equal(String(error.stack).includes('http'), false, '异常里不能带 URL');
      return true;
    });
    assert.equal(sent.length, 0, '端点不匹配不得发出任何请求: ' + JSON.stringify(value));
    assert.throws(() => rewrite('http://127.0.0.1:3090/app/open?token=' + token, {}), /BRIDGE_ENDPOINT_UNAVAILABLE/, '不匹配只能拒绝，不能「不注入就放行」');
  }
  assert.equal(warnings.join(' ').includes(token), false, '日志里不能带 token');

  // ── 5. 选定端口成功路径：只经 X-Token，URL 里没有 token ─────────────────────────────
  setPort('3091\n'); reset();
  await fetch('http://127.0.0.1:3091/app/open?token=' + token, {headers: {'X-Custom': 'kept'}});
  assert.equal(sendCount(), 1);
  assert.equal(lastSent().input.includes(token), false, 'token 不能进 URL');
  assert.equal(lastSent().init.headers.get('X-Token'), token, 'token 必须进 X-Token 头');
  assert.equal(lastSent().init.headers.get('X-Custom'), 'kept');

  // ── 6. 端口文件读取矩阵：ASCII 有限完整普通文件，只有真缺目录项才回退 3090 ─────────────
  const expectUnavailable = (label, call) => {
    assert.equal(allowedPort(), null, label + ' 必须判端点未知');
    assert.throws(call, /BRIDGE_ENDPOINT_UNAVAILABLE/, label + ' 必须拒绝');
  };
  removePort();
  assert.equal(allowedPort(), 3090, '缺文件 = 老 App，兼容 3090');
  const callTheBridgeAt3090 = () => rewrite('http://127.0.0.1:3090/app/open?token=' + token, {});
  assert.equal(callTheBridgeAt3090().init.headers.get('X-Token'), token);
  for (const bad of ['0', '70000', 'abc', '', '3090 3091', '+3090', '3090\n3091\n', '3091 ', '3091\t', '3091\n\t', '3091\nabc', '9'.repeat(40)]) {
    setPort(bad);
    expectUnavailable(JSON.stringify(bad), callTheBridgeAt3090);
  }
  // 有效前缀 + 大量空格后跟垃圾的超长文件：旧 readSync(32) 会只认前 32 字节。
  setPort('3091\n' + ' '.repeat(64) + 'GARBAGE');
  expectUnavailable('padding+garbage>16', callTheBridgeAt3090);
  // Unicode 空白：旧 .trim() 会把 NBSP / 行分隔符当成空白吞掉。
  for (const [label, raw] of [['NBSP', '3091\u00a0'], ['LS', '3091\u2028'], ['NNBSP', '3091\u202f']]) {
    fs.writeFileSync(portFile, Buffer.from(raw, 'utf8'));
    expectUnavailable('unicode ' + label, callTheBridgeAt3090);
  }
  fs.writeFileSync(portFile, Buffer.from([0xff, 0xfe, 0x30, 0x39]));
  expectUnavailable('非 ASCII 字节', callTheBridgeAt3090);
  fs.rmSync(portFile, {force: true}); fs.mkdirSync(portFile);
  expectUnavailable('目录', callTheBridgeAt3090);
  fs.rmdirSync(portFile);
  // 悬空符号链接：目录项存在但目标是空的 —— 不是「文件不存在」，绝不能回退 3090。
  fs.symlinkSync(path.join(home, 'missing-target'), portFile);
  expectUnavailable('悬空符号链接', callTheBridgeAt3090);
  reset();
  await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token=' + token), /BRIDGE_ENDPOINT_UNAVAILABLE/);
  assert.equal(sent.length, 0, '悬空链接不得回退 3090 发请求');
  // 指向有效文件的符号链接也不是契约里的普通文件，同样 fail-closed。
  removePort();
  fs.writeFileSync(path.join(home, 'real-port'), '3097\n');
  fs.symlinkSync(path.join(home, 'real-port'), portFile);
  expectUnavailable('指向有效文件的符号链接', callTheBridgeAt3090);
  removePort();
  setPort('3092');
  assert.equal(allowedPort(), 3092, '没有换行也合法');
  assert.equal(rewrite('http://127.0.0.1:3092/app/open?token=' + token, {}).init.headers.get('X-Token'), token);
  setPort('3091\n'); removePort(); removePort();

  // ── 7. 浏览器注入：未定义（老 App）才兼容 3090；null / getter 抛错 / 非法值一律 fail-closed ──
  function browserFetch(inject) {
    const captured = [];
    const sandbox = {URL, Headers, Promise, fetch: async (input, init) => { captured.push({input: String(input), init}); return new Response('OK'); }};
    if (inject !== 'absent' && inject !== 'getter-throws') sandbox.__DSHA_BRIDGE_PORT__ = inject;
    const context = vm.createContext(sandbox);
    // 「读就抛错」的 getter 只能在上下文内定义（vm 会丢掉宿主对象上的访问器属性）。
    if (inject === 'getter-throws') vm.runInContext("Object.defineProperty(globalThis,'__DSHA_BRIDGE_PORT__',{configurable:true,get(){throw Error('fixture-getter')}})", context);
    vm.runInContext(SOURCE, context);
    return {context, captured};
  }
  // 老浏览器（没有 Request / Object.hasOwn）+ 没有注入：唯一回退 3090 的场景。
  const legacy = browserFetch('absent');
  const legacySignal = new AbortController().signal;
  vm.runInContext('Object.hasOwn=undefined;Request=undefined;', legacy.context);
  await legacy.context.fetch('http://127.0.0.1:3090/app/open?token=' + token, {signal: legacySignal});
  assert.equal(legacy.captured.length, 1);
  assert.equal(legacy.captured[0].init.signal, legacySignal);
  assert.equal(legacy.captured[0].init.headers.get('X-Token'), token);
  assert.equal(new URL(legacy.captured[0].input).searchParams.has('token'), false);
  // 注入选定端口：本端口迁移，别的候选端口拒绝。
  const selected = browserFetch(3091);
  await selected.context.fetch('http://127.0.0.1:3091/app/open?token=' + token, {});
  assert.equal(selected.captured.length, 1);
  assert.equal(selected.captured[0].init.headers.get('X-Token'), token);
  assert.equal(new URL(selected.captured[0].input).searchParams.has('token'), false);
  const wrongPort = browserFetch(3091);
  await assert.rejects(wrongPort.context.fetch('http://127.0.0.1:3090/app/open?token=' + token, {}), /BRIDGE_ENDPOINT_UNAVAILABLE/);
  assert.equal(wrongPort.captured.length, 0, '注入端口不匹配时不得发出任何请求');
  // 注入了但是坏值（含 null）：不能当成「没注入」而回退 3090。
  for (const bogus of [null, '0', 0, 65536, 'abc', 3091.5]) {
    const bad = browserFetch(bogus);
    await assert.rejects(bad.context.fetch('http://127.0.0.1:3090/app/open?token=' + token, {}), /BRIDGE_ENDPOINT_UNAVAILABLE/, '注入 ' + JSON.stringify(bogus));
    assert.equal(bad.captured.length, 0, '非法注入 ' + JSON.stringify(bogus) + ' 不得回退 3090');
  }
  const throwing = browserFetch('getter-throws');
  await assert.rejects(throwing.context.fetch('http://127.0.0.1:3090/app/open?token=' + token, {}), /BRIDGE_ENDPOINT_UNAVAILABLE/, 'getter 抛错 = 端点未知，不能回退 3090');
  assert.equal(throwing.captured.length, 0);
  // 浏览器侧的范围外请求（例如 dsh Web 鉴权）原样放行。
  const browserOther = browserFetch(3091);
  await browserOther.context.fetch('http://127.0.0.1:3080/?token=' + token, {});
  assert.equal(browserOther.captured.length, 1);
  assert.ok(browserOther.captured[0].input.includes('token=' + token), '范围外请求保持原样');

  // ── 8. 新 App provider 路径：每次请求现取端口，拿不到就拒绝，绝不回退 3090 ─────────────
  const setProvider = impl => {
    globalThis.__DSHA_BRIDGE_PORT_REQUIRED__ = true;
    globalThis.__DSHA_BRIDGE_PORT_PROVIDER__ = impl;
  };
  const clearProvider = () => {
    delete globalThis.__DSHA_BRIDGE_PORT_REQUIRED__;
    delete globalThis.__DSHA_BRIDGE_PORT_PROVIDER__;
  };
  try {
    // 文件里是 0（旧路径会 fail-closed）：provider 现取 3091 时必须成功，证明没走旧文件规则。
    setPort('0\n');
    let providerCalls = 0;
    setProvider(async () => { providerCalls++; return 3091; });
    reset();
    await fetch('http://127.0.0.1:3091/app/open?token=' + token);
    assert.equal(providerCalls, 1, '每个桥请求现取一次');
    assert.equal(sent.length, 1);
    assert.equal(lastSent().init.headers.get('X-Token'), token);
    assert.equal(new URL(lastSent().input).searchParams.has('token'), false, 'token 不进 URL');

    // 桥换端口：下一次请求拿到 3092；打到旧端口的请求拒绝且零 fetch（不换端口重放）。
    setProvider(async () => 3092);
    reset();
    await fetch('http://127.0.0.1:3092/app/device?token=' + token);
    assert.equal(sent.length, 1);
    await assert.rejects(fetch('http://127.0.0.1:3091/app/device?token=' + token), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    assert.equal(sent.length, 1, '旧端口请求不得发出');

    // 0 = 未就绪：拒绝；坏值同理。
    for (const value of [0, null, undefined, '0', 65536, 'abc', 3091.5]) {
      setProvider(async () => value);
      reset();
      await assert.rejects(
        fetch('http://127.0.0.1:3090/app/open?token=' + token),
        /BRIDGE_ENDPOINT_UNAVAILABLE/, 'provider=' + JSON.stringify(value));
      assert.equal(sent.length, 0, '未就绪不得发出请求');
    }
    // provider reject / 缺失 / 挂起：一律拒绝，绝不回退 3090。
    setProvider(async () => { throw Error('fixture-no-bridge'); });
    await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token=' + token), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    globalThis.__DSHA_BRIDGE_PORT_REQUIRED__ = true;
    delete globalThis.__DSHA_BRIDGE_PORT_PROVIDER__;
    await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token=' + token), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    setProvider(() => new Promise(() => {}));
    reset();
    const hungAt = Date.now();
    await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token=' + token), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    assert.ok(Date.now() - hungAt >= 1800, 'shim 兜底超时必须生效');
    assert.equal(sent.length, 0, '挂起 provider 也不得发出请求');

    // 并发乱序：各请求只用自己的端口，局部结果不覆盖全局。
    const concurrentA = 'fixture-concurrent-a', concurrentB = 'fixture-concurrent-b';
    const resolvers = [], ports = [3091, 3092];
    let nextPort = 0;
    setProvider(() => {
      const port = ports[nextPort++];
      return new Promise(resolve => resolvers.push(() => resolve(port)));
    });
    reset();
    const requestA = fetch('http://127.0.0.1:3091/app/open?token=' + concurrentA);
    const requestB = fetch('http://127.0.0.1:3092/app/open?token=' + concurrentB);
    assert.equal(resolvers.length, 2, '两个请求各自现取一次');
    resolvers[1](); resolvers[0]();
    await Promise.all([requestA, requestB]);
    const sentA = sent.find(item => item.init.headers.get('X-Token') === concurrentA);
    const sentB = sent.find(item => item.init.headers.get('X-Token') === concurrentB);
    assert.ok(sentA && sentB, '两个并发请求都必须发出');
    assert.equal(new URL(sentA.input).port, '3091');
    assert.equal(new URL(sentB.input).port, '3092');
    assert.equal(sent.length, 2);

    // 普通 Web / LAN 鉴权与范围外请求不受影响，且完全不打扰 provider。
    let foreignCalls = 0;
    setProvider(async () => { foreignCalls++; return 3091; });
    reset();
    for (const url of [
      'http://127.0.0.1:3080/?token=' + token,
      'http://127.0.0.1:3081/auth?token=' + token,
      'http://127.0.0.1:3095/api/status?token=' + token,
      'https://fixture.example.invalid/?token=' + token,
    ]) await fetch(url);
    assert.equal(foreignCalls, 0, '范围外请求不得打扰原生端口通道');
    assert.equal(sent.length, 4);
    assert.ok(lastSent().input.includes('token=' + token), '范围外请求保持原样');

    // REQUIRED !== true（老 App / 老浏览器）：回到 .bridge_port 旧规则。
    globalThis.__DSHA_BRIDGE_PORT_REQUIRED__ = false;
    globalThis.__DSHA_BRIDGE_PORT_PROVIDER__ = async () => 65000;
    setPort('3091\n');
    reset();
    await fetch('http://127.0.0.1:3091/app/open?token=' + token);
    assert.equal(sent.length, 1, 'REQUIRED!==true 时旧规则仍生效');
    assert.equal(lastSent().init.headers.get('X-Token'), token);
  } finally {
    clearProvider();
    removePort();
  }

  // ── 9. 真实 startup.js provider 段：就绪前 / 每次 fresh / 超时 / 并发 / 有界 / pagehide ──
  const ORIGIN = 'http://127.0.0.1:3080';
  function hostWindow(timeoutMs) {
    const listeners = {message: [], pagehide: []};
    const win = {
      location: {origin: ORIGIN},
      addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
      dispatch(type, event) { for (const fn of listeners[type] || []) fn(event); },
      postMessage(data) { this.dispatch('message', {origin: this.location.origin, data}); },
    };
    win.top = win;
    win.__DSHA_BRIDGE_PORT_REQUIRED__ = true;
    if (timeoutMs) win.__DSHA_BRIDGE_PORT_TIMEOUT_MS__ = timeoutMs;
    return win;
  }
  function installProvider(win) {
    vm.runInNewContext(PROVIDER_SOURCE, {window: win, setTimeout, clearTimeout});
    return win;
  }
  for (const mode of ['unconfigured', 'disabled', 'iframe']) {
    const win = hostWindow();
    if (mode === 'unconfigured') delete win.__DSHA_BRIDGE_PORT_REQUIRED__;
    if (mode === 'disabled') win.__DSHA_BRIDGE_PORT_REQUIRED__ = false;
    if (mode === 'iframe') win.top = {};
    installProvider(win);
    assert.equal(win.__DSHA_BRIDGE_PORT_PROVIDER__, undefined, mode + ' must not gain a bridge provider');
    if (mode === 'unconfigured') assert.equal(win.__DSHA_BRIDGE_PORT_REQUIRED__, undefined);
  }
  function bridgeHost(win) {
    const host = {posted: [], postMessage(id) { host.posted.push(String(id)); }};
    win.DshaBridgePort = host;
    return host;
  }
  function replyToHost(host, port) {
    const id = Number(host.posted[host.posted.length - 1]);
    host.onmessage({data: JSON.stringify({__dshaBridgePortReply: id, port})});
  }
  {
    const win = hostWindow();
    const host = bridgeHost(win);
    installProvider(win);
    assert.equal(win.__DSHA_BRIDGE_PORT_REQUIRED__, true, 'provider 安装必须声明 REQUIRED');
    assert.equal(typeof win.__DSHA_BRIDGE_PORT_PROVIDER__, 'function');
    assert.equal('__DSHA_BRIDGE_PORT__' in win, false, 'provider 不得写静态端口全局');
    assert.equal(typeof host.onmessage, 'function', 'provider 必须挂到原生读通道对象');
    const first = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    assert.equal(host.posted.length, 1);
    replyToHost(host, 3091);
    assert.equal(await first, 3091);
    const second = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    assert.notEqual(host.posted[1], host.posted[0], '每个请求使用各自 id');
    replyToHost(host, 0);
    assert.equal(await second, 0, '0 原样返回，交给 shim fail-closed');
    const third = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    replyToHost(host, 3092);
    assert.equal(await third, 3092, '每次请求现取（3091→0→3092）');
    assert.equal('__DSHA_BRIDGE_PORT__' in win, false, 'provider 全程不写全局');
  }
  {
    const win = hostWindow(500);
    const host = bridgeHost(win);
    installProvider(win);
    const first = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    const second = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    const idA = Number(host.posted[0]), idB = Number(host.posted[1]);
    host.onmessage({data: JSON.stringify({__dshaBridgePortReply: idB, port: 3092})});
    host.onmessage({data: JSON.stringify({__dshaBridgePortReply: idA, port: 3091})});
    assert.equal(await first, 3091, '乱序回包各自对应');
    assert.equal(await second, 3092);
  }
  {
    const win = hostWindow(200);
    const posted = [];
    win.postMessage = data => { posted.push(data); };
    installProvider(win);
    const started = Date.now();
    await assert.rejects(win.__DSHA_BRIDGE_PORT_PROVIDER__(), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    assert.ok(Date.now() - started >= 150, '首次未就绪必须超时 reject');
    assert.equal(posted.length, 1);
    assert.match(posted[0], /^[1-9][0-9]{0,9}$/, 'Gecko 请求是纯十进制文本');
    // 通道就绪后同一个 provider 立刻可用（不缓存失败）。
    const host = bridgeHost(win);
    const ready = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    replyToHost(host, 3091);
    assert.equal(await ready, 3091);
  }
  {
    const win = hostWindow(200);
    const host = bridgeHost(win);
    installProvider(win);
    const inFlight = [];
    for (let index = 0; index < 8; index++) inFlight.push(win.__DSHA_BRIDGE_PORT_PROVIDER__());
    assert.equal(host.posted.length, 8, 'pending 上限 8');
    await assert.rejects(win.__DSHA_BRIDGE_PORT_PROVIDER__(), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    assert.equal(host.posted.length, 8, '超限不得再发原生消息');
    const settled = await Promise.allSettled(inFlight);
    assert.ok(settled.every(entry => entry.status === 'rejected'), '未回复 pending 必须超时');
  }
  {
    const win = hostWindow(1000);
    const host = bridgeHost(win);
    installProvider(win);
    const pending = win.__DSHA_BRIDGE_PORT_PROVIDER__();
    win.dispatch('pagehide', {});
    await assert.rejects(pending, /BRIDGE_ENDPOINT_UNAVAILABLE/);
    await assert.rejects(win.__DSHA_BRIDGE_PORT_PROVIDER__(), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    // 迟到回包不得复活旧 pending。
    host.onmessage({data: JSON.stringify({__dshaBridgePortReply: Number(host.posted[0]), port: 3091})});
  }

  // ── 10. Gecko：真实 startup-relay.js 内容脚本 + 真实 provider 段的跨世界往返 ────────────
  function geckoWorld(timeoutMs) {
    const pageEvents = [], contentEvents = [];
    const fire = (list, type, event) => { for (const entry of list) if (entry.type === type) entry.fn(event); };
    const page = {
      location: {origin: ORIGIN},
      addEventListener(type, fn) { pageEvents.push({type, fn}); },
      postMessage(data) {
        fire(pageEvents, 'message', {origin: ORIGIN, data});
        fire(contentEvents, 'message', {origin: ORIGIN, data});
      },
    };
    const content = {
      location: {origin: ORIGIN},
      addEventListener(type, fn) { contentEvents.push({type, fn}); },
      postMessage(data) { fire(pageEvents, 'message', {origin: ORIGIN, data}); },
    };
    page.top = page; content.top = content;
    page.__DSHA_BRIDGE_PORT_REQUIRED__ = true;
    if (timeoutMs) page.__DSHA_BRIDGE_PORT_TIMEOUT_MS__ = timeoutMs;
    return {page, content};
  }
  function nativePort() {
    const port = {
      posted: [], listeners: [],
      postMessage(message) { port.posted.push(message); },
      onMessage: {addListener(fn) { port.listeners.push(fn); }},
    };
    return port;
  }
  function installGecko(world, port) {
    const browser = {runtime: {connectNative: () => port}};
    vm.runInNewContext(
      RELAY_SOURCE,
      {window: world.content, location: world.content.location, browser, setTimeout, clearTimeout});
    vm.runInNewContext(PROVIDER_SOURCE, {window: world.page, setTimeout, clearTimeout});
  }
  function deliverNative(port, message) { for (const listener of port.listeners) listener(message); }
  {
    const world = geckoWorld(500), port = nativePort();
    installGecko(world, port);
    assert.equal(world.page.__DSHA_BRIDGE_PORT_REQUIRED__, true);
    const delivered = [];
    world.page.addEventListener('message', event => delivered.push(event.data));
    const request = world.page.__DSHA_BRIDGE_PORT_PROVIDER__();
    assert.equal(port.posted.length, 1);
    assert.equal(port.posted[0].type, 'bridge-port-request');
    assert.equal(Number.isInteger(port.posted[0].id), true);
    deliverNative(port, {type: 'bridge-port-reply', id: port.posted[0].id, port: 0});
    assert.equal(await request, 0, '未就绪的 0 原样回页面');
    const next = world.page.__DSHA_BRIDGE_PORT_PROVIDER__();
    deliverNative(port, {type: 'bridge-port-reply', id: port.posted[1].id, port: 3092});
    assert.equal(await next, 3092, '每次请求现取，桥 0→3092 生效');
    // 迟到 / 未知 id 的回包不得回页面世界。
    const before = delivered.length;
    deliverNative(port, {type: 'bridge-port-reply', id: port.posted[1].id, port: 3091});
    deliverNative(port, {type: 'bridge-port-reply', id: 999999, port: 3091});
    assert.equal(delivered.length, before, '迟到/未知回包必须丢弃');
  }
  {
    // native 不回：provider 超时；内容脚本 pending 有界且忽略非请求消息。
    const world = geckoWorld(200), port = nativePort();
    installGecko(world, port);
    await assert.rejects(world.page.__DSHA_BRIDGE_PORT_PROVIDER__(), /BRIDGE_ENDPOINT_UNAVAILABLE/);
    assert.equal(port.posted.length, 1);
    const bounded = geckoWorld(500), boundedPort = nativePort();
    installGecko(bounded, boundedPort);
    for (let id = 1; id <= 8; id++) bounded.page.postMessage(String(id));
    assert.equal(boundedPort.posted.length, 8, '内容脚本 pending 上限 8');
    bounded.page.postMessage('9');
    assert.equal(boundedPort.posted.length, 8, '超限请求不得转发');
    bounded.page.postMessage(JSON.stringify({__dshaBridgePortReply: 1, port: 3091}));
    bounded.page.postMessage({__dshaBridgePortRequest: 1});
    bounded.page.postMessage('0');
    bounded.page.postMessage('abc');
    bounded.page.postMessage('12345678901');
    assert.equal(boundedPort.posted.length, 8, '非请求消息不得转发');
  }

  console.log('Bridge fetch migration: peak 4.1.8 tgz function, Request/Headers/AbortSignal, credential conflicts/duplicates, known-bridge rejection with zero underlying fetch, ASCII-bounded port file matrix (padding/Unicode/dangling symlink), non-bridge pass-through, browser injection and per-request provider semantics (fresh port, 0/reject/timeout/missing fail-closed, concurrency isolation, real startup.js provider + Gecko content-script relay round trip, bounded pending/pagehide) PASS. No network/device request sent.');
} finally {
  console.warn = originalWarn;
  if (previousHome === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = previousHome;
  fs.rmSync(home, {recursive: true, force: true});
}
