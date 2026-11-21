package com.wy.openx.plugin.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 插件里的图标名 → Material 图标。
 *
 * ## 命名约定
 * 组件里写 `{ icon: 'add' }` 即可。**同时接受**下划线与驼峰两种写法
 * （`arrow_back` / `arrowBack` / `ArrowBack`），因为插件作者记不住我们用的是哪种，
 * 与其让人反复试错，不如在映射入口统一归一化。
 *
 * ## 未知图标
 * 回退到 [Icons.Outlined.Extension]（而不是崩溃或空白）——
 * 插件用了没登记的图标时，结果应是"图标不好看"，而不是"界面坏了"。
 *
 * 依赖 `material-icons-extended`（项目已有）。用通配符导入是本文件唯一合理的用法：
 * 逐个 import 上百个图标只会让文件臃肿且难以增删。
 */
object UiIcons {

    /** 归一化：去掉下划线/连字符/空格并转小写，便于宽松匹配。 */
    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace("_", "").replace("-", "").replace(" ", "")

    fun of(name: String): ImageVector = when (normalize(name)) {
        // ---------------------------------------------------------- 增删改查
        "add", "plus", "addcircle", "createnew" -> Icons.Outlined.Add
        "delete", "trash", "remove", "deleteforever" -> Icons.Outlined.Delete
        "removecircle" -> Icons.Outlined.RemoveCircleOutline
        "edit", "pencil", "rename", "modify" -> Icons.Outlined.Edit
        "search", "find", "magnify" -> Icons.Outlined.Search
        "settings", "gear", "config", "tune" -> Icons.Outlined.Settings
        "build", "wrench", "tool" -> Icons.Outlined.Build
        "bug", "debug", "bugreport" -> Icons.Outlined.BugReport

        // ---------------------------------------------------------- 状态
        "refresh", "reload", "sync" -> Icons.Outlined.Refresh
        "play", "run", "start", "playarrow" -> Icons.Outlined.PlayArrow
        "pause" -> Icons.Outlined.Pause
        "stop" -> Icons.Outlined.Stop
        "shuffle", "random" -> Icons.Outlined.Shuffle
        "repeat", "loop", "repeatall" -> Icons.Outlined.Repeat
        "repeatone", "repeat_one", "singleloop" -> Icons.Outlined.RepeatOne
        "check", "ok", "done", "yes" -> Icons.Outlined.Check
        "checkcircle", "success" -> Icons.Outlined.CheckCircle
        "close", "cancel", "x", "clear" -> Icons.Outlined.Close
        "info", "about" -> Icons.Outlined.Info
        "warning", "warn", "alert" -> Icons.Outlined.Warning
        "error", "fail" -> Icons.Outlined.ErrorOutline
        "help", "question", "support" -> Icons.Outlined.HelpOutline
        "star", "favorite" -> Icons.Outlined.Star
        "starborder" -> Icons.Outlined.StarBorder
        "bookmark" -> Icons.Outlined.BookmarkBorder
        "label", "tag" -> Icons.Outlined.Label

        // ---------------------------------------------------------- 方向 / 导航
        "arrowback", "back" -> Icons.Outlined.ArrowBack
        "arrowforward", "forward", "next" -> Icons.Outlined.ArrowForward
        "arrowupward" -> Icons.Outlined.ArrowUpward
        "arrowdownward" -> Icons.Outlined.ArrowDownward
        "arrowdropdown", "dropdown", "expandmore", "chevrondown" -> Icons.Outlined.ArrowDropDown
        "expandless", "chevronup", "collapse" -> Icons.Outlined.ExpandLess
        "chevronright", "rightarrow" -> Icons.Outlined.ChevronRight
        "chevronleft", "left" -> Icons.Outlined.ChevronLeft
        "expandmore2" -> Icons.Outlined.ExpandMore
        "home", "house" -> Icons.Outlined.Home
        "menu", "hamburger" -> Icons.Outlined.Menu
        "morevert" -> Icons.Outlined.MoreVert
        "morehoriz" -> Icons.Outlined.MoreHoriz

        // ---------------------------------------------------------- 文件 / 数据
        "file", "insertdrivefile", "document", "doc" -> Icons.Outlined.InsertDriveFile
        "fileopen", "folderopen" -> Icons.Outlined.FolderOpen
        "folder" -> Icons.Outlined.Folder
        "createnewfolder", "newfolder" -> Icons.Outlined.CreateNewFolder
        "save", "disk" -> Icons.Outlined.Save
        "download", "filedownload" -> Icons.Outlined.FileDownload
        "upload", "import", "fileupload" -> Icons.Outlined.FileUpload
        "copy", "contentcopy", "duplicate" -> Icons.Outlined.ContentCopy
        "paste", "contentpaste", "clipboard" -> Icons.Outlined.ContentPaste
        "undo" -> Icons.Outlined.Undo
        "redo" -> Icons.Outlined.Redo
        "print" -> Icons.Outlined.Print
        "archive", "zip", "package" -> Icons.Outlined.Archive
        "cloud" -> Icons.Outlined.Cloud
        "cloudupload" -> Icons.Outlined.CloudUpload
        "clouddownload" -> Icons.Outlined.CloudDownload

        // ---------------------------------------------------------- 媒体
        "image", "photo", "picture" -> Icons.Outlined.Image
        "camera", "photocamera" -> Icons.Outlined.PhotoCamera
        "videocam", "video", "movie" -> Icons.Outlined.Videocam
        "mic", "microphone", "record" -> Icons.Outlined.Mic
        "volumeup", "volume", "speak", "sound" -> Icons.Outlined.VolumeUp
        "volumeoff", "mute", "silent" -> Icons.Outlined.VolumeOff
        "skipnext" -> Icons.Outlined.SkipNext
        "skipprevious" -> Icons.Outlined.SkipPrevious

        // ---------------------------------------------------------- 通讯
        "email", "mail", "message" -> Icons.Outlined.Email
        "chat", "sms", "comment" -> Icons.Outlined.ChatBubbleOutline
        "phone", "call" -> Icons.Outlined.Phone
        "send", "share", "submit" -> Icons.Outlined.Send
        "notifications", "bell", "notify" -> Icons.Outlined.Notifications
        "person", "user", "account" -> Icons.Outlined.Person
        "accountcircle", "avatar", "profile" -> Icons.Outlined.AccountCircle
        "group", "people", "users" -> Icons.Outlined.Group

        // ---------------------------------------------------------- 时间 / 位置
        "calendar", "calendartoday", "date", "event" -> Icons.Outlined.CalendarToday
        "schedule", "clock", "time" -> Icons.Outlined.Schedule
        "timer", "alarm" -> Icons.Outlined.Timer
        "location", "locationon", "pin", "place" -> Icons.Outlined.LocationOn
        "map" -> Icons.Outlined.Map

        // ---------------------------------------------------------- 商务
        "shoppingcart", "cart", "buy" -> Icons.Outlined.ShoppingCart
        "payment", "pay", "money" -> Icons.Outlined.Payment
        "creditcard", "card" -> Icons.Outlined.CreditCard
        "receipt", "order", "bill" -> Icons.Outlined.Receipt
        "currencyexchange", "exchange", "convert" -> Icons.Outlined.CurrencyExchange
        "trendingup", "grow" -> Icons.Outlined.TrendingUp
        "trendingdown", "fall" -> Icons.Outlined.TrendingDown
        "showchart", "chart", "graph" -> Icons.Outlined.ShowChart
        "barchart", "stats", "statistics" -> Icons.Outlined.BarChart

        // ---------------------------------------------------------- 安全
        "lock", "secure", "private" -> Icons.Outlined.Lock
        "lockopen", "unlock" -> Icons.Outlined.LockOpen
        "key", "password", "token" -> Icons.Outlined.Key
        "visibility", "eye", "show" -> Icons.Outlined.Visibility
        "visibilityoff", "hide" -> Icons.Outlined.VisibilityOff
        "shield", "security" -> Icons.Outlined.Security
        "login", "signin" -> Icons.Outlined.Login
        "logout", "signout", "exit" -> Icons.Outlined.Logout

        // ---------------------------------------------------------- 选择控件
        "radiobuttonchecked", "radioon", "selected" -> Icons.Outlined.RadioButtonChecked
        "radiobuttonunchecked", "radiooff", "unselected" -> Icons.Outlined.RadioButtonUnchecked
        "checkbox", "checked" -> Icons.Outlined.CheckBox
        "checkboxoutline", "unchecked" -> Icons.Outlined.CheckBoxOutlineBlank
        "toggleon", "switch" -> Icons.Outlined.ToggleOn

        // ---------------------------------------------------------- 技术
        "code", "script", "js", "javascript", "develop" -> Icons.Outlined.Code
        "terminal", "console", "shell" -> Icons.Outlined.Terminal
        "memory", "chip", "cpu" -> Icons.Outlined.Memory
        "storage", "database", "db" -> Icons.Outlined.Storage
        "dns", "server" -> Icons.Outlined.Dns
        "wifi", "network" -> Icons.Outlined.Wifi
        "bluetooth" -> Icons.Outlined.Bluetooth
        "android" -> Icons.Outlined.Android
        "smartphone", "mobile", "device" -> Icons.Outlined.Smartphone
        "computer", "desktop", "pc" -> Icons.Outlined.Computer
        "language", "translate", "i18n" -> Icons.Outlined.Language
        "psychology", "brain", "ai" -> Icons.Outlined.Psychology
        "science", "experiment", "lab" -> Icons.Outlined.Science
        "extension", "plugin", "puzzle" -> Icons.Outlined.Extension
        "widgets", "apps", "grid" -> Icons.Outlined.Widgets
        "dashboard" -> Icons.Outlined.Dashboard
        "list" -> Icons.Outlined.List
        "subject", "text", "article" -> Icons.Outlined.Subject
        "filter", "filterlist", "funnel" -> Icons.Outlined.FilterList
        "sort" -> Icons.Outlined.Sort
        "zoomin" -> Icons.Outlined.ZoomIn
        "zoomout" -> Icons.Outlined.ZoomOut
        "link", "url", "chain" -> Icons.Outlined.Link
        "openinnew", "external", "launch" -> Icons.Outlined.OpenInNew

        else -> Icons.Outlined.Extension
    }

    /**
     * 是否有这个图标（用于文档/调试提示）。
     * 注意：未知图标不报错，只回退，所以这个判断仅供调试面板展示。
     */
    fun knows(name: String): Boolean = of(name) !== Icons.Outlined.Extension ||
        normalize(name) in setOf("extension", "plugin", "puzzle")
}
