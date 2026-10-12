// 双实例桥的端点发现回归：guest 消费者只认「本实例端口」，读不到就 fail-closed。
//
// 契约（与原生侧约定）：<DSH_HOME>/.bridge_port（默认 /root/.dsh）是原子写入的
// ≤16 字节普通文件（同原生 BridgeEndpointFiles.READ_LIMIT），内容只有 ASCII 十进制
// 端口 + 一个可选换行，1..65535；只有真正缺目录项（老 App 不写这个文件）才回退旧默认
// 3090；文件存在但为 0、坏格式、越界、非 ASCII、带垃圾尾巴、是目录、符号链接（含悬空
// 链接）或读不动都属于「端点未知」——不发请求、不回退默认、不改试别的回环端口，
// token 也不进 URL/错误。
//
// 跑法：NODE_OPTIONS= node tools/test-bridge-port-discovery.mjs（不需要设备、不读真实凭据）。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {openSync, readSync, closeSync, lstatSync, fstatSync, constants} from 'node:fs';
import {join} from 'node:path';
import {createRequire} from 'node:module';

const ROOT = new URL('..', import.meta.url).pathname;
const computer = createRequire(import.meta.url)(path.join(ROOT, 'app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs'));
const vscreen = createRequire(import.meta.url)(path.join(ROOT, 'app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/server.cjs'));
const {reportPluginStates} = await import(new URL('../app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js', import.meta.url));
const {apply: notifierApply} = await import(new URL('../app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js', import.meta.url));
// 引导插件 import 了 @deepseek-ai/dsh-llm，只能在准备好的运行时里加载（见 test-dsh-builtins.mjs）。
// 这里按 overlay-bridge-test.mjs 的做法只取出端点发现与提示词构造函数，不给插件加导出。
const guideSource = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js'), 'utf8');
const guideStart = guideSource.indexOf('const LEGACY_BRIDGE_PORT = 3090;');
const guideEnd = guideSource.indexOf("  ].join('\\n');", guideStart);
checkSource(guideStart >= 0 && guideEnd > guideStart, '找不到引导提示词构造函数，测试需要跟上');
const guideBlock = guideSource.slice(guideStart, guideEnd + "  ].join('\\n');".length) + '\n}';
const guideBuild = new Function('openSync', 'readSync', 'closeSync', 'lstatSync', 'fstatSync', 'constants', 'join', 'process', 'Buffer',
  guideBlock + '\nreturn {bridgePort,deviceGuidePrompt};');

function checkSource(condition, message) { if (!condition) throw new Error('测试自身失效: ' + message); }

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-bridge-discovery-'));
const portFile = path.join(home, '.bridge_port');
const tokenFile = path.join(home, '.bridge_token');
const TOKEN = 'fixture-discovery-token';
const previousHome = process.env.DSH_HOME;
const previousFetch = globalThis.fetch;
process.env.DSH_HOME = home;
fs.writeFileSync(tokenFile, TOKEN + '\n');
const setPort = value => value === null ? fs.rmSync(portFile, {force: true}) : fs.writeFileSync(portFile, value);
const setPortBytes = value => fs.writeFileSync(portFile, value);
const removePort = () => fs.rmSync(portFile, {force: true});
const linkDanglingPort = () => { removePort(); fs.symlinkSync(path.join(home, 'missing-target'), portFile); };

let sent = [];
let checked = 0;
globalThis.fetch = async (url, options) => {
  sent.push({url: String(url), headers: options && options.headers});
  return {ok: true, status: 200, text: async () => '{"result":"OK"}', body: null, headers: new Headers()};
};
const check = (condition, message) => { checked++; if (!condition) throw new Error('发现契约不满足: ' + message); };
const expectBlocked = async (action, label) => {
  sent = [];
  let error = null;
  try { await action(); } catch (caught) { error = caught; }
  check(error !== null, label + ' 必须拒绝而不是放行');
  check(sent.length === 0, label + ' 不得发出任何请求（真实 fetch 零调用）');
  check(!sent.some(entry => entry.url.includes('3090')), label + ' 不得改试旧端口 3090');
  check(!TOKEN.includes(String(error && error.message)), label + ' 错误里不能带 token');
  return error;
};
const headerOf = entry => entry.headers instanceof Headers ? entry.headers.get('X-Token') : (entry.headers || {})['X-Token'];

try {
  // ── 1. computer-use：端口发现、同数据根 token、选定端口、每次请求重读 ──────────
  setPort(null);
  check(computer.bridgePort() === 3090, 'ENOENT 才回退旧默认 3090');
  check(computer.bridgeTokenPath() === path.join(home, '.bridge_token'), 'token 必须与端口文件同数据根');
  setPort('3091\n');
  check(computer.bridgePort() === 3091, '读选定端口');
  let tokenPathSeen = null, signal = new AbortController().signal;
  const callComputer = () => computer.call('android_get_state', {}, signal, {
    fs: {readFile: async (file) => { tokenPathSeen = file; return TOKEN; }},
    fetch: globalThis.fetch,
  });
  sent = [];
  await callComputer();
  check(sent.length === 1, 'computer-use 必须发一次请求');
  const computerUrl = new URL(sent[0].url);
  check(computerUrl.hostname === '127.0.0.1' && computerUrl.port === '3091', 'computer-use 用选定端口');
  check(computerUrl.pathname === '/app/ui/dump', 'computer-use 路由不变');
  check(!computerUrl.searchParams.has('token'), 'token 不能进 URL');
  check(headerOf(sent[0]) === TOKEN, 'token 必须在 X-Token 头');
  check(tokenPathSeen === path.join(home, '.bridge_token'), 'computer-use 读同数据根 token');
  setPort('3092');
  await callComputer();
  check(new URL(sent[1].url).port === '3092', '端口变化必须在下一次请求生效');
  check(!sent.some(entry => entry.url.includes(':3090')), '选定 3092 时不得出现旧端口 3090');
  sent = [];
  await computer.call('android_get_state', {}, signal, {
    fs: {readFile: async () => TOKEN}, fetch: globalThis.fetch, bridgePort: () => 42777,
  });
  check(new URL(sent[0].url).port === '42777', '注入的选定端口优先');
  for (const bad of ['0', '70000', 'abc', '', '3090 3091']) {
    setPort(bad);
    await expectBlocked(() => computer.call('android_get_state', {}, signal, {fs: {readFile: async () => TOKEN}, fetch: globalThis.fetch}), 'computer-use 坏端口 ' + JSON.stringify(bad));
  }

  // ── 2. vscreen：requestFor/call 都走选定端口，坏端口不发请求 ──────────────────
  setPort('3091\n');
  check(vscreen.bridgePort() === 3091, 'vscreen 读选定端口');
  check(vscreen.requestFor('android_vscreen_status', {}, 3091).url === 'http://127.0.0.1:3091/app/vscreen/status', 'vscreen 显式端口');
  check(vscreen.requestFor('android_vscreen_status', {}).url === 'http://127.0.0.1:3091/app/vscreen/status', 'vscreen 默认走发现');
  sent = [];
  await vscreen.call('android_vscreen_status', {}, signal);
  check(sent.length === 1 && new URL(sent[0].url).port === '3091', 'vscreen call 用选定端口');
  check(headerOf(sent[0]) === TOKEN, 'vscreen token 必须在头里');
  check(!new URL(sent[0].url).searchParams.has('token'), 'vscreen token 不能进 URL');
  setPort('0');
  await expectBlocked(() => vscreen.call('android_vscreen_status', {}, signal), 'vscreen 端口为 0');

  // ── 3. status-overlay：上报走选定端口；端点未知时静默不发 ─────────────────────
  const overlayCalls = async (port) => {
    setPort(port);
    const handlers = new Map(), timers = new Map();
    let serial = 0;
    const ctx = {registry: new Map(), on: (event, fn) => handlers.set(event, fn)};
    const io = {
      token: () => TOKEN,
      setTimeout: fn => { timers.set(++serial, fn); return serial; },
      clearTimeout: id => timers.delete(id),
      fetch: globalThis.fetch,
    };
    reportPluginStates(ctx, io);
    const tick = () => { const [id, fn] = timers.entries().next().value; timers.delete(id); return fn(); };
    await tick();
    handlers.get('dispose')?.();
    return sent;
  };
  sent = [];
  const overlay = await overlayCalls('3091\n');
  check(overlay.length >= 1, 'overlay 必须上报一次');
  const overlayUrl = new URL(overlay[0].url);
  check(overlayUrl.port === '3091' && overlayUrl.pathname === '/app/plugins', 'overlay 上报走选定端口');
  check(headerOf(overlay[0]) === TOKEN, 'overlay token 必须在头里');
  sent = [];
  await overlayCalls('0');
  check(sent.length === 0, 'overlay 端点未知必须静默停用（不打扰 agent）');

  // ── 4. task-notifier：turn/end 通知走选定端口；端点未知不通知 ─────────────────
  const notifyOnce = async (port, sessionId) => {
    setPort(port);
    const handlers = new Map();
    notifierApply({on: (event, fn) => handlers.set(event, fn)});
    handlers.get('session/event')({id: sessionId}, {type: 'turn/end', data: {reason: {kind: 'completed'}}});
    for (let i = 0; i < 50 && sent.length === 0 && port !== '0'; i++) await new Promise(done => setTimeout(done, 5));
    await new Promise(done => setTimeout(done, 10));
    return sent;
  };
  sent = [];
  const notified = await notifyOnce('3093\n', 'session-a');
  check(notified.length === 1, 'notifier 必须发一次通知');
  const notifyUrl = new URL(notified[0].url);
  check(notifyUrl.port === '3093' && notifyUrl.pathname === '/app/notify', 'notifier 走选定端口');
  check(headerOf(notified[0]) === TOKEN, 'notifier token 必须在头里');
  check(!notifyUrl.searchParams.has('token'), 'notifier token 不能进 URL');
  sent = [];
  await notifyOnce('70000', 'session-b');
  check(sent.length === 0, 'notifier 端点未知必须静默跳过');

  // ── 5. device-shell-guide：提示词里的端口就是选定端口，未知时写明未知 ──────────
  const guide = guideBuild(openSync, readSync, closeSync, lstatSync, fstatSync, constants, join, process, Buffer);
  check(!/:3090\b/.test(guideSource), '引导源码里不得再有 :3090 端点字面量');
  check(guideSource.includes('const LEGACY_BRIDGE_PORT = 3090;'), '回退常量必须显式声明');
  setPort('3094\n');
  const prompt = guide.deviceGuidePrompt(guide.bridgePort());
  check(prompt.includes('http://127.0.0.1:3094/app/help'), '引导提示词必须带选定端口');
  check(prompt.includes('curl -s -H @' + home + '/.bridge_headers'), '引导提示词用同数据根的凭据头文件');
  check(!prompt.includes('127.0.0.1:3090'), '引导提示词不得留着旧端口');
  setPort('0');
  const unknown = guide.deviceGuidePrompt(guide.bridgePort());
  check(/端口未知/.test(unknown), '端点未知时提示词必须写明未知');
  check(!unknown.includes('127.0.0.1:3090'), '端点未知时不能猜旧端口');
  setPort(null);
  const legacy = guide.deviceGuidePrompt(guide.bridgePort());
  check(legacy.includes('http://127.0.0.1:3090/app/help'), '老 App 缺文件时提示词回退 3090');

  // 执行完整生产 apply；只替换消息工厂依赖，不复制插件注入逻辑。
  const messageFactory = 'data:text/javascript,' + encodeURIComponent('export const createUserMessage = value => value;');
  const moduleSource = guideSource.replace("'@deepseek-ai/dsh-llm'", JSON.stringify(messageFactory));
  const {apply: guideApply} = await import('data:text/javascript,' + encodeURIComponent(moduleSource));
  let section;
  setPort('0');
  guideApply({
    inject: (_services, install) => install({systemPrompt: {section: value => { section = value; }}, effect: fn => fn()}),
    on() {},
  });
  check(typeof section.text === 'function', '标准提示必须按次求值，不能缓存首次未知值');
  check(/端口未知/.test(section.text()), '首次不可用时不得猜端口');
  setPort('3091\n');
  const firstPrompt = section.text();
  check(firstPrompt.includes('127.0.0.1:3091'), '桥准备好后同一个已注册 provider 必须恢复');
  check(firstPrompt === guide.deviceGuidePrompt(3091), '动态化不新增提示正文或上下文开销');
  setPort('3105\n');
  check(section.text().includes('127.0.0.1:3105') && !section.text().includes('127.0.0.1:3091'), '桥换端口后不保留旧引导');
  setPort('0');
  check(/端口未知/.test(section.text()), '停止后同一个 provider 重新报告未知');

  // ── 6. 端口文件契约矩阵：≤16 字节、ASCII、完整、必须是普通文件 ─────────────────
  // 每个用例都必须「零真实 fetch + 不回退 3090」；悬空符号链接尤其不能当成缺文件。
  const overlayCallsBytes = async () => {
    const handlers = new Map(), timers = new Map();
    let serial = 0;
    const ctx = {registry: new Map(), on: (event, fn) => handlers.set(event, fn)};
    const io = {token: () => TOKEN, setTimeout: fn => { timers.set(++serial, fn); return serial; }, clearTimeout: id => timers.delete(id), fetch: globalThis.fetch};
    reportPluginStates(ctx, io);
    const [id, fn] = timers.entries().next().value;
    timers.delete(id);
    await fn();
    handlers.get('dispose')?.();
    return sent;
  };
  const notifierFire = async (sessionId) => {
    const handlers = new Map();
    notifierApply({on: (event, fn) => handlers.set(event, fn)});
    handlers.get('session/event')({id: sessionId}, {type: 'turn/end', data: {reason: {kind: 'completed'}}});
    for (let i = 0; i < 40 && sent.length === 0; i++) await new Promise(done => setTimeout(done, 5));
    await new Promise(done => setTimeout(done, 10));
    return sent;
  };
  const expectEveryConsumerBlocked = async (label) => {
    sent = [];
    let error = null;
    try { await callComputer(); } catch (caught) { error = caught; }
    check(error !== null, 'computer-use ' + label + ' 必须拒绝');
    check(sent.length === 0, 'computer-use ' + label + ' 真实 fetch 必须零调用');
    let directThrow = false;
    try { computer.bridgePort(); } catch { directThrow = true; }
    check(directThrow, 'computer-use ' + label + ' bridgePort 必须拒绝');
    await expectBlocked(() => vscreen.call('android_vscreen_status', {}, signal), 'vscreen ' + label);
    sent = [];
    await overlayCallsBytes();
    check(sent.length === 0, 'overlay ' + label + ' 必须静默停发');
    sent = [];
    await notifierFire('matrix-' + label);
    check(sent.length === 0, 'notifier ' + label + ' 不得发通知');
    check(guide.bridgePort() === null, 'guide ' + label + ' 必须判端点未知');
    const text = guide.deviceGuidePrompt(guide.bridgePort());
    check(/端口未知/.test(text) && !text.includes('127.0.0.1:3090'), 'guide ' + label + ' 不得猜旧端口');
  };
  const badCases = [
    ['有效前缀+填充+垃圾(>16字节)', Buffer.from('3091\n' + ' '.repeat(64) + 'GARBAGE', 'utf8')],
    ['有效前缀+短垃圾', Buffer.from('3091\nabc', 'utf8')],
    ['有效前缀+尾随空白', Buffer.from('3091\n\t', 'utf8')],
    ['Unicode NBSP', Buffer.from('3091\u00a0', 'utf8')],
    ['Unicode 行分隔符', Buffer.from('3091\u2028', 'utf8')],
    ['Unicode 窄不换行空格', Buffer.from('3091\u202f', 'utf8')],
    ['非 ASCII 原始字节', Buffer.from([0xff, 0xfe, 0x30, 0x39])],
    ['越界', Buffer.from('70000', 'utf8')],
    ['零', Buffer.from('0', 'utf8')],
    ['坏格式', Buffer.from('3090 3091', 'utf8')],
    ['超长数字', Buffer.from('9'.repeat(40), 'utf8')],
    ['空文件', Buffer.alloc(0)],
  ];
  for (const [label, value] of badCases) {
    setPortBytes(value);
    await expectEveryConsumerBlocked(label);
  }
  // 目录与悬空符号链接：都不是「文件不存在」。
  removePort(); fs.mkdirSync(portFile);
  await expectEveryConsumerBlocked('端口文件是目录');
  fs.rmdirSync(portFile);
  linkDanglingPort();
  await expectEveryConsumerBlocked('悬空符号链接');
  check(!guide.deviceGuidePrompt(guide.bridgePort()).includes('127.0.0.1:3090'), 'guide 悬空符号链接不得猜旧端口');
  // 指向有效文件的符号链接也不是契约里的普通文件。
  removePort();
  fs.writeFileSync(path.join(home, 'real-port'), '3097\n');
  fs.symlinkSync(path.join(home, 'real-port'), portFile);
  await expectEveryConsumerBlocked('指向有效文件的符号链接');
  // 「文件真的不存在」仍是唯一回退：删掉链接后回退 3090，且只走回退端口。
  removePort();
  check(computer.bridgePort() === 3090, '真正缺文件才回退 3090');
  sent = [];
  await computer.call('android_get_state', {}, signal, {fs: {readFile: async () => TOKEN}, fetch: globalThis.fetch});
  check(sent.length === 1 && new URL(sent[0].url).port === '3090', '缺文件时 computer-use 走 3090');
  check(guide.bridgePort() === 3090, '缺文件时 guide 回退 3090');

  console.log(`Bridge port discovery: ${checked} assertions across adb-shell contract consumers (computer-use/vscreen/overlay/notifier/guide) PASS, including the ≤16-byte ASCII/complete/regular-file matrix, Unicode whitespace, padding+garbage, dangling symlink and zero-real-fetch fail-closed cases; no device or real credential was touched.`);
} finally {
  globalThis.fetch = previousFetch;
  if (previousHome === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = previousHome;
  fs.rmSync(home, {recursive: true, force: true});
}
