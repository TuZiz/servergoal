# ServerGoal

ServerGoal 是一个基于 Kotlin 的 Minecraft 全服物品收集活动插件，兼容 Paper / Spigot / Folia。

## 特性

- 全服物品收集活动
- 多阶段通知节点
- 全服集齐后按贡献占比分配最终奖池
- 管理员可点击 GUI 星星按钮开启默认收集活动
- 活动通知可点击参与，也可输入 `/sg join` 打开参与界面
- 箱子 GUI 查看状态、提交物品、查看贡献与奖励信息
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

- `main.yml`：唯一 GUI。`S` 星星按钮用于管理员开启默认活动，`P` 是收集按钮，`C` 集中显示贡献、阶段和奖池信息。
- `activities/<id>.yml`：一个完整活动，配置时长、总目标、启用的 `collections`、通知阶段和最终贡献奖池。
- `collections/<id>.yml`：一个收集任务，配置展示物品、目标量和可提交材料判定规则。

`activities/default.yml` 里的 `collections` 顺序决定 `main.yml` 中多个 `P` 按钮显示什么：

```yml
collections:
  - wood
  - stone
```

上面表示第一个 `P` 是木头，第二个 `P` 是石头。每个收集按钮只提交自己对应的材料。
如果 GUI 里 `P` 数量多于活动启用的收集任务，多余的 `P` 会自动显示为 `-` 的灰玻璃材质，并且不会触发提交。

## 活动规则

- 管理员点击主界面的 `S` 星星按钮，或输入 `/sg start` / `/sg star` 开启默认收集活动。
- 玩家点击全服通知，或输入 `/sg join`，打开参与 GUI。
- `stages` 是通知阶段，只广播阶段进度，不发放阶段奖励。
- 只有全服集齐 `target-total` 后，才会触发 `contribution-reward`，按玩家贡献占比分配最终奖池。

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
- 木头任务：允许提交原木、木头、去皮变体、下界菌柄/菌核和竹块
- 石头任务：允许提交石头、圆石、深板岩、花岗岩、闪长岩、安山岩、方解石、凝灰岩等
- 通知阶段：5000、12000、20000
- 最终贡献奖池：30000 金币
