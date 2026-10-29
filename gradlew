#!/bin/bash
# 协议 3.0.0 起没有浏览器 WebUI，前端子模块（AEMonitor-Frontend）已移除，
# 这里只做转发：不再下载 node、不构建前端、也不往 src/main/resources/static 塞产物。
exec ./gradlew.in "$@"
