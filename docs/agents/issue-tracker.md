# Issue tracker: GitHub

本仓库的 issue 与 spec 都作为 GitHub issue 存在：`DSH-APP/DSHA`。所有读写走 `gh` CLI。

## 约定

- **新建 issue**：`gh issue create --title "..." --body "..."`。多行正文用 heredoc。
- **读 issue**：`gh issue view <number> --comments`，用 `jq` 过滤评论，并一并取标签。
- **列 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需加 `--label` / `--state`。
- **评论**：`gh issue comment <number> --body "..."`。
- **加 / 去标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`。
- **关闭**：`gh issue close <number> --comment "..."`。

仓库从 `git remote -v` 推断；在 clone 目录里 `gh` 会自动识别。标题与正文用中文，跟本仓库既有 issue 的写法一致。

标签词汇（triage 五角色 + wayfinder 五枚）见 `docs/agents/triage-labels.md`。

## Pull requests as a triage surface

**PRs as a request surface: no.**

设为 `yes` 时，PR 走与 issue 相同的标签与状态，命令换成 `gh pr` 系列：

- **读 PR**：`gh pr view <number> --comments`，diff 用 `gh pr diff <number>`。
- **列出待 triage 的外部 PR**：`gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`，只留 `authorAssociation` 为 `CONTRIBUTOR` / `FIRST_TIME_CONTRIBUTOR` / `NONE` 的（丢掉 `OWNER` / `MEMBER` / `COLLABORATOR`）。
- **评论 / 打标签 / 关闭**：`gh pr comment`、`gh pr edit --add-label` / `--remove-label`、`gh pr close`。

GitHub 的 issue 与 PR 共用一个编号空间，裸写的 `#42` 可能是两者之一：先 `gh pr view 42`，失败再回退 `gh issue view 42`。

## When a skill says "publish to the issue tracker"

建一张 GitHub issue。

## When a skill says "fetch the relevant ticket"

跑 `gh issue view <number> --comments`。

## Wayfinding operations

`/wayfinder` 用这几条。**map** 是一张带 `wayfinder:map` 标签的单张 issue，**票**（ticket）是它的子 issue。

- **Map**：`gh issue create --label wayfinder:map`，正文装 Notes / Decisions so far / Fog。
- **Child ticket**：作为 GitHub sub-issue 挂到 map（`gh api` 的子 issue 端点）。仓库没开 sub-issue 时，把子 issue 加进 map 正文的 task list，并在子 issue 正文顶部写 `Part of #<map>`。标签 `wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）；被认领后 assign 给驱动这张 map 的开发者。
- **Blocking**：用 GitHub **原生 issue 依赖**，在 tracker 自己的界面上就看得见。加边：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，不是 `#number`，也不是 `node_id`）。GitHub 用 `issue_dependencies_summary.blocked_by` 报还开着的 blocker。依赖功能不可用时回退：在子 issue 正文顶部写一行 `Blocked by: #<n>, #<n>`。所有 blocker 关闭后，这张票才算 unblocked。
- **Frontier query**：列出 map 未关闭的子 issue（`gh issue list --state open`，范围限定在该 map 的 sub-issue / task list），去掉有未关闭 blocker 的（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行里还有未关闭的 issue）和已有 assignee 的；按 map 顺序取第一张。
- **Claim**：`gh issue edit <n> --add-assignee @me`，本次会话的第一笔写。
- **Resolve**：`gh issue comment <n> --body "<answer>"`，再 `gh issue close <n>`，最后把上下文指针（gist + 链接）追加到 map 的 Decisions so far。
