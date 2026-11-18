# reference

早期用 Frida 做的对照实现，**不参与构建，不进部署链路**。保留是因为它记录了每一处 hook 的
原始意图——payload 的模块基本是照着这些脚本改写成 C++ 的。

| 文件 | 说明 |
|---|---|
| `iccoa_fullscreen.js` | Frida 版 ICCOA 投屏全屏：改 `Surface` 尺寸、覆盖 `mDisplayDensityDpi`。对应 `payload/modules/iccoa_fullscreen/` |

`payload/load-payload.js` 和 `poc/load-poc.js` 也是 Frida 脚本，但它们是**工具**
（把 `.so` 加载进进程），留在各自目录里。

> 这些脚本只在有 Frida server 的调试环境里能跑，日常部署走 `nhinject`。
