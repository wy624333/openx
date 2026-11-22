# OpenX 更新日志

> 倒序排列，最新在最上。

## 2.10.1（vc104）· 内置桌面宠物插件、首次引导、每日检查更新（2026-11-22）

> 版本号：2.10.0 / vc103 → **2.10.1 / vc104**。

### 新增
- **内置桌面宠物插件**（`openx.pet` v2.2.11）：DeepSeek 吉祥物桌宠，30 张透明 WebP 立绘，
  用浮窗绘制。会呼吸、浮动、自己沿屏幕走动、走到边上紧贴屏幕边缘；气泡窗口独立开窗且不接收
  触摸（否则气泡会把底下 App 的点击吞掉）。能跟着 AI 会话状态变脸（思考 / 干活 / 等你确认 /
  上下文吃满 / 出错），靠 `ctx.host.session` 的聚合状态快照实现——只读状态不读对话正文。
  体型可小到 4dp；10 个配置项分外观 / 性格 / 行为三组，走宿主声明式配置页渲染。
  声明 `overlay` / `env` / `notify` / `schedule` / `session` 五个权限，
  `overlayAutoStart` 让 App 启动时自动回到桌面，定时任务 15 分钟检查一次掉线拉起。
- **首次启动引导弹窗**（`OnboardingDialog`）：本机首次进入软件时弹一次，三步介绍
  （配置 API / 配置容器环境 / 配置插件运行环境），每步带「去配置」直接跳到设置对应分区。
  没配 API 发不出消息、没装容器插件跑不起来，不说清楚用户会以为软件坏了。
  标记存独立 SharedPreferences（`openx_onboarding`），点「开始使用」或「稍后再说」后不再出现。
- **每日自动检查更新**（`UpdateScheduler`）：启动后延迟 3 秒查一次，24 小时内最多一次。
  本应用没有常驻后台进程，用户不开软件就没有代码在跑，只能等他下次启动时补查，
  所以用时间戳节流而非定时任务。查失败也记一次时间戳——否则断网时每次启动都白等一次超时。
- **新版本提醒弹窗**（`UpdateAvailableDialog`）：显示版本号与更新说明，可选「立即更新」
  （应用内下载并拉起安装器）或「跳过此版本」。跳过的 versionCode 记在本地，
  之后只对更高版本再提醒——否则同一版本天天弹，用户只能学会无视弹窗。

### 改进
- **设置页三个「全局模式」开关文案统一**：工具权限 / 思考强度 / 模型选择统一为
  「开：聊天页切X改全局并统一所有会话；关（默认）：…」，标点也用同一套半角分号。
- **「模型选择模式」默认值改为关**（`modelGlobalMode = false`）：全局模式下全新安装会直接
  沿用 `AppSettings.model` 的出厂默认值，聊天页顶栏一开就显示一个用户从未选过的模型名。
  默认会话级后新会话一律「未选择模型」，需用户自己选。已有用户存过的值不受影响
  （读取顺序：新键 → 旧键取反 → 默认值）。

### 修复
- **全新安装凭空多出一条 SiliconFlow 提供商**：`seedProvidersFromLegacy()` 本是给旧版升级用的
  迁移逻辑（把扁平字段里的旧配置转成提供商记录），但只按「提供商表为空」判断，
  而 `AppSettings` 的默认值本身就写着 `SiliconFlow` / `Qwen/Qwen3.5-4B`，于是把默认值当成
  用户配置存进了数据库，还因 `selectedProviderId` 被回填而标上「当前」。
  新增 `hasLegacyConfig()`：只有用户填过 Key 或改过地址 / 服务商 / 模型任意一项才执行迁移。
- **未选模型时顶栏显示出厂默认模型名**：改为先判 `effectiveProviderId`，未绑定任何提供商
  一律显示 `[未选择模型]`。会话列表的新建 / 分叉两处 meta 文案同病，一并修。

## 2.10.0（vc103）· 应用内检查更新与下载安装、关于页法律信息（2026-11-21）

> 版本号：2.9.9 / vc102 → **2.10.0 / vc103**。
> 首个开源发布版本：https://github.com/wy624333/openx

### 新增
- **应用内检查更新**：`update/UpdateChecker` 走 GitHub Releases API（`wy624333/openx`）。
  `api.github.com` 国内常被拦截，故按 `API_HOSTS` 顺序逐个尝试官方与反代镜像；
  版本号优先取更新说明里约定的 `versionCode: 103` 一行，缺失时按
  `主*10000 + 次*100 + 修订` 折算，保证单调可比。
- **应用内下载与安装**：下载按 `MIRROR_PREFIXES` 逐条回退（前缀 + 原始地址），
  全失败才报错；落 `cacheDir/update/`，带进度回调，界面显示百分比。
  未授予「安装未知应用」时先引导去授权页，安装前经 `FileOpenHelper` 校验包名后缀。
- **关于页法律信息**：新增「用户协议」「隐私政策」「开源许可」三个条目，
  文档在 `assets/legal/`，由 `DocScreen` 复用 `MarkdownContent` 渲染，离线可读。
  刻意不放 `assets/docs/`——那是 AI 记忆参考文档目录，会被 `seedBuiltinDocs()`
  释放给模型检索，用户协议这类文档不该进参考资料库。
- **意见反馈**：关于页新增入口，跳 GitHub Issues。
- **恢复内置设备监控插件**（`openx.device-monitor` v1.1.1）：CPU 频率负载、内存、
  存储、电池温度与运行时长，支持浮窗常驻；随 `BuiltinPluginInstaller` 释放到 `.openx/plugins/`。

### 改进
- **关于页版本行改为可点**：查询最新版本，有新版本再次点击即在应用内下载并拉起安装器。
  状态依次显示 `vX (vcN)` → `检查中…` → `已是最新` / `vX 可更新` / `下载中 N%` / `检查失败，重试`。
- **各管理页说明收进顶栏 ⓘ 弹窗**：`ManageCommonUi` 新增 `GuideDialog`（小标题 + 正文分段，可滚动），
  `ManageTopBar` 增加 `onGuide` 参数，标题补 `maxLines=1` + 省略号。
  插件 / MCP / 记忆 / 技能 / 工作流管理页、远程连接页、设置页（子代理 / 工具 / 运行环境）
  底部大段说明文字全部上移，页面正文不再被一屏小字淹没。
- `AgentConsoleScreen` 顶栏标题与副标题改为可横滑看全文，切会话回到开头。

### 修复
- **复制消息时剔除 AI 思考内容**：AI 回复复制经 `ChatUtils.removeThinkingContent`
  过滤思考段；气泡复制按钮改用正文原文 `bodyContentRaw`。
- **远程连接与文件浏览面板紧急修复**：`RemoteConnectionBrowser` 重写（剪贴板复制/剪切/粘贴、
  新建文件夹、重命名、删除、属性查看、路径自动定位）；`RemoteSshClient` / `RemoteManager` /
  `RemoteFileBridge` / `RemoteFtpClient` / `RemoteModels` 修复；
  `RemoteServerEditDialog` 密码与私钥路径状态分离。

### 其他
- 移除关于页的「构建时间」显示（每次构建都变，对用户无意义）。
- 新增 `RemoteTrafficMonitorTest`；`PluginAutomationBridge` 新增 `cpu.freqMax`
  （各核 `scaling_max_freq` / `cpuinfo_max_freq` 最大值，kHz），
  并注明 `cpu.usage` 在 Android 8.0+ 恒为 -1（SELinux proc_stat 限制），
  负载比例改用 freqs 均值 / freqMax。

## 2.9.9（vc102）· 插件浮窗与声明式配置、Git 凭据管理、SAF 文件开放（2026-11-19）

> 版本号：2.9.8 / vc101 → **2.9.9 / vc102**。

### 新增
- **插件浮窗（桌面宠物）**：`PluginOverlayWindow` 重写，支持 `chrome`（标题栏）/
  `transparent` / `dragAnywhere` / `rememberPosition`，几何与可见状态落盘；
  UI 轨道与后台脚本轨道共用同一窗口实例（`obtain` 按插件 id 共享），
  避免「面板已开浮窗、后台任务以为没开又拉一个」。
  新增 `overlayAutoStart` 清单字段：App 启动时按上次退出状态恢复浮窗（后台线程 + 延迟拉起，
  建窗要等 ComposeView 就绪，不能阻塞启动）；管理页加「自启」开关。
  后台脚本也能拉浮窗：服务轨道新增 `openxOverlayShow` / `MoveTo` / `Geometry` /
  `SetPassThrough` / `IsShowing` / `CanShow`。新增独立气泡窗口——浮窗本体常只有几十 dp
  摆不下字，气泡单独开窗且不接收触摸。
- **Canvas 绘制**：新增 `UiRendererCanvas`，插件可用绘制指令画自定义图形（线条/路径/圆角/文字）。
  图片支持 `asset://` 前缀读插件包内资源，加 `LruCache`，key 带 `assetDir`
  避免不同插件同名资源互相顶掉。
- **帧序列与节点动画**：`frames` 由宿主一次性预解码，定时器只切 index（下限 80ms）——
  若让插件用 `setInterval` 改 src，每帧都要重新读盘 + 解码，画面会闪。
  `nodeAnimate` 支持 `bob` / `tilt` / `breathe` 三种无限循环，作用于绘制变换而非布局尺寸；
  绘制变换新增 `alpha` / `rotate` / `scale` / `scaleX` / `scaleY` / `offsetX` / `offsetY`
  （以前插件想做呼吸只能改 width/height，那会真的改变布局把旁边控件顶开）。
- **浮窗贴边与气泡跟随**：窗口加 `FLAG_LAYOUT_NO_LIMITS`——没有它系统会把窗口夹回屏内，
  负坐标移出屏幕完全不生效。`moveTo` 越界加上限：最多 80% 移出屏幕、至少留 20% 可见，
  否则窗口会被拖丢。气泡是独立窗口，坐标不会自己跟着主窗口变，
  抽出 `positionBubble` / `followBubble` 在 `moveTo` 与拖动回调里都跟着走。
- **插件读 AI 会话状态**（`ctx.host.session`）：新增 `HostSessionState`，给插件看的
  **聚合状态快照**——忙闲、是否思考中、正在跑的工具名、待确认类型、上下文 token 用量、
  是否在重连。刻意只放状态不放内容：拿不到对话正文、思考正文、工具参数。
  提供者由 `MainViewModel` 在 `init` 里注册，而不是把 ViewModel 传进插件桥——
  插件桥活在插件 JS 生命周期里，与 ViewModel 没有父子关系。
- **插件声明式配置页**：`PluginManifest` 新增 `settings`（可空），宿主把清单里的
  settings 渲染成原生控件（`switch` / `segment` / `slider` / `text` / `number`，
  认不出的类型按 text），按 group 分组；值写进插件 env，插件侧 `ctx.getEnv(key)` 读。
  只补缺失的默认值、已有值一律不动——用户改过的配置不能被插件升级/重装冲掉。
  新增 `PluginEnvStore` 收口 env 文件路径：UI 面板桥、服务桥、插件配置页三处必须共用同一份文件。
- **插件详情分「管理 / 配置」两页**：管理页只读（ID/版本/目录/权限/诊断），
  配置页能动手（服务状态与启停、刷新状态、settings 控件、定时任务）。
  切页条走 `ManageDetailDialog` 新增的 `subHeader` 槽位，放在滚动区外不跟内容滚走。
  新增「刷新状态」：容器的常驻进程可能是从插件面板里起的，宿主没有运行记录，
  只读自身记录会一直显示未启动，所以再按声明地址探一次回环端口。
- **服务脚本 `ctx.exec`**（需 filesystem 权限）：后台脚本可在容器里执行命令，
  用于管理容器里的常驻进程（JVM / Node 网关等）——这类插件的后台不在宿主进程里，
  以前只能先打开面板手动拉起来。同步模型，默认 20s / 上限 45s。
- **插件服务启动过程可见**：启动时置「正在启动…」并禁用按钮，避免重复点击；
  `onStart` 抛错不再只记日志，错误信息回传宿主并显示在配置页。
- **Git 凭据管理、仓库切换与克隆**：凭据与署名独立页（底部双 tab），署名写
  `user.name` / `user.email` 与 `remote.origin.url`；凭据按主机管理，token 打码显示。
  自定义 credential helper 脚本（`assets/openx/git-credential-openx`）：
  凭据文件 base64 + 反转存储，helper 解码后按 host 精确命中喂给 git。
  文件 IPC 弹窗：helper 未命中时写 `cred-req-<id>`，App 轮询捕获后弹窗回填，
  响应写回后 git 自动续跑；Git 页 / AI Bash / 交互终端三端共用。
  顶栏仓库切换器扫描工作区根与一层子目录；克隆远程仓库支持自定义目录名，
  失败自动清理半截目录。修复老容器不重跑 `provision.sh` 导致 credential 配置永不生效
  （App 启动时幂等写 `gitconfig.credential` 并补 `includeIf`）。
- **通过 SAF 开放 OpenX 文件**：`OpenXDocumentsProvider` 把私有目录（工作区 / 插件与记忆 /
  容器根）暴露给系统文件管理器。

### 新权限
- `SESSION`（wireName=`session`，不危险）：插件读 AI 会话聚合状态。

### 修复
- **插件供应商在用户改设置后被当成手填条目重建、模型列表丢失**：
  `syncPluginProviders` 原先按 `source == plugin` 认定「插件建的那条」，
  但用户在供应商编辑器里拉取模型 / 改 key 都会触发保存，
  而 `ProviderEditorScreen` 组装 config 时漏了 `source` / `pluginId`，保存后 source 被重置回 user。
  下次插件刷新时认不出自己建的那条，走「新建」分支把用户拉取的模型覆盖成空。
  两处一起修：编辑器组装 config 时带上 `initialProvider` 的 source / pluginId；
  `syncPluginProviders` 改用 id 前缀（`plugin:`）认定插件条目。
- **停用插件时连带停掉它的服务**：否则关掉开关后端口还占着、定时任务照跑，
  而工具与钩子已经撤掉。
- **插件声明的供应商改为随启用状态置灰而不是删除**：用户填的 key、拉取的模型不白丢。
- `hasBackground` 不再要求 `SERVER` 权限：`service` 脚本只是 `ctx.exec` 管容器进程的插件
  以前不被当成后台插件，配置页里显得什么都没有。

### 其他
- 移除内置设备监控插件（后续 2.10.0 恢复）。
- 移除与凭据页重复的 `GitConfigDialog`。
- 新增 `UiRangeSafetyTest` 覆盖绘制参数越界。

## 2.9.8（vc101）· 插件声明 AI 供应商、DeepSeek Harness、运行环境 JDK（2026-11-16）

> 版本号：2.9.7 / vc100 → **2.9.8 / vc101**。

### 新增
- **插件声明 AI 供应商**：`PluginManifest` 新增 `aiProvider`（可空——Gson 不套用 Kotlin
  默认值，声明成非空会让 `copy` 直接 NPE）；插件启用时登记进 API 配置，禁用/卸载连带移除。
  供应商表加 `source` / `pluginId` 区分「用户手填」与「插件声明」，DB 20 → 21 迁移，
  已有行默认 user。插件来源的条目在列表里打「插件」标记，编辑页说明地址会被清单覆盖
  （否则用户改完下次插件一启动又被覆盖，以为是没生效）。
- **内置 DeepSeek Harness 插件**（`com.operit.sidebar_deepseek_harness`，13 个文件）：
  走 `BuiltinPluginInstaller`，比对 `plugin.json` 的 version 与 `.installed`，
  版本变了才覆盖，用户改过的配置不会被启动冲掉。
- **运行环境新增 DeepSeek Harness（dsh）**：`RuntimeToolDef` 取代原先的 `Triple`
  （多为 npm/apk 包名与是否可卸载），新增 dsh 项排在 OpenCode 后。
  检测路径修正：插件把 dsh 装在 `/root/sidebar_deepseek_harness/node_modules/.bin/dsh`，
  **不是全局安装**（dsh 不在 PATH），改为先查插件目录、再回退全局。
  安装也装到插件同一目录（`pnpm add --prod`），避免设置页与插件各装一份互不相认；
  装前校验 Node ≥ 24，apt 容器缺 cc 时先装 `build-essential`
  （node-pty 无 Android arm64 预编译包，必须现场编译），超时 30 分钟。
- **运行环境新增 JDK 17（headless）**检测/安装/卸载：`java -version` 走 stderr 故带 `2>&1`
  并抽出引号里的版本号；装完仅在缺失时补 `/usr/lib/jvm/default-java` 软链
  （镜像自带时不能改指，否则等于把用户的默认 JDK 降版）。
- **运行环境「可更新」提示**：检测时顺带问上游 npm 源要 latest，有新版就在行上提示；
  并发查询、8 秒超时，查不到就当没有更新。版本比较按 semver，
  主体相同时比预发布段（dsh 上游发的是 `0.2.2-rc.1` 这种）。
- **卸载入口**：已安装且可卸载的工具并排显示「卸载」+「重装」，卸载中显示转圈。
  `RuntimeToolState` 增加 `uninstallable` 字段——UI 原先只判断 `version != null`，
  导致 Node.js/npm/Corepack 也出现卸载按钮（它们是官方二进制手 cp 进 `/usr/bin` 的，
  删了会连带弄坏 pnpm/opencode/dsh 的入口，容器重建才彻底）。卸载前必须弹窗确认。
  卸完重新探测版本判定成败，不信退出码（`npm uninstall` 对不存在的包也返回 0）。
- **插件快捷入口可点**：插件管理页详情里的快捷入口由纯文本改为可点条目，
  点击执行（打开界面 / 填入提示词 / 用浏览器打开网址）；`openUrl` 类型新增 `OpenInNew` 图标。
  `PluginQuickAction` 字段可空化 + `orEmpty` 兜底：同 aiProvider，清单缺字段时 Gson 给 null，
  原先直接读 label/icon 会 NPE。
- **插件页侧边栏入口**：顶栏 ≡ 打开侧边栏，新增 `AppSidebarContent`，
  复用聊天页那套侧边栏内容（会话历史 / 文件树 / 插件快捷区 / 底部入口）。
  抽屉只在打开时启用手势，避免与插件页自身的滚动/横向手势互抢，同时保住点遮罩关闭。
- **浏览器收藏夹**：顶栏刷新左边加星标（空心=未收藏、实心=已收藏），
  菜单「浏览器设置与工具」加「收藏夹」入口，可跳转与删除。
  收藏存独立的 SharedPreferences 文件（与 UA/夜间模式那些设置分开，清设置不会连带清收藏）；
  url 归一去掉尾部斜杠，避免同一页重复收藏。
- **供应商模型测试可停止**：`ModelApiService` 改用 `enqueue` + `await` 把协程取消接到
  `Call.cancel` 上（原先阻塞在 readTimeout 300s 的请求根本停不下来）；
  新增单个模型停止按钮与任务表；拉取模型弹窗每行支持就地测试。

### 修复
- **插件 WebView 长按选不中**：`UiRendererWebView` 的 `AndroidView.update` 原先每帧都跑
  `settings.applyTo(web)`，`WebSettings` 的 setter（textZoom / useWideViewPort）会触发
  WebView 重新布局，把用户长按选中的文字清掉，表现为选择手柄闪一下就消失。
  改为记住上次已应用的设置，只在真正变化时才 apply。
- **视口兜底强改布局导致页面滑不动**：`VIEWPORT_FALLBACK_JS` 原先无条件给 html/body 与
  `#root` / `#app` 等容器设 `height:100%` + `overflow:hidden`，且用 `setTimeout` 反复刷：
  对自带布局的网页是灾难，滚动容器被改成 `overflow:hidden` 后页面就滑不动了。
  改为先判断页面是否已有自己的布局（文档内容超出视口、或容器有实际高度/内部内容），
  有则一律不动；只对真正拿不到高度的 SPA 白屏做兜底。
- **插件运行时兼容**：`PluginUiRuntime` 渲染前把 `ctx.UI` 挂到全局 `root.UI`——
  operit 的 UI 脚本大量直接用全局 `UI.Text(...)`，不补这个全局对象，
  脚本只在用到 `UI.xxx` 的分支抛 ReferenceError，表现成「渲染结果解析失败」。
  `UiRenderContext` 增加 `renderEpoch`，`dispatched` 的 remember 带 key，
  否则插件热重载后 `onLoad` 再也不派发。`PluginUiSession.settlePending` 改为
  先试 UI 运行时的 pending 表、再试兼容层的（两张独立表，只调一个会漏）。
- **长命令挂后台**：`CompatHostBridge` 新增 `hostScope` + `pendingSink`，
  超时命令挂后台、立即返回 `pending:<id>`，QuickJS 线程释放后 `Screen()` 能重跑——
  这是「装依赖时界面没反应」的解法。分段续跑只在超时时续跑（退出码非 null 说明真跑完了）；
  总预算 20 分钟、累积输出 20 万字符上限。`exists` 对路径不可解析返回 `exists:false`
  而非抛错（否则插件的 `if (!exists.exists)` 分支走不到，DSH 就是这样误判成已装好）。
- **超长内容 OOM 防护**（真机崩溃：`Failed to allocate 1136 byte @ ChatUtils.doExtractThinkingContent`）：
  `MainViewModel.clampToolResult` 对超过 20 万字符的工具结果保留头 3/4 + 尾 1/4——
  原先 `onToolResult` 无任何上限，而 readFile 允许读 50MB 文件，一条转储就能顶穿堆。
  `ChatUtils` 对超过 100 万字符的 content 直接当正文、不扫 think 标签
  （命中标签时要建 `StringBuilder(content.length)` 副本，单次处理同时存在 2~3 份等长字符串，
  崩溃现场正是这行）。
- **附件添加去重**：协程里取 MIME / 查文件名都是挂起点，连续两次选同一文件能绕过入口检查，
  留下两条同 URI 附件；附件条是 `LazyRow` 且拿 URI 当 key，重复 key 会直接 throw 崩掉。
- **MemoryStore 释放内置文档改用内容指纹**（SHA-256）而不是只比版本号：
  文档改动多数不伴随升版本号，只比版本号会让设备上的旧文档永远刷不新。
- 插件管理页详情「打开界面」移到内容最顶部，原先压在权限清单后面要滚一屏才点到。

### 其他
- 补提交 2.9.7 遗漏的插件源码与测试文件（`PluginBus.kt` / `PluginFileOps.kt`），
  上一笔只按显式路径 add，漏掉未跟踪的新文件导致编译直接失败。
  教训：提交时除了 `git status` 的 M/D，必须同时看未跟踪的源码文件（`??` 里的 .kt）。
- 新增 `PluginAiProviderTest` / `PluginQuickActionTest`，覆盖「Gson 不套默认值」这类 NPE 风险。

## 2.9.7（vc100）· 插件定时任务与消息总线、会话槽位内存回收（2026-11-14）

> 版本号：2.9.6 / vc99 → **2.9.7 / vc100**。

### 新增
- **插件定时任务**：`plugin.json` 新增 `schedule` 声明（每项一条任务）：
  ```json
  "schedule": [
    { "id": "poll", "every": "30m" },
    { "id": "morning", "at": "08:00" }
  ]
  ```
  `every` 支持 `30s` / `10m` / `2h` / 纯数字（毫秒），**下限 10 秒**（再快就是轮询刷屏，
  QuickJS 单线程也扛不住）；`at` 支持每天固定时刻 `HH:mm`（本地时区）；两者都写时以 `every` 优先。
  回调是脚本导出的 `onSchedule(taskId, info)`。**刻意不做完整 cron**：表达式解析与时区边界
  容易写错，而插件 99% 的需求就是这两类。
- **纯定时插件无需开端口**：只声明 `schedule`、不声明 `service` 的插件，启动时不绑端口
  （`port` 保持 0），也就不需要 `server` 权限。管理页的「启动/停止」按钮由新的
  `manifest.hasBackground`（有 HTTP 服务 **或** 有可调度任务）决定是否显示。
- **插件间消息总线**（`PluginBus`）：按 topic 分队列、单调递增序号，拉取式（`poll(topic, sinceSeq)`）。
  为何是「拉」不是「推」：每个插件跑在**独立的 QuickJS 实例**里，实例之间没有共享堆，
  宿主若持有所有实例并跨线程重入别人的引擎，轻则阻塞重则崩溃，而插件可能正处在渲染中途。
  每个 topic 保留最近 100 条，超出丢最旧——总线不该无限增长把内存吃光。
- **插件发起 AI 任务**（`ctx.chat.runTask`）：可指定新建会话。关键约束是**新建会话不切 UI**——
  定时任务可能在用户正看别的东西时触发，沿用 `createNewConversation` 会直接抢走前台。
  这里只往库里插一条 `active = false` 的会话，再用 `sendMessage` 的 `targetConversationId` 唤醒它。
  新会话必须**显式带上当前生效的 provider/model**：会话级模型模式下不带就是「未选择模型」，发送会直接失败。
- **插件服务 JS 运行时与宿主动作桥**：`PluginServiceJsRuntime`、`PluginHostActionsBridge`、
  `PluginUiHostBridge`、`PluginAutomationBridge` 等配套实现。

### 新权限
- `SCHEDULE`（定时任务）、`BUS`（消息总线）、`CHAT_TASK`（发起 AI 任务）。
  `CHAT_TASK` 标为**危险权限**：会消耗 Token 并驱动工具循环，与只读的 `CHAT` 分开。

### 修复
- **会话槽位内存泄漏导致 OOM**：崩溃表象是
  `OutOfMemoryError: Failed to allocate a 40 byte allocation`（堆满 512MB）落在
  `AgentConsoleScreen` 会话抽屉重组的 `background` 调用上——**那行只是最后一根稻草**，不是原因。
  真因：`MainViewModel.slots` 只 `getOrPut` 从不删除，而每个 `SessionSlot.messages` 持有整份消息
  （工具输出最长 1 万字符、识图 base64 单张 1~3MB），用户打开过的每个会话都驻留内存到进程结束。
  新加的定时任务「新建会话」把慢泄漏放大成快爆（`every: 30s` 跑一小时 = 120 个会话槽位）。
  修法：`SessionSlot.releaseHeavyState()` 释放消息正文/折叠历史/附件/撤销快照/增量缓冲
  但**保留**徽标、未读、模型 override、草稿；`MainViewModel.evictIdleSlots()` 在切会话后
  与后台会话跑完时回收，只留前台、正在跑、有草稿/等确认（复用原本写好却从未被调用的
  `isWorthKeeping`）以及最近用过的 3 个会话。**清理前必须先 `cancel()` 该槽位的 `observeJob`**，
  否则 Room 下一次发射会立刻把刚释放的消息填回来。
- **WebView 资源拦截的 `block` / `rewrite` 分支**：原先这两个分支统一放行（注释说「WebView 层
  不好精确表达」），现在 `block` 返回空响应、`rewrite` 按新 URL 重新取并回填，游戏类插件的
  资源改写才真正生效。

### 其他
- `app/build.gradle.kts`：单测开启 `unitTests.isReturnDefaultValues = true`——JVM 上
  `android.jar` 里的方法是空壳，不开这个，任何间接调到 `android.util.Log` 的代码
  （如 `FileLogger`）都会抛「Method i in android.util.Log not mocked」，被测逻辑根本没机会执行。
- 《插件开发指南》+253 行：定时任务、消息总线、宿主动作桥的 API 契约与踩坑。

## 2.9.6（vc99）· AI 知识库、插件浮窗缩放与浏览器/输入法系列修复（2026-11-12）

> 版本号：2.9.5 / vc98 → **2.9.6 / vc99**。

### 新增
- **AI 知识库**：沉淀下来的经验可提炼成条目落盘（`~/.openx/knowledge/`），新增
  `knowledge_search` 工具供检索，管理页 3 个 tab（条目 / 提炼 / 导入）支持增删改、手动新建与
  文件导入（导入时优先取文档首个 H1 作标题）；存储占用页同步接入（条目统计、展开明细、清理）。
- **插件浮窗等比缩放**：拖右下角改窗口大小时，插件界面跟着按比例缩 —— 以首次显示时的
  窗口尺寸为基准，取宽、高两个比例的较小者，只缩不放，下限 0.4 倍（再小就没法看）；
  通过缩放后的 `LocalDensity` 实现，dp/sp 换算成更少的像素，文字仍按最终字号光栅化（清晰）、
  点击热区随布局一起缩。

### 修复
- **子代理写权限被误拒**：`auto` 档下审批通道为 `null`，调用方把「没有审批人」当成了
  「用户拒绝」，子代理的 `writeFile` 一律被挡。改为按档位判定，工具白名单内的写操作直接放行。
- **混合工具（file_manager / browser）的写动作绕过确认**：原先按“工具名”判断是否需确认，
  这两个工具读写混在同一入口，`write`/`edit` 等动作直接跳过弹窗。改为按 `action` 逐个判定。
- **工具确认弹窗改动可读摘要**：原先直接展示原始 JSON 参数，长参数需要横向拖。现改为
  「动作行 + 关键元信息 + 动态「始终允许」说明」，一眼能看出要写哪个文件、允许范围是什么。
- **工具表并发 `ConcurrentModificationException` 崩溃**（`ToolRegistry.getApiTools` ←
  `toolSchemaTokens`）：插件引擎在 `Dispatchers.IO` 上启停/重载并 `register`/`remove`，
  而主线程同时统计工具 schema token、子代理线程也读同一张表，裸 `LinkedHashMap` 边写边遍历就炸。
  工具表改 `Collections.synchronizedMap`，所有遍历先持锁取快照；`register` 的
  「重名判断 + 写入」合并进同一把锁。
- **连接与同步页（三 tab）输入框被键盘遮挡**：App 关了 `decorFitsSystemWindows`（edge-to-edge），
  manifest 的 `adjustResize` 不生效，页面必须自己消费 ime inset。`imePadding()` 加在
  **内容列**上而不是外层 `Box` —— 加在 `Box` 会让底部悬浮 tab 栏一起浮到键盘上方。
- **内置 FTP 页聚焦输入框不被键盘盖住但也不上移**：`TextField` 只在「光标矩形变化」时重算
  滚动位置，视口被键盘压矮不会触发重滚，靠页面自己滚动没用。现给四个输入框登记
  `BringIntoViewRequester`，键盘由隐藏转可见时对聚焦中的那个请求一次 `bringIntoView()`。
- **知识详情标签行被挤压**（标签多时 `Android` 被拆成 `Andr/oid`）：标签行改横向滚动，
  标签保持自然宽度。
- **插件浮窗底部一截被裁**：窗口高度 = 标题栏 + 内容区，缩放时却把「窗口高度」直接当成
  「内容区高度」，内容区永远比窗口高一个标题栏，底部（含最后一行的下半截）被裁。
  改为内容区 = 窗口高 − 标题栏实测高（`onSizeChanged` 实测，不写常量）。
- **浏览器页网页输入框被键盘遮挡**：承载 WebView 的内容区没消费 ime inset，键盘弹出时网页
  可视区高度不变、页面底部输入框被压住。给内容区加键盘避让，让 WebView 容器跟着变矮
  （等价 `adjustResize`），由网页自己把聚焦输入框滚进可视区。
- **浏览器页键盘上方空出一整条工具栏的高度**：上面的避让与 Scaffold 给底栏预留的 padding
  叠加了（内容底边 = 屏幕底 − 56dp 底栏 − 导航条 − 键盘，实测多让出 ≈87dp）。
  改为取「底栏高」与「键盘高」的**较大者**，不再相加。
- **浏览器地址栏光标与占位文字不在一条线上（一打字又对齐）**：占位符走 M3 `Text`，会继承
  `LocalTextStyle`（本项目 `AppTypography = Typography()`，即 M3 默认表里 `bodyLarge` 的
  24sp 行高），而输入框 `textStyle` 只给了 `fontSize`、行高按字体度量算（≈17sp）——
  两侧行盒差 7sp，同一个顶对齐容器里基线自然错开。现改为占位符与正文共用同一份含 `lineHeight`
  的 `TextStyle`（地址栏 14/20sp，UA 自定义框 13/18sp）。
- **浏览器崩溃 `IllegalStateException: The specified child already has a parent`**：
  浏览器把 WebView 容器缓存在 `BrowserManager` 里反复复用，Scaffold 内容槽重新 subcompose 时
  旧的 `AndroidViewHolder` 还没把这个 `FrameLayout` 摘下来，新宿主构造里的 `addView` 直接抛异常
  （栈顶 `ViewFactoryHolder.<init>` → `AndroidViewHolder.<init>`）。现改为**每次组合新建容器、
  绝不复用**，并在释放时校验容器身份（`onRelease` 而非 `DisposableEffect`），旧组合槽的 dispose
  不会再把已被接管的容器清空（那会变成白屏）。
- **工具循环内「就地压缩」的默认触发比例与设置不一致（0.9 → 0.8）**：设置页写着「使用默认阈值 =
  按上下文窗口 80% 触发压缩」、事后自动压缩也是 0.8，只有工具循环内这一处硬编码 0.9，
  128K 窗口下阈值被抬到 115200（应为 102400）。现统一为 0.8、三处同口径；关掉默认开关改用
  手动滑块时不受影响。
- **MCP：容器内启动的 stdio server 看不到工作区**：`startStdioProcess` 的 `projectPath` 原先传
  `null`，而 proot 只在 `projectPath != null` 时才加 `-b <宿主工作区>:/root/workspace` 与
  `-w /root/workspace`，于是配置里写容器视角路径的（如 codegraph 的 `--path /root/workspace/...`）
  找不到目录。改为传 `hostWorkspaceDir`，与 `GitTool`/`TerminalEngine` 的用法一致。
- **AudioBar 的循环/随机键在单色主题下看不出开没开**：「简约白」主题刻意单色，
  `primary #6B7280` 与 `onSurfaceVariant #6E7379` 是同一档灰，而这两个键只靠 tint 从次要色换主色
  表达开启。现改为开启态除变色外再加 28dp、主色 18% alpha 的圆底，用**形状差异**表达状态。
- **浏览器自定义 UA 从行内输入框改为点击弹窗**：原先「自定义」这一项在 UA 抽屉里常驻一个
  输入框 + 确定按钮，凭空占一屏。现改为和预设一样的单行可点选项（副标题显示当前自定义 UA，
  超长省略号截断），点开弹出输入弹窗（默认 `AlertDialog`，自带键盘避让）；点选项时**先收抽屉
  再开弹窗**，避免两个窗口叠着键盘避让互相打架。
- **Token 统计归属错模型 + 非 chat 调用不记账**：
  ① 埋点原先记 `uiState.settings.model`/`selectedApiPreset`，而那是**前台槽投影**，请求实际用
  `settingsFor(cid)` + `forcedProvider=effectiveProviderFor(cid)`。会话级模型模式或多会话并行
  （尤其后台会话请求收尾那一瞬）会把用量记到当时前台的模型名下，表现就是「有的模型不统计」。
  ② 只有主聊天路径记账，起标题 / 压缩摘要 / 识图 / 子代理都走旁路、一律不写记录，
  而这些恰恰常用「默认模型」里的专用模型，于是那些模型恒为零。
  现抽出统一埋点 `recordLlmCall()`（归属按本次请求实际用的提供商/模型），五种 `kind`
  （`chat`/`title`/`compaction`/`vision`/`subagent`）全部记账；`SubAgentRunResult` 补
  `inputTokens/outputTokens/cacheTokens` 三个字段承载子代理用量。连接测试探针刻意不计入（只增噪）。

### 其他
- **记忆/知识注入条数放宽**：长期记忆（全局）5 → 20 条、项目记忆 5 → 50 条、知识库目录
  30 → 50 条，提成具名常量（`MEMORY_GLOBAL_LIMIT` / `MEMORY_PROJECT_LIMIT` / `KNOWLEDGE_INDEX_LIMIT`）。
  三者都只注**索引**（名称 + 短摘要，单条 ≤180 字符），正文仍按需 `memory_read` / `knowledge_search`。
  注意这是**每轮固定开销**（最坏 120 行 × 180 字符 ≈ 8~9K token），要压上下文先砍这三个常量。
- **管理页卡片压成三行**：记忆/技能/MCP/提示词/插件共用的 `ManageCard` 摘要 `maxLines` 2 → 1、
  上下内边距 14 → 10dp；工作流独立卡片同口径。MCP 卡片的连接状态从独立一行挑到标题同一行右侧
  （紧凑文案 `已连接·N 工具` / `连接失败`，完整失败原因仍在详情页），卡片行数 4 → 3。

## 2.9.5（vc98）· 输入法避让、跳转行号与消息分支修复（2026-11-09）

> 版本号：2.9.4 / vc97 → **2.9.5 / vc98**。

### 修复
- **全屏输入框被输入法遮挡**：Compose 的 `Dialog` 走独立 Window，`decorFitsSystemWindows`
  默认 `true` 时窗口 softInputMode 是 ADJUST_UNSPECIFIED、`DialogLayout` 也直接跳过 insets 应用，
  Compose 侧 `WindowInsets.ime` 恒为 0，已写好的 `imePadding()` 加不出高度 → 内容被键盘盖住。
  改为 `decorFitsSystemWindows = false`，与文件查看器那个全屏 Dialog 同一套写法。
- **键盘弹出后输入框不跟光标**：`TextFieldScrollerPosition.update()` 只在「光标矩形变化」时
  才滚动，视口被键盘压矮只更新滚动上限 → 文字停在原处、要手动往上滑。现改为键盘弹出瞬间把
  光标挪一格再跨帧放回，借「光标变了」那条分支逼它按新视口重算（带引用校验，不覆盖这一帧的
  真输入）。全屏输入与「编辑消息」弹窗都适用。
- **「编辑消息」弹窗被输入法遮挡**：同一个 `decorFitsSystemWindows` 缺失，卡片（75% 高、居中）
  下部被键盘压住。
- **从聊天点 `文件:行号` 跳编辑器落点严重偏前**（3000 行的文件落到 1000 行出头，且不报错）：
  Sora 的软换行行高是后台异步算的，原先只给 1.5 秒修正窗口，算不完就把修正作废。改为按
  120/320/700/1400/2500/4000ms 补位、总窗口 4.5 秒，布局空闲事件到达时也补一次；用户一动
  光标立即停手。另修「编辑器实例尚未建好时跳转请求被静默丢弃、而调用方无条件上锁」的竞态。
- **用户消息下方的「从此处新建分支」点了没反应**：聊天页 UI 早已把用户消息算进分支入口
  （`ASSISTANT || USER`），但 ViewModel 的守卫还停在旧版「只认助手回复」，用户消息被静默
  丢弃（连失败提示都没有）。改为只挡工具结果行（SYSTEM）。

## 2.9.4（vc97）· 远程 SSH 终端 + AI 远端执行（2026-11-08）

> 版本号：2.9.3 / vc96 → **2.9.4 / vc97**。

### 新增
- **远程 SSH 终端**：终端页新增远端 shell 会话。把 `TerminalSession` 的传输层抽成
  `SessionBackend` 接口，本地 pty（`SubprocessBackend`，JNI fork）与远端 shell
  （`SshShellBackend`，sshj shell 通道）共用同一套终端仿真与渲染。
- **AI 远端执行工具 `remote_ssh`**：在已授权的远端服务器上执行命令，输出限幅与本地
  `terminal` 一致、末尾附退出码；命令跑完保留 SSH 通道供下次复用，不必重复建连。

### 安全
- **`allowAiExec` 默认关闭且必须逐台开启**。本地容器 `rm -rf /` 顶多废掉一个可重装的
  Alpine rootfs，远端是真机器、同一串命令不可逆；默认给 AI 开这个能力等于把「不可逆」
  当成默认值。
- **做成独立工具，而非让 `terminal` 按执行模式静默改道**：静默改道会让模型以为自己在
  沙盒里，这种误判的代价太大，不值得为省一次参数交换去冒。独立工具让「在哪执行」
  变成每次调用都要显式说清的事。
- 复用 `TerminalCommandGuard` 同一套硬拦截（格式化磁盘 / 改分区 / fork 炸弹 / 关机）——
  远端误伤无法挽回。
- **主机密钥 TOFU**：首次连接与指纹变化都交给用户过目（不在代码里默默信任，known_hosts
  的意义就是让人过一次目）。指纹用 `SHA256:<base64>`，与 `ssh-keygen -lf` 输出一致，
  用户可自行核对。

### 修复
- **`RemoteStore` 未持久化 `savedKeyId`**：重启后连接就丢了「用哪把已保存密钥」，只剩
  `authData` 里的旧路径，用户得回编辑器重选一次——而界面上的选项看着已经是选中的样子。
- **主线程网络 I/O**：`SshShellBackend.resize` 与 `RemoteTerminalHolder.close` 都会被 UI
  直接调用（`TerminalView` 在布局变化时**同步**调 resize），主线程做网络必抛
  `NetworkOnMainThreadException`，已统一转到单线程执行器 / 独立线程。

## 2.9.3（vc96）· 连接与同步 + 并行默认开启 + OOM 修复（2026-11-07）

> 版本号：2.9.2 / vc95 → **2.9.3 / vc96**。

### 新增
- **连接与同步（远程工作区）**：设置页新增独立全屏页（连接 / 挂载 / FTP 三个 tab），
  可添加 SFTP / FTP 连接、浏览读写远端文件、按目录同步；内置 FTP 服务器可把手机存储
  挂给电脑访问。底层 `remote/` 包：`RemoteSshClient`（sshj）、`RemoteFtpClient`
  （commons-net）、`RemoteManager`、`RemoteSyncEngine`、`BuiltinFtpServer`。
  新增依赖 sshj 0.38.0 + bcprov/bcpkix-jdk18on 1.78.1 + commons-net + ftpserver-core；
  sshj 用 X25519 做密钥交换，Android 自带裁剪版 BouncyCastle 不含该算法，必须显式引入
  BC（选 1.78.1 是为避开 1.75 的 CVE-2024-30172）。`packaging.resources.excludes`
  排掉 BC / Apache 系列 jar 里重复的 META-INF 资源，否则 `mergeJavaResource` 直接失败。
- **SSH 密钥管理**：密钥列表、粘贴私钥、生成密钥对，`RemoteKeyStore` 持久化。
- **Token 统计按渠道 / 模型筛选**：7 个查询加可选 `providerId` / `model` 参数，SQL 用
  `:param IS NULL OR col = :param`，旧调用点靠默认值兼容。筛选项列表特意用「不带筛选」
  的全量查询生成，否则选中渠道 A 后就再也切不回 B。
- **并行执行批内工具默认开启**，并新增「频繁 429」提示窗作为兜底（见下）。
- **外观设置新增「界面大小」**：按比例缩放整个界面。只乘 `LocalDensity.density`、不动
  `fontScale`（dp/sp 同比例放大，系统无障碍字号仍生效），也不改 `Configuration`
  （`screenWidthDp` 保持真实值）。
- **Gradle 堆内存设置**：`gradlew` 按设备内存 50% 自适应（钳制 1024~8192MB），设置页可
  覆盖，写入**容器内用户级** `~/.gradle/gradle.properties`。写用户级而非项目级，是因为
  项目级会覆盖用户级、而每个工程都有自己的 `gradle.properties`，写项目级只对一个工程生效。

### 变更
- **消息下方元信息放大**：耗时 / Token 统计 / 时间戳 10sp → 12sp、角色标签 11sp → 13sp、
  操作图标 12dp → 14dp；时间戳 alpha 0.4 → 0.7（0.4 在深色背景上过淡）。
  按钮容器保持 18dp 不变，故这排按钮总宽不变，窄屏不会挤到换行。
- **模型名超过 18 字符截断**：完整显示会把同一行的时间与操作图标挤到换行，但完全不
  显示又不便于确认当前模型，故取折中。
- **横屏输入栏放宽**到右栏 0.8（原 0.6 是**相对右栏**算的，实际只有全屏 40%，底部 10 个
  图标排不下，发送键被挤出圆角）。顶栏共用该系数，视觉上成一列的对齐关系不变。
- **偏好设置图标** `Tune` → `ToggleOn`（原与「上下文配置」重复，同一列表里无法区分）。
- **编辑器长路径改为可横向滑动**：`Ellipsis` 截断后看不出「哪个工作区 / 哪层目录」，
  而路径正是用户确认文件位置的唯一线索。

### 修复
- **长会话 OOM（真机崩溃）**：`groupMessages` 对每条消息调了两次 `extractThinkingContent`
  （一次取思考、一次取正文）。历史消息能命中缓存，但**流式中那条 `cacheable=false`**，
  等于对同一篇超长文本做两遍全文正则 + 两份等长副本。叠加 `m.groupValues[1]` 会把
  **整段匹配**（group 0，即 thinking 标签全长）也拷成 String、
  `StringBuilder(content.length)` 无条件全文复制。三处修完，流式那条的分配量从约
  5×文本长度降到约 1–2×（崩在 GC 后只剩 2MB 时申请 5KB 也失败）。
- **多轮工具调用 OOM**：`gson.toJson` 四层放大（StringBuilder 增长时新旧数组同驻留 +
  日志 `buildString` 复制 + `redact` 正则复制 + `toByteArray` 复制），2.2MB body 峰值 10MB+。
  改为 `JsonRequestBody` 把 JSON 直接流式写进 Okio sink，内存占用与 body 大小无关。
  两个坑：`contentLength()` 必须精确（否则 OkHttp 转 chunked 编码，部分网关拒收）、
  UTF-8 字节计数要处理代理对（emoji 按 4 字节而非 3，否则 Content-Length 偏大导致截断）。
- **编辑器跳转行号落偏**：点 `文件:行号` 链接后落点不准（跳 1755 行落在 728 行附近），
  **且不报错**。根因是 Sora 的软换行布局异步计算，布局就绪前 `ensurePositionVisible`
  只能按「逻辑行号 × 行高」估算，而长注释文件的平均每逻辑行约占 2.4 倍行高。改为订阅
  `LayoutStateChangeEvent`，等布局空闲后用真实行高重定位（`CodeEditor` 只有
  `setLayoutBusy()` 没有 getter，无法直接查询）。加 1.5s 超时窗口，避免在用户已开始
  编辑/滚动后把光标拽回。
- **429 限流提示窗**：并行把同批工具一起发出去，瞬时并发容易超过供应商容忍度，但表现
  只是「某几个工具失败」，用户看不出真正原因是并发。累计 3 次后弹窗，可一键切回串行
  （走 `updateSettings` 落盘，否则重启又变回并行，用户会以为开关没生效）。
  阈值取 3 而非 1：偶发一次 429 是供应商抖动，不值得打断；切串行要付「同批工具耗时
  变长」的代价。
- **备份新增「连接与同步」段**：含服务器列表、挂载、登录密钥、主机指纹、同步设置，
  按 id 合并而非整份覆盖。凭据即使备份出来换机后也解不开（Keystore 密钥不可导出），
  新设备需重填；同设备恢复备份则仍可用。

### 其他
- **`largeHeap`**：堆上限 256MB → 512MB，为长会话 + 大文件场景留余量。

## 2.9.2（vc95）· 语音输入 + 出站脱敏 + 长会话 OOM 修复（2026-11-05）

> 版本号：2.9.1 / vc94 → **2.9.2 / vc95**。

### 新增
- **语音输入**：基于 sherpa-onnx 的离线流式识别（模型可下载，不走网络），录音键常驻发送键左侧；
  系统 TTS 朗读（单条回复可点朗读、可开「自动朗读」）。
- **出站脱敏**：发给供应商前把正文/思考/工具参数里的凭据换成 `{{SECRET_xxx}}` 占位符，
  入站再回填真值。**默认关闭**，在「默认模型」页开启。
- **文件树「加入输入栏」**：长按文件/目录可直接投进聊天输入区当附件（工作区内文件不复制副本）。

### 修复
- **长会话 OOM（真机崩溃）**：`extractThinkingContent` 的缓存被流式中间态污染——流式中那条
  content 每帧都在变，每变一次就插入一条新缓存条目，把历史消息的条目全挤出去，随后每帧
  对 500+ 条消息全量重算全文正则，崩在 `StringBuilder` 构造上（只申请 9KB 也失败）。
  修：流式中那条不缓存（治本）+ 总字符预算 1.5M + 单条上限 200K→64K + 条目 512→256。
- **`OutboundMasker.flush()` 从未被调用**：流末尾被 `restore()` 掐住的半截占位符永不吐出，
  还会污染下一轮开头。
- **累积正文未还原**：`acc.fullText` 是未还原的占位符版本，而它是落库与工具执行的数据源——
  重启后会看到 `{{SECRET_xxx}}`、工具拿到假参数。现收流后统一还原（回灌时再掩一次，闭环正确）。

## 2.9.1（vc94）· 备份 OOM 修复 + git 工具容器化（2026-11-03）

> 版本号：2.9.0 / vc93 → **2.9.1 / vc94**。

### 修复
- **备份导出 OOM（真机崩溃）**：`BackupManager.export` 的会话段对同一份数据做了三层内存放大——
  `ChunkedReader.readAll` 把全表实体攒成 List、`gson.toJsonTree` 再建一棵等大的 JsonElement 树、
  `gson.toJson` 再拼出完整 JSON 串（真机就崩在第三步，要一次分配 37MB）。改为「分块读 → 逐块流式写」：
  `ChunkedReader.forEachChunk` 每块用完即弃，`TypeAdapter.write(writer, obj)` 逐条写进 zip，
  内存占用与数据量无关（峰值 = 单块）。
- **备份还原同源风险**：导入侧更重（`readText()` + `parseString` 树 + `toString()` + `fromJson` 共四份），
  导出修好后必然轮到它，一并改为 `JsonReader` 逐条流式解析。
- **git 工具整条不可用**：`GitTool` 在**宿主进程**裸跑 `ProcessBuilder("git")`，而 git 只装在容器 rootfs 里
  （宿主 PATH 里根本没有）。报错还被包装成 `error=13 Permission denied`（posix_spawnp 把 PATH 中
  不可搜索目录的 EACCES 当成最终 errno），掩盖了真实原因。改为经 `LinuxContainerEngine` 在 PRoot
  容器内执行，与 terminal 工具同一条通道。
- **git 分支名把错误文本当分支名**：仓库尚无提交（HEAD 未创建）时 `rev-parse --abbrev-ref HEAD`
  返回 128，旧代码把整段 fatal 错误拼进输出。现按退出码判断，显示「(尚无提交)」。
- **git 中文路径显示为八进制转义**：命令加 `-c core.quotepath=false`（沿用 GitRepository 约定）。
- **插件 WebView 控制台噪音**：`ResizeObserver loop` 告警随页面布局抖动反复触发且被记为 ERROR
  刷屏，现直接丢弃（不记日志、不转发插件）；其余 WARNING/ERROR 不受影响。

### 变更
- `ChunkedReader` 新增 `forEachChunk`；`readAll` 改为其委托（行为不变，其他调用方零影响）。
- `GitTool` 的 `dir` 改为容器路径（白名单 `/root/workspace`、`/root/local`），默认目录 `/root/workspace`。

## 2.9.0（vc93）· 流式渲染性能优化 + Operit 兼容层扩展（2026-11-01）

> 版本号：2.8.10 / vc92 → **2.9.0 / vc93**。

### 性能
- **流式打字顿挫**：打字机速率与上游提交节奏不匹配导致「打一下停一下」（旧上限 400 字符/秒，
  正文每 150ms 提交一批约 30ms 打完、剩 120ms 静止）。对齐官方 AiCode 机制：双层速率上限
  （常规 220、滞后超阈值放宽到 1600）、滞后目标、结束不再一帧补全而按 0.2 秒匀速收尾、
  快照间隔 80ms→24ms。追赶参数取 0.25/40（官方 0.06/12 是配合其 60ms 高频到达）。
- **Markdown 增量解析**：`parseMarkdownState(text, prev)` 只重解析稳定边界之后的尾部，
  边界前的块直接取上一轮实例；成本降为与新增段成正比（此前每次快照都 split("\n") 整篇）。
  边界只在空行后定形，且需 `i < lines.size`（表格靠下一行含 `---` 判定，不能提前定形）。
- **思考提取单遍扫描**：`extractThinkingContent` 旧写法 `findAll` + 两次 `replace` =
  三遍全文扫描并分配两份副本，正在流式的那条每帧都在变、必然走不到缓存。改单遍。
- **思考提取 LRU 缓存**：`groupMessages` 每帧对每条 assistant 消息调两次提取，
  历史消息 content 不变，缓存后 O(1) 命中。
- **解析结果全局缓存**：`MarkdownParseCache`（按字符预算淘汰，3M 字 ≈ 12MB），
  item 回收再挂载 O(1) 命中，首帧即拿到正确高度。
- **truncateToCodePoints** 去掉每帧重复的全文 `codePointCount`。

### 兼容层扩展（Operit）
- `registerNavigationEntry` 定义侧边栏快捷项：title 多语言对象、官方图标名映射 Material、仅 main_sidebar。
- 插件管理页对 compat 包展示「在 OpenX 下不生效的能力」报告。
- `CompatHostBridge.deleteFile` 忽略多余 environment 参数、recursive 默认 true。
- `MainViewModel` 把 nav 拼回 manifest 后整体重建 quickGroups（避免既有 quickActions 出现重复组）。
- 插件开发指南新增第 18 章「Operit 插件兼容层」。

### 修复（2.8.10/vc92 期间，同版本多次修复）
- 插件面板与悬浮窗内容超出时无法滚动：按内容类型分流（webview/Lazy 无 height → 保持有界；
  普通树 → 套 verticalScroll），只换 modifier 不换容器（否则根节点 onLoad 重复派发）。
- 流式长思考链 OOM 防护：`StreamBudget` 单响应 4MB 上限（含工具参数增量）；
  `deltaAccumulator` 64KB 滑动窗口。
- WebView 尺寸丢失：`modifier = m.fillMaxSize()`；深色反色彻底关闭；视口兕底。
- 思考卡重叠：回退 graphicsLayer 与 type-key；打字机参数反调。

## 2.8.10（vc92）· 插件面板/悬浮窗滚动修复 + WebView 适配（2026-10-31）

> 版本号：2.8.9 / vc91 → **2.8.10 / vc92**。

## 2.8.9（vc91）· 插件第十二期 + 正则 OOM 修复（2026-10-30）

> 版本号：2.8.8 / vc90 → **2.8.9 / vc91**。

### 新增
- **插件第十二期**：`ctx.memory.*`（记忆库，需 `memory` 权限）、`ctx.chat.*`（只读聊天记录，需 `chat` 权限）、`onPromptInject` 钩子，`PluginAutomationBridge`/`PluginUiHostBridge`/`PluginUiRuntime` 接入，`MessageDao.getRecentMessages`、`ChatRepository.systemPromptInject`，`PluginHookManager` 新增 `ON_PROMPT_INJECT`。

### 修复
- **正则 OOM**：修复 `findAll().toList()` 全量收集 `MatchResult` 导致超长文本堆积、`Matcher.group()` 分配子串时触发 OOM（`BatchReplaceTool`、`FileAnalyzerTool`、`WebSearchTool`）。
- **QuickJS**：补充 `installBootstrap/registerBootstrap/unregisterBootstrap` 适配。

## 2.8.7（vc89）· 插件系统增强：无障碍 UI 自动化、设备信息、聊天钩子、悬浮窗（2026-10-29）

> 版本号升级：2.8.6 / vc88 → **2.8.7 / vc89**。

### 新增
- **插件 UI 自动化（`ctx.ui`）**：新增无障碍读屏、点击、输入、滑动、全局动作等 API，扁平化节点树，支持多字段选择器匹配（`text` 同时匹配 `text` 与 `contentDescription`），需 `ui` 权限且需开启无障碍服务。
- **设备与应用信息（`ctx.system`）**：新增 `battery/device/apps/searchApps/launchApp/hash/base64`，需 `system` 权限。
- **设备实时状态（`ctx.device.stats()`）**：新增 CPU/内存/存储/运行时长/电池实时统计（`cpu.usage` 两次采样求差，`memory` 用 `ActivityManager.MemoryInfo` 系统级数据，`cpu.freqs` 逐核频率）。
- **聊天钩子（Hook）**：新增 `PluginHookManager`，支持 `onAppStart/onBeforeSend/onAfterReply/onBeforeTool/onAfterTool`，需 `hook` 权限，接入 `JsPackageManager`/`ToolRegistry`/`MainViewModel`.
- **插件悬浮窗（`ctx.overlay`）**：新增 `PluginOverlayWindow`，支持 `show()` 传入 `width/maxHeight/minWidth/minHeight/focusable`（比例或 dp），支持拖拽缩放（右下角把手），需 `overlay` 权限。
- **无障碍桥接**：新增 `PluginAutomationBridge`、`UiTreeQuery`，增强 `AgentAccessibilityService` 能力。
- **UI 组件**：`UiNode` 支持 `weight/grow/grid/markdown`，`UiRenderer` 支持权重与 `LazyVerticalGrid`，`LinearProgressIndicator` 支持确定模式。
- **文档**：`插件开发指南.md` 新增第 11—16 章，更新悬浮窗尺寸与缩放说明。

### 修复
- **UI 主题**：修复 `UiTheme.fillMaxHeight`。

## 2.8.6（vc88）· 设置项、编辑器保存与思考框滚动修复（2026-10-28）

> 版本号升级：2.8.5 / vc87 → **2.8.6 / vc88**（用户明确要求）。

### 变更
- **设置**：上下文压缩阈值默认 90% → 80%（设置项文案同步）；「用量统计」行上下间距调整到与其他设置项一致（12dp）；恢复「存储占用」下方分割线。
- **API 配置**：拉取模型弹窗新增「全部添加」按钮（带确认弹窗，模型列表限高 320–380dp）。
- **Token 统计**：清空统计移到顶栏右侧图标，点击弹确认框；移除底部按钮。
- **关于页**：文案改为「中文AI智能体客户端。功能：对话，AI驱动代码编辑，内置终端，git，股票分析等」。
- **文件编辑器**：保存（💾）不再退出文档，写入后回到预览/查看态；「保存并退出」仅在关闭确认框里使用；修复三处 `onSaved` 回调误关弹窗（侧边栏文件树、文件查看器、聊天内文件链接）。
- **AI 思考框滚动**：流式期间用户回看上文时立即停掉内层自动跟随与外层贴底；顶部越界手势直接驱动外层列表；关闭内层拉伸回弹；外层上翻锁只在用户真正滚到严格底部时解除，避免流式布局变化误解锁导致抖动。

### 构建
- `:app:assembleDebug` 成功。
- APK `versionCode='88' versionName='2.8.6'`。
- 交付：`OpenX_2.8.6.apk`、`openx_2.8.6-src-<时间戳>.tar.gz`。
- **未真机验证**（思考框滚动此前多轮由用户实测反馈）。

## 2.8.5（vc87）· 模型品牌图标接入、存储占用优化（2026-10-28）

### 变更
- **模型图标**：接入品牌图标（`ProviderLogo`/`ModelLogoIcon`），`ModelSelectionSheet`、`ProviderEditorScreen` 显示图标，会话输入框底部「选择模型」按钮随生效模型切换图标，未匹配用 OpenAI 图标兜底。
- **适配**：补充 `agnes` 等模型识别。
- **存储占用**：进入页直接展示骨架+清理建议，不再全屏转圈；分类支持展开查看并自定义选择清理（带确认弹窗）；加强扫描（日志/ai-logs/crash/量化/技能/外置 `/storage/emulated/0/openx`）；增加展开箭头。

### 构建
- `:app:assembleDebug` 成功。
- APK `versionCode='87' versionName='2.8.5'`。
- 交付：`OpenX_2.8.5-storage3.apk`。
- **未真机验证**。

## 2.8.4（vc86）· 文件查看器光标位置与设置、TextMate 语法高亮（2026-10-27）

> 版本号升级：2.8.3 / vc85 → **2.8.4 / vc86**。

### 变更
- **文件查看器**：光标位置统一显示在底部状态栏右侧，编辑态也显示；仅有选区时才在右上角显示「已选 X 字符 · 行 Y : 列 Z」。
- **编辑器设置**：新增「显示光标位置（行：列）」和「显示底部状态栏（行数、字符数、大小、UTF-8）」开关，可按需隐藏。
- **TextMate 语法高亮**：接入 `language-textmate`，按文件扩展名应用语言高亮，使用 `TextMateColorScheme`，根据应用背景色自动切换深浅主题，保留主题背景色。
- **注释清理**：清理源码注释中对其他项目的引用，统一用中性表述，并在开发笔记中记录约定。

### 构建
- `clean + testDebugUnitTest + assembleDebug` 成功，**599 用例 0 失败 0 跳过**。
- APK `versionCode='86' versionName='2.8.4'`。
- 交付：`OpenX_2.8.4-editorpos.apk`、`OpenX_2.8.4-syntax8.apk`；源码包 `openx_2.8.4-src-20261027-1535-editorpos.tar.gz`、`openx_2.8.4-src-20261027-1640-syntax5.tar.gz`。
- **未真机验证**。

## 2.8.2（vc84）续 · 「从此处新建分支」增加确认弹窗

> 基于 2.8.2 / vc84，未升版本号（交付文件名加 `forkconfirm` 后缀）。

### 变更
- 点击 AI 回复操作行的分支图标后**不再立即执行**，先弹出「新建分支会话」确认框，
  说明会复制此回复及之前的全部历史、自动切换到新会话，且原会话保持不变。
- 点「新建分支」才真正创建；「取消」、点弹窗外部或返回键关闭均不产生任何写入。
- 弹窗样式与现有「确认删除 / 确认重新生成」一致。

### 构建
- `clean + testDebugUnitTest + assembleDebug` 成功，**599 用例 0 失败 0 跳过**。
- APK `versionCode='84' versionName='2.8.2'`，38,958,168 B，
  MD5 `7d6cf5a697357b6257ce1abdf9df2f2f`。
- 交付：`OpenX_2.8.2-forkconfirm.apk`；源码包
  `openx_2.8.2-src-20261026-1100-forkconfirm.tar.gz`。
- **未真机验证**（弹窗文案与确认/取消行为建议实机确认）。

---

## 2.8.2（vc84）· 助手消息支持「从此处新建分支」

> 版本号升级：2.8.1 / vc83 → **2.8.2 / vc84**（用户要求）。

### 新增
- **从消息新建分支**：助手回复的操作行新增「从此处新建分支」（`CallSplit` 图标，
  位于「复制」与「删除」之间），参考 AiCode 的 `forkFromMessage`。点击后把该回复
  （含）之前的全部历史复制到一个新会话，原会话保持不变、正在跑的任务也不打断。
- 新会话标题为 `分支 · 原标题` 并直接定稿（不再被 AI 自动改标题），沿用源会话的
  提供商/模型选择；请求数与上下文 token 归零，下一轮按真实 usage 重建。
- 分叉是干净副本：不复制任务/待办、检查点与 LLM 调用记录；附件按路径共享，不复制文件。

### 细节
- 新增 `ConversationForkPolicy`（纯函数，含单测）：按聊天页显示序截取目标消息及之前
  的历史、裁掉尾部悬挂的工具调用/结果（避免回放时 API 400）、复制时 id 归零、
  `queued` 清零。
- **压缩历史适配**：OpenX 的折叠消息仍显示、也能被选为分叉点。若分叉点在压缩之前
  （前缀里没有 marker/摘要），复制后把 folded 恢复为正常上下文；若前缀含 marker/摘要，
  则保留 folded，避免摘要与原文同时进请求。
- `MessageRepository.insertAll` 单事务批量复制，保留 `seq` 显示序。
- 流式中的半条回复不显示入口（可能尚未落库）。

### 构建
- `clean + testDebugUnitTest + assembleDebug` 成功，**599 用例 0 失败 0 跳过**
  （原 592 + `ConversationForkPolicyTest` 7）。
- APK `versionCode='84' versionName='2.8.2'`，38,958,168 B，
  MD5 `aaf830e48ad3f788627ce224caa5edf4`。
- 交付：`OpenX_2.8.2-fork.apk`；源码包
  `openx_2.8.2-src-20261026-1046-fork.tar.gz`。
- **未真机验证**（分支按钮、历史复制与切换建议实机确认）。

---

## 2.8.1（vc83）· 新增 Shizuku 系统命令能力（设置 → 应用权限授权）

> 版本号升级：2.8.0 / vc82 → **2.8.1 / vc83**（用户要求）。

### 新增
- **Shizuku 授权入口**：设置 → 应用权限授权 → 扩展能力新增「Shizuku」行，
  按状态显示 未安装 / 未运行 / 未授权 / 已授权；点击分别跳转下载页、打开 Shizuku
  启动服务、申请授权或刷新。从 Shizuku 应用返回或页面回到前台时自动重算状态。
- **`shizuku` 工具**：AI 可通过 Shizuku 以 adb shell（uid 2000）身份执行
  `pm` / `am` / `cmd` / `settings` / `dumpsys` 等系统命令。参数 `command`（必填）、
  `timeout`（秒，默认 120，上限 1800）。与容器内的 `terminal` 明确区分：后者在
  Alpine/proot 里执行，`shizuku` 直接作用于宿主 Android。
- **UserService 执行通道**：新增 AIDL `IShizukuShellService` 与运行在 shell 进程的
  `ShizukuShellService`（`ProcessBuilder("sh", "-c", ...)`）；输出用 `BoundedOutput`
  限幅（避免 Binder 1MB 事务上限），超时强杀并返回负退出码。
- **授权策略接入**：`shizuku` 与 `terminal` 同属按指令前缀授权的 shell 工具
  （`ToolPermissionPolicyEngine.SHELL_TOOLS`），支持「始终允许」前缀记忆、DENY 覆盖
  与内置只读白名单；同时加入 `ToolRegistry.WRITE_AND_EXEC_TOOLS`，享受只读模式屏蔽
  与参数截断保护。
- **状态机**：`ShizukuManager` 以 `StateFlow<ShizukuState>` 暴露
  `NOT_INSTALLED / NOT_RUNNING / PERMISSION_DENIED / READY`，由 binder 存活/死亡与
  授权回调驱动；UserService 绑定超时 30 秒、命令超时上限 30 分钟。

### 构建与依赖
- 依赖内置为本地 maven 仓库：`dev.rikka.shizuku:api:13.1.5`、
  `provider:13.1.5`（AAR + POM 置于 `app/libs/shizuku-repo`，
  `settings.gradle.kts` 注册仓库，`--offline` 可构建）；开启 `buildFeatures.aidl = true`。
- **build-tools 固定为 35.0.0**：本项目原先隐式使用 34.0.0，其 `aidl` 在本机（ARM64）
  会以 `Illegal instruction` 崩溃，导致 AIDL 无法编译；35.0.0 正常。
- Manifest 注册 `rikka.shizuku.ShizukuProvider`
  （authority `${applicationId}.shizuku`，`android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`）。
- `OpenXApp.onCreate` 初始化 `ShizukuManager`（只持有 applicationContext）。
- AgentConsole 工具调用图标/分类纳入 `shizuku`。
- 单测 **592 用例 0 失败 0 跳过**（原 588 + 授权策略 2 + 工具契约 2）。

### 说明
- 需用户自行安装并启动 Shizuku（或 Sui），本应用不内置提权。
- **未真机验证**：授权弹窗、UserService 绑定与实际命令执行需在装有 Shizuku 的设备上确认。

---

## 2.8.1（vc83）续 · 「关于」版本弹窗改为独立子页

> 基于 2.8.1 / vc83，未升版本号（交付文件名加 `aboutpage` 后缀）。

### 变更
- **版本入口改为子页**：设置 → 关于 → 版本 不再弹 `AlertDialog`，而是下钻到
  「关于 OpenX」页（`ui/screen/AboutScreen.kt`），交互与「偏好设置 / 应用权限授权」一致：
  左上返回键、系统返回键都回设置页。
- **页面内容**：顶部应用图标 + 名称 + 简介；「版本信息」组显示
  `v2.8.1 (83)` 与构建时间；「链接」组预留 GitHub 仓库 / 开源许可证入口；
  下方保留原弹窗里的 13 条主要功能列表与反馈说明。
- **地址占位**：`AboutScreen.kt` 顶部预留 `GITHUB_REPO_URL` / `LICENSE_URL` 常量。
  当前为空 → 行尾显示「待发布 / 待补充」且不可点击，避免打开无效链接；
  后续上传 GitHub 后只填这两个常量即可启用跳转，无需改 UI。
- **导航接线**：`AppScreen.About`、`MainViewModel.openAbout()`、
  `SettingsScreen(onOpenAbout)`，返回键列表同步加入 `About`。

### 构建
- `clean + testDebugUnitTest + assembleDebug` 成功，**592 用例 0 失败 0 跳过**。
- APK `versionCode='83' versionName='2.8.1'`，38,941,784 B，
  MD5 `57a84014202f4a46cf9db4c8039c51be`。
- 交付：`OpenX_2.8.1-aboutpage.apk`；源码包
  `openx_2.8.1-src-20261026-0801-aboutpage.tar.gz`。
- **未真机验证**（子页滚动、图标加载与返回行为建议实机确认）。

---

## 2.8.0（vc82）续 · 新增「简约白」主题（纯白 + 浅灰按钮）

> 基于 2.8.0 / vc82，未升版本号（交付文件名加 `minimalwhite` 后缀）。

### 新增
- **「简约白」配色**：设置 → 外观 → 主题配色新增一项。浅色为纯白 `#FFFFFF` 背景、
  近黑主文字、中性灰点缀；面板/用户气泡/分隔线全部为浅灰阶，整体只保留黑白灰。
  同时补齐深色变体，主题模式为「跟随系统/浅色/深色」时都能正常切换。
- **按钮浅灰语义色**：新增 `ButtonBg`/`ButtonFg`/`ButtonFgOnFill`/`ButtonSoftBg`/
  `ButtonSoftFg`（`ui/theme/Theme.kt`）。「简约白」下实心按钮为浅灰 `#E6E7E9` + 深色文字，
  弱化按钮为更浅的 `#F0F1F3`；其它配色回落到原 `Accent`/`onPrimary`，外观完全不变。

### 说明
- 浅灰只用于**按钮填充**，不改 `Accent`：`Accent` 同时承担全仓 124 处文字/图标与
  开关/滑块等控件，直接改浅会牺牲可读性。
- 设置页主题列表、`resolveTheme` 配色族映射与 `ResolveThemeTest` 同步更新（新增 2 个用例）。

---

## 2.8.0（vc82）续 · 斜杠命令统一 + 技能并入 `/` 菜单 + `/usage` 用量与费用

> 基于 2.8.0 / vc82，未升版本号（交付文件名加 `slashcommands` 后缀）。

### 新增
- **`/usage` 用量与费用统计**：以 Markdown 表格输出今日与累计的调用次数、输入/输出/
  缓存命中 tokens 与预估费用。费用按「渠道 + 模型」分别取自定义单价（提供商编辑模型里
  填写的输入/输出/缓存读写价格），元数据没有价格时退回内置价格表。
- **技能并入 `/` 菜单**：已启用且正文非空的技能会以 `/技能名` 出现在输入框命令菜单和
  `/help` 里；发送 `/技能名 参数` 即把技能正文作为本轮指令执行（参数附加在末尾）。
  与内置命令重名的技能不展示（内置优先）。

### 优化
- **斜杠命令单一清单**：新增 `ui/command/SlashCommands.kt` 作为唯一定义源，输入框菜单、
  命令面板、「快捷命令」面板、`/help` 与 `@help` 引用全部由它生成，结束此前三份清单
  各自漂移（菜单缺 `/mcp` `/plugins` `/themes`、帮助缺 `/mcp` `/plugins` 等问题）。
  别名统一归一化到主命令名后再分发。

### 修复
- **`/thinking` 没有实现**：此前只出现在菜单与帮助里，输入后提示「未知命令」。现在会
  显示当前思考强度与可用档位，`/thinking <档位>` 可直接切换（支持中文档位名，如
  `/thinking 高`），并保证始终可以切回 `default`。
- **技能描述把 `/` 菜单撑乱**：技能描述往往是一整段话（含换行），此前在菜单里会换行/
  挤占宽度。现在统一把连续空白压成空格、单行显示，超出部分用省略号；`/help` 里的技能
  描述同样压成一行。

---

## 2.8.0（vc82）· 文件编辑器快捷符号栏 + 模型选择分组折叠 + 编辑模型自定义价格

> 版本号由 2.7.10 / vc81 升到 2.8.0 / vc82（用户指定）。

### 新增
- **文件编辑器快捷符号栏**：在文件查看/编辑弹窗（侧边栏文件树与聊天内文件链接共用）
  进入编辑态时，编辑区下方新增可横向滚动的快捷符号栏：光标左右移动、`Tab` 缩进，
  以及 `{ } ( ) [ ] " ' \` ; : , . < > = + - * / | & ! ? _ # @ % \` 等常用符号，
  点击即在光标处插入（有选区时先替换选区），移动端无需频繁切换输入法符号页。
- **提供商编辑模型弹窗自定义价格**：支持填写输入/输出/缓存读取/缓存写入单价
  （USD/1M Tokens），写入自定义模型元数据，计价相关逻辑可直接读取。

### 优化
- **对话页模型选择弹窗分组折叠**：按提供商分组默认折叠，仅当前使用模型所在分组默认展开；
  搜索时自动全部展开；点击分组头可展开/收起（修掉状态不刷新的问题，改用
  `mutableStateMapOf`）。
- **模型选择弹窗弹出方式**：改为与拉取模型/编辑模型弹窗一致的分段弹出（先半展开，
  上滑全展开）。
- **拉取模型弹窗弹出方式对齐**：改为全展开样式，与「添加/编辑模型」弹窗保持一致。

### 修复
- **模型选择分组点击无反应**：展开状态存在普通 `MutableMap` 里，赋值不触发重组；
  改用 `mutableStateMapOf`。

---

## 2.7.10（vc81）· 插件输入框候选词/初值修复 + HTTP 直链音频修复 + 此前全部改动

> 版本号由 2.7.9 / vc80 升到 2.7.10 / vc81（用户指定）。2.7.6–2.7.9 为中间构建。

### 新增
- **插件 `AudioBar` 播放条版式重做 + 循环/随机**：曲名与状态独占一行不再被按钮挤压；
  时间嵌到进度条两端；上一曲/播放/下一曲/停止与循环/随机六个按钮同一行居中；进度条与
  音量滑杆压到 32dp（整条约 152dp，比原来矮）。循环走 ExoPlayer `repeatMode`
  （不循环 → 列表循环 → 单曲循环，单曲态换 `repeat_one` 图标），随机走
  `shuffleModeEnabled`，开启态用主色高亮；通知栏与蓝牙按键同步。`stop()` 只清曲目、
  不重置循环/随机偏好。`UiIcons` 新增 shuffle/repeat/repeatone 图标名映射，
  内置《插件开发指南》同步版式说明。
- **侧边栏文件长按「用浏览器打开」支持更多格式**：原来只有 `html/htm/svg/xml/txt`。
  现在新增
  **音频**（mp3 / m4a / m4b / aac / wav / ogg / oga / opus / flac / weba）、
  **视频**（mp4 / m4v / webm / ogv / mov / 3gp / 3gpp）、
  **图片**（png / jpg / jpeg / gif / webp / bmp / ico / avif）、
  **文本**（json / jsonl / js / mjs / css / md / markdown / csv / tsv / yaml / yml /
  ini / conf / cfg / toml / log）。
  音视频由内置浏览器自动包一层深色 `<audio>` / `<video>` 播放页（WebView 没有 Chrome 的
  「媒体文档」页，直接打开会白屏），带播放/进度/全屏，不自动播放。
  受限说明：mkv / avi / pdf / office 文档仍不给入口（WebView 不支持相应容器，也没有 PDF 渲染器）。

### 新增
- **提供商编辑模型弹窗自定义价格**：支持填写输入/输出/缓存读取/缓存写入单价（USD/1M Tokens），写入自定义模型元数据，计价相关逻辑可直接读取。

### 优化
- **模型选择弹窗默认折叠分组**：对话页「选择模型」按提供商分组默认折叠，仅当前使用的分组展开；搜索时自动展开全部，支持点击分组头展开/收起。
- **拉取模型弹窗弹出方式对齐**：改为全展开样式，与「添加/编辑模型」弹窗保持一致。

### 修复
- **插件面板切 Tab 后输入框还是上一个 Tab 的内容**：`remember` 按树中位置复用状态，
  两个 Tab 的输入框落在同一位置时会沿用上一个 Tab 的本地内容。现在宿主支持插件传
  `key`（或 `fieldKey`）——同位置不同 key 视为不同输入框重新接管；同时改为
  **未聚焦时跟随外部值**（聚焦期间仍不跟随，保护输入法 composition），顺带补上了
  2.7.10 的已知边界「插件无法程序化改输入框内容」。不传 key 的插件行为不变；
  内置《插件开发指南》补充 `key` 属性与「切 Tab 串台」小节。
- **插件输入框候选词闪烁、已有值不显示**（光标补丁的收尾）：`TextFieldValue` 版本每次都
  跟随外部值，而插件状态回环是异步的——输入瞬间外部值还是旧的，覆盖会把刚输入的字符和
  输入法 composition 一起清掉（候选词闪、字母时有时无）；改成完全不跟随又会丢掉插件传下来
  的初值（如「扫描目录」的 `/mnt/sdcard` 只剩 placeholder）。现在**只在第一次拿到非空外部值
  时补齐一次**（`seeded` 锁定），之后完全交给输入框自己维护。
  已知边界：插件无法再程序化改输入框内容（需要重置时让节点重建即可）。
- **HTTP 直链电台（`.mp3` 等 Progressive 流）全部播放失败，HLS 正常**：2.7.6 引入的
  自定义 `ExtractorsFactory` 把 `createExtractors()` 的结果缓存后反复返回，而 `Extractor`
  是有状态对象、契约要求每次新建；从第二次解析起内部缓冲错乱，抛
  `NullPointerException: Object.getClass() on null object reference` 并被包成 `Source error`。
  HLS 走 `HlsMediaSource` 不经 extractor，所以只有直链中招。修法：每次
  `DefaultExtractorsFactory().createExtractors()` 新建；按文件头命中时用 `prioritize()`
  把对应类型提到最前（复用工厂实例，避免 media3 1.5.1 已弃用的无参构造）。
- **插件输入框每打一个字光标跳回开头**：`UiRenderer` 用 `OutlinedTextField(String)` 重载，
  该重载不携带 selection；插件「输入 → onAction → JS setState → 重渲染」回环中光标位置被
  重置为 0，于是字序看起来是反的。改用 `TextFieldValue` 在本地维护光标，先接管本地状态
  再通知插件，仅在外部值与本地文本真正不一致时（插件主动清空/填充）同步。
- **AI 执行工具时立即发送「图片 + 文字」，聊天页只有文字、图片卡片不显示**：生成中的
  消息走排队分支，旧实现只把文本写进排队气泡（注释还写着「带附件的消息不排队」），
  附件却留在输入区——AI 结束后这一轮把残留附件当作本轮附件发了出去，于是
  「AI 收到了图，气泡上没有图」。现在附件随排队消息一起入队（气泡记录 `attachments`/
  `imageUri`，槽位另存一份完整附件应对入队瞬间尚未落盘），消费队列时取回；
  只发图片不打字也可以入队；取消/清空队列时同步清理暂存附件。
- **插件面板键盘遮住底部输入框**：App 是 edge-to-edge（`enableEdgeToEdge` +
  `setDecorFitsSystemWindows(false)`），`adjustResize` 实际不生效、键盘浮在内容之上，
  插件面板（如音频插件的路径输入框）打字时看不到光标。修法：页面根 `Column` 加
  `imePadding()` 让整页上收。
- **插件面板滑到底会莫名拉起输入法**：首版修复在内容容器上加的 `imeNestedScroll()` 是
  「IME 跟手动画」——内容滚到边界后继续上滑，剩余手势会被用来弹出键盘。这层是所有插件
  共用的，于是任何插件（哪怕没有输入框）滑到底都会弹键盘。已删除该修饰符：遮挡由
  `imePadding()` 解决，聚焦时 `TextField` 自带 `bringIntoView` 会滚到可见位置。
- **技能卡片 / 插件卡片的「已启用 / 已停用」位置不对**：原来卡片**底部 meta 行**又重复了
  一遍启用状态（技能卡更明显：徽标行已有「启用」，底部再写「已启用」），与 MCP 卡片
  （底部 meta 只放连接状态）不一致。现在：
  - 技能卡片：底部 meta 去掉，启用状态只留在徽标行、紧跟「全局技能 / 项目技能」之后，
    文案统一为「已启用 / 已停用」（与 MCP、详情弹层一致）。
  - 插件卡片：底部 meta 只保留运行态——加载失败 / 服务端口 / 「N 个工具」；
    「已启用 / 已停用」只出现在徽标行（界面 / 服务徽标之后）。
- **技能卡片的「已启用」掉到「全局技能」下面单独一行**：`ManageCard.titleBadges` 是
  **Column 槽位**，原写法把两个 `ManageTag` 直接并列传入，于是各占一行。改为内部包一层
  `Row(spacedBy(6.dp))`（与 MCP 卡片一致），作用域徽标与启用徽标同一行对齐。
- **本地音乐全部播放失败（网络电台正常，界面只显示 `Source error`）**：三个原因叠加 ——
  ① `OkHttpDataSource` 只认 http/https，`file://` 直接被拒（真实异常藏在 cause 链里）；
  ② 插件在容器里给的是 `/mnt/sdcard/...`，宿主进程打不开（需映射到
  `/storage/emulated/0/...`）；③ 大量文件扩展名是 `.mp3`、内容其实是 M4A，按扩展名排序的
  extractor 嗅探误判。修法：数据源改 `DefaultDataSource.Factory(ctx, OkHttp…)` 按 scheme
  分派；新增 `localFileFor()` 做容器路径映射；按文件头（`ftyp`/`ID3`/`fLaC`/`OggS`/`RIFF`）
  选 extractor；`onPlayerError` 打印完整异常链，界面区分「文件不存在/解析失败/网络错误」。
- **设置 → 外观 → 语言只有一个「简体中文」选项**：在它前面补「跟随系统」
  （跟随设备语言；当前资源仅简体中文，效果等同但语义正确）。新安装默认「跟随系统」，
  老用户已存的「简体中文」保持不变；分组副标题补充说明。

---

## 2.7.5（vc76）· 媒体通知补全 + 播放队列

> 版本号由 2.7.4 / vc75 升到 2.7.5 / vc76（用户指定），合入作者提供的
> `openx-audio-notify-patch-v2.7.4`（5 个文件，完整覆盖，基线 v2.7.4；不涉及依赖与 Manifest）。

### 新增
- **播放队列**：`ctx.audio.playQueue([{ uri, title, artist }], startIndex)` /
  `ctx.audio.next()` / `ctx.audio.previous()`。用 ExoPlayer 原生播放列表
  （`setMediaItems(items, index, 0)`）而非逐曲替换 `MediaItem`——只有播放器自己知道
  「还有下一首」，系统媒体卡片的上下曲按钮才会出现。`play()` 保留原签名，内部转成长度 1 的队列
  （单曲时点上下曲重播当前曲）。`AudioBar` 同步加上/下一曲按钮。
- `onMediaItemTransition` 监听自动续播与切歌同步；`STATE_READY` 立即刷新快照
  （媒体卡片要等时长就绪才能算进度/时间）；`MediaMetadata` 补
  `setIsPlayable(true)` / `setIsBrowsable(false)`；`stop()` 清空队列。

### 修复
- **部分 ROM（iQOO / OriginOS）媒体通知不显示控制键**：原通知缺少 ROM 判定「可控媒体」
  的必要条件，OriginOS 当普通通知渲染，只剩标题/副标题。补齐：
  `setCategory(CATEGORY_TRANSPORT)`、上一曲/播放暂停/下一曲/停止 四个 action、
  `setShowActionsInCompactView(0,1,2)`、`setProgress`、`setSubText`（进度文本）、
  `setLargeIcon`；通知渠道 `IMPORTANCE_LOW → IMPORTANCE_DEFAULT`（低重要级在部分 ROM
  会收起控件）。直播流不画进度，`subText` 显示「直播中」。
- 新增 `ACTION_PREV` / `ACTION_NEXT`，`onStartCommand` 分发到 `hub.previous()` / `next()`。
- 桥接：`openxAudioPlayQueue(tracksJson, startIndex)` / `openxAudioNext` / `openxAudioPrevious`
  （均非阻塞，只 post 主线程）；`tracksJson` 用 `JsonParser` 解析（避免 Kotlin 泛型 class literal）。

### 指南
- `assets/docs/插件开发指南.md`：`ctx.audio` 方法块与第 10 节补 `playQueue/next/previous`、
  队列与上下曲说明；10.3 `AudioBar` 按钮说明；10.4 通知栏控制补媒体卡片要素与队列前提。

---

## 2.7.4（vc75）· 插件音频播放

> 版本号由 2.7.3 / vc74 升到 2.7.4 / vc75（用户指定），合入作者提供的
> `openx-audio-patch-v2.7.3-to-v2.7.4`（完整文件覆盖包，基线 v2.7.3）。
> 顶部 `## 版本` 段是历史遗留（2.5.7 时期），实际版本以本节标题为准。

### 新增
- **插件音频播放**：宿主内置 ExoPlayer（AndroidX media3 1.5.1），支持本地音频文件与网络电台流，
  **含 HLS（`.m3u8`）**——大量中文电台是 HLS，系统 `MediaPlayer` 放不了。
  - `ctx.audio.play(url, { title, artist })` / `pause` / `resume` / `toggle` / `stop`
  - `ctx.audio.seekTo(ms)` / `setVolume(0..1)` / `state()`
  - 全部**同步方法**（不返回 Promise）：宿主只把命令 post 到播放线程即返回，不阻塞插件。
  - 播放器只在主线程访问，插件从后台线程只读 `@Volatile` 状态快照（事件 + 500ms tick），
    避免占住插件 session 锁把面板冻住。
- **新权限 `audio`**：`plugin.json` 声明后可用。`dangerous = false`（放声不涉及用户数据外泄），
  但会在后台持续出声，权限描述与插件 `description` 中已写明。
- **`AudioBar` 播放条组件**：`ctx.UI.AudioBar({ showVolume, compact })`，宿主渲染，
  自带进度刷新、音量滑杆、播放/暂停/停止按钮，读宿主真实状态；同页面/多页面共享同一份状态。
- **媒体通知与后台播放**：播放时拉起前台服务（`AudioPlaybackService`），切走 App 继续播；
  通知栏播放/暂停/停止，接 `MediaSession` 故耳机键与蓝牙亦可控制；停止后撤通知。
- **插件开发指南新增第 10 节「音频播放」**（`assets/docs/插件开发指南.md`）：权限声明、
  播放 API、播放条组件、行为说明、踩坑；同步更新目录、组件参考「音频播放条」小节、
  权限总表 `audio` 行、ctx 方法全集、异步注意、6.1.5 旧限制描述。

### 调整
- **插件 HTTP 超时 60s → 300s**：60s 对国内网络下的慢接口不够用，易表现为「插件转圈很久才报错」。
  `ctx.http` 仍为 runBlocking 同步实现且在 action 派发期间持锁，插件侧不应在 `onClick` 里直接 `await`
  （指南 6 节与 6.1 已强调）。
- `UiRenderer` 的 `COMPONENT_FALLBACKS`：`audio` 由「无替代（暂不支持音频播放）」改为
  「AudioBar（宿主渲染播放条；用 ctx.audio.play/pause/stop 控制）」。
- 附带 `lint { disable += "ExpiredTargetSdkVersion" }`（补丁自带；targetSdk 28 是 proot 容器的
  刻意取舍，与上架无关，不关会让 `assembleRelease` 末尾恒定失败）。

### 修复
- **停止播放后通知栏残留（MIUI）**：三处配合缺一不可 —— ① 无内容时 `updateNotification`
  直接 return 不刷新；② `onDestroy` 调 `stopForeground(STOP_FOREGROUND_REMOVE)`；
  ③ `onDestroy` 再显式 `manager.cancel(NOTIFICATION_ID)`（MIUI 在 ② 后仍留一条
  `flags=0x8` = FLAG_ONGOING_EVENT_CANCELLED 的记录）。
- **音量滑块拖不动**：`setVolume` 在未播放时会丢失意图，且 500ms 轮询会在拖动时重置滑块。
  改为快照先行（播放器建好时应用）+ 拖动期间本地值接管、`onValueChangeFinished` 才提交。
- **网络电台卡在「缓冲中…」**：改用 `OkHttpDataSource`（跟随 301/302，直播读超时 60s），
  放宽 `DefaultLoadControl` 缓冲参数，并加 25s 缓冲超时报错。
- 包含 2.7.3 同版本追加的**会话标题修复**（首条为系统消息时不再跳过/被污染/永久不重试）。

### 依赖
```kotlin
implementation("androidx.media3:media3-exoplayer:1.5.1")
implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
implementation("androidx.media3:media3-session:1.5.1")
implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
```

### 已知限制
- 跨境慢或频繁重定向的电台流可能缓冲超时（网络问题，非功能问题）；插件侧应处理
  `ctx.audio.state().error` 并提示用户。
- 直播流无有意义的时长，`AudioBar` 自动隐藏进度条。
- 插件被禁用/卸载时应调 `ctx.audio.stop()`，否则会持续出声。

---

## 2.7.3（vc74）

> 版本号由 2.7.2 / vc73 升到 2.7.3 / vc74（用户指定），并合入作者提供的
> `openx-2.7.2-fix1-patch`（完整文件覆盖包，基线 v2.7.2）。

### 修复
- **卸载插件时 JS 侧 handler 删不掉（日志刷 `[PluginRuntime] JS 侧卸载 xx 失败:
  unexpected token 'wy_calc'`）**：`callFunction` 的 `argsJson` 是**实参列表**，会被逐个展开成
  位置实参；`unload` 原来直接传了工具名数组 → `$unloadByIds(idsJson)` 只收到第一个工具名，
  `JSON.parse("wy_calc")` 报语法错误。现在先序列化成文本再包一层
  （`unloadHandlerArgsJson()`，抽成顶层函数便于单测）。影响仅是 JS 侧残留（内存泄漏），
  Kotlin 侧 `registry.remove()` 一直生效，所以模型不会误调用；属「日志噪音 + 残留」。
  新增 `JsPackageManagerUnloadArgsTest` 4 例回归。
- **容器镜像编辑抽屉顶到状态栏**：内容高度原写死 `heightIn(max = 720.dp)`，小屏（屏高不足
  720dp）时被撑满。改为按屏高比例限高 `screenHeightDp * 0.82f`（与提供商编辑页 0.85f、
  模型选择弹窗半屏的既有写法一致）。
- **首条消息是系统消息时，会话标题不再总结**（同版本号追加构建）：新会话先切「股票模式」等
  → 落一条 `[已切换至股票模式（行情分析）]` 系统消息 → 用户再发第一条消息，侧边栏标题会
  一直停在新会话占位。三处原因一并修：
  1. 标题协程读的是内存槽位，而切模式刚写过库，Room 回流可能把槽位覆盖成「只剩系统消息」
     → 判定 0 轮用户发言直接放弃。现在传入本轮用户气泡补齐；仍无用户发言时回库重读
     （发送前用户消息已 `await` 落库）。
  2. 标题提示词原样拼进系统消息，模型容易把标题起成「切换股票模式」。现在 SYSTEM 消息
     既不算用户轮数，也不进提示词（`ConversationTitlePolicy`，纯函数 + 9 例单测）。
  3. 标题协程被取消/抛异常时只是记日志，`titleFinalized` 已置位却没复位 → 该会话永久
     不再重试。现在两条 catch 都复位。

---

## 2.7.2（vc73）

> 版本号由 2.7.1 / vc72 升到 2.7.2 / vc73（用户指定）。内容 = 2.7.1 加上「冷启动后 Git 页
> 看不到当前项目仓库」修复与「插件快捷区默认折叠 + 设置开关」（此前以 `2.7.1-gitfix`、
> `2.7.1-quickbar` 后缀交付）。

### 新增
- **设置 → 偏好设置新增「展开插件快捷区」开关**：控制侧边栏插件快捷区默认展开还是折叠，
  **默认折叠**（插件开多了不再把侧边栏撑满、压掉会话列表）。开关状态持久化
  （`AppSettings.pluginQuickBarExpanded`）；在侧边栏点标题行/箭头就地切换也会写回该设置，
  所以「就地折起来」下次启动仍然保持。

### 修复
- **冷启动后 Git 页看不到当前项目的仓库（切一次项目、或先跟 AI 说句话就恢复）**：
  Git 页的仓库路径由进程级设置副本 `AppContextHolder.currentSettings.currentProject` 推导，
  而这份副本此前只在「用户改设置」或「发消息」时赋值，冷启动后一直是 `null` → 路径退化成
  默认工作区（没有 git 仓库）。现在设置持久化流一到就同步这份副本；Git 页也以「当前项目」
  为 key 触发刷新，设置异步到位后自动重刷。（同类受影响的 memory / skill / 终端 cwd 一并修好。）

---

## 2.7.1（vc72）

> 版本号由 2.7.0 / vc71 升到 2.7.1 / vc72（用户指定）。内容 = 2.7.0 加上「文件编辑器闪屏」
> 与「重新打开文档后自动换行不生效」两处修复（此前以 `2.7.0-editorflicker*` 后缀交付）。

### 新增
- **设置 → 偏好设置新增「展开插件快捷区」开关**：控制侧边栏插件快捷区默认展开还是折叠，
  **默认折叠**（插件开多了不再把侧边栏撑满、压掉会话列表）。开关状态持久化
  （`AppSettings.pluginQuickBarExpanded`）；在侧边栏点标题行/右侧箭头就地切换也会写回该设置，
  所以「就地折起来」下次启动仍然保持，不会又弹开。

### 修复
- **文件浏览器里编辑文档时「每输入一个字就闪一下」**：编辑器封装层 `SoraEditorSurface` 把
  「应用样式」的调用写在了 `AndroidView.update` 里，而每次重组都会跑 update。Sora 的
  `setTextSizePx` 会**无条件重建全部行布局**、`setColorScheme` 会 detach/attach 触发**全量重绘**、
  `setCursorBlinkPeriod` 会重置闪烁相位，每键都下发一遍就是整块编辑区一次次闪。现在记住已下发
  参数，**只在值真的变化时**才调；文本回写判据也从「整篇比较」改成**实例身份比较**。
- **重新打开文档后「自动换行开关是开的，正文却不换行」**：上一条改动引入的死角——factory 漏了
  `setWordwrap`（sora 默认不换行），而参数快照被一次记满 → 守卫认为「已下发」永不纠正。
  现在 factory 不预记快照，样式**只有一条下发路径**（首次 update 应用全部，之后仅在变化时应用）。
- **从聊天里的 `文件:行号` 链接打开后编辑，光标每敲一个字被拽回目标行**：定位效果以 `content`
  为 key，输入时每键重启并重新 `gotoLine`；改为定位成功一次即上锁。
- **冷启动后 Git 页看不到当前项目的仓库（切一次项目、或先跟 AI 说句话就恢复）**：
  Git 页的仓库路径由「进程级设置副本」`AppContextHolder.currentSettings.currentProject` 推导，
  而这份副本此前**只在用户改设置**（`updateSettings`）或**发消息**（`ChatRepository.sendMessage`）
  时才赋值，冷启动后一直是 `null` → 路径退化成默认工作区（没有 git 仓库）→ 页面显示「非仓库」。
  现在设置持久化流一到就同步这份副本，并且 Git 页以「当前项目」为 key 触发刷新（设置异步到位后
  会自动重刷，页内切换项目也能跟上）。

---

## 2.7.0（vc71）

> 版本号由 2.6.10 / vc70 升到 2.7.0 / vc71（用户指定）。此前以 `2.6.10-*` 后缀做过的预发布内容
> 全部并入本版；升级会重新释放内置规则到 `memory/agent/`，**自定义层 `agent-custom/` 与项目层不受影响**。

### 新增
- **设置 → 新增「提示词」子页**（借鉴 AiCode 的提示词页思路）：列出内置 AI 行为规则
  （`memory/agent/NN-*.md`，7 条），顶部**总开关**「启用内置提示词」，每条卡片右侧**单独开关**；
  点卡片进 Markdown 只读预览，右上角可编辑。列表按编号排序，标题/摘要取文件首行
  `<!-- 标题：摘要 -->`。
  - **分层覆盖，升级不丢改动**：编辑写入 `memory/agent-custom/`，同编号压过内置层
    （徽章显示「已自定义」），APK 升级 seed 只刷内置层；卡片里「恢复内置默认」删自定义文件回落原文。
  - **右上角「+」新建片段**：编号（00–99，唯一）+ 名称（清洗成文件名片段）+ 正文 → 只写自定义层
    `memory/agent-custom/NN-名称.md`，徽章「自建」。自建片段没有内置原文可回，详情页底部按钮
    是**「删除此片段」**（红色）而非「恢复内置默认」，删除确认单独一个弹窗。
  - 开关只影响注入：总开关关掉则整段不注入，单条停用按 key 剔除；列表始终展示全部，
    否则关掉后就找不到开关再打开。设置落 `AppSettings.agentRulesEnabled` / `agentRuleDisabled`。
- **提示词升级为四层作用域**：`APK 内置 → 本机内置副本(memory/agent) → 全局自定义(memory/agent-custom) → 项目自定义(memory/projects/<项目>/agent-custom)`，
  同编号只生效最高层。编辑/新建时可勾选「仅当前项目生效」，徽章区分「内置 / 已自定义 / 项目 / 自建」；
  删除项目层会回落到全局，删除全局层回落到内置。内置资产兜底：本机副本损坏/丢失时仍能显示原文。
- **工具输出超长时改为「保头 20K + 保尾 20K」**：此前只保留前 12 万字符，尾部（编译错误末尾、
  测试汇总、diff 末段）会永久丢失。现在中间省略、头尾都保留，并把完整输出落盘
  （`files/openx/tool-output/<会话>/`），在结果里注明总字符数与文件路径。
- **后台完成提醒升级为系统通知**：App 在后台时任务完成会发一条高优先级可点击通知
  （点击回到 App），前台仍只响铃/震动、不打扰。提示音设为「无」时通知静音。
- **`ask_user` 支持一次问多个问题**（1–4 个）：每个问题可带短标题、带说明的选项、独立单选/多选，
  全部答完一次提交（答案按编号拼接）。旧的单问题参数继续兼容。
- **真正读取 `AGENTS.md` / `CLAUDE.md`**：全局（`files/openx/AGENTS.md`）与当前项目根各读一份，
  `AGENTS.md` 优先、`CLAUDE.md` 兜底，超 32K 截断；每轮随提示词注入并标明「全局规则 / 项目规则」，
  优先级为 用户记忆 > 项目规则 > 内置规则。按 (mtime, 长度) 缓存，改文件立刻生效。
- **长期/项目记忆改为「索引注入」**：`memory` 仍按最近 5 条注入，但单条超过 180 字符时只给截断摘要
  并标注「已截断」，需要细节时由模型调用 `memory_read` 取回（与技能清单同一策略），避免整篇笔记每轮重复烧 token。
- **侧边栏「插件快捷区」支持折叠**：标题行整行可点，右侧新增 ︿/﹀ 按钮。默认展开，
  用户手动折叠后保持（`rememberSaveable`，旋转/回收恢复不重置）。折叠后仅留标题行，
  不再挤压会话列表与文件浏览。`PluginQuickBar` 内改动，无接口变化。

### 修复
- **重新打开文档后「自动换行开关是开的，正文却不换行」**：上一处修复把样式下发改成「仅变化时
  执行」后，`SoraEditorSurface` 的 factory 里漏了 `setWordwrap`（sora 默认不换行），而参数快照又
  被一次性记满 → 守卫认为「已下发」永不纠正。现在 factory 不再预记快照，样式**只有一条下发路径**
  （首次 update 应用全部、之后仅变化时应用），杜绝这类「漏设 + 快照说已设」的死角。
- **文件浏览器里编辑文档时「每输入一个字就闪一下」**：编辑器封装层 `SoraEditorSurface` 把
  「应用样式」的调用写在了 `AndroidView.update` 里，而输入时每敲一个字上层都会更新文本状态 →
  Compose 重组 → update 再跑一遍。Sora 的 `setTextSizePx` 会**无条件重建全部行布局**、
  `setColorScheme` 会 detach/attach 触发**全量重绘**、`setCursorBlinkPeriod` 会重置闪烁相位，
  每键都下发一遍，视觉上就是整块编辑区一次次闪。现在记住已下发的参数，**只在值真的变化时**才调，
  并把「文本回写」的判据从「整篇 toString 比较」改成**实例身份比较**（输入回流时是同一个
  String 实例，直接跳过），顺带省掉大文件每键一次的全量比较与分配。
- **从聊天里的 `文件:行号` 链接打开后编辑，光标每敲一个字被拽回目标行**：定位效果用 `content`
  做 key，输入时 content 每键都变 → 效果反复重启并重新 `gotoLine`。改为定位成功一次即上锁。
- **压缩摘要不再丢弃最老的历史**：原实现一次性摘要，超出预算时 `takeLast(budget)` 直接丢掉最早一段
  （只留「前段历史已省略」）。现在按预算分块、**每块带着上一块的摘要继续总结**，长会话里的早期需求
  与约束不会被整段丢弃；任一块失败则整体放弃、保留原历史，不用半截摘要覆盖。
- **压缩加入会话级互斥**：同一会话的多个压缩入口（回合结束、工具循环内落库、手动 /compact、
  超长强制压缩）此前可能并发折叠同一段历史，导致摘要重复计入或刚插入的摘要又被折进去。
  现在同一会话只允许一个压缩在跑，后来的直接跳过（不排队）。
- **上下文将满时发消息触发压缩后「只有压缩卡片、AI 不回复」**：
  - `buildTurnHistory` 不再盲目 `dropLast(1)`，改用 `historyWithoutCurrentTurn` 按本轮用户消息
    id 精确剔除。压缩提交后展示序是「保留尾 → marker → 摘要」，最后一条可能是摘要；盲删会把
    摘要从请求里删掉，保留尾里的本轮消息又被 `sendMessage` 追加一次（模型收到两条相同用户消息）。
  - 流式正文追加与成功收尾都排除压缩摘要（`ASSISTANT` + `author="系统"`）。否则回答会被拼进
    摘要卡片，界面上看起来「AI 没有回复」。
  - 新增 `HistoryWithoutCurrentTurnTest`（3 例）；单测 **527 用例 0 失败**。

### 文档
- **内置《插件开发指南》更新**：用用户提供的新版（1036 行）整体替换
  `app/src/main/assets/docs/插件开发指南.md`。
  - `server` 权限说明补充「UI 面板内可用 `ctx.serviceControl` 启停/查询服务」。
  - 上下文 API 速查新增 `ctx.serviceControl({cmd})` 的 `status` / `start` / `stop` 三种用法示例。

---

## 2.6.9（vc69）

### 修复
- **压缩后上下文圆环/判定「回弹」修复（A）**：压缩完成后 `conversationCtx`/DB 里仍是压缩前的
  真实 usage（大值），紧接着 `ensureUsageAnchor` 会拿旧值重建报告锚，导致圆环与自动压缩判定
  「先降后弹回压缩前量级」。现改为压缩后把记账值与 DB `lastInputTokens` 同步为压缩后的估算
  （真机日志实证：压缩到 ≈18K 后曾建锚 `rep=34952`）。
  - 说明：圆环在工具循环内仍会随真实请求上下波动（工具输出落盘 `ToolOutputBudget` 会让请求体
    骤减几万 token），这是真实值的正常表现，**未**改为单调不回落。

### 新增
- **插件面板内控制服务（`ctx.serviceControl`）**：UI 面板脚本新增 `ctx.serviceControl({cmd})` API
  （需 `server` 权限），支持 `start` / `stop` / `status` 三种命令，可在插件面板内直接启停/查询
  服务型插件的本地服务。启停成功后插件管理页与卡片状态自动同步刷新（`onServiceChanged` 回调
  → `vm.refreshPluginServices()`，Handler 切主线程避免重组竞争）。
  - `PluginUiHostBridge` 新增 `openxServiceControlAsync`（start 复用管理页启停逻辑：
    `store.load()` → `readServiceSource` → `PluginServiceManager.start`）。
  - `PluginUiRuntime` JS 侧新增 `ctx.serviceControl` + `__openxServiceControlAsync` 桥转发 + 自检清单登记。
  - `PluginUiSession` 透传 `onServiceChanged` 给桥；`PluginUiScreen` 新增 `onServiceChanged` 参数。
  - `MainActivity` 回调接线。

---

## 2.6.8（vc68）

### 新增
- **服务型插件（service 插件）**：为插件系统新增「服务型插件」能力。插件可在 `plugin.json` 声明
  `service`（如 `service/gateway.js`）与 `port`（默认 18899），详情页一键启动后宿主在本地 `127.0.0.1`
  启动 HTTP 服务并把请求交给插件 JS 处理。权限新增 `server`（dangerous）。
- 新增核心：`plugin/PluginServiceManager.kt`、`plugin/PluginServiceJsRuntime.kt`、
  `plugin/PluginHostActionsBridge.kt`，并接入 `PluginManageScreen`、`MainViewModel`、`MainActivity`、
  `ConsoleUiState`、`PluginModels/Permission/Store`。

### 修复
- **UI 插件首次渲染卡「加载中」**（`plugin/ui/PluginUiSession.renderLocked`）：首次渲染后检查
  `rerenderRequested`（最多 3 次），确保异步回调触发的重渲染能落到界面。
- **UI 插件宿主状态覆盖异步回调**（`plugin/ui/PluginUiRuntime`）：宿主向插件同步 state 时只补缺失键，
  不覆盖插件已更新的键，避免异步回写的新值被旧快照冲掉。

### 文档
- `assets/docs/插件开发指南.md` 新增「服务型插件」章节，更新 manifest 字段表与权限表。

---

## 2.6.7（vc67）

### 修复
- **消息里点文件路径链接，编辑器报「文件不存在或不是文件」**：AI 有时把链接目标写成**相对路径**
  （如 `项目名/app/src/...`，相对项目父目录 `/root/workspace/projects/`），而聊天链接点击后由
  `BrowseFileSystem.hostFileOf` 把相对路径一律拼到工作区根 `/root/workspace` 下，项目文件实际在
  `/root/workspace/projects/<项目>/`，于是落空。现于内置「助手行为与输出规范」规则中明确：
  文件链接目标**必须写容器内绝对路径**（`/root/workspace/projects/<当前项目>/...`，或 `~/...`），
  **禁止相对路径**。
- 本项仅改内置规则文本，需升 versionCode 触发规则重新释放（`MemoryStore.seedBuiltinAgentRules`）。

## 2.6.6（vc66）

### 新增
- **内置「助手行为与输出规范」规则**：新增 `assets/agent-rules/05-assistant-behavior.md`（随 APK 打包，
  首次/升级时释放到 `memory/agent/`），排在全部内置规则最前，每轮全量注入。内容为 AI 的职责、
  意图理解、输出文本规范（含文件路径 Markdown 链接引用、冷启动式进度说明、结尾总结）等总纲。
- `seedBuiltinAgentRules` 说明由「5 个内置文件」更正为「7 个」（新增本条，原注释与实际 6 个已不符）。

## 2.6.5（vc65）

### 2.6.5-imedodge（同版本补齐，不升号）
- **上下文设置页下方输入框被软键盘遮挡**：设置页滚动容器缺少 `imePadding()`，edge-to-edge 下
  `adjustResize` 不生效，容器底边伸到键盘后面，下面那个输入框（压缩摘要输出上限）被挡住。
  给滚动 `Column` 加 `.imePadding()`，键盘弹出时容器让位、Compose 自动把聚焦输入框滚进可视区。

### 修复
- **模型测试报错信息点不开详情**：提供商编辑页「模型」列表里，单个模型点「测试」失败后只在下方
  显示一行报错，无法查看完整的请求 URL/头/体与响应头/体——排查 400 具体字段只能导出日志。
  现把结果行做成可点击：点「✓/✗ 结果」或「详情 ›」弹出调试详情底部弹窗（对齐 rc7
  `ModelTestDetailBottomSheet`），逐段展示结果/请求 URL/请求头/请求体/响应头/响应体/错误详情，
  并提供「复制」一键复制完整调试日志。成功与失败结果均可点击查看。

## 2.6.4（vc64）

### 2.6.4-comptokens（同版本补齐，不升号）
- **压缩摘要输出上限独立成项**：新增设置 `compactionMaxTokens`（默认 4096），与聊天「最大输出 tokens」解耦。
  此前两处压缩都复用 `maxTokens`——工具循环内就地压缩（`compactToolLoopContext → summarizeForCompaction`）
  与自动/手动压缩（`performCompaction` 走 `sendMessage`）——导致写大文件把 maxTokens 调到 16384 时，
  摘要请求也跟着放大。现在两处均只读 `compactionMaxTokens`。
- 在「上下文设置」页「最大输出 tokens」下方新增「压缩摘要输出上限」输入框（默认 4096，可调）。
- 新增文件级常量兜底（`COMPACT_SUMMARY_OUTPUT_TOKENS` / `COMPACT_DEFAULT_OUTPUT_TOKENS`），
  与 `AppSettings.compactionMaxTokens` 默认一致。

### 2.6.4-maxoutput（同版本补齐，不升号）
- **新增「最大输出 tokens」设置项**：放进设置页「上下文设置」区，用户可自行填写（留空或填 0 回退默认 16384）。
  此前该字段 `maxTokens` 未在 UI 暴露，导致老安装持久化的旧默认值 4096 无法修改，单轮输出（尤其
  `writeFile` 大文件、长工具参数 JSON）超过 4096 token 会被硬截断。现可直接调整。
- 字段同时用于请求 `max_tokens`、上下文窗口兜底与压缩摘要输出上限，改动后三处一致跟随用户值。
- **不做存量迁移**：按用户要求，已有安装的 4096 保持不变，由用户自行在设置页修改。

### 修复
- **内置 AI 规则注入顺序不确定 → 前缀缓存整段失效**：`assets/agent-rules/*.md` 无 frontmatter
  `timestamp`，`MemoryStore.load` 按 `timestamp` 排序时全部并列，最终顺序落到 `File.listFiles()` 的
  目录顺序（不保证稳定），而规则位于 prompt 靠前位置——顺序一变，其后的记忆/技能/工作区/历史全部
  前缀不匹配。改为 `compareByDescending { timestamp }.thenBy { key }`，内置规则恰好按 10/20/30/50/70/80
  的期望顺序稳定输出。
- **非流式兜底未上报缓存命中**：`syncFollowUpFallback` 只记 `inputTokens` 不记 `cachedInputTokens`，
  拉低统计页命中率。补上与流式一致的解析（`prompt_cache_hit_tokens` / `prompt_tokens_details.cached_tokens`）。
- 新增回归单测 `MemoryStoreOrderTest`（内置规则顺序稳定，不依赖目录列举顺序）。

## 2.6.3（vc63）

### 修复
- **项目技能卡片开关关不掉**（全局技能卡片正常）：`SkillStore.writeDisabled` 中 `projects` 经 `?:` 合并后
  被推断为星投影类型（`MutableMap<out Any?, out Any?>`），`projects.apply { set(...) }` 的 `set` 因接收者
  类型不可用而**静默回退绑定到外层 map**，把项目禁用名单写到 `skills.json` 顶层（`"<项目名>":[...]`）而非
  `projects."<项目名>"`；读取端只看 `projects`，故项目技能永远读不到「已停用」。全局分支只动外层 map，故正常。
  改为显式 `MutableMap<String, Any>` 并显式构造嵌套结构。
- **兼容旧数据**：`disabledNamesFor` 读取项目名单时，`projects.<项目>` 缺失则回退读顶层同名键，恢复此前被
  bug 版写错位置的项目禁用状态；`writeDisabled` 落地时清理该顶层残留键。
- 新增回归单测 `SkillStoreScopeTest`（项目开关持久化 / 全局与项目互不影响 / 旧格式回退读取）。

## 2.6.2（vc62）

### 2.6.2-streamretry（同版本补齐，不升号）
- **正文流到一半断网也自动重试**（对齐 rc7 的「重试前清空已展示文本」通道，补齐此前唯一不重试的断流场景）：
  - 此前 `sseCallWithRetry` 只在「本次尝试尚未向 UI 输出任何增量」时才重试；正文吐出一半后断网会**立即**弹
    「请求失败」，只留半截正文。现新增仓库级回调 `ChatRepository.onStreamReset`：注册后，正文流到一半断网也会
    重试——重试前先回调让 UI **精确回退**本次尝试已展示的正文，再从头重发，做到既不丢内容、也不叠字。
  - UI 侧（`MainViewModel`）在本次尝试**首次吐字前**采集「展示态快照」（当前消息列表引用 + 增量缓冲内容）；
    重试前用快照整体覆盖 `messages`、清空并还原缓冲，回退到本次尝试开始前的展示状态。`onReconnectChange`
    归零（attempt=0，本轮成功/放弃）时丢弃快照，供下一轮重新采集。
  - 子代理的 `onStreamDelta` 为空、无可见回吐，采不到快照，回调对其天然 no-op，不会误清父会话正文。
  - 「等首字节/建连阶段断网」的重试行为**不变**（6 次、退避封顶 10s、UI 显示「重连中 (第 N/6 次)」）。

### 新特性
- **插件 UI 支持定时器驱动重渲染**：`setInterval` / `setTimeout` 回调执行完后，宿主会重新调用插件的 `Screen()`，秒表 / 倒计时 / 心跳计数等可以真正跳动。
  - 注入脚本里给 `setInterval`/`setTimeout` 包一层：回调执行完（含抛异常分支）调 `__openxRequestRerender()` 通知宿主重组。
  - `onRerenderRequest` 切到主线程（定时器回调在 QuickJS 线程、`rerenderTick` 是 Compose state）。
  - 更新《插件开发指南》6.1.1：定时器现在能驱动重渲染，但**不能重复注册**（给出「首帧 booted 标记 + 模块级 setter + clearInterval」正确写法）。

### 调整
- **子代理设置**：并发上限默认 **3**（原 2，范围 1..5）；工具轮次上限默认 **50**，最大 **200**（原 12，最大 100）；单任务超时默认 **1800 秒 = 30 分钟**，最大 **7200 秒 = 120 分钟**（原 300 秒，最大 5400 秒 = 90 分钟）。同步更新设置页说明与相关单测。

## 2.6.1（vc61）

### 调整
- **子代理设置**：并发上限默认 **3**（原 2，范围 1..5）；工具轮次上限默认 **50**，最大 **200**（原 12，最大 100）；单任务超时默认 **1800 秒 = 30 分钟**，最大 **7200 秒 = 120 分钟**（原 300 秒，最大 5400 秒 = 90 分钟）。同步更新设置页说明。

## 2.6.0（vc60）

### 2.6.0-skill-scope（同版本补齐，不升号）
- **`skill_load` 回显技能作用域**：返回文本由「技能「X」的完整指令如下…」改为
  「技能「X」（项目技能（仅在当前项目「&lt;项目名&gt;」内有效）/ 全局技能（所有项目通用））的完整指令如下…」。
  仅改返回文案，不改判定逻辑；解决同名时项目技能整条覆盖全局、模型不知作用域而误套跨项目约定的问题。
  补丁文件：`tools/SkillTool.kt`。

### 2.6.0-browser（同版本补齐，不升号）
- **内置浏览器加「更多设置」菜单**（对齐 AiCode）：底栏右侧新增三条杠按钮，打开「浏览器设置与工具」
  抽屉，内含三项：
  - **夜间模式**：向页面声明 `prefers-color-scheme: dark` + `color-scheme: dark`，并叠加半透明暗色
    蒙版（只压暗不改色、不挡点击）；关闭时移除。
  - **开发者工具**：注入 Eruda 移动端控制台（`assets/scripts/eruda.min.js`）并开启
    `WebView.setWebContentsDebuggingEnabled`，可用 USB `chrome://inspect` 调试。
  - **浏览器标识 (User-Agent)**：7 种——系统默认 / 安卓手机 / 安卓平板 / 桌面电脑 / 苹果 iPhone /
    苹果 iPad / 自定义（手输 UA 字符串），切换后自动重载当前页生效。
- 原底栏「桌面/移动」一键切换按钮移入抽屉（作为 UA 选项）；`BrowserTool` 的
  `desktopMode/mobileMode` 语义不变。设置持久化于 `openx_browser_settings`。

### 正式版 2.6.0
- 版本号升级：versionCode 59 → 60，versionName 2.5.9 → **2.6.0**。
- **内置 AI 系统提示词更新**（用户反馈：加了项目技能后 AI 装不对位置）：
  - `70-skills-and-mcp.md` 重写——记忆/技能真实两级路径（全局 + `projects/<项目名>/` 子目录、
    按项目物理隔离、同名项目优先）、安装技能首选 `skill_add`（scope=global|project）、
    `skills.json`/`plugins.json` 只存禁用名单且按作用域分存、新增「插件 (plugins)」节告知 AI
    插件实装位置 `~/.openx/plugins/<id>/`。
  - `80-plugin-docs-index.md` 补插件位置指针。
  - 老设备装上本包后自动重刷 `memory/agent/` 内置规则（vc60 ≠ 标记 vc59）。
- 纳入 2.5.9-intl 的 QuickJS Intl 精简 polyfill（DateTimeFormat / NumberFormat，幂等不覆盖原生）。

## 2.5.9（vc59）

### 2.5.9-intl（同版本补齐，不升号）
- **Intl 标准库补丁**（用户提供补丁包合并）：宿主导入 QuickJS（Bellard ingot 版）**没有编译
  ECMA-402**，新增 `PluginJsStdlib.kt`（JS polyfill），在两个引擎启动时注入——
  `JsPackageManager.ensureInit()`（工具侧，bootstrap 之前）+ `PluginUiSession.render()`（UI 侧，
  新增步骤 2，原步骤顺延）。
- 提供 `Intl.DateTimeFormat`（`dateStyle/timeStyle/year/month/day/hour/minute/second/weekday/
  hour12/timeZone/timeZoneName`，中英文式排布）与 `Intl.NumberFormat`（小数位 + 千分位）。
- **刻意边界**：`timeZone` 只认 `'UTC'` 与设备本地时区，其它 IANA 名抛 RangeError（容器无时区库）；
  `Collator/PluralRules/RelativeTimeFormat/ListFormat/Segmenter` 显式抛 TypeError；`formatToParts`
  仅单 literal；`globalThis.Intl` 已存在时不覆盖（幂等，换带 ECMA-402 引擎自动让路）。
- **文档**：《插件开发指南》新增「6.1 宿主运行时限制」（无 useEffect / Intl 边界 / Date 本地化 /
  main.js 无 ctx / 无 WebSocket·Worker·localStorage·完整 fetch），根目录与 `assets/docs/` 两份
  同步，`80-plugin-docs-index.md` 不变。
- 版本保持 2.5.9 / vc59 不变。

### 正式版 2.5.9
- 版本号升级：versionCode 58 → 59，versionName 2.5.8 → **2.5.9**（用户明确要求）。
- 纳入 2.5.8 期间的平级补齐：MCP 管理两级作用域 + 点开就地编辑 + 单工具开关，以及
  技能管理全局/项目作用域（与记忆管理一致）。
- 副作用说明：versionCode 变更触发内置 AI 规则/参考文档的释放标记重刷（`.builtin-version` /
  `.builtin-docs-version` 判等），属既定设计。

## 2.5.8（vc58）

### 2.5.8-mcp-skills（同版本补齐，不升号）
- **技能管理分全局/项目作用域**（与记忆管理一致）：全局技能在 `openx/skills/`（跨项目共享），
  项目技能在 `openx/skills/projects/<项目>/`（按项目隔离）。
- **管理页同构改版**：胶囊 Tab「全局技能 / 项目技能」（计数 + 未选项目提示），导入 .md / 压缩包
  落入当前 Tab 作用域；卡片与详情标注「全局/项目」标签；删除/启停均带作用域。
- **禁停名单按作用域分开**：`skills.json` 升级为 `{"disabled":[...全局...],"projects":{<项目>:[...]}}`，
  旧版单层数据兼容读取；备份合并逻辑同步支持项目级并集。
- **运行时/注入**：全局 + 当前项目技能合并注入系统提示（项目优先同名覆盖，项目技能标注）
  ；`skill_add` 新增 `scope` 参数（未选项目时项目作用域报错），`skill_delete/skill_list/skill_load`
  按合并列表解析（同名优先项目）。
- 版本保持 2.5.8 / vc58 不变。

### 2.5.8-mcp（同版本补齐，不升号）
- **MCP 管理补齐**（对照 AiCode mcp 页）：新增两级作用域——全局
  `filesDir/openx/mcp.json` 与项目级 `workspace/.aicode/mcp.json`，生效配置合并且
  **项目级优先覆盖同名**；列表条目标注「全局/项目」pill。
- **点开已装 MCP 就地编辑**：点击卡片进入编辑弹窗（对齐 AiCode）——
  「基础设置」Tab 可改作用域/启停/名称/传输类型与字段；「工具」Tab 实时列出该 Server 的工具，
  逐个开关注入 `disabledTools`（禁用项 AI 无法调用），右上角可刷新工具列表。
- 删除确认、预设/表单添加均带作用域（预设默认加入当前工作区）；AI 用 `mcp_add_server`
  添加的服务落到项目作用域。
- 版本保持 2.5.8 / vc58 不变。

### 2.5.8-plugin-p2p3（版本升级，并入插件二三期补丁包）
- **插件体系二期+三期**：二期组件 9 个（Switch/Checkbox/Slider/RadioButton/Dropdown/Chip/Tabs/
  Dialog/ModalBottomSheet）+ 三期组件 29 个（按钮族/芯片族/Surface/BoxWithConstraints/BadgedBox/
  Divider/LazyColumn/LazyRow/四款 TabRow/Snackbar/Scaffold/PullToRefreshBox/TimePickerDialog），
  补充宿主动作、图片加载、文件/URI 工具等运行时桥接。
- **参考文档机制**：新增 `memory/docs/` 作用域（不注入上下文），AI 用 `memory_read` 按需取回
  `插件开发指南.md`；`80-plugin-docs-index.md` 作索引指针。
- **并发会话数 3→6**：`MAX_CONCURRENT_SESSIONS`（闸门与超限提示共用常量）。
- 版本 2.5.7→**2.5.8**，versionCode 57→58。

### 2.5.7-jumpfix（同版本修复，不升号）
- **修复「快速到底」按钮在 AI 回复期间闪烁**：按钮显隐加了 150ms 防抖，流式贴底校准的
  `isAtBottom` 瞬态抖动被滤掉；只有真离开底部（翻历史）才显示，点按仍瞬时到底零卡顿。

### 2.5.7-fpsboost（同版本修复，不升号）
- **流畅度 A+B**：
  - A「申请 120Hz 高刷」：`onResume` 把窗口刷新的 hint 指到本机「分辨率不变、刷新率最高」
    的显示档（目标 120fps），面板支持即解锁更高上限，不支持则维持原状。
  - B「贴底校准循环降频降噪」：用户正在拖/惯性滚时不抢位（原逻辑内容一长就立刻回拉，跟手势
    抢方向既抖又掉帧）；`layoutInfo` 快照降为每 4 帧一次、修正动作 ≥80ms 间隔一次。
- AI 执行任务时的持续掉帧（30-60）预期改善；流式自动贴底、回看停跟语义不变。

### 2.5.7-thinkfix（同版本修复，不升号）
- **根治「滑动长思考内容卡死/闪退」**：思考块内层由 `verticalScroll` 的**全文渲染**改为
  **块级 LazyColumn**（`MarkdownLazyContent`）—— 每个 markdown 块一个 item，只组合/测量
  可视区块，单帧成本与思考总长度无关。删除原「尾部 4000 字窗口」截断，全文直给，不再有
  「上滑/思考结束 → 突然切全文」的跳跃式切换。
- 流式仍自动贴底、用户上滑停跟、回底恢复跟随（判据改为首项序号/偏移 + `canScrollForward`）。

### 2.5.7-scrollfade（同版本修复，不升号）
- **输入栏滚动淡出**：仅当列表在**用户手势滚动**时，输入框内容透明度淡到 0.4f（200ms），
  静止即恢复实色；程序性自动贴底不触发。对齐参考应用 AiCode 的滚动跟随感。

### 2.5.7-rewindkeep（同版本修复，不升号）
- **修复「仅恢复会话」丢压缩摘要、整卷回滚上下文**：`撤销与检查点` 弹窗点「仅恢复会话」
  时，目标在本轮可见上下文 → 折叠区**一条不展开**、之前的压缩/摘要卡片原样保留，只删目标
  之后的会话内容重发；目标在折叠区深处才回滚/展开到目标为止（更早轮次保持折叠）。
- **上下文数字口径统一**：折叠区是历史存档、发请求时装不下 DTO，估算同步跳过 folded 行，
  恢复后顶部圆环不再虚高、不再误触压缩。

### 2.5.7-layers5（同版本修复，不升号）
- **压缩历史最多保留 5 层，更老的自动清理**：压缩本身不限次数，但每次压缩后会自动核对层数，
  超过 5 层就把**最老的一层**整段删除（折叠原文 + 摘要 + 压缩卡），防止长跑会话的折叠区无限膨胀。

### 2.5.7-immsend-restore（同版本修复，不升号）
- **修复「立即发送」打断长工具不生效/消息卡排队**：AI 正在阻塞执行工具（终端命令最长能跑 30 分钟）
  时点「立即发送」，旧实现会死等被打断那轮协程结束，而工具进程 cancel 不掉——表现就是
  「AI 已停止、消息一直排队中」。现在打断后立即发目标消息，旧轮迟到的收尾用**轮次代次守卫**
  自动作废（不复位新轮状态、不抢出队、不误触压缩）。
- **修复「仅恢复会话」把整个压缩会话全部展开**：压缩是「摘要+保留尾部」的链式结构（可累计多次），
  旧实现一把解开全部折叠历史（1 万条也全展开）→ 恢复瞬间上下文飙高 → 再次压缩的死循环。
  现在只展开**最近一次压缩的那一段**，更早轮次保持折叠、摘要继续生效。

### 2.5.7-anchor（同版本修复，不升号）
- **根治「上下文显示 200K 秒变 600K + 整个会话被误压」**：长工具执行途中按「停止」再发新消息时，
  真实 usage 锚处于抑制期（无锚），旧逻辑退回**对槽内全文的历史重估**。本地估算漏掉 provider
  侧 input 缓存折扣（长工具循环每轮真实 usage 被缓存压低，估算却按全价算全程历史），把真实
  ~221K 估成 653K → 误判超窗 → 把整段 2199 条历史不可逆地压成一条摘要。
- 修复后**无锚（含抑制期）时不再拿估算做不可逆决策**：自动压缩直接跳过并记日志；上下文显示
  兜底改为「上次真实 usage」而非全量估算（宁可短暂偏低也不虚高）。真正的超窗兜底由工具循环内
  provider 真实上报 `lastInputTokens` 承担，该路径本就安全。

### 2.5.7-dsmlscroll（同版本修复，不升号）
- **支持 deepseek 系列的 DSML 工具调用**：部分模型（如 deepseek-v4.1-flash）把工具调用写成
  DSML 伪 XML（`<｜｜DSML｜｜ invoke name="terminal">`…）塞在回复内容里，不产生原生 tool_calls。
  此前宿主不识别 → 原文刷进气泡、工具不执行、AI 停在半途；本轮在工具调用解析器里把 DSML
  归一成标准 `<tool_calls>/<invoke>` 再走既有链路（流式抑制、执行、历史剥离、回显兜底）。
- **修复长思考链滚动锁死**：AI 输出特别长的思考链时，滚动一会后「下面滚不动」。
  根因是思考块内滚的「尾部窗口 ↔ 跟随开关」翻转：恢复跟随时的尾部收缩把滚动位置 clamp 掉落，
  被误判成「用户上滑」又关掉跟随 → 内容切回全文、位置定在链中间、底部实时增长的内容再也追不上。
  现在只有当滚动值下降且内容高度没有缩水时才判定用户上滑，长链跟随不再锁死。

## 修复（插件 UI）
- **`Row` 组件支持 `wrap` 属性**（应用外部补丁 fix-row-wrap）：`ctx.UI.Row({ wrap: true }, children)`
  渲染为流式换行（FlowRow），一行放不下自动折行；此前 `wrap` 被宿主静默忽略，子节点
  被挤压重叠、右侧裁掉（如 memory-manager 分类筛选栏的 5 个 chip 无法点击、无法滑动）。
- 未声明 `wrap` 的 Row 行为与之前完全一致。

# v2.5.6 更新日志

## 版本
- versionCode: 55 → 56（versionName: 2.5.5 → 2.5.6）

## 调整
- 会话输入框上方的「AI 正在思考」状态胶囊上下内边距收紧（竖屏 compact=false 10→6dp，
  横屏 compact 6→4dp），胶囊整体更扁，减少对输入区上方的挤压。

### 2.5.6-errcard（同版本修复，不升号）
- 重连胶囊从「重连中」改为「重连中 (第 N/M 次)」：网络失败重试时明示已重试次数与上限
  （重试上限 6 次，指数退避；大报文疑似逐字输出限速时上限收紧为 1 次），让用户知道还在重试。
- 请求彻底失败后，**不再往对话流插入「请求失败」系统消息**（旧实现会落库污染消息流/历史）：
  改为输入框上方的**错误卡片**，纯 UI 状态——⚠ 请求失败 + 复制按钮 + 展开查看完整错误文本
  （最高 160dp 可滚动）；一直显示直到下一次发送/操作自动清空（对齐参考项目 rc7 StatusBanner）。
- 错误不会出现在模型上下文里（未入消息流，模型天然看不到）。

# v2.5.5 更新日志

## 版本
- versionCode: 54 → 55（versionName: 2.5.4 → 2.5.5）
- 本轮行为与 2.5.4-m3fix 一致（material3 升 1.4.0 修弹窗慢速滑动卡住），
  只是按用户要求升版。

## 修复（队列「立即发送」打断 AI）
- 输入框上方消息队列里，点某条的「立即发送」（或头部「立即发送」发全部）不再干等本轮结束、
  而是**打断当前 AI 生成后立刻发出**（对齐参考项目 operit-ai 语义）。
- 保留已生成的部分内容（不闪屏不丢稿）；没点到的排队消息保持原顺序留队，随后依次发出。

# v2.5.4 更新日志

## 版本
- versionCode: 53 → 54（versionName: 2.5.3 → 2.5.4）
- 本轮行为与 2.5.3-ctxrework 一致（上下文「真值优先」重构 + 恢复链路修复），
  只是按用户要求升版。

# v2.5.3 更新日志

## 新增
- **插件系统**（自外部补丁按需摘取，非整包覆盖）：`.js` 插件可注册自定义工具并出现在工具列表；
  插件管理页支持启用 / 禁用 / 卸载 / 导入；侧边栏快捷区直达插件；插件可开独立界面。
  权限**默认一律不给**，走双端强制（宿主桥 + 运行时各校验一次）
  - ⚠ 补丁**未附带 `assets/plugins/`**，因此**内置插件为 0**、列表初始为空，
    需自行导入 `.js` 或压缩包
- **① 上下文压缩不再把上游临时故障当成「超限」**（聊天）：413 / 500 / 502 / 503 等
  上游临时错误原先会走**不可逆压缩丢历史**那条路；现按临时故障重试，不动历史

## 修复
- **上下文判定/圆环改「真值优先」架构（对齐参考项目 AiCode ContextCompactor）**：有 provider
  真实上报时，判定值 = 上次真实值 + 其后新增（纯标量增量），**不再取「本地估算与修正值取大」**
  ——本地估算一旦偏高（旧口径曾把真实 147K 顶成 432K / 628K）就会盖过真实值，把 512K 模型
  误判成超窗而强制压缩。「数字虚高 / 忽大忽小 / 切小窗口模型被误压」这一类反复发作的根全在
  「估算取大」这一处，现从架构上移除：本地估算只在从未收到过 true usage 时兜底（thinking 剔除 +
  旧工具输出按落盘提示计的口径不变）。重构后圆环与压缩触发同源
- **压缩后再点压缩后的新消息「仅恢复会话」，回不到压缩前（只剩十几K）**：压缩后上下文只剩
  压缩保留尾 + 新消息，此时对其中某条新消息点「仅恢复会话」，旧逻辑看「目标不在折叠区」就按
  「截断到该消息」处理，截出来是压缩后那一点点上下文，而折叠区原文还挂在界面上 → 数字像平白
  蒸发。现在只要会话存在压缩折叠区，会话类回退一律在**解除压缩后的全量历史**上算：仅恢复会话
  = 全部原文回到上下文、尾部一条不砍（与点在哪儿无关，顺带自动剔除 marker/摘要/通知）；
  恢复代码和会话 = 全量历史按目标截断（顺带回滚文件）。同一窗口压缩好几回时，多个折叠区与
  多组 marker/摘要对也会一次性全部解开
- **上下文圆环数字虚高、切换小窗口模型被误判超窗而强制压缩**（严重）：思考是内联在
  assistant 正文的 `<thinking>` 里的，而 token 估算把整段思考按正文字面计入；但真正发请求时
  思考被拆进独立的 `reasoning_content`，**provider 端不计这部分的 prompt token**。
  于是长思考会话被系统性高估约 4.3 倍（真机日志：估 641K / 真实 148K），从 1M 模型切到 512K
  模型瞬间越过阈值、把整个会话压掉。现估算前先剥掉 thinking（`MainViewModel` 与
  `ChatRepository` 两处口径一致），与 provider 实际计入的输入对齐
- **上下文估算仍高约 3 倍、切 512K 模型依旧误压**（上一修复的续）：发请求前
  `ToolOutputBudget` 会把**近期工具输出**只保留 50K token、更旧的落盘并换成一句提示，
  但库里仍存全文；而估算按库里的全文计量，几轮大 readFile / terminal 后与实际发送量差出一大截
  （真机日志：估 432K / 实际请求体 156K / provider reported 147K）。现 `estimateBodiesTokens`
  按同一预算口径模拟投影——旧工具输出按提示语长度（约 130 token）计，估算回到 ≈142K，与真实吻合
- **剪切粘贴后源目录不刷新**（文件树）：粘贴只刷新目标目录，而文件列表是按目录缓存的，
  源目录仍显示已被移走的文件、重进才消失。现剪切会连源父目录一并刷新（复制不影响源）
- **插件冷启动不注册工具**：启动时只加载、不启动插件运行时，导致管理页显示「已启用」、
  侧边栏也出现快捷图标，但工具列表里根本没有 —— 静默失败。现启动即启动运行时
- **插件包名冲突互相覆盖**：包名由首个工具名前缀推出，`get_weather` / `get_time` 都推成 `get`，
  后加载的会把前一个整个顶掉（工具注册不上、卸载时还删错条目）。现冲突时加 `#2`、`#3`… 后缀
- **插件工具并发执行竞争**：`QuickJsEngine` 缺整体锁，并发调用会踩同一引擎。
  现按实例加锁，覆盖绑定 / 加载 / 执行 / 卸载 / 关闭全链路
- **终端 `list_tabs` 句柄与实际不符**：列表显示标题，`create_tab` 的提示和取用却按 `id`
  走，照列表给的值取不到 tab。现三处口径统一为 `id`（唯一标题仅作兜底）
- **管理页徽标被长标题挤掉**：技能 / MCP 管理页右侧徽标在标题过长时被挤没。
  现标题让位、徽标常驻
- **去设置页再回来消息被拽到最底**：`userPulledUp`（用户上翻锁定）存在会随聊天页销毁的
  `remember` 里，一离开就被清零，回来时两个自动贴底 effect 误判「用户在底部」→ 强制拽底。
  现把它提升进按会话存活的 `ChatScrollState`
- **AI 执行工具时上翻思考块、整页不停抖动**：思考是流式输出，`content` 每变一次就无条件
  `scrollTo(maxValue)`，等于每帧把正在上翻的用户拽回底部，与外层列表的贴底校准互相抢。
  现改为「上滑即停跟随、回到底部 ≤8px 自动恢复」（注释里一直这么写，代码此前没照做）
- **思考内容漏到下方消息气泡里**：内容块按**换行数**（>4 行）判是否内滚，且 `heightIn`
  只限自身尺寸**不裁孩子**；长段落/代码块换行少却很高 → 既不滚也不裁，画出 200dp 之外，
  正好落在平级的下一项气泡上。现给内容 Box 兜底 `clipToBounds()`，并改成始终挂 `verticalScroll`、
  按真实溢出（`maxValue > 0`）判遮罩
- **AI 流式输出思考过程时卡顿**：思考是流式的，每个 delta 都携带完整累积文本，此前**每个 token
  都会在主线程同步重跑一遍 markdown 解析 + 完整重组/测量**，长思考下主线程被持续占满（不输出思考、
  只执行工具时内容不流式，所以不卡）。现三管齐下：①思考渲染文本**节流到 ~120ms** 一次快照
  （折叠/行数/计时仍用实时文本）；②`MarkdownContent` 改在 **`Dispatchers.Default` 后台解析**
  （用 `conflate` 只认最新文本、解析期间沿用上一次结果，不闪「裸文本」）；③`parseMarkdown` 逐行判定
  用的正则全部**提为文件级常量预编译**（此前在循环里现构造 `Regex`，Pattern 编译开销是主线程热点）
- **快速长思考时显示仍跟不上（肉眼看就是卡）**：上一版把思考节流到 ~120ms/次仍不够 —— 模型吐字
  速度远超解析/测量能力时，每次更新仍要把**整段**思考全量解析 + 全量测量。再补三招：
  ①节流从「定时」改为「**按字符攒块**」：攒够 400 字、或满 250ms 才渲染一次，快速输出时一次蹦
  一大块（就是用户说的「大块大块显示」）；②思考**跟随到底**时只把**尾部 4000 字**交给 markdown
  渲染（可视区仅 200dp、顶部本就在屏外），单次渲染成本与思考总长度**无关**；用户上滑回看时自动
  切回全文，不影响阅读前文；③**正文流式也纳入同一套攒块**（此前只修了思考）

## 变更
- **思考块底部加橡皮筋空白**：滑到思考内容底部还想继续往上时，手势在卡内吃掉、内容上移露出
  空白，松手弹簧回弹（上限 140dp）；短内容不参与、手势照常透传给外层列表。
  关键是把 `available` 在子级 `onPostScroll` 里吃掉，不留给外层 LazyColumn
- **思考块竖线改 `drawBehind` 绘制**：原 `Row.height(IntrinsicSize.Min)` 会让流式内容每 token 多跑
  一轮 intrinsic 测量；现竖线直接画在 Row 左缘，省掉该测量，视觉不变
- **插件相关注释与实现对齐**：包名推导注释原写「无下划线返回空串」，实际返回**原串**；
  已改正并标明该推导仅作兜底

## 版本
- versionCode: 52 → 53
- versionName: 2.5.2 → 2.5.3

---

# v2.5.2 更新日志

## 修复
- **「子代理设置 → 使用说明」里的数字与实际不符**：说明写「工具轮次上限 1~200」，
  实际滑杆只能到 100（照说明调会以为是滑杆坏了）；「单任务超时 30 秒~30 分钟」
  实际是 30 秒~90 分钟。已按实现逐项改正，并补上原本没写的默认值
  （并发 2、轮次 12 轮、超时 5 分钟）
- **「主题」里的旧值会让老用户显示「无选中」**：配色档从「跟随系统」改名「系统默认」，
  读取设置时会把老数据（含此前已删除的旧主题项）统一归一化，新旧版本都能正常选中

## 变更
- **子代理「使用说明」重写**：新增「子代理是什么」（角色列表、不能嵌套派发）、
  强制只读的两个例外（记忆管理员仍可写记忆；文件写入与网页点击/执行脚本是动作级拦截）、
  子代理模型的配置位置
- **系统提示词默认改为「通用助手」**：原先默认是一套很长的编程规范，
  在翻译、写作等非编程场景属于干扰。需要时可在「高级 → 系统提示词」点「编程专家」切换
- **外观设置「主题 → 跟随系统」改名「系统默认」**：与同页「主题模式」里的
  「跟随系统」（管明暗）区分开，避免同名混淆
- **「模型参数」改名「上下文配置」**：该分区实际只有上下文压缩阈值设置，
  原名与内容不符

## 版本
- versionCode: 51 → 52
- versionName: 2.5.1 → 2.5.2

---

# v2.5.1 更新日志

## 修复
- **压缩卡片要按「停止」才出现**：工具循环内触发压缩后，压缩结果只在回合收尾时落库，
  期间 AI 已在用压缩后的上下文继续干活、UI 上却没有卡片。根因是「登记待落库」写在了
  「通知完成」**之后**，而落库协程由通知回调启动，读到空槽后从未启动。改为登记在前、
  通知在后——压缩一完成就落库，卡片立刻出现在触发压缩的那个工具下面
- **压缩卡片位置偏高若干个工具**：保留尾按 4000 token 预算保留（约 7 组工具），
  卡片被顶到触发点上方。现按参考项目 AiCode 的做法把 marker/摘要排在全部消息之后，
  卡片紧贴触发工具；模型请求侧则把压缩产物提到历史最前，保证先读摘要再读保留历史
  （展示顺序与请求顺序**故意不同**，详见开发笔记轮次一百七十一）
- **「仅恢复会话」后上下文变少**：恢复压缩前消息时按点击位置截断，把该位置之后的
  消息一起截掉了。改为完整解除压缩，并配对剔除 marker 与摘要
- **输入框上方显示「正在压缩上下文 29853498 分钟」**：发送后的自动压缩在回合收尾之后
  才执行，此时计时起点已被清零，UI 把 1970 年至今当成了压缩耗时（该分钟数不是随机值）。
  改为进入压缩态时补计时起点，并在计时起点缺失时不显示时长
- **压缩提交后回填被暂停取消**：取消保护只包住了事务提交，漏了提交之后读库回填内存的
  那段代码——数据都写对了、卡片却不显示。改为提交与回填一并保护
- **发送中压缩卡片延迟出现**：发送期间观察者不回填上下文列表（避免数据库快照把正在
  流式的消息回退掉），压缩产生的结构性行迟迟不出现。改为发送中走合并策略：同一条消息
  保留内存里的流式版本，数据库独有的新行立即补入
- **上下文占用读数在工具执行过程中跳变**：改用参考项目的标量校准口径，报告锚点由
  「按消息 id 记位置」改为标量差值

## 变更
- 压缩历史与消息显示序统一由数据库 `seq` 决定（新增 DB 迁移，version 20）
- 压缩卡片外观对齐参考项目：默认折叠为一行摘要，展开后按 Markdown 渲染
- 上下文压缩全链路真机验证通过

## 版本
- versionCode: 50 → 51
- versionName: 2.5.0 → 2.5.1

---

# v2.1.10 更新日志

## 修复
- **上下文自动压缩失效**：设置页「自动压缩」开关与触发比例此前仅有 UI 与存储，发送消息时从不消费 → 永不触发。现在发送前检查 `compactMode` + `contextRatio`，用最近一次 API 真实输入上下文（`lastInputContext`，取不到时本地估算含工具参数/结果）对比「上下文窗口 × 比例」阈值，超阈值先自动压缩（早期消息 → LLM 摘要，保留最近 5 条 + 当前消息），失败则原样继续发送；`/compact` 手动命令同步复用该逻辑并补 try/finally 防 `isSending` 卡死
- **顶部上下文条占用虚高（显示 bug）**：此前把会话所有消息的 inputTokens+tokens **累计和**当"窗口占用"算百分比（曾显示 192071/128K 假满条）。改为优先显示最近一次 API 真实输入上下文（真实占用），无请求记录时回退本地估算
- **真实输入上下文不持久化**：`conversationCtx` 为内存缓存，退出重进后 `lastInputContext` 归 0，顶部/压缩退化为估算。现新增 DB 迁移 v8→v9：`conversations` 表加 `lastInputTokens` 列，每次请求后写入、切会话/重启后从库回读
- 上下文本地估算纳入工具参数与工具结果文本，避免长工具调用链被低估

## 新增功能
- **消息队列**：AI 回复期间输入的消息自动排队，可随时展开「队列」标签查看
  - **立即发送**：队列中每条消息独立「发送」按钮，点击打断当前 AI 回复并优先发送该条，其余排队消息按序保留在队尾
  - **停止后自动发送**：修复此前点「停止」后队列消息残留不发——旧协程被取消时因跳过 finally 之后的处理导致队列永不消费。现主动停止生成后自动把队列消息逐条发送
  - 保留原有逐条「取消」与「清空队列」操作

## 版本
- versionCode: 23 → 24
- versionName: 2.1.9 → 2.1.10

---

# v2.1.9 更新日志

## 新增功能
- **AI 终端安全限制**：AI 通过 terminal 工具执行命令前增加安全守卫，防止 AI 误删 PRoot 容器 root 系统文件导致系统崩溃
  - **硬拦截（直接拒绝）**：`rm -rf /` 及删除整个系统关键目录（/bin /usr /etc /lib /var 等）、`mkfs`/`fdisk`/`parted` 等磁盘格式化分区、`dd` 写入块设备、fork 炸弹、`reboot`/`shutdown` 等命令返回"已拦截"文案给 AI，不执行
  - **危险确认弹窗**：递归删除（如 `rm -rf node_modules`）、向系统区域写入/移动/解压（/etc /usr /var 等）、`sed -i` 修改系统文件、`chmod` 系统目录权限等，弹出红色"危险命令确认"对话框，由用户选择"仍要执行"或"拒绝"
  - 仅作用于 **AI 工具入口**（execute / start_background / send_input），真人手输命令不受影响
- **Token 统计完整移植**：在线程级统计输入/输出 token、按小时/按天聚合分析与趋势图，宽口径渠道/模型排行与调用明细分页

## 版本
- versionCode: 22 → 23
- versionName: 2.1.8 → 2.1.9

---

# v2.1.7 更新日志

## Token 统计修复
- **消息下方 input token 改为"本回合增量"**：此前把整段输入上下文（total_tokens − completion_tokens）算给当前单条消息，导致历史会话回复后 input token 虚高（曾到 11 万）。现在按用户回合计算相对上一回合的新增输入，准确反映本回合实际新增长度（`currentInputContext − lastTurnInputContext`，下限 0），消息下方显示 `Token:总 ↑输入 ↓输出`
- **顶部上下文条改用 API 真实输入上下文**：以 API 返回的 `total_tokens − completion_tokens` 显示顶部上下文（代表上下文窗口累计占用），取代本地估算（此前本地估算仅 6000，实际 11 万+）
- **按会话记忆真实输入上下文**：每个会话记录自己最近一次的 API 真实输入上下文，切换会话时恢复对应值显示当前会话的真实情况，不再残留上一个会话的数值，也不会切回才 6000 的本地估算；新建/清空/删除会话时归零，从未发过消息的会话回退到本地估算占位
- 切会话时同步按会话恢复增量基准，避免历史会话的 input 增量计算错误

## 其他
- （缓存拆分曾尝试后回退）DB 维持 v6 版本与 `cachedInputTokens` 空列，功能逻辑已移除

## 版本
- versionCode: 20 → 21
- versionName: 2.1.6 → 2.1.7

---

# v2.1.6 更新日志

## 新增功能
- **聊天记录保存到下载目录**：`/导出` 或 `/export` 直接保存到手机 `下载/OpenX/` 目录，方便用文件管理器打开查看（Android 10+ 用 MediaStore，旧版自动降级为传统写入）
- **分享导出**：`/导出分享` 或 `/exportshare` 保留原分享面板功能
- **思维链展示**：解析模型返回的 `reasoning`/`reasoning_content` 字段（以及正文中的 `<thinking>` 标签），在 assistant 回复顶部以可折叠的"🧠 thinking..."块展示，展开可查看思考内容（字号略小于正文）
- **思维链自动折叠设置**：设置页「模型参数」卡片内可配置思考块的显示方式——**折叠**（默认收起）、**不折叠**（默认展开、不自动收起）或设定秒数（默认展开、到点自动折叠），配置持久化到 DataStore

## 修复
- **MiniMax M3 等模型的 HTTP 400 降级链增强**：`streamChat` / `streamFollowUpRound` 在「流式+工具」请求失败时，依次降级为「无工具流式」→「非流式（保留工具）」→「非流式（无工具）」，兼容对流式/工具组合敏感的模型
- **报错信息截断调大**：HTTP 错误响应从 200/300 字符提升到 2000 字符，完整暴露 OpenRouter 嵌套的 `metadata.raw`/`details` 字段，便于定位上游真实错误
- **提供商切换立即生效**：设置页下拉选提供商时同步更新 baseUrl、模型、端点选项
- **修复"卡在 AI 正在思考"导致后续消息无法发送**：`sendMessage` 主协程增加 `try/finally`，无论异常、提前 `return` 还是正常结束都复位 `isSending`/`currentToolName` 状态，避免 `isSending` 残留为 `true` 时阻断后续所有发送
- **子代理报告不再截断**：工具结果回传上限从 15000 提升到 60000，子代理 `maxTokens` 下限提升到 8192，代码审查等长报告可完整传回主 Agent
- **任务面板工具兼容性**：`findTaskByDescription` 采用精确匹配→双向包含→忽略非字母数字多级匹配，`remove_task`/`update_task_status` 匹配失败时输出反馈日志，兼容无法可靠调用工具的模型

## 其他
- 上下文 token 估算精度优化（新增图片/消息开销权重，CJK 按 1 token/字符）
- 设置页 Token 统计弹窗、思维链折叠配置、上下文压缩开关统一收纳进「模型参数」卡片，改卡片可滚动避免铺满屏幕

## 版本
- versionCode: 19 → 20
- versionName: 2.1.5 → 2.1.6

---

# v2.1.3 更新日志

## 新增功能
- **消息单独删除**：每条消息（含 ToolCall 分组）增加删除按钮 + 确认弹窗
- **重新生成确认弹窗**：点击重新生成前弹出确认
- **工具调用自动折叠**：AI 调用工具时展开，AI 回复完成后 2 秒自动折叠（用户手动切换不覆盖）
- **极简亮 / 极简暗**：两个全新简约主题

## Token 显示改进
- 每条 assistant 消息下方显示 `Token:X  ↑Y  ↓Z`（单条消息的输入/输出拆分）
- 上下文条改用中文感知估算（中文×2 + 英文/4），修复之前严重低估中文 token 的问题
- 全局 `totalInputTokens` 持久化到 DataStore，按模型区分累计

## 修复
- `inputTokens` 字段添加：DB 迁移 `MIGRATION_4_5`，`ChatMessage` UI 模型补全 `inputTokens`
- ViewModel Smart cast 闭包捕获编译错误
- 上下文条只计输出不计输入的 bug（改为统一按内容长度估算）
- 设置页主题/语言行点击区域过小（整行可点）
- 暖白主题过黄（降低饱和度，背景 `FEFBF7`）

## 主题调整
- 删除「浅绿」
- 「暗色」→「科技蓝」（仅改名）
- 默认主题改为「跟随系统」

## 版本
- versionCode: 16 → 17
- versionName: 2.1.2 → 2.1.3
