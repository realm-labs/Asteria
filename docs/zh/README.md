# Asteria 文档

这个目录按使用者关心的能力拆分文档。`README.md` 只保留项目入口和模块清单；具体模块的职责、接入方式和运行约定放在这里。

## 阅读路径

- [模块地图](module-map.md)：先判断项目需要哪些模块，以及哪些模块只是适配层。
- [应用生命周期](application-lifecycle.md)：`gameApplication`、模块安装顺序、服务注册和 actor 基础设施。
- [事件系统](events.md)：事实事件、topic 树、父节点 fan-out 和事件 handler 注册。
- [配置系统](config.md)：配置快照、热更、配置中心、Luban、代码生成和配置变更分发。
- [Pekko 集群](cluster-pekko.md)：集群启动、实体/单例声明、实体预唤醒和配置中心拓扑。
- [消息、协议和网关](messaging-protocol-gateway.md)：业务消息、处理器注册、protobuf 协议生成、网关会话和 Netty transport。
- [持久化](persistence.md)：actor-local 数据管理、Mongo 跟踪包装器和 KSP 约定。
- [脚本和 GM](script-and-gm.md)：脚本运行时、异步脚本任务、GM feature 和 Spring HTTP starter。
- [运行时补丁](patch.md)：补丁仓库、插件解析、单节点/集群应用补丁。
- [观测和启动器](observability-and-starter.md)：metrics/tracing 抽象、OpenTelemetry 适配和本地/集群启动 DSL。

## 模块到文档的对应关系

- `foundation-*`：先读 [应用生命周期](application-lifecycle.md)
  ，事件部分读 [事件系统](events.md)，消息部分读 [消息、协议和网关](messaging-protocol-gateway.md)。
- `cluster-*`：读 [Pekko 集群](cluster-pekko.md)，配置中心拓扑读 [配置系统](config.md)。
- `config-*`：读 [配置系统](config.md)。
- `gateway-*`、`protocol-*`、`rpc-*`、`broadcast-*`：读 [消息、协议和网关](messaging-protocol-gateway.md)。
- `persistence-*`：读 [持久化](persistence.md)。
- `script-*`、`gm-*`：读 [脚本和 GM](script-and-gm.md)。
- `patch-*`：读 [运行时补丁](patch.md)。
- `observability-*`、`starter-*`、`utils-game`：读 [观测和启动器](observability-and-starter.md)。

## 示例项目

- [Antares](https://github.com/mikai233/antares)：一个真实游戏服务器脚手架，展示基于 Asteria 的服务如何拆分网关、世界、玩家、GM、配置、协议和工具等区域。

## KSP 生成快照

使用 Asteria KSP 的模块会额外生成 JSON 快照，记录本次代码生成扫描到的原始模型。快照用于业务项目做可选的生成结果校验，不作为运行时配置读取。

- `META-INF/asteria/codegen-snapshots/message/<moduleId>.json`
- `META-INF/asteria/codegen-snapshots/event/<rootPackage>.json`，文件名会把 root package 中的非字母数字字符替换为 `_`
- `META-INF/asteria/codegen-snapshots/config/config.json`
- `META-INF/asteria/codegen-snapshots/persistence-mongodb/entities.json`

业务项目可以把这些快照同步到版本管理中，在 CI 或发布构建前重新生成并比较 diff，用来发现 handler、topic、配置表或 Mongo wrapper 模型的意外变化。

## 文档约定

文档里的示例优先展示框架期望的集成方式，不覆盖业务侧完整实现。需要业务实现的地方会明确标出，比如认证、玩家绑定、脚本审批、配置事件投递、Mongo
client 生命周期等。
