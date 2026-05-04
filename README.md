# ServerGoal

ServerGoal 是一个基于 Kotlin 的 Minecraft 全服物品收集活动插件，兼容 Paper / Spigot / Folia。

## 特性

- 全服物品收集活动
- 多阶段目标解锁
- 阶段奖励与个人贡献奖励
- 箱子 GUI 查看状态、提交物品、领取奖励
- 聊天栏广播活动关键节点
- 默认本地 YAML 存储
- 可选 MySQL / MariaDB 数据库存储，支持跨服同步
- 所有 GUI、语言和活动模板都放在资源文件中

## 资源文件

- `src/main/resources/config.yml` 全局配置
- `src/main/resources/gui/main.yml` GUI 布局
- `src/main/resources/lang/zh_cn.yml` 语言文本
- `src/main/resources/activities/default.yml` 默认木头收集模板

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

内置默认模板是“木头收集”：

- 目标数量：10000
- 允许提交任意木头类材料
- 个人贡献奖池：30000 金币
