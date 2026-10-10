# Root / ADB 支持评估与实现

本目录保存 TB321FU 的历史取证、修正后的设计，以及 2026-10-10 的原生实现与落地前验证。本轮已从纯策略准备扩展到五个固定自举动作和原生 UI 接线；没有操作 Android 设备，也没有生成或安装发布 APK。历史样本不能代替当前设备验收。

| 材料 | 用途 |
|---|---|
| [本轮验证回执](validation-20261010.json) | 精确命令、通过/受阻范围、源码与日志摘要 |
| [实现与验证说明](implementation-20261010.md) | 本轮入口、状态、事务、UI、兼容分支及验证限制 |
| [虚拟屏真机验证](device-vscreen-verification-20261010.md) | 在另一包（SunsetRNE）上跑一键自举的真实结果、三处阻塞与待修缺陷 |
| [打通到 OPEN：四个真因](guard-visibility-20261011.md) | 失败可见 + 证据通路；`ps` 列名/空格、证据污染、幂等与通道顺序四个真因；真机已到 OPEN 的回执 |
| [同步上游 build170 与 C 方案](fix-c-wireless-debug-20261010.md) | 合并上游 7 个提交（含 ADB 冷目录修复）的冲突处置，以及无线调试冲突的 C 实现 |
| [设计](design.md) | 原生授权、固定操作、密钥所有权与双栈顺序 |
| [工作量与阶段](workload-assessment.md) | 已实现范围、剩余设备矩阵与历史估算边界 |
| [历史证据](evidence.md) | R1–R12 捕获原文及修正判读 |
| [机型档案](devices/TB321FU.md) | 历史环境，非当前兼容性声明 |
| [chroot 状态卡](chroot-l1.md) | L1 执行链仍未证明；本轮不改变 L0 默认运行时 |

当前代码包括独立默认关闭的自举授权、原生确认、应用级单任务控制器、短期原生授权代理、特权身份核验、密钥事务与双栈规则。`authorize/revoke/narrow/open/close` 是固定命名动作；`status` 只读。不接受任意设备路径、属性名、公钥正文或 shell 程序作为命名请求参数，通用命令白名单未放宽。

界面分别展示授权状态、最后请求、公钥、监听、防护和观测时间。旋转或语言切换只重新绑定状态，不重发设备动作。关闭授权不等于系统公钥已撤销或监听已关闭；清理操作需要仍有效的 Root 原生许可。

历史 R12 分别记录过 daemon UID 0 和一次强制 ADB 会话 UID 0。它不能证明所有会话与任意 root 通道完整同权。属性回显不证明仅监听回环，IPv4 规则不证明 IPv6 已受保护；本轮实现检查两族及规则位置，但不承诺规则跨重启或厂商网络刷新持续有效。

历史原型脚本已在入口退出 126；原始字节和原文保存在交付材料的 ORIGINAL。它们不是部署入口，不能用来绕过设备策略。密钥撤销只处理本应用证明拥有的条目，不能清空共享授权表。

主机定向回归入口：

```bash
python3 -B tools/test-root-support.py
python3 -B tools/test-root-bootstrap-endpoint.py
python3 -B tools/test-native-device-policy.py
python3 -B tools/test-device-shell-policy.py
python3 -B tools/run-host-tests.py --check-manifest
python3 -B tools/verify-runtime-descriptor-source.py
```

完整软件验证继续使用仓库构建入口。离线归档、签名、API 23–25 设备、真实双栈网络、root 管理器和 UI 样式均有独立前提；实际通过范围以本轮最终验证回执为准，不继承历史报告数字。
