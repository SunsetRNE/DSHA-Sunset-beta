// 平板 / 宽触屏两项修复的行为测试：直接执行随包产物的真实导出函数，不读源码字符串。
//
// 覆盖：
//   1) 团队 chip「再点关闭」接管的门控：(pointer: coarse) 全宽度（含平板）安装，
//      pointer: fine 桌面在任意宽度完全不安装，媒体查询变化会撤销/重装监听；
//   2) createSheetRiseTask 的 WAAPI 所有权：prefers-reduced-motion: reduce 下零动画、
//      只取消本任务启动的动画；关闭（列转 visibility:hidden）、同列重开、元素替换、
//      槽位缺席、任务卸载都取消自己那条；observer 有界且随 dispose 释放。
//
// 限度：本测试在独立 Node 进程里驱动真实产物，但没有真实合成事件、布局与绘制。
// 像素级「列完整可见」、真实平板命中测试仍须设备验收（见交接件 §3/§3b）。
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { applyMobileClientPatches, upstreamClientHash } from './apply-mobile-client-patches.mjs';

const REPO_BUNDLE = fileURLToPath(new URL(
  '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', import.meta.url));
// DSHA_MOBILE_CLIENT 可指向另一份产物拷贝（例如 HEAD 基线或交接测试包），用于复现红/绿对照。
const BUNDLE = process.env.DSHA_MOBILE_CLIENT ? process.env.DSHA_MOBILE_CLIENT : REPO_BUNDLE;
// 产物末尾的引导只装载 index.js；宿主外部模块不在本测试范围，这里换成登记表出口。
const BOOT = 'var module = { exports: {} };\n__modules["index.js"](__localRequire, module, module.exports);\nreturn module.exports;';
const HOOK = 'window.__mobileTestModules = __modules; window.__mobileTestRequire = __localRequire;';

const EXPLORER = '[data-aionui-explorer-col]';
const PREVIEW = '[data-aionui-preview-col]';
const TRIGGER = '[data-team-action] > button[aria-haspopup="dialog"]';
const ROOT = '[data-team-action]';
const PANEL = '[data-team-panel]';

class FakeAnimation {
  constructor(keyframes, options) {
    this.keyframes = keyframes;
    this.options = options;
    this.cancelled = 0;
    this.finished = Promise.resolve();
  }
  cancel() { this.cancelled += 1; }
}

class FakeElement {
  constructor(tag = 'div') {
    this.tagName = tag.toUpperCase();
    this.isConnected = true;
    this.parentElement = null;
    this.style = {};
    this.__closest = new Map();
    this.__attrs = new Map();
    this.__animations = [];
    this.__foreign = [];
    this.__observers = new Set();
    this.__visibility = 'hidden';
  }
  animate(keyframes, options) {
    const animation = new FakeAnimation(keyframes, options);
    this.__animations.push(animation);
    return animation;
  }
  // 宿主动画一起暴露：真正的实现必须只取消自己启动的那条。
  getAnimations() { return [...this.__foreign, ...this.__animations]; }
  getAttribute(name) { return this.__attrs.get(name) ?? null; }
  setAttribute(name, value) { this.__attrs.set(name, value); }
  closest(selector) { return this.__closest.get(selector) ?? null; }
}

class FakeMutationObserver {
  static instances = [];
  constructor(callback) {
    this.callback = callback;
    this.targets = new Set();
    this.disconnected = false;
    FakeMutationObserver.instances.push(this);
  }
  observe(target) { this.targets.add(target); target.__observers.add(this); }
  disconnect() {
    this.disconnected = true;
    for (const target of this.targets) target.__observers.delete(this);
    this.targets.clear();
  }
  static fire(target) { for (const observer of [...target.__observers]) observer.callback([], observer); }
}

class FakePointerEvent {
  constructor(type, init) { this.type = type; Object.assign(this, init ?? {}); }
}

function loadMobileArtifact({ width = 360, pointer = 'coarse', reduce = false } = {}) {
  const source = readFileSync(BUNDLE, 'utf8');
  assert.equal(source.split(BOOT).length, 2, '随包产物引导锚点必须唯一存在');
  const device = { width, pointer, reduce };
  const queries = [];
  const mediaLists = [];
  const evaluate = query => {
    if (query === '(pointer: coarse)') return device.pointer === 'coarse';
    if (query === '(max-width: 1023px) and (pointer: coarse)')
      return device.width <= 1023 && device.pointer === 'coarse';
    if (query === '(min-width: 1024px)') return device.width >= 1024;
    if (query === '(prefers-reduced-motion: reduce)') return device.reduce;
    throw new Error('测试未覆盖的媒体查询: ' + query);
  };
  const windowStub = {
    matchMedia(query) {
      queries.push(query);
      const list = {
        media: query,
        matches: evaluate(query),
        handlers: new Set(),
        addEventListener(type, handler) { this.handlers.add(handler); },
        removeEventListener(type, handler) { this.handlers.delete(handler); },
      };
      mediaLists.push(list);
      return list;
    },
  };
  const elements = new Map();
  const documentListeners = [];
  const bodyEvents = [];
  const documentStub = {
    addEventListener(type, handler, capture) {
      documentListeners.push({ type, handler, capture: capture === true });
    },
    removeEventListener(type, handler) {
      const index = documentListeners.findIndex(row => row.type === type && row.handler === handler);
      if (index >= 0) documentListeners.splice(index, 1);
    },
    querySelector(selector) { return elements.get(selector) ?? null; },
    querySelectorAll(selector) {
      const element = elements.get(selector);
      return element === undefined || element === null ? [] : [element];
    },
    body: { dispatchEvent(event) { bodyEvents.push(event); return true; } },
  };
  FakeMutationObserver.instances = [];
  const context = vm.createContext({
    window: windowStub,
    document: documentStub,
    getComputedStyle: element => ({ visibility: element.__visibility }),
    MutationObserver: FakeMutationObserver,
    Element: FakeElement,
    PointerEvent: FakePointerEvent,
    console,
    setTimeout,
    clearTimeout,
    queueMicrotask,
    requestAnimationFrame: callback => setTimeout(callback, 0),
  });
  windowStub.__ModuleLoader__ = {
    load({ factory }) {
      factory(id => { throw new Error('测试不应要求宿主外部模块: ' + id); });
    },
  };
  vm.runInContext(source.replace(BOOT, HOOK), context, { filename: 'dsh-web-mobile-client.js' });
  const requireModule = vm.runInContext('window.__mobileTestRequire', context);
  const createElement = tag => new FakeElement(tag);
  const setDevice = patch => {
    Object.assign(device, patch);
    for (const list of mediaLists) {
      const next = evaluate(list.media);
      if (next === list.matches) continue;
      list.matches = next;
      for (const handler of [...list.handlers]) handler();
    }
  };
  return {
    createElement, requireModule, setDevice,
    device, queries, mediaLists, elements, documentListeners, bodyEvents,
  };
}

function collectingContext() {
  const disposers = [];
  return {
    disposers,
    effect(run) {
      const dispose = run();
      if (typeof dispose === 'function') disposers.push(dispose);
      return dispose;
    },
  };
}

function installTeamChip(artifact) {
  const chip = artifact.requireModule('./effects/team-chip-toggle.js');
  chip.installTeamChipToggle(collectingContext());
  return artifact.documentListeners;
}

function chipFixture(artifact, { expanded = true, panel = true } = {}) {
  const trigger = artifact.createElement('button');
  trigger.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  trigger.__closest.set(TRIGGER, trigger);
  artifact.elements.set(ROOT, artifact.createElement('div'));
  artifact.elements.set(PANEL, panel ? artifact.createElement('div') : null);
  return { trigger };
}

// 模拟「面板已打开时再点同一颗」：捕获阶段先 pointerdown，再 click。
function tapTeamChip(artifact, trigger) {
  let stopped = 0;
  const pointerdown = artifact.documentListeners.find(row => row.type === 'pointerdown');
  const click = artifact.documentListeners.find(row => row.type === 'click');
  pointerdown?.handler({ target: trigger });
  click?.handler({ target: trigger, stopPropagation() { stopped += 1; } });
  return { stopped, events: artifact.bodyEvents };
}

function sheetFixture(artifact) {
  const parent = artifact.createElement('div');
  const explorer = artifact.createElement('section');
  explorer.parentElement = parent;
  artifact.elements.set(EXPLORER, explorer);
  const preview = artifact.createElement('section');
  preview.parentElement = parent;
  artifact.elements.set(PREVIEW, preview);
  return { parent, explorer, preview };
}

const show = element => { element.__visibility = 'visible'; };
const hide = element => { element.__visibility = 'hidden'; };
const riseTask = artifact => artifact.requireModule('./effects/aionui-compat.js').createSheetRiseTask();

// ---------------------------------------------------------------------------
// 团队 chip：接管的媒体门控
// ---------------------------------------------------------------------------

test('随包产物可由锁定上游 + 当前补丁字节级重建',
  { skip: process.env.DSHA_MOBILE_UPSTREAM_CLIENT ? false : '未提供 DSHA_MOBILE_UPSTREAM_CLIENT' },
  () => {
    const upstream = readFileSync(process.env.DSHA_MOBILE_UPSTREAM_CLIENT);
    assert.equal(createHash('sha256').update(upstream).digest('hex'), upstreamClientHash,
      '上游字节必须等于锁定 commit 的摘要，不能变更锁定版本');
    assert.equal(applyMobileClientPatches(upstream), readFileSync(REPO_BUNDLE, 'utf8'),
      '随包产物必须等于「锁定上游 + tools/apply-mobile-client-patches.mjs 当前补丁」的重建结果');
  });

test('1280×800 触屏（pointer:coarse）安装团队 chip 接管：再点同一颗派发外部 pointerdown 并吞掉 click', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { trigger } = chipFixture(artifact);
  installTeamChip(artifact);
  assert.equal(artifact.documentListeners.length, 2, '宽触屏必须安装 pointerdown/click 捕获监听');
  const result = tapTeamChip(artifact, trigger);
  assert.equal(result.events.length, 1, '必须向 body 派发一次外部 pointerdown 触发宿主关闭');
  assert.equal(result.events[0].type, 'pointerdown');
  assert.equal(result.events[0].bubbles, true);
  assert.equal(result.stopped, 1, '必须吞掉宿主那颗只开不关的 click');
  assert.ok(artifact.queries.includes('(pointer: coarse)'), '门控必须落在全宽度触屏查询上');
  assert.ok(!artifact.queries.includes('(max-width: 1023px) and (pointer: coarse)'),
    '不得再按窄屏查询安装（平板宽度恒不命中）');
});

test('1280×800 鼠标窗口（pointer:fine）完全不安装，同样的事件序列零接管', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'fine' });
  const { trigger } = chipFixture(artifact);
  installTeamChip(artifact);
  assert.equal(artifact.documentListeners.length, 0, '桌面鼠标窗口不得安装');
  const result = tapTeamChip(artifact, trigger);
  assert.equal(result.events.length, 0);
  assert.equal(result.stopped, 0);
});

test('360×800 触屏仍安装（手机档无回归），800×600 鼠标窗口仍不安装', () => {
  const phone = loadMobileArtifact({ width: 360, pointer: 'coarse' });
  const phoneTrigger = chipFixture(phone).trigger;
  installTeamChip(phone);
  assert.equal(phone.documentListeners.length, 2);
  assert.equal(tapTeamChip(phone, phoneTrigger).events.length, 1);

  const narrowDesktop = loadMobileArtifact({ width: 800, pointer: 'fine' });
  const desktopTrigger = chipFixture(narrowDesktop).trigger;
  installTeamChip(narrowDesktop);
  assert.equal(narrowDesktop.documentListeners.length, 0, '窄鼠标窗口不是触屏设备，不得因宽度命中');
  assert.equal(tapTeamChip(narrowDesktop, desktopTrigger).events.length, 0);
});

test('面板未打开（aria-expanded=false）或面板不在场时不接管', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { trigger } = chipFixture(artifact, { expanded: false });
  installTeamChip(artifact);
  assert.equal(tapTeamChip(artifact, trigger).events.length, 0, '闭态点击不得被当成关闭');

  trigger.setAttribute('aria-expanded', 'true');
  artifact.elements.set(PANEL, null);
  assert.equal(tapTeamChip(artifact, trigger).events.length, 0, '面板缺席时不得凭空关闭');

  artifact.elements.set(PANEL, artifact.createElement('div'));
  assert.equal(tapTeamChip(artifact, trigger).events.length, 1, '面板在场且开态仍应接管');
});

test('触屏↔鼠标的媒体查询变化撤销并重装监听，不残留重复监听', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { trigger } = chipFixture(artifact);
  installTeamChip(artifact);
  assert.equal(artifact.documentListeners.length, 2);

  artifact.setDevice({ pointer: 'fine' });
  assert.equal(artifact.documentListeners.length, 0, '切成鼠标后必须撤销全部监听');
  assert.equal(tapTeamChip(artifact, trigger).events.length, 0);

  artifact.setDevice({ pointer: 'coarse' });
  assert.equal(artifact.documentListeners.length, 2, '切回触屏只应有一份监听');
  assert.equal(tapTeamChip(artifact, trigger).events.length, 1);

  artifact.setDevice({ pointer: 'fine' });
  artifact.setDevice({ pointer: 'coarse' });
  assert.equal(artifact.documentListeners.length, 2, '反复切换不得累积监听');
});

// ---------------------------------------------------------------------------
// createSheetRiseTask：reduce、所有权与释放
// ---------------------------------------------------------------------------

test('reduce 下零 WAAPI 调用，列保持原样完整可见（无内联样式、无动画）', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse', reduce: true });
  const { explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  assert.equal(explorer.__animations.length, 0, 'reduce 下不得启动任何 WAAPI 动画');
  assert.deepEqual(explorer.style, {}, 'reduce 路径不得写内联样式把列留在半透明/屏外');
  assert.ok(artifact.queries.includes('(prefers-reduced-motion: reduce)'), '必须实际读取减弱动态效果');

  hide(explorer);
  task.ensure();
  show(explorer);
  task.ensure();
  assert.equal(explorer.__animations.length, 0, '关开一轮后仍不得播动画');
  assert.deepEqual(explorer.style, {});
  task.dispose();
});

test('正常升起只播一次，关键帧结束态可见（opacity 1 / transform none）', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  task.ensure();
  assert.equal(explorer.__animations.length, 1, '列保持可见时不得重复播动画');
  const [animation] = explorer.__animations;
  // 关键帧对象来自产物自己的 realm，逐字段比较，避免跨 realm 原型差异。
  const lastKeyframe = animation.keyframes.at(-1);
  assert.equal(lastKeyframe.opacity, 1, '结束关键帧必须回到完全可见');
  assert.equal(lastKeyframe.transform, 'none', '结束关键帧必须回到原位');
  assert.equal(animation.options.duration, 280);
  assert.equal(animation.options.fill, 'backwards');
  assert.equal(animation.cancelled, 0);
  task.dispose();
});

test('列转 visibility:hidden（本插件实际的关闭路径）立即取消自己那条动画', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  const [animation] = explorer.__animations;
  hide(explorer);
  task.ensure();
  assert.equal(animation.cancelled, 1, '关闭必须取消自己的动画（元素仍在 DOM 内，仅 visibility 变化）');
  task.dispose();
});

test('关闭后再打开：旧动画已取消，新列只有一条自己的动画在跑', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  hide(explorer);
  task.ensure();
  show(explorer);
  task.ensure();
  assert.equal(explorer.__animations.length, 2, '重开必须重新升起');
  const [first, second] = explorer.__animations;
  assert.equal(first.cancelled, 1, '同列重开前必须取消上一条，避免两条抢同一属性');
  assert.equal(second.cancelled, 0);
  assert.equal(explorer.__animations.filter(row => row.cancelled === 0).length, 1);
  task.dispose();
});

test('列被替换（父节点 childList）时取消旧动画；替换后的列不因重渲染重复升起', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { parent, explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  const [animation] = explorer.__animations;

  const replacement = artifact.createElement('section');
  replacement.parentElement = parent;
  show(replacement);
  explorer.isConnected = false;
  artifact.elements.set(EXPLORER, replacement);
  FakeMutationObserver.fire(parent);
  assert.equal(animation.cancelled, 1, '元素被换掉必须取消旧元素上那条动画');

  task.ensure();
  assert.equal(replacement.__animations.length, 0, '仍开着的列在 React 重渲染后不得反复重播');
  hide(replacement);
  task.ensure();
  show(replacement);
  task.ensure();
  assert.equal(replacement.__animations.length, 1, '真正关开一轮后新列仍应升起');
  task.dispose();
});

test('槽位缺席释放所有权；重新挂回的列沿用交接产物「不重播」语义', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { parent, explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  const [animation] = explorer.__animations;

  artifact.elements.set(EXPLORER, null);
  task.ensure();
  assert.equal(animation.cancelled, 1, '槽位缺席必须取消自己那条动画，不能把记录一直挂着');

  const fresh = artifact.createElement('section');
  fresh.parentElement = parent;
  show(fresh);
  artifact.elements.set(EXPLORER, fresh);
  task.ensure();
  assert.equal(fresh.__animations.length, 0,
    '交接产物刻意保留「摘掉再挂回不重播」的既有语义，整合不得擅自改变');
  hide(fresh);
  task.ensure();
  show(fresh);
  task.ensure();
  assert.equal(fresh.__animations.length, 1, '真正的关闭→打开一轮仍必须重新升起');
  task.dispose();
});

test('dispose 取消自己的动画并断开 observer，之后不再被 DOM 变化唤醒', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { parent, explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  task.ensure();
  const [animation] = explorer.__animations;
  const observer = FakeMutationObserver.instances.at(-1);
  assert.ok(observer, '必须创建有界 observer 观察列的父节点');
  assert.equal(observer.targets.has(parent), true);

  task.dispose();
  assert.equal(animation.cancelled, 1, '卸载必须取消自己的动画');
  assert.equal(observer.disconnected, true, '卸载必须断开 observer');
  assert.equal(observer.targets.size, 0);
  assert.doesNotThrow(() => FakeMutationObserver.fire(parent));
});

test('observer 有界：全任务只建一个，且只观察当前列的直接父节点', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const first = artifact.createElement('div');
  const second = artifact.createElement('div');
  let explorer = artifact.createElement('section');
  explorer.parentElement = first;
  artifact.elements.set(EXPLORER, explorer);
  let preview = artifact.createElement('section');
  preview.parentElement = second;
  artifact.elements.set(PREVIEW, preview);
  const task = riseTask(artifact);
  show(explorer);
  show(preview);
  task.ensure();
  assert.equal(FakeMutationObserver.instances.length, 1, '不得为每个列/每次 ensure 新建 observer');
  const observer = FakeMutationObserver.instances[0];
  assert.deepEqual([...observer.targets].sort(), [first, second].sort(), '只观察列的直接父节点');
  task.dispose();
});

test('从不取消宿主 / 第三方的动画', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { explorer, preview } = sheetFixture(artifact);
  const task = riseTask(artifact);
  const foreignExplorer = new FakeAnimation([], {});
  const foreignPreview = new FakeAnimation([], {});
  explorer.__foreign.push(foreignExplorer);
  preview.__foreign.push(foreignPreview);

  show(explorer);
  show(preview);
  task.ensure();
  hide(explorer);
  task.ensure();
  show(explorer);
  task.ensure();
  explorer.isConnected = false;
  FakeMutationObserver.fire(explorer.parentElement);
  task.dispose();

  assert.equal(foreignExplorer.cancelled, 0, '只应取消本任务启动的 Animation 对象');
  assert.equal(foreignPreview.cancelled, 0);
  const cancelled = [...explorer.__animations, ...preview.__animations].filter(row => row.cancelled > 0);
  assert.ok(cancelled.length > 0, '本任务自己的动画仍应被取消');
});

test('快速开关 25 轮：创建数=打开数、每次关闭取消自己的那条、无叠加', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { explorer } = sheetFixture(artifact);
  const task = riseTask(artifact);
  for (let round = 0; round < 25; round += 1) {
    hide(explorer);
    task.ensure();
    show(explorer);
    task.ensure();
  }
  assert.equal(explorer.__animations.length, 25, '每轮打开一条，不多不少');
  assert.equal(explorer.__animations.filter(row => row.cancelled > 0).length, 24,
    '除最后一条外，每次关闭都取消自己那条');
  assert.equal(explorer.__animations.filter(row => row.cancelled === 0).length, 1,
    '任何时刻同一列最多一条自己的动画在跑');
  task.dispose();
});

test('两列各自拥有自己的动画：关掉 explorer 不影响 preview', () => {
  const artifact = loadMobileArtifact({ width: 1280, pointer: 'coarse' });
  const { explorer, preview } = sheetFixture(artifact);
  const task = riseTask(artifact);
  show(explorer);
  show(preview);
  task.ensure();
  const [explorerAnimation] = explorer.__animations;
  const [previewAnimation] = preview.__animations;
  hide(explorer);
  task.ensure();
  assert.equal(explorerAnimation.cancelled, 1);
  assert.equal(previewAnimation.cancelled, 0, '另一列的动画不属于本列，不得被连带取消');
  task.dispose();
});
