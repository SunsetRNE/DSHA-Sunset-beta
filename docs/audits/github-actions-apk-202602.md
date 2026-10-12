# GitHub Actions 二月变更与 APK 直接下载

核验日期：2026-10-12。研究以 GitHub 官方公告、官方 Actions release/action.yml 为准。

## 2026 年 2 月的直接相关变化

- **2026-02-26**：Actions 新增非压缩 artifact。`actions/upload-artifact` v7 配合 `archive: false` 可让浏览器直接下载上传的文件（包括 APK）；默认 `archive: true` 保持 ZIP，历史 artifact 不变。[官方公告](https://github.blog/changelog/2026-02-26-github-actions-now-supports-uploading-and-downloading-non-zipped-artifacts/)
- v7 的直接上传**一次只允许一个文件**；通配符匹配多个文件会失败；`name` 被忽略，artifact 名称取上传文件名。这是通用文件支持，没有 APK 专属格式开关。[v7.0.0 发布说明](https://github.com/actions/upload-artifact/releases/tag/v7.0.0) · [输入定义](https://github.com/actions/upload-artifact/blob/v7.0.0/action.yml)
- 同一工作流的下载端要使用 `actions/download-artifact` v8；它识别非压缩内容，摘要不符默认失败。[v8.0.0 发布说明](https://github.com/actions/download-artifact/releases/tag/v8.0.0)
- **2026-02-19**：workflow dispatch API 可用 `return_run_details: true` 返回运行信息；新版 `gh workflow run` 可直接返回运行 URL。[官方公告](https://github.blog/changelog/2026-02-19-workflow-dispatch-api-now-returns-run-ids/)

## 本仓库接入

实验打包保持固定测试签名和 `com.dsh.clienu.SunsetRNE` 包名，等待同一 SHA 的 Source checks 通过，核验离线与应急资产、两版 APK 及回执后上传：

- `SunsetRNE-standard-experimental.apk`：单文件，`archive: false`。
- `SunsetRNE-low-experimental.apk`：单文件，`archive: false`。
- `SunsetRNE-experimental`：仅摘要和回执，ZIP。其共同根为 `dist`，下载后回执与摘要位于目标目录根部。
- `experimental-test-reports`：主机和 Gradle 测试报告，ZIP。

可选发布步骤采用 v8 的 `pattern: SunsetRNE-*` 和 `merge-multiple: true`，两份 APK 与回执在同一目录验证；本次仅 CI 打包，`publish=false`。

锁定上传版本为 v7.0.2 / `cf430e030ddbb5b0abf93d22962f4752f3646cd9`；下载为 v8.0.0 / `70fc10c6e5e1ce46ad2ea6f2b72d43f7d47b13c3`。v7.0.2 发布于 2026-10-07，是二月功能的后续补丁版。[官方发布](https://github.com/actions/upload-artifact/releases/tag/v7.0.2)

基础 checkout/setup-java/setup-node 也固定到实际使用 Node 24 的 v5 提交。历史 runner 对 Node 20 Action 曾强制使用 Node 24 运行，因此不能仅从旧 action.yml 推断全部旧 CI 必然失败；本次更新消除对该兼容行为的依赖。

## 输入与可验证范围

离线构建输入不进 Git；`tools/ci-assets.py` 只允许固定成员集，并核对源码描述符及 SHA-256。允许从同仓库固定 Release asset ID 认证下载到临时文件后以 `--file` 校验；外部 HTTPS 地址使用无凭据下载。构建输入可保存为草稿，APK 不自动发布 Release。

远程通过、APK 文件摘要和是否可覆盖安装必须以本次实际 Actions 结果为准。源码静态检查不代替远程构建、设备安装或 Android 版本兼容矩阵。
