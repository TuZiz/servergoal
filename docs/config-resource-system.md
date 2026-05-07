# ServerGoal 配置资源体系

## 目录分层

JAR 内置默认资源放在 `src/main/resources/` 母目录：

- `config.yml`
- `main.yml`
- `lang/zh_cn.yml`
- `activities/default.yml`
- `collections/wood.yml`
- `collections/stone.yml`

首次启用时，插件会把这些默认文件释放到服务器插件数据目录中。服主只需要修改释放后的文件；插件运行时优先读取自定义文件，未覆盖的字段回退到 JAR 内置模板默认值。

## 文件职责

- `config.yml`：全局设置、默认模板、存储模式、数据库和同步参数。
- `main.yml`：唯一箱子 GUI 配置，`S` 星星按钮用于管理员开启默认活动，`P` 是收集按钮，`C` 集中显示贡献、阶段和奖池信息。
- `activities/*.yml`：活动模板，配置活动时长、总目标、启用的 `collections`、收集按钮展示说明、通知阶段和最终贡献奖池。
- `collections/*.yml`：单个收集任务，配置该任务目标量、代表物品和可提交材料判定规则。
- `lang/zh_cn.yml`：命令反馈、广播、运行时状态文本和 GUI 缺省/兜底文本。默认 GUI 按钮文案直接写在 `main.yml` 中。

## 默认活动行为

- 管理员点击 `main.yml` 中的 `S` 星星按钮，或输入 `/sg start` / `/sg star`，开启默认收集活动。
- 玩家点击活动广播，或输入 `/sg join`，打开参与 GUI。
- `stages` 是通知阶段，只广播进度节点，不发放阶段奖励。
- 全服提交达到 `target-total` 后，才触发 `contribution-reward`，按个人贡献占比分配最终奖池。

## 存储模式

默认是本地 YAML：

```yml
storage:
  type: yaml
```

本地模式只读写本服 `data/activity.yml`，不做跨服同步。

需要跨服时切换到数据库：

```yml
storage:
  type: database
database:
  type: mysql
  host: localhost
  port: 3306
  database: servergoal
  username: root
  password: password
  table-prefix: "servergoal_"
```

数据库模式会把活动状态写入共享 MySQL/MariaDB 表，所有子服使用同一个数据库，并用不同的 `server-id` 标记来源。ServerGoal 不把共享文件作为跨服正式方案。

## 冲突合并

跨服状态合并采用 `sync.conflict-policy: merge-max`：

- 总进度、单项进度、个人贡献按最大值和分服分片合并。
- 阶段解锁取更高阶段。
- 已领取阶段奖励和个人奖励按集合并集处理。
- 贡献奖池只允许一个 `server-id` 成为发放者，避免重复发钱。

## 容错策略

`storage.database-failure-strategy` 控制数据库不可用时的行为：

- `fail-fast`：启动阶段数据库不可用时禁用插件，运行期同步失败会抛出错误。
- `fallback-yaml`：数据库不可用时保留本地 YAML 状态继续运行，后续恢复数据库后再同步。

所有文件和数据库 IO 都通过插件异步 IO 执行器执行，不能在 Bukkit/Paper/Folia 主线程直接读写。
