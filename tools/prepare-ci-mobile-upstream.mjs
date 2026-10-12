#!/usr/bin/env node
// 在 CI 前置按固定提交取回 dsh-web-mobile 的服务端模块原始字节。
// 只读固定 HTTPS 地址：仓库与提交来自随包 package.json 的 dshaUpstream，
// 提交必须与 tools/apply-mobile-client-patches.mjs 导出的 upstreamCommit 一致；
// 每份字节写盘前核对锁定 sha256。测试自身不联网，也不允许用“跳过”冒充通过。
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { upstreamCommit, upstreamServerHashes } from './apply-mobile-client-patches.mjs';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const metadataFile = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/package.json');
// 与 run-host-tests.py 的 --mobile-upstream 固定默认目录保持一致。
const defaultOutput = 'app/build/mobile-upstream-a094/lib';

function parseArguments(argv) {
  const options = { output: defaultOutput, force: false };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--output') {
      options.output = argv[index + 1];
      index += 1;
    } else if (value === '--force') {
      options.force = true;
    } else {
      throw new Error('用法: node tools/prepare-ci-mobile-upstream.mjs [--output <上游 lib 目录>] [--force]');
    }
  }
  if (!options.output) throw new Error('--output 需要目录参数');
  return options;
}

function upstreamRepository() {
  const metadata = JSON.parse(readFileSync(metadataFile, 'utf8')).dshaUpstream;
  if (!metadata) throw new Error('内置插件缺少 dshaUpstream 固定来源');
  if (metadata.commit !== upstreamCommit) {
    throw new Error(`内置插件固定提交与补丁模块不一致: ${metadata.commit} != ${upstreamCommit}`);
  }
  const match = /^https:\/\/github\.com\/([^/]+)\/([^/]+?)(?:\.git)?$/.exec(String(metadata.source ?? '').replace(/\/+$/, ''));
  if (!match) throw new Error('内置插件 dshaUpstream.source 不是可识别的 GitHub HTTPS 仓库: ' + metadata.source);
  return { owner: match[1], name: match[2], commit: metadata.commit };
}

async function fixedUpstreamBytes(url) {
  const response = await fetch(url, { redirect: 'follow' });
  if (!response.ok) throw new Error(`固定上游取值失败 HTTP ${response.status}: ${url}`);
  return Buffer.from(await response.arrayBuffer());
}

const options = parseArguments(process.argv.slice(2));
if (typeof fetch !== 'function') throw new Error('需要 Node 24 的内置 fetch');
const repository = upstreamRepository();
const directory = path.resolve(project, options.output);
mkdirSync(directory, { recursive: true });
const files = [];
for (const [name, sha256] of Object.entries(upstreamServerHashes)) {
  const target = path.join(directory, name);
  let bytes = null;
  try {
    bytes = readFileSync(target);
  } catch {
    bytes = null;
  }
  if (!options.force && bytes && createHash('sha256').update(bytes).digest('hex') === sha256) {
    files.push({ name, sha256, fetched: false });
    continue;
  }
  // 上游模块目录由补丁模块的锚点固定（package.json main=lib/index.js），摘要不符即失败。
  const url = `https://raw.githubusercontent.com/${repository.owner}/${repository.name}/${repository.commit}/lib/${name}`;
  bytes = await fixedUpstreamBytes(url);
  const actual = createHash('sha256').update(bytes).digest('hex');
  if (actual !== sha256) throw new Error(`固定上游字节与锁定摘要不符: ${name} ${actual} != ${sha256}`);
  const temporary = target + '.partial';
  writeFileSync(temporary, bytes);
  renameSync(temporary, target);
  files.push({ name, sha256, fetched: true });
}
console.log(JSON.stringify({
  repository: `${repository.owner}/${repository.name}`,
  commit: repository.commit,
  directory: path.relative(project, directory).split(path.sep).join('/'),
  files,
}, null, 2));
