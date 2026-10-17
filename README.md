# CoAP over TLS/TCP Demo

基于 **Eclipse Californium 3.1.0** + **JDK 21** 的 CoAP over TLS/TCP 示例工程，演示如何用**同一份 JKS 证书 + 密码**实现服务端与客户端的双向 TLS 通信，并提供图形化压测工具。

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21 | 运行环境 |
| Eclipse Californium | 3.1.0 | CoAP 协议实现 |
| element-connector-tcp-netty | 3.1.0 | CoAP over TCP/TLS 传输层（基于 Netty） |
| Maven | 3.8+ | 构建工具 |
| slf4j-simple | 1.7.36 | 日志 |

## 协议栈

```
CoAP (RFC 7252)
  └─ TCP (RFC 8323)
       └─ TLS 1.3 (双向认证 mTLS)
```

URI Scheme：`coaps+tcp://host:port/path`

## 项目结构

```
coaplearn/
├── pom.xml                                    # Maven 配置
├── server.jks                                 # 自签名 CA 证书（服务端+客户端共用）
├── gen-cert.ps1                               # 证书生成脚本
├── run-server.bat                             # 启动服务端
├── run-client.bat                             # 运行客户端（单次请求）
├── run-loadtest.bat                           # 命令行压测
├── run-loadtest-gui.bat                       # 图形化压测工具
├── Californium3.properties                    # Californium 运行时配置（自动生成）
└── src/main/
    ├── java/com/coaplearn/tls/
    │   ├── SslContextFactory.java             # 从 JKS 构建 SSLContext（身份+信任锚）
    │   ├── CoapTlsServer.java                 # CoAP over TLS/TCP 服务端
    │   ├── CoapTlsClient.java                 # CoAP over TLS/TCP 客户端（单次请求示例）
    │   ├── CoapTlsLoadTest.java               # 命令行压测（多线程 + 延迟统计）
    │   └── CoapTlsLoadTestGui.java            # 图形化压测工具（JMeter 风格）
    └── resources/
        └── simplelogger.properties            # 日志级别配置
```

## 证书说明

本工程使用**同一份 `server.jks`**（密码 `123456`）作为服务端和客户端的身份与信任锚。

`server.jks` 内部包含两个条目：

| alias | 条目类型 | 用途 |
|-------|---------|------|
| `server` | PrivateKeyEntry（私钥+证书） | 服务端/客户端证明身份 |
| `ca` | TrustedCertEntry（同一张证书） | 校验对方证书的信任锚 |

证书为自签名 CA，EKU 同时包含 `serverAuth` 和 `clientAuth`，SAN 包含 `localhost` 和 `127.0.0.1`，因此可同时充当服务端和客户端证书。

### 重新生成证书

```powershell
powershell -ExecutionPolicy Bypass -File gen-cert.ps1
```

## 快速开始

### 1. 启动服务端

```powershell
.\run-server.bat
```

服务端监听 `coaps+tcp://0.0.0.0:5684/`，注册了两个资源：
- `GET /hello` — 返回问候信息
- `POST /hello` — 回显载荷
- `POST /echo` — 回显载荷

### 2. 运行客户端（单次请求）

```powershell
.\run-client.bat
```

输出示例：
```
[Client] GET /hello -> 2.05
[Client]   Hello from CoAP+TLS+TCP! peer=/127.0.0.1:60250
[Client] POST /echo -> 2.01
[Client]   echo: Hello CoAP+TLS+TCP!
```

## 压测

### 命令行压测

```powershell
# 50 并发，持续 30 秒，GET /hello
.\run-loadtest.bat --threads 50 --duration 30 --path /hello --method GET

# 100 并发，共 50000 请求，POST /echo
.\run-loadtest.bat --threads 100 --requests 50000 --path /echo --method POST
```

参数说明：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--threads N` | 并发线程数（= TLS/TCP 连接数） | 50 |
| `--duration S` | 持续压测秒数（与 `--requests` 二选一） | 10 |
| `--requests N` | 总请求数（达到即停止） | - |
| `--path /xxx` | 请求路径 | `/hello` |
| `--method GET\|POST` | 请求方法 | `GET` |
| `--warmup S` | 预热秒数（不计入统计） | 2 |

输出示例：
```
===== Result =====
Elapsed      : 10.00 s
Total        : 165580
Success      : 165580
Failure      : 0
Throughput   : 16551 req/s
Latency (us) : min=0, avg=50, max=87043
  p50=0, p90=0, p99=2445, p999=4379 (us)
```

### 图形化压测工具

```powershell
.\run-loadtest-gui.bat
```

提供类似 JMeter 的界面：

- **配置区**：JKS 路径、密码、URI、方法、载荷、并发数、时长、预热
- **请求结果表格**：序号、方法、路径、响应码、延迟(ms)、状态（OK 绿 / FAIL 红）、响应体
- **聚合统计**：Samples、Success、Fail、Error%、Avg/Min/Max 延迟、p90/p95/p99、吞吐(req/s)
- **可变参数**：载荷和 URI 支持占位符

#### 可变参数

在载荷或 URI 中使用占位符，每个请求自动替换：

| 占位符 | 说明 | 示例 |
|--------|------|------|
| `${counter}` | 全局自增计数 | `1`, `2`, `3`... |
| `${threadId}` | 线程编号 | `0`, `1`... |
| `${timestamp}` | 当前毫秒时间戳 | `1729170000000` |
| `${random:N}` | 0 到 N-1 的随机整数 | `${random:1000}` → `452` |
| `${uuid}` | UUID | `a1b2c3d4-...` |

示例：载荷填 `msg-${counter}-${random:1000}`，实际发出：
```
msg-1-452
msg-2-37
msg-3-981
...
```

## 实测参考（本机 localhost）

| 场景 | 并发 | 时长 | 吞吐 | p99 |
|------|------|------|------|-----|
| GET /hello | 50 | 10s | 16,551 req/s | 2.4ms |
| POST /echo | 50 | 10s | 15,842 req/s | 2.5ms |
| GET /hello | 200 | 10s | 16,337 req/s | 11.8ms |

> 注：本机回环测试受 CPU 和 TCP 栈限制，实际生产环境需将客户端与服务端部署在不同机器。

## 核心设计

### 单 JKS 双向 TLS

```java
// SslContextFactory.build(jksPath, password)
KeyStore ks = KeyStore.getInstance("JKS");
ks.load(in, password);

// 身份：取 server 条目的私钥+证书链
KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
kmf.init(ks, password);

// 信任：取 ca 条目作为信任锚
TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
tmf.init(ks);

SSLContext ctx = SSLContext.getInstance("TLSv1.3");
ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
```

- 服务端 `TLS_CLIENT_AUTHENTICATION_MODE = NEEDED`（要求客户端证书）
- 客户端用同一个 JKS 提供证书，服务端用 `ca` 条目验证

### CoAP over TCP/TLS

服务端：
```java
TlsServerConnector connector = new TlsServerConnector(sslContext, new InetSocketAddress(5684), config);
CoapEndpoint endpoint = new CoapEndpoint.Builder().setConnector(connector).build();
```

客户端：
```java
TlsClientConnector connector = new TlsClientConnector(sslContext, config);
CoapEndpoint endpoint = new CoapEndpoint.Builder().setConnector(connector).build();
```

## 常见问题

### 控制台中文乱码

运行脚本已加 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8`。如仍乱码，检查终端编码是否为 UTF-8。

### POST 请求没有打印

确认 URI 路径是 `/hello` 或 `/echo`（这两个资源都实现了 `handlePOST`）。如果 POST 到未实现 `handlePOST` 的资源，服务端返回 4.05 Method Not Allowed，不会进入处理方法。

### 端口占用

```powershell
# 查看并释放 5684 端口
Get-NetTCPConnection -LocalPort 5684 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### 切换为单向 TLS

将服务端的 `CertificateAuthenticationMode.NEEDED` 改为 `NONE`，客户端 SSLContext 初始化时 KeyManager 传 `null` 即可。

## 许可证

Eclipse Public License v2.0
