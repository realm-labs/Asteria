# 模块地图

## 基础层

| 模块                                                                         | 职责                                                  | 何时使用                                    |
|----------------------------------------------------------------------------|-----------------------------------------------------|-----------------------------------------|
| `foundation-core`                                                          | 应用生命周期、模块系统、角色、实体和单例声明、服务注册表                        | 所有 Asteria 应用都需要                        |
| `foundation-actor`                                                         | Pekko actor 基类、actor dispatcher 上的协程 scope、timer 辅助 | 业务 actor 需要协程或生命周期 gate 时               |
| `foundation-ksp-support`                                                   | KSP 诊断格式和生成器通用辅助                                    | 框架 KSP 模块内部依赖，业务侧通常不直接使用                |
| `foundation-contribution` / `foundation-contribution-ksp`                  | 通用贡献点注解和 KSP 聚合清单生成                                 | 业务扩展点需要生成列表后自行建索引或 patchable registry 时 |
| `foundation-event`                                                         | 进程内事实事件、topic 树、父节点 fan-out、事件 handler              | 业务模块需要解耦领域通知时                           |
| `foundation-event-ksp`                                                     | 扫描事件 handler 注解并生成 event dispatcher                 | event handler 多、希望避免手写注册时               |
| `foundation-message`                                                       | 消息接口、handler、dispatch                               | 需要框架级消息分发或生成 handler 注册时                |
| `foundation-message-ksp`                                                   | 扫描消息 handler 注解并生成注册代码                              | handler 多、希望避免手写注册时                     |
| `foundation-message-gradle-plugin`                                         | 给业务工程接入 message KSP                                 | 业务模块使用 Gradle 插件接入代码生成时                 |
| `foundation-protobuf`                                                      | protobuf message registry 基础设施                      | protobuf 消息需要统一 id registry 时           |
| `foundation-id`                                                            | worker id 租约和 Snowflake 风格 id 生成                    | 多节点生成唯一 id 时                            |
| `foundation-id-etcd` / `foundation-id-mongodb` / `foundation-id-zookeeper` | worker id 租约后端                                      | 按已有基础设施选择一种                             |

## 运行时和通信

| 模块                                                                                        | 职责                                                  | 何时使用                           |
|-------------------------------------------------------------------------------------------|-----------------------------------------------------|--------------------------------|
| `cluster-pekko`                                                                           | Pekko actor system、cluster sharding、singleton、实体预唤醒 | 服务器使用 Pekko 集群时                |
| `cluster-pekko-management` / `cluster-pekko-kubernetes`                                   | Pekko Management 或 Kubernetes 启动发现                  | 不同部署环境的集群发现                    |
| `cluster-config`                                                                          | 从配置中心读取节点拓扑                                         | 节点 host/port/role/seed 来自配置中心时 |
| `gateway-core`                                                                            | 网关 transport/session、route registry、dispatch 抽象     | 需要客户端连接层时                      |
| `gateway-netty`                                                                           | WebSocket/binary Netty transport                    | 直接暴露 Netty 网关时                 |
| `gateway-pekko`                                                                           | 网关到 Pekko runtime 的转发适配                             | 网关包要转发到 actor 时                |
| `ephemeral-broadcast-core` / `ephemeral-broadcast-protobuf` / `ephemeral-broadcast-pekko` | 本地或 Pekko at-most-once 广播、protobuf 广播消息             | 需要在线、非持久通知时                    |
| `event-stream-core` / `event-stream-protobuf`                                             | broker-neutral 事件契约、outbox、protobuf 事件 codec        | 事件需要持久化、回放或跨系统集成时              |
| `event-stream-nats-jetstream`                                                             | NATS JetStream 持久事件 backend                         | 使用 JetStream 承载内部业务事件时         |

## 协议和 RPC

| 模块                               | 职责                                       | 何时使用                                |
|----------------------------------|------------------------------------------|-------------------------------------|
| `protocol-protobuf`              | 网关 protobuf envelope 和 protocol registry | 客户端协议是 protobuf 时                   |
| `protobuf-codegen`               | 生成 gateway/RPC protocol Kotlin 文件        | 协议多，不想手写 id registry 时              |
| `protobuf-codegen-gradle-plugin` | Gradle 接入 protobuf protocol 生成器          | 业务工程自动生成协议 registry 时               |
| `rpc-protobuf`                   | protobuf RPC id registry 和实体 id registry | actor 间 RPC 消息走 protobuf registry 时 |
| `rpc-protobuf-pekko`             | Pekko serializer 和 sharding extractor    | RPC 消息需要跨节点序列化时                     |

## 数据和配置

| 模块                                                                       | 职责                                    | 何时使用                    |
|--------------------------------------------------------------------------|---------------------------------------|-------------------------|
| `config-core`                                                            | 配置快照、校验、组件派生、热更、变更分发                  | 业务依赖配置表时                |
| `config-annotations` / `config-ksp` / `config-gradle-plugin`             | 配置表和配置变更 handler 代码生成                 | 希望强类型访问配置表和 handler 清单时 |
| `config-luban`                                                           | Luban Java loader 适配                  | 配置表由 Luban 生成 Java 代码时  |
| `config-publisher`                                                       | 发布配置 artifact 和 manifest 到配置中心        | 需要版本化发布配置包时             |
| `config-center`                                                          | 配置中心抽象、typed repository、watch 恢复封装    | 需要运行时配置、集群拓扑或热更触发时      |
| `config-center-zookeeper` / `config-center-etcd` / `config-center-nacos` | 配置中心后端适配                              | 按基础设施选择一种               |
| `persistence-core`                                                       | actor-local 数据加载、缓存、flush、idle unload | actor 管理自己的内存态数据时       |
| `persistence-mongodb-*`                                                  | Mongo 注解、KSP、跟踪 wrapper、写入队列          | actor 数据存在 MongoDB 时    |

## 运维能力

| 模块                                                                                   | 职责                                      | 何时使用                                |
|--------------------------------------------------------------------------------------|-----------------------------------------|-------------------------------------|
| `script-core`                                                                        | 脚本引擎、上下文、目标、执行结果                        | 需要运行 GM 脚本或运维脚本时                    |
| `script-pekko`                                                                       | 将脚本目标映射到 Pekko 节点、角色、实体、单例              | 脚本需要打到 actor runtime 时              |
| `script-job` / `script-job-mongodb`                                                  | 异步脚本任务、结果持久化、限流和租约                      | GM 脚本可能长耗时或多目标时                     |
| `gm-core`                                                                            | GM feature 元数据、操作授权入口、审计上下文             | 有 GM 后台时                            |
| `gm-shutdown`                                                                        | 业务侧停服 plan、phase、step 编排和 GM action 元数据 | 需要 GM/运维触发 graceful shutdown 时      |
| `gm-config-center`                                                                   | 底层 ConfigStore 只读浏览、受限预览和 decoder 扩展    | GM 需要查看配置中心原始 path/revision/bytes 时 |
| `gm-*` starters                                                                      | Spring HTTP API 和具体 feature 适配          | 需要直接暴露 HTTP GM 接口时                  |
| `ops-http-ktor`                                                                      | 节点本地 Ktor HTTP 运维入口，支持 SSH/curl 脚本和补丁控制 | 没有 GM 节点但需要本机运维控制面时                 |
| `patch-core` / `patch-jar` / `patch-mongodb` / `patch-config-center` / `patch-pekko` | 运行时补丁、插件解析、补丁仓库和集群控制                    | 需要在线热补丁或补丁审计时                       |
| `observability-core` / `observability-opentelemetry`                                 | metrics/tracing 抽象和 OTel 实现             | 需要接入可观测性时                           |
| `starter-game-server-pekko`                                                          | 本地和集群启动 DSL、route module、patch starter  | 业务项目希望少写启动胶水时                       |

## 内部机制速览

- `foundation-core`：按模块声明顺序执行 `install` 和 `start`，按反向顺序执行 `stop` 和 `uninstall`。`ServiceRegistry`
  是精确类型查找，不会自动按父类或接口搜索。
- `foundation-contribution-*`：KSP 在编译期扫描 `@AsteriaContribution`，生成静态贡献清单。运行期不会扫描
  classpath；业务侧把清单转换成
  list、map、groupBy 或 patchable registry。
- `foundation-event-*`：KSP 生成 handler handle、registry 和 dispatcher。生成的 registry 是 patchable slot registry，
  补丁替换的是具体 handler slot，不是整个 dispatcher。
- `foundation-message-*`：message KSP 只生成 handler handles。应用启动层选择具体 `MessageHandleRegistry` 并构造
  `MessageDispatcher`；需要运行时补丁语义时使用 `patch-core` 提供的 registry。
- `config-*`：`ConfigLoader` 每次生成完整快照，validator 通过后才发布。配置中心 watch 只触发重读，不携带完整配置状态；配置表和变更
  handler 的 KSP 只生成强类型访问和 handler 清单。
- `cluster-pekko-*`：应用拓扑先由 `foundation-core` 声明，Pekko runtime 再按 role、entity、singleton 元数据启动 actor
  system、
  sharding 和 singleton。
- `gateway-*`、`protocol-*`、`rpc-*`：协议 registry 只负责 id、类型和 parser 映射；gateway transport 只负责连接和帧；转发到
  actor
  runtime 由 route/forwarder 决定。
- `persistence-*`：actor 持有自己的数据管理器。Mongo KSP 生成 tracked wrapper 和 dirty path 计划，flush 时由 repository
  批量写回。
- `script-*`、`gm-*`、`ops-http-ktor`：脚本执行、GM feature 和节点本地 HTTP 入口是三层能力。脚本 runtime 负责目标和策略；GM/ops
  入口负责权限、审计和提交请求。
- `patch-*`：补丁插件通过 `RuntimePatchInstallContext` 声明 service、message handler 或 event handler 替换。基础注册项保留，
  卸载补丁时回退到下一层 patch 或 base 实现。

## 组合建议

最小单进程服务只需要 `foundation-core` 加业务模块。Pekko 集群服务通常组合 `foundation-core`、`foundation-actor`、
`cluster-pekko`、`config-core` 和某个配置中心后端。对外网关服务再加 `gateway-core`、`gateway-netty`、`protocol-protobuf`
和协议生成插件。需要 GM 脚本时再加 `script-core`、`script-pekko`、`script-job`、对应 repository 和 `gm-script`。如果没有 GM
节点但允许运维 SSH 到业务机器，可以加 `ops-http-ktor`，默认通过 loopback HTTP 和 bearer token 提供本机控制入口。
