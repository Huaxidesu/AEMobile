# AEMobile

| 仓库 | 是什么 |
|---|---|
| **[AEMobile](https://github.com/Huaxidesu/AEMobile)** | 服务端 mod（本仓库）：AE 监控器、TCP 服务、图标导出命令 |
| [AEMobileApp](https://github.com/Huaxidesu/AEMobileApp) | 手机端 App：AE 监控、物品 / 流体 / 源质终端 |
| [AEMobileIcoOutput](https://github.com/Huaxidesu/AEMobileIcoOutput) | 图标库维护包：导出、建库、出包脚本 |

服务端 mod：把 GTNH 服务器里的 AE 网络暴露给手机 App，用自定义 TCP 协议传输。

> 本项目修改自 [koiNoCirculation/AEMobile](https://github.com/koiNoCirculation/AEMobile)。
> 原版通过 HTTP 与浏览器 WebUI 提供监控能力；本分支移除了 Tomcat 与 WebUI，
> 改为自定义 TCP 协议（协议 3.0.0），由手机 App 直接连接。

## 功能

- 查询 AE 网络里的物品、流体、神秘时代源质
- 提交合成计划，查看每台 CPU 的进度与剩余任务
- 导出图标，供 App 的图标库使用
- 客户端兜底渲染 ServerUtilities 的现代 TAB（见下）

## 客户端：现代 TAB

3.5.0 起，客户端会兜底渲染 ServerUtilities 的现代 TAB：居中面板、页眉页脚、ping 数字。
文案由服务器侧 [AEMobileBridge](https://github.com/Huaxidesu/AEMobileBridge) 通过 `SU|TabHF` 信道下发，
客户端不需要改任何配置。

工作原理：监听 `RenderGameOverlayEvent.Pre(PLAYER_LIST)`，**只有该事件被其它模组取消**（即 SU 的
mixin 分支被挡掉）时，才反射调用 `serverutils.client.tab.ModernTabRenderer.render()`，
画完再把事件重新取消，避免和原版重复绘制。SU 正常接管时本模组什么也不做。

- 依赖 GTNH 整合包自带的 ServerUtilities；找不到该渲染器时静默跳过。
- 已知冲突：`BetterPingDisplay` 会取消该事件并整份重画原版 TAB，和 SU 的 `showPingNumber`
  功能重复，装了它页眉页脚就不显示，建议移除。
- `NecroTempus` 的 PlayerTab 模块同样会接管 TAB，需要在 `config/NecroTempusConfig.cfg`
  里把 `"PlayerTab"` 的 `B:PlayerTab` 设为 `false`。

## 使用

1. 服务端安装本 mod
2. 在 ME 控制器旁边放置「AE 监控器」，方块会把网络注册到服务，并在聊天栏输出访问密码
3. 手机端打开 [AEMobileApp](https://github.com/Huaxidesu/AEMobileApp)，填写服务器地址、端口与密码

合成表：

![](./recipe.png)

## 配置

配置文件 `config/AEMobile.cfg`：

| 配置项 | 说明 |
|---|---|
| `server.port` | TCP 监听端口，默认 `44444`。App 里填的端口必须与之一致 |

端口需要在防火墙或端口映射中放行，App 才能连上。

## 图标

App 的图标库由 [AEMobileIcoOutput](https://github.com/Huaxidesu/AEMobileIcoOutput) 生成。
客户端进入世界后执行：

```
/aemobileicons dump all
```

物品图标、流体颜色与方块图集快照、源质颜色与贴图路径会写入 `dumps/aemobile/`，
再按该仓库的 `AGENTS.md` 建库打包。

也可以只导其中一类：

```
/aemobileicons dump items [尺寸]    仅物品图标，默认 128px
/aemobileicons dump fluids          仅流体
/aemobileicons dump aspects         仅源质
```

## Build

```bash
./gradlew build
```

产物在 `build/libs/`：`*.jar` 为发布包，`*-dev.jar` 为开发调试用。

## Development

发布包对部分依赖做了重定位（shadow jar），直接用 IDE 的 runClient 可能加载到未重定位的依赖而报错。
推荐用打包产物运行：

1. `./gradlew build`
2. 把 `build/libs/aemobile-*-dev.jar` 复制到 `run/mods`
3. 由 GradleStart 加载该 dev 包启动客户端或服务端

## Credits

- 原项目：[koiNoCirculation/AEMobile](https://github.com/koiNoCirculation/AEMobile)，最初的 HTTP 与 WebUI 监控实现
- 图标素材版权归各模组作者所有（GregTech、Thaumcraft 及 GTNH 各模组）
