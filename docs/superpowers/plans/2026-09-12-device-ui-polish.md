# 实机 UI 精细化实施与验收

用户已批准根据实机检查直接修改、构建并安装到已连接的 iQOO 手机。

目标：消除加载完成后的布局错乱、统一页面边距与主题表现，提高首页、持仓、K 线的信息层级。

技术：Kotlin Multiplatform / Kuikly 2.7 / Android。

- [ ] 骨架屏计时改用所属 Pager 的定时回调，测试未到回调时不得继续，以及慢请求无需额外等待。
- [ ] 首页和我的使用普通 View 承担内容 padding，避免 Scroller 内外重复留白。
- [ ] 首页指南轻量化、被动刷新不显示成功提示、盘面去重、工作台紧凑化。
- [ ] 持仓总览拆成独立数字区域，单股持仓分行，操作按钮居中。
- [ ] 主 Tab 标题栏与底栏图标统一；主题选择器动态取色；切主题同步系统状态栏。
- [ ] K 线保留周期/MA/副图，高级工具折叠，AI 说明移到图表之后。
- [ ] 执行 Android 单测与 assembleDebug，adb install -r 保留用户数据。
- [ ] 实机验收五个 Tab、详情工具展开、浅深反复切换和快速切页，保存截图。

诊断依据：旧版实机截图在 Codex visualizations 的 ui-audit 目录。Interaction.kt 使用 kotlinx.coroutines.delay，而 Kuikly LifecycleScope 没有调度器；延时后视图更新必须回所属页面队列。Scroller 初始化复制四边 padding 后只清 ALL，改由普通内容 View 管理内边距。
