# 插件开发参考文档（按需查阅）

App 内置了完整的《插件开发指南》，包含：

- 二期组件（Switch / Checkbox / Slider / RadioButton / Dropdown / Chip / Tabs / Dialog / ModalBottomSheet）
- 三期组件（按钮族 / 芯片族 / Scaffold / TabRow 家族 / LazyColumn / Snackbar / PullToRefreshBox / TimePickerDialog 等）
- 每个组件的 **props 契约表**（属性名、类型、回调签名）
- `ctx` API 清单、权限声明、调试与热重载方法

## 怎么取

文档放在内置参考区（`memory/docs/插件开发指南.md`），**不随对话注入**以省上下文。
写插件或不确定组件属性时，用 `memory_read` 按关键词取回：

```
memory_read(key="插件开发指南")
memory_read(key="TabRow")
memory_read(key="ctx UI 组件")
```

> 被要求安装/查看插件时，插件实装位置是 `~/.openx/plugins/<id>/`（清单 `plugin.json`，
> 启停名单 `~/.openx/plugins.json`），宿主「插件」页负责安装导入；详见《70·AI 扩展机制》。

## 必须遵守的一条写法约定

`ctx.useState(key, init)` 返回 `[值, setter]`，**setter 是整体替换**。
回调里连续改多次状态时，必须用函数式更新，否则后一次会覆盖前一次：

```js
// ✅ 正确
function patchP(x) { setP(function (prev) { return merge(prev, x); }); }

// ❌ 错误：回调里连续调两次，第二次会把第一次覆盖回旧值
function patchP(x) { setP(merge(p, x)); }
```

> 组件的 props 名一律以指南里的契约表为准。例如 `RadioButton` 用 `checked` + `onCheckedChange`
> （不是 `selected` + `onClick`），`Dropdown` 用 `selectedIndex`（整数，不是 `value` 字符串）。