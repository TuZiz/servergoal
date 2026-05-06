# ServerGoal

ServerGoal 是一个基于 Kotlin 的 Minecraft 全服物品收集活动插件，兼容 Paper / Spigot / Folia。

## 特性

- 全服物品收集活动
- 多阶段目标解锁
- 阶段奖励与个人贡献奖励
- 箱子 GUI 查看状态、提交物品、领取奖励
- 贡献排行榜点击后直接输出到聊天栏，不打开排行 GUI
- 聊天栏广播活动关键节点
- 默认本地 YAML 存储
- 可选 MySQL / MariaDB 数据库存储，支持跨服同步
- 所有 GUI、语言和活动模板都放在资源文件中

## 资源文件

- `src/main/resources/config.yml` 全局配置
- `src/main/resources/main.yml` GUI 布局
- `src/main/resources/lang/zh_cn.yml` 语言文本
- `src/main/resources/activities/default.yml` 默认活动、启用任务和奖励
- `src/main/resources/collections/wood.yml` 木头收集任务
- `src/main/resources/collections/stone.yml` 石头收集任务

## 配置结构

默认资源首次启用会释放到插件数据目录。当前结构不再使用 `gui/main.yml` 和 `rewards/default.yml`：

- `main.yml`：唯一 GUI。主界面里包含收集按钮和 `C` 个人贡献按钮；奖励进度、奖池信息和排行榜入口集中在 `C` 按钮。
- `activities/<id>.yml`：一个完整活动，配置时长、总目标、启用的 `collections`、阶段奖励、个人奖励和贡献奖池。
- `collections/<id>.yml`：一个收集任务，配置展示物品、目标量和可提交材料判定规则。

`activities/default.yml` 里的 `collections` 顺序决定 `main.yml` 中多个 `P` 按钮显示什么：

```yml
collections:
  - wood
  - stone
```

上面表示第一个 `P` 是木头，第二个 `P` 是石头。每个收集按钮只提交自己对应的材料。

## 配置模式

默认是本地 YAML：

```yml
storage:
  type: yaml
```

需要跨服时改成数据库：

```yml
storage:
  type: database
database:
  type: mysql
```

## 命令

- `/servergoal`
- `/sg`
- `/goal`

## 权限

- `servergoal.use`
- `servergoal.admin`
- `servergoal.admin.reload`
- `servergoal.admin.start`
- `servergoal.admin.end`
- `servergoal.admin.create`
- `servergoal.admin.additem`
- `servergoal.admin.test`

## 构建

```bash
mvn clean package
```

生成文件：

```text
target/servergoal-1.0-SNAPSHOT.jar
```

## 默认活动

内置默认模板是“木头与石头收集”：

- 总目标数量：20000
- 允许提交任意木头类材料
- 默认也提供石头收集按钮
- 个人贡献奖池：30000 金币
