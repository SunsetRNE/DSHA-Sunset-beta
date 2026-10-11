# 仓库关系、远程与同步流程

本文说明本仓库（实验分支）与主线仓库的关系，以及日常同步 / 提交的固定做法。
2026-10-12 建立。

## 1. 两个仓库的角色

| 角色 | 仓库 | 说明 |
|---|---|---|
| **主线（主分支）** | <https://github.com/DSH-APP/DSHA> | 项目的正式主线，默认分支 `main`。功能与发布以此为准 |
| **本仓库（实验分支）** | <https://github.com/SunsetRNE/DSHA-Sunset-beta> | **不是 GitHub Fork**（没有 fork 关系、不显示 “forked from”）。用于设备实测、修复批次与验收取证 |

本仓库**不替代**主线：正式发布、上游功能演进仍在 `DSH-APP/DSHA`。本仓库承载的是
「在真机上先把问题查清 / 修好 / 验证一遍」的实验性工作，成熟后再以正常 PR 贡献回主线。

## 2. 本地远程配置

```bash
git remote -v
# origin    https://github.com/SunsetRNE/DSHA-Sunset-beta.git  (fetch/push)  ← 本仓库（实验分支）
# upstream  https://github.com/DSH-APP/DSHA.git                (fetch/push)  ← 主线
```

- `origin`：本地改动提交后推到这里。
- `upstream`：只用来拉主线最新；推送到 upstream 需要主线的写权限（一般没有，走 PR）。
- `main` 跟踪 `origin/main`（实验仓库的 main）。

## 3. 同步主线最新（固定流程）

```bash
git fetch upstream --prune
git merge upstream/main            # 有冲突就按业务语义解决，禁止 force push 覆盖历史
# 生成物冲突（docs/star-history*.svg / .json 等自动刷新文件）直接取主线版本：
#   git checkout --theirs docs/star-history-dark.svg docs/star-history.svg docs/star-history.json
git push origin main
```

约定：

- **不 force push `main`**；历史一律保留（实验仓库也要能追溯）。
- 合并主线后跑一遍宿主回归与描述符检查（见 [handover-20261012.md](handover-20261012.md) §5）。
- 与主线冲突时以主线行为为准，除非本次实验**有意**改变该行为 —— 那要在提交信息与
  `docs/` 里写清原因。

## 4. 向主线贡献

本仓库与主线之间**没有 fork 关系**，因此 GitHub 不会提供 “Create PR from fork” 的快捷入口；
需要正常发起 PR：

```bash
gh pr create --repo DSH-APP/DSHA --base main --head SunsetRNE:main   # 需要主线的对应权限
```

若没有直接 PR 权限，就把改动整理成补丁 / 分支说明交给主线维护者。

## 5. 其他

- 已忽略、不进公开仓库的内容：构建产物、离线 rootfs、发布 APK、`verify-vscreen/`（本地真机取证）。
- 仓库描述（GitHub About）由本仓库维护者通过 `gh repo edit SunsetRNE/DSHA-Sunset-beta --description …` 更新，
  与本文第 1 节保持一致。
