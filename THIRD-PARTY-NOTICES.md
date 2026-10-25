# 第三方声明 / Third-Party Notices

本插件在设计与实现上参考了以下项目。其版权与许可声明随本项目一同分发。

## TaskbarLyrics

- 项目：https://github.com/ANYNC/TaskbarLyrics
- 用途：任务栏嵌入方案、设置体系、歌词版式与视觉参数（透明度、动画时长、曲线等）参考/复刻自该项目。
- 许可证：MIT

```
MIT License

Copyright (c) 2026 anync

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## JNA

- 项目：https://github.com/java-native-access/jna
- 版本：5.15.0
- 用途：通过 JNA 调用 Windows `user32` / `gdi32` 等原生接口。
- 许可证：Apache License 2.0 或 LGPL 2.1+（双许可）。
- 发行包内的 `lib/jna-5.15.0-win.jar` 保留了 `META-INF/LICENSE`；完整许可文本见该文件或上述项目主页。

## Salt Player for Windows Workshop API

- 项目：https://github.com/Moriafly/spw-workshop-api
- 用途：插件宿主 API（`com.xuncorp.spw.workshop.api`），由播放器在运行时提供，不随插件分发。
- 许可证：以宿主项目声明为准。
