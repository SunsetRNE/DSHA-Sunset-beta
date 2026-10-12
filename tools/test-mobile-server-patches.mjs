import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { applyMobileServerPatch, upstreamCommit, upstreamServerHashes } from './apply-mobile-client-patches.mjs';

// 固定上游目录由 run-host-tests 的 {mobileUpstream} 传入（默认 app/build/mobile-upstream-a094/lib）。
// 缺字节必须失败：上游原始字节核验是这个用例的证据，不能用“文件不存在就跳过”冒充通过。
// 目录由 tools/prepare-ci-mobile-upstream.mjs 按固定提交取回，测试自身不联网。
const upstreamDirectory=process.env.DSHA_MOBILE_UPSTREAM_DIR
  ??'app/build/build166/upstream/dsh-web-mobile-9b16223e6c5ee8209c25034b24fb790990967b3f/lib';

for(const [name,hash] of Object.entries(upstreamServerHashes)) {
  test(`mobile server ${name} canonical output is idempotent and input drift is rejected`,()=>{
    const output=readFileSync(join('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib',name));
    assert.deepEqual(applyMobileServerPatch(name,output),output);
    assert.deepEqual(applyMobileServerPatch(name,applyMobileServerPatch(name,output)),output);
    assert.throws(()=>applyMobileServerPatch(name,Buffer.concat([output,Buffer.from('\n// drift')])) ,/fixed commit\/anchor/);
    const upstream=join(upstreamDirectory,name);
    assert.ok(existsSync(upstream),`固定上游模块缺失: ${upstream}；先运行 node tools/prepare-ci-mobile-upstream.mjs 取回 ${upstreamCommit} 的原始字节`);
    const bytes=readFileSync(upstream);
    assert.equal(createHash('sha256').update(bytes).digest('hex'),hash);
    assert.deepEqual(applyMobileServerPatch(name,bytes),output);
  });
}
