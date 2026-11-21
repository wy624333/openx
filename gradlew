#!/usr/bin/env sh

APP_HOME=$(cd "${0%/*}" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

# Gradle（含 in-process 的 Kotlin 编译）堆大小。
#
# 不写死：设备内存跨度极大（4GB 手机到 16GB 平板），给小了在 R8 / dex 合并阶段 OOM
# （大 APP 尤其容易挂），给大了会和系统抢内存、反而被低内存杀手连进程一起干掉。
# 这里按设备总内存的 **50%** 计，钳制在 1024MB~8GB：
#   15GB → 7612MB，8GB → 4096MB，4GB → 2048MB，2GB → 1024MB。
# 取一半而不是更小的比例，是因为构建失败（OOM）比构建时内存吃紧的代价大得多。
#
# 用户若已在设置里配过「Gradle 堆内存上限」（写在 ~/.gradle/gradle.properties），
# 则以用户的选择为准、这里不插手：命令行 -D 的优先级高于 properties，
# 不判断的话会反过来把用户配的值覆盖掉。
USER_GRADLE_PROPS="${HOME}/.gradle/gradle.properties"
if [ -f "$USER_GRADLE_PROPS" ] && grep -q '^[[:space:]]*org\.gradle\.jvmargs' "$USER_GRADLE_PROPS" 2>/dev/null; then
    JVMARGS=""
else
    MEM_MB=$(awk '/^MemTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo 2>/dev/null)
    case "$MEM_MB" in
        ''|*[!0-9]*) MEM_MB=0 ;;
    esac
    if [ "$MEM_MB" -gt 0 ]; then
        XMX_MB=$((MEM_MB / 2))
        [ "$XMX_MB" -lt 1024 ] && XMX_MB=1024
        [ "$XMX_MB" -gt 8192 ] && XMX_MB=8192
    else
        XMX_MB=2048
    fi
    JVMARGS="-Xmx${XMX_MB}m -Dfile.encoding=UTF-8"
fi

if [ -n "$JVMARGS" ]; then
    exec "$JAVACMD" -classpath "$CLASSPATH" -Dorg.gradle.jvmargs="$JVMARGS" org.gradle.wrapper.GradleWrapperMain "$@"
else
    exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
fi
