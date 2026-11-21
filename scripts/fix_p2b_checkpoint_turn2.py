# -*- coding: utf-8 -*-
"""
纠正：上一版脚本把 checkpointTurnId 加到了**错误**的函数签名上
（`toolPolicy: ... = null` 在文件里出现多次，第一次命中在 L1011 的另一个函数）。

本脚本：
1) 撤销那次错误插入；
2) 从 `private suspend fun executeAndFollowUp(` 定位它自己的参数区，只在**那里**加参数。
"""
CR = 'app/src/main/java/com/wy/openx/data/repository/ChatRepository.kt'
src = open(CR).read()

BAD = ('        toolPolicy: ((String, String) -> String?)? = null,\n'
       '        /** 本轮 user 消息 id：文件快照桶归属。0=沿用全局指针（子代理/旧语义）。 */\n'
       '        checkpointTurnId: Long = 0L')
ORIG = '        toolPolicy: ((String, String) -> String?)? = null'

n = src.count(BAD)
assert n == 1, f'错误插入块出现 {n} 次（期望 1）'
src = src.replace(BAD, ORIG)
print('  ok 已撤销错误插入')

# 定位 executeAndFollowUp 的参数区
start = src.index('    private suspend fun executeAndFollowUp(')
end = src.index('    ): String {', start)
region = src[start:end]
assert region.count(ORIG) == 1, f'executeAndFollowUp 参数区内 toolPolicy 出现 {region.count(ORIG)} 次'

NEW = (ORIG + ',\n'
       '        /** 本轮 user 消息 id：文件快照桶归属。0=沿用全局指针（子代理/旧语义）。 */\n'
       '        checkpointTurnId: Long = 0L\n')
src = src[:start] + region.replace(ORIG, NEW) + src[end:]
open(CR, 'w').write(src)
print('  ok 已加到 executeAndFollowUp 自己的参数区')
