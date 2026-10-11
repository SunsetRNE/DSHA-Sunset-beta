# Triage Labels

技能用五个固定的 triage 角色说话。本文件把角色映射到本仓库 issue tracker 里实际使用的标签字符串。

| 技能里的标签 | 本仓库的标签 | 含义 |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | 维护者需要先评估这张票 |
| `needs-info` | `needs-info` | 等报告人补信息 |
| `ready-for-agent` | `ready-for-agent` | 已完整规格化，可交给 AFK agent 实现 |
| `ready-for-human` | `ready-for-human` | 需要人来实现 |
| `wontfix` | `wontfix` | 不会处理 |

技能提到某个角色时（例如「贴上 AFK-ready 的 triage 标签」），用表右列对应的标签字符串。本仓库沿用默认五角色，不做改名。

## Wayfinder labels

`/wayfinder` 另有五枚标签，与 triage 五角色并存、各管各的：

| 标签 | 含义 |
| --- | --- |
| `wayfinder:map` | map 本体：一张 issue 承载 Notes / Decisions so far / Fog |
| `wayfinder:research` | research 票（AFK）：把决策等的那件事查清楚 |
| `wayfinder:prototype` | prototype 票（HITL）：先做个粗糙实物，再谈怎么定 |
| `wayfinder:grilling` | grilling 票（HITL）：靠对话把决策问清楚（默认类型） |
| `wayfinder:task` | task 票：做掉某件不决策、但挡住决策的事 |

`bug` 也是本仓库常用的既有标签。给票打标签时严格按技能规则来，不额外强制任何标签。
