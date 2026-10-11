# Domain Docs

工程技能在探索本仓库之前，按这里的规则读域文档。

## 布局结论：single-context

本仓库的域文档布局是 **single-context**：全仓库共用一份根目录的域词汇文件，架构决策放在 `docs/adr/`。（插件初始化卡片把这份根目录文件称作 `CONTEXT.md`；本仓库该文件的实际文件名是 `GLOSSARY.md`。）

当前只有这一份根目录词汇文件，没有 `CONTEXT-MAP.md`，也没有子项目级词汇文件 —— 按下面的规则按需创建。

## Before exploring, read these

- **`GLOSSARY.md`**（仓库根）：域词汇的唯一真源，也是术语漂移要对照的那一份。
- **`docs/adr/`**：读与你接下来要动的区域相关的 ADR。

这些文件不存在就**安静跳过**：不要点出它们缺失，也不要提议先建。`/domain-modeling`（经 `/grill-with-docs`、`/improve-codebase-architecture` 到达）会在词条或决策真正定下来的那一刻按需创建它们；第一次写下词条之前，不预建任何骨架。

## File structure

single-context 仓库：

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-build154-boundaries.md
│   └── 0002-current-threat-model.md
└── app/
```

multi-context 仓库（根目录有 `GLOSSARY-MAP.md` 时才算）：系统级决策放根 `docs/adr/`，每个上下文各自一份词汇文件与 `docs/adr/`，由根目录的 map 指向它们。

## Use the glossary's vocabulary

当你的产出要写下一个域概念（issue 标题、重构提案、假设、测试名）时，用 `GLOSSARY.md` 里定义的词，不要漂到词条明确标注要避免的同义词上。

需要用的概念词条里还没有，这是一个信号：要么你在发明这个项目不用的语言（再想想），要么确实缺一条（记下来给 `/domain-modeling`）。

## Flag ADR conflicts

产出的结论与既有 ADR 冲突时，明确指出来，不要让新结论悄悄覆盖旧决策：

> _与 ADR-0007（event-sourced orders）冲突，但值得重开，因为……_
