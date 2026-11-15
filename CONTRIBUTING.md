# 贡献指南

欢迎修复问题、完善无障碍支持和文档。开发者可以使用 AI 辅助，但需要自己检查改动，并如实说明验证情况。

## 第一次贡献

1. 注册并登录 GitHub，打开 https://github.com/zzzttt1573/DoubaoA11y ，点击 Fork，创建自己账号下的副本。
2. 安装 Git 或 GitHub Desktop，将自己的 Fork 克隆到电脑。登录由本人完成，不向 AI 或其他人发送密码、验证码和访问令牌。
3. 先阅读 AGENTS.md、README.md 和 docs/开发备注.md。新功能和较大修改先创建 Issue 说明计划，等待维护者确认方向并认领任务；小型修复、错别字和文档改进可以直接提交 PR。
4. 从上游最新 main 新建功能分支，例如 fix/voice-cancel。不要把多项不相关功能混在一起。
5. 修改、编译并测试后，提交并推送到自己的 Fork。
6. 在 GitHub 点击 Contribute → Open pull request，确认目标是 zzzttt1573/DoubaoA11y 的 main，来源是自己的功能分支。
7. 按 PR 模板说明问题和测试结果。维护者审核后可能要求修改；继续推送到同一分支即可更新 PR，由维护者决定合并和发布。

已有未合并的工作不要丢弃。每次新任务前同步上游；同步遇到冲突应保留双方意图，不直接用一方覆盖。

## 任务认领与沟通

Issue 是“准备做什么”的任务单，PR 是“代码已经改了什么”的审核单。

- 开始前查看已有 Issue 和 PR，避免重复开发。在 Issue 评论“我准备处理”，说明范围，等待维护者确认。
- 大功能先讨论方案、验收标准，必要时拆成多个小 PR；可以提前开 Draft PR（草稿合并请求）讨论未完成代码。
- QQ 群用于日常交流，重要决定、任务负责人和验收结果要同步到对应 Issue 或 PR。
- 不能通过 Assignees 指派时，在评论中写明负责人并由本人确认即可。
- 已有独立版本的贡献者，先列出与上游的差异，再分项贡献；保留原始分支和作者记录，不直接用整个工程覆盖主线。

## 分支和提交命名

- 新功能：`feat/功能名`，如 `feat/candidate-navigation`。
- Bug 修复：`fix/问题名`，如 `fix/voice-cancel`。
- 文档：`docs/文档名`；重构：`refactor/模块名`。
- 提交说明推荐 `类型: 中文说明`，类型使用 feat、fix、docs、refactor、test 或 chore。例如 `fix: 修复取消语音后文字仍然上屏`。
- 一个提交表达清楚一项修改，保留真实作者和来源，不编造贡献者或测试记录。

## 命令行示例

将“你的用户名”和分支名替换成自己的。以下首次设置远程只执行一次；GitHub Desktop 用户可用界面完成同样操作。

```bash
git clone https://github.com/你的用户名/DoubaoA11y.git
cd DoubaoA11y
git remote add upstream https://github.com/zzzttt1573/DoubaoA11y.git
git fetch upstream
git switch -c fix/voice-cancel upstream/main
```

修改后先检查差异，只暂存实际修改的文件：

```bash
git status
git diff
git add 本次修改的文件路径
git diff --cached
git commit -m "fix: 说明具体问题"
git push -u origin fix/voice-cancel
```

GitHub 官方图文教程：https://docs.github.com/en/get-started/exploring-projects-on-github/contributing-to-a-project

## 同步开发分支与处理冲突

网页上的 Sync fork 只更新 Fork 的对应分支，不会自动更新已有功能分支。先提交或妥善保存手头工作，再在自己的功能分支执行：

```bash
git fetch upstream
git switch fix/voice-cancel
git merge upstream/main
```

发生冲突时，检查双方修改的目的，与相关开发者协调后解决；不要整文件强制保留一边。解决后重新编译和验证，再推送同一分支。对已有人协作的分支，不擅自变基或强推。

## 提交 PR 与审核

- 目标仓库：`zzzttt1573/DoubaoA11y`；目标分支：`main`；来源：自己的 Fork 功能分支。
- 按模板写明修改目的、关联 Issue、修改类型、测试结果和已知限制。确实解决对应问题时可写 `Closes #编号`。
- 界面和无障碍修改可附脱敏截图、控件树或文字操作记录，不强制视障贡献者提供截图。
- 维护者可能评论、要求修改或暂缓合并。向原分支继续提交即可更新 PR，不必重复创建。
- 自动编译通过不代表读屏实测通过，提交 PR 也不代表一定合并。
- 由维护者审核合并并统一发布；合并后可删除已完成的功能分支。

## 编译和测试

- 使用 JDK 17 或 21、Android SDK platform 35。自己的 SDK 路径写入 local.properties，不提交该文件。
- Windows：`.\gradlew.bat assembleRelease`；Linux/macOS：`bash ./gradlew assembleRelease`。
- 产物位于 app/build/outputs/apk/release/app-release.apk。本地和 CI 构建使用调试证书，不能当作维护者正式发布包；安装前注意签名不一致可能无法覆盖。
- PR 自动检查会尝试编译，不会自动证明读屏行为正确，也不会自动合并或发布。
- 触摸、朗读、语音和焦点行为请尽量实机验证，报告读屏名称、手机、安卓、豆包版本及相关配置；没有设备就写“未实机验证”。
- 修改文档时确保 docs/使用说明.txt 与 package/使用说明.txt 一致。

## 交付边界与来源

- 不修改无关功能，不擅自改版本、更新地址或发布标签。
- 不提交隐私日志、账号、密钥、第三方 APK 或 docs/local/ 里的资料。
- 外部实现和数据须说明来源及许可证；保留原有版权和署名，不能将借鉴或搬运声明成原创。
- 贡献按本仓库 GPL-3.0 许可证提供，第三方部分仍需遵守各自兼容的许可要求。

## 可以交给 AI 的任务说明

> 请先读 AGENTS.md、CONTRIBUTING.md 和开发备注。从上游最新 main 创建独立功能分支，保留已有工作，只处理本次需求。完成后编译，说明真实测试结果和未验证内容，检查差异及隐私信息。不要改版本号或发布配置。经我确认后推送到我的 Fork，向上游 main 创建中文 PR，不自行合并或发布。GitHub 登录由我本人完成。
