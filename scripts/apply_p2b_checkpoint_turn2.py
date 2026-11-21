# -*- coding: utf-8 -*-
"""
P2b 补充 2：把 checkpointTurnId 透传到 executeAndFollowUp（真正调 snapshotBeforeWrite 的地方）。

注意：`onCacheHit, enableCache)` 在全文件出现 10 次（含子代理等其他链路），
所以必须**按行号**只改 runChat 内的 5 个调用点，不能全局替换。
"""
CR = 'app/src/main/java/com/wy/openx/data/repository/ChatRepository.kt'
lines = open(CR).read().split('\n')

# 1) 签名加参数（行号 1536 = toolPolicy 那行，1-based）
idx = None
for i, ln in enumerate(lines):
    if ln.strip() == 'toolPolicy: ((String, String) -> String?)? = null' and idx is None:
        idx = i
assert idx is not None, '未找到 executeAndFollowUp 的 toolPolicy 参数行'
lines[idx] = ('        toolPolicy: ((String, String) -> String?)? = null,\n'
              '        /** 本轮 user 消息 id：文件快照桶归属。0=沿用全局指针（子代理/旧语义）。 */\n'
              '        checkpointTurnId: Long = 0L')
print(f"  ok 签名加参数（原 L{idx+1}）")

# 2) 按行号改 runChat 内的调用点（1-based：969 985 1106 1139 1184）
targets = [969, 985, 1106, 1139, 1184]
for ln_no in targets:
    i = ln_no - 1
    cur = lines[i]
    assert 'executeAndFollowUp(' in cur, f'L{ln_no} 不是 executeAndFollowUp 调用：{cur[:60]!r}'
    assert cur.rstrip().endswith(')'), f'L{ln_no} 结尾不是 )'
    stripped = cur.rstrip()
    lines[i] = stripped[:-1] + ', checkpointTurnId)' + cur[len(stripped):]
    print(f"  ok L{ln_no} 透传")

open(CR, 'w').write('\n'.join(lines))
print('完成')
