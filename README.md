# fcm-push：本地 FCM 推送服务

在自己电脑上运行的推送工具：网页里保存设备 token，勾选设备发送通知，查看每台设备的发送结果。
基于 Spring Boot 3.5 + Firebase 官方 Admin SDK（FCM HTTP v1），Java 17。

## 准备

1. **服务账号私钥**：Firebase 控制台 → 项目设置 → 服务账号 → 生成新的私钥，下载一个 JSON。
   这是机密（拿到它就能以你的名义给所有设备发推送）：放在本项目目录之外，不要提交、不要发给别人。
2. **设备 token**：在 HardwareLens「信息 → 关于 → 消息推送」里复制。

## 运行

```bat
run.bat
```

或者：

```bat
mvn package -DskipTests
java -jar target\fcm-push-1.0.0.jar
```

打开 <http://127.0.0.1:8090>：

1. 顶部填私钥文件路径，点「加载」（右上角变成「已就绪 · 项目 xxx」）
2. 左侧粘贴 token 添加设备
3. 右侧填标题正文，点「发送给勾选的设备」
4. 下方「发送记录」看每台设备是否成功；失败会写明原因（如 token 失效、不属于这个项目）

也可以启动时直接指定私钥，省去每次在网页里填：

```bat
set FCM_CREDENTIALS=C:\keys\hardwarelens-test-firebase-adminsdk.json
run.bat
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 服务账号是否已加载、项目 id |
| POST | `/api/config` | `{"credentialsPath": "..."}` 加载 / 更换私钥 |
| GET / POST | `/api/devices` | 设备列表 / 添加 `{"name","token"}`（同一 token 再加则改名） |
| DELETE | `/api/devices/{id}` | 删除设备 |
| POST | `/api/push` | `{"tokens":[...] 或 "topic":"news", "title","body","data":{},"dataOnly":false}` |
| POST | `/api/push/devices` | `{"deviceIds":[...], "title","body","data","dataOnly"}` 按已保存的设备发送 |
| GET | `/api/history` | 最近 200 条发送记录（内存，重启清空） |

例如用 curl 发一条：

```bat
curl -X POST http://127.0.0.1:8090/api/push -H "Content-Type: application/json" -d "{\"tokens\":[\"<token>\"],\"title\":\"告警\",\"body\":\"CPU 温度过高\"}"
```

## 说明

- **只监听 127.0.0.1**：不对局域网开放。要给别的程序调用，改 `application.yml` 的 `server.address`，并自己加鉴权
- **消息格式**：notification + data（标题正文也放进 data），安卓高优先级，通知渠道 `push`（与 HardwareLens 一致）。
  勾选「只发数据」时不带 notification，App 在后台也会回调 `onMessageReceived`，由 App 自己显示
- **"成功"的含义**：FCM 接受了这条消息，不代表手机已经显示（手机离线时 FCM 会暂存，上线后送达）
- 设备列表保存在 `data/devices.json`；发送记录里的 token 只显示首尾几位
- 一次最多发给 500 台（FCM 批量接口上限）

## 测试

```bat
mvn test
```

接口层用假的发送器测试（不连谷歌）；真实发送器测试消息组装和私钥加载（用测试里临时生成的假私钥）。
真正发到手机需要你的服务账号私钥和设备 token，测试里不涉及。
