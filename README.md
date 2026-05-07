# ServerGoal

ServerGoal 是一个基于 Kotlin 的 Minecraft 全服物品收集活动插件，兼容 Paper / Spigot / Folia。

## 特性

- 全服物品收集活动
- 活动运行期间可配置定时进度通知
- 全服集齐后按贡献占比分配最终奖池
- 最终奖池支持最低贡献门槛，未达标玩家只记录贡献，不参与瓜分
- 活动结束后写入历史记录，并可在 GUI 中分页查看历史活动
- 支持活动轮换池，可每周从多个活动模板中选择一个开启
- 支持启动时按在线人数计算动态目标，本次活动期间目标固定；数据库模式下会汇总所有子服在线人数心跳
- 管理员可点击 GUI 星星按钮开启默认收集活动
- `/sg` 只显示指令帮助，不再默认打开 GUI
- 活动 GUI 使用 `/sg open <活动ID>` 打开，例如 `/sg open default`
- 活动通知可点击参与，也可输入 `/sg join` 打开当前活动界面
- 箱子 GUI 查看状态、提交物品、查看贡献与奖励信息
- 贡献排行榜点击后直接输出到聊天栏，不打开排行 GUI
- 聊天栏广播活动开启、定时进度、完成和奖池发放
- 默认本地 YAML 存储
- 可选 MySQL / MariaDB 数据库存储，支持跨服同步
- 所有 GUI、语言和活动模板都放在资源文件中

## 资源文件

- `src/main/resources/config.yml` 全局配置
- `src/main/resources/main.yml` GUI 布局
- `src/main/resources/lang/zh_cn.yml` 语言文本
- `src/main/resources/activities/default.yml` 基础木石收集活动，可用 `/sg open default` 或 `/sg start default`
- `src/main/resources/activities/survival.yml` 生存服物资集结活动，可用 `/sg open survival` 或 `/sg start survival`
- `src/main/resources/collections/wood.yml` 木头收集任务
- `src/main/resources/collections/stone.yml` 石头收集任务
- `src/main/resources/collections/gear/iron/*.yml` 铁套装和铁工具收集任务
- `src/main/resources/collections/gear/gold/*.yml` 金套装和金工具收集任务

## 配置结构

默认资源首次启用会释放到插件数据目录。当前结构不再使用 `gui/main.yml` 和 `rewards/default.yml`：

- `main.yml`：唯一 GUI。`S` 星星按钮用于管理员开启默认活动，`P` 是收集按钮，`C` 集中显示贡献和奖池信息，并进入活动历史 GUI。
- `activities/<id>.yml`：一个完整活动，配置时长、总目标、启用的 `collections`、收集按钮展示说明和最终贡献奖池。
- `collections/<id>.yml`：一个独立收集任务，配置目标量、代表物品和可提交材料判定规则；GUI 里的一个 `P` 按钮对应一个 collection。支持子文件夹路径，例如 `gear/iron/helmet` 会读取 `collections/gear/iron/helmet.yml`。

`activities/<id>.yml` 里的 `collections` 顺序决定 `main.yml` 中多个 `P` 按钮显示什么：

```yml
collections:
  - wood
  - stone
  - gear/iron/helmet
```

上面表示第一个 `P` 是木头，第二个 `P` 是石头，第三个 `P` 是铁头盔。每个收集按钮只提交自己对应的材料，且每个任务独立计算目标。
如果 GUI 里 `P` 数量多于活动启用的收集任务，多余的 `P` 会自动显示为 `-` 的灰玻璃材质，并且不会触发提交。
`main.yml` 只负责变量映射和动态进度展示；具体某个活动里木头、石头按钮要显示哪些说明，放在 `activities/<id>.yml` 的 `collection-overrides`。

## 活动规则

- 管理员输入 `/sg open default` 打开默认活动 GUI 后，可点击 `S` 星星按钮开启默认收集活动；也可以输入 `/sg start` / `/sg star`。
- 玩家输入 `/sg open default` 打开指定活动 GUI；有正在进行的活动时，也可以点击全服通知或输入 `/sg join` 参与。
- 活动不再配置 `stages`。运行期间的进度提醒由 `config.yml` 的 `notifications.progress` 控制，广播内容在 `lang/zh_cn.yml` 的 `messages.activity-progress-periodic` 配置。
- 活动完成条件按每个 collection 独立判断：所有启用任务都达到自己的 `target` 后，才会触发 `contribution-reward`。
- `target-total` 是汇总展示目标和无 collection 时的兜底目标，默认建议等于所有 collection 的 `target` 之和；它不会允许玩家只交一种物品来替代另一个任务。
- `contribution-reward.min-contribution` 是最终奖池最低贡献门槛；低于门槛的玩家仍会上榜和记录贡献，但不参与最终金额分配。
- 生存服默认奖励只发 tagscoin：`tags admin coin give %player% %amount%`。默认大型活动奖池为 20 个 tagscoin，按玩家贡献比例拆分。
- `dynamic-target` 会在活动启动时按在线人数计算一次目标倍率，并同步放大总目标和单个收集目标；活动进行中不会继续跳动。`storage.type: database` 时会统计所有子服最近心跳内的在线人数，YAML 模式下只能使用本服在线人数。
- 活动结束会写入 `data/activity-history`，GUI 的历史菜单会读取这些记录；最终奖励 outbox 审计仍写入 `data/reward-history`。

## 活动轮换池

`config.yml` 中可以配置活动轮换池：

```yml
rotation:
  enabled: true
  auto-start: false
  interval-days: 7
  pool:
    - default
    - farming
    - mining
```

- `/sg rotate` 会按当前周期开启轮换池中的一个活动模板。
- `auto-start: true` 时，插件会在没有活动运行且达到间隔后自动开启本周活动。
- 轮换池只引用 `activities/<id>.yml`，不会把活动 ID 硬编码进代码。

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

常用子命令：

- `/sg open <活动ID>` 打开指定活动 GUI
- `/sg join` 参与当前活动
- `/sg top` 在聊天栏查看贡献排行
- `/sg history` 打开活动历史 GUI
- `/sg start <活动ID>` 开启指定活动
- `/sg rotate` 按轮换池开启本周期活动

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

内置默认启用模板是 `survival`，活动名是“生存物资集结”：

- 总目标数量：38000
- 木头任务：允许提交原木、木头、去皮变体、下界菌柄/菌核和竹块
- 石头任务：允许提交石头、圆石、深板岩、花岗岩、闪长岩、安山岩、方解石、凝灰岩等
- 铁套装收集：铁头盔、铁胸甲、铁护腿、铁靴子、铁剑、铁镐、铁斧、铁锹
- 金套装收集：金头盔、金胸甲、金护腿、金靴子、金剑、金镐、金斧、金锹
- 定时通知：默认每 300 秒广播一次当前进度，消息在 `lang/zh_cn.yml` 中配置
- 最终贡献奖池：20 个 tagscoin
- 最低奖励贡献：80
- 动态目标：按 20 名在线玩家为基准，倍率范围 1.0 到 3.0
