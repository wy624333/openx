# -*- coding: utf-8 -*-
"""
撤掉「默认模型」页的会话级警告提示（P4b 加的 UI 部分），恢复作者原文案。

为什么撤：提示是基于「专用模型与会话模型会打架」写的，但实测——
  没配专用模型时 resolveSpecialModel 本来就返回 null，门控什么都不做，
  页面显示「跟随聊天模型」，用户看到橙色警告反而会误以为设置坏了。
保留：resolveSpecialModel 里的 modelSessionMode 门控（配了才起作用，是那道
  「不打架」的闸，零成本保险），以及识图/子代理的按会话取提供商修复。
"""
S = 'app/src/main/java/com/wy/openx/ui/screen/SettingsScreen.kt'
L = open(S).read().split('\n')

# 1) 删掉 Warning 块（注释行 → 与之配对的 if 块闭合 }）
start = next(i for i, ln in enumerate(L) if '会话级模型模式】本页配置全部失效' in ln)
assert 'if (modelSessionMode) {' in L[start + 1], f'块起始异常: {L[start+1]!r}'
# 从 if 起做花括号配平，找到闭合行
depth = 0
end = None
for i in range(start + 1, len(L)):
    depth += L[i].count('{') - L[i].count('}')
    if depth == 0:
        end = i
        break
assert end is not None and end - start == 9, f'块跨度异常 {start}..{end}'
snippet = '\n'.join(L[start:end + 1])
assert '本页所有设置均不生效' in snippet, '删错内容'
del L[start:end + 1]
print(f'  ok 1 删 Warning 块（原 L{start+1}-{end+1}，共 {end-start+1} 行）')

# 2) 导航行副标题恢复原文
for i, ln in enumerate(L):
    if 'SettingsNavRow("默认模型"' in ln:
        assert 'if (modelSessionMode)' in ln, '副标题已被改过？'
        L[i] = '                    SettingsNavRow("默认模型", "识图、压缩与起标题的专用模型", Icons.Outlined.AutoAwesome) {'
        print(f'  ok 2 副标题恢复（L{i+1}）')
        break
else:
    raise AssertionError('未找到导航行')

open(S, 'w').write('\n'.join(L))
print('已恢复（门控与串台修复保留）')
