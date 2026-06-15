# MMMFlight 1.6.0

适用于 Purpur `1.21.10` 的飞行能量插件，支持 Velocity 多子服场景。

作者: `Xiaomenxin`

## 功能概览

- 玩家使用 `/mfly` 开关飞行，避免和 `CMI` 的 `/fly` 冲突。
- 支持给玩家充值飞行点数、直接设置飞行点数、清空飞行点数。
- 支持通过 LuckPerms 权限节点控制飞行能量上限。
- 支持限制只在指定服务器、指定世界中允许使用飞行。
- 支持 BossBar 显示当前总量、百分比、以及本次实际扣除值 `-%cost%`。
- 支持 ActionBar 单独开关。
- 支持自动回复飞行点数，默认关闭。
- 支持 PlaceholderAPI 跨服变量。
- 支持按 `默认 -> 服务器 -> 世界` 设置不同飞行能量消耗倍率。
- 玩家切服进入子服时会自动从数据库重新读取飞行点数。
- BossBar 的扣除值支持双色闪动显示。
- BossBar 扣费提示现在是“扣费后短暂高亮，再恢复常态颜色”的脉冲效果。

## 指令

玩家指令:

```text
/mfly
/mmmflight:mfly
```

作用:

- 开启或关闭插件管理的飞行

管理员指令:

```text
/flight balance <玩家>
/flight add <玩家> <数值>
/flight remove <玩家> <数值>
/flight set <玩家> <数值>
/flight clear <玩家>
/flight reload
```

作用:

- `balance` 查看玩家当前飞行点数
- `add` 给玩家增加飞行点数
- `remove` 减少玩家飞行点数
- `set` 直接设置玩家飞行点数
- `clear` 清空玩家飞行点数
- `reload` 重载插件配置和语言文件

## 飞行点数充能

玩家可以通过消耗指定物品给自己的飞行点数充能。默认配置中，每次充能增加玩家飞行点数上限的 `25%`，计算结果向上取整，但最终点数不会超过玩家当前飞行点数上限。

玩家指令:

```text
/flight recharge
/flight recharge <物品Key>
/flight recharge info <物品Key>
```

作用:

- `/flight recharge` 查看今日总充能次数和可用物品。
- `/flight recharge <物品Key>` 使用指定物品充能一次。
- `/flight recharge info <物品Key>` 查看该物品今日已用次数、下次需要数量、预计充能点数。

默认可用物品 Key:

```text
rotten_flesh
pumpkin
sugar_cane
```

默认限制:

- 每个玩家每天最多总充能 `12` 次。
- 每种物品默认每天最多充能 `5` 次。
- 每种物品和每日总次数都会在 `Asia/Shanghai` 北京时间自然日 0 点刷新。
- 每种物品的消耗量按次数翻倍，公式为 `base-cost * multiplier ^ 今日该物品已用次数`。

默认消耗示例:

```text
rotten_flesh: 32, 64, 128, 256, 512
pumpkin:      16, 32, 64, 128, 256
sugar_cane:   32, 64, 128, 256, 512
```

Invero 菜单联动时，建议每个按钮直接执行一次固定命令:

```text
flight recharge rotten_flesh
flight recharge pumpkin
flight recharge sugar_cane
```

如果菜单需要展示详情，可以使用:

```text
flight recharge info rotten_flesh
flight recharge info pumpkin
flight recharge info sugar_cane
```

对应配置位于 `config.yml`:

```yml
recharge:
  enabled: true
  timezone: Asia/Shanghai

  limits:
    daily-total: 12
    per-item-default: 5

  reward-default:
    mode: percent
    amount: 25

  cost-default:
    multiplier: 2.0

  items:
    rotten_flesh:
      material: ROTTEN_FLESH
      base-cost: 32

    pumpkin:
      material: PUMPKIN
      base-cost: 16

    sugar_cane:
      material: SUGAR_CANE
      base-cost: 32
```

单个物品可以覆盖默认每日次数、消耗倍率和充能方式:

```yml
recharge:
  items:
    cactus:
      material: CACTUS
      base-cost: 24
      daily-limit: 3
      cost-multiplier: 2.0
      reward:
        mode: fixed
        amount: 180
```

## 权限节点

基础权限:

- `mmmflight.use`
  允许玩家使用 `/mfly`
- `mmmflight.admin`
  允许使用 `/flight` 管理指令
- `mmmflight.bypass.consume`
  飞行时不消耗能量
- `mmmflight.bypass.server`
  无视服务器限制
- `mmmflight.bypass.world`
  无视世界限制

飞行上限权限:

- `mmmflight.limit.1000`
- `mmmflight.limit.2000`
- `mmmflight.limit.4000`
- `mmmflight.limit.8000`

插件会读取玩家拥有的上限节点，并使用其中最大的数值作为该玩家的最大飞行点数。

LuckPerms 示例:

```text
/lp user 玩家名 permission set mmmflight.limit.1000 true
/lp user 玩家名 permission set mmmflight.limit.2000 true
/lp group vip permission set mmmflight.limit.4000 true
/lp group svip permission set mmmflight.limit.8000 true
```

如果你不喜欢这四档，可以直接修改或删除 `config.yml` 里的 `limits.presets`，然后给玩家发放对应的新权限节点即可。
插件会自动把这些预设档位注册成权限节点，便于 LuckPerms 指令自动补全。

## PlaceholderAPI 变量

可用变量:

- `%mmmflight_points%`
  当前飞行点数
- `%mmmflight_max_points%`
  当前最大飞行点数
- `%mmmflight_remaining_percent%`
  当前剩余百分比
- `%mmmflight_server_id%`
  当前插件读取到的 `server-id`

这些变量可用于全服记分板、菜单、广播、跨服展示等场景。

## 倍率配置

配置位置:

```yml
consume-multiplier:
  default: 1.0
  servers:
    survival-1: 1.0
    survival-2: 1.0
  worlds:
    survival-1:
      world: 1.0
      world_nether: 1.5
      world_the_end: 2.0
```

优先级:

1. `consume-multiplier.worlds.<server-id>.<world>`
2. `consume-multiplier.servers.<server-id>`
3. `consume-multiplier.default`

示例说明:

- 如果玩家在 `survival-1` 的 `world_nether`，倍率用 `1.5`
- 如果玩家在 `survival-1` 但当前世界没有单独配置，倍率用 `servers.survival-1`
- 如果服务器和世界都没配置，倍率用 `default`

实际扣除公式:

```text
实际扣除 = base-cost-per-tick × 当前倍率
```

BossBar 的 `-%cost%` 显示的是已经乘完倍率后的实际扣除值。

## 跨服同步说明

- 使用 `mysql` 并且所有子服连接同一张表时，玩家进入子服会自动重新读取最新飞行点数。
- 不需要再手动 `/flight reload` 才看到同步结果。
- 如果玩家始终停留在同一个子服不切服，那么本服仍然会优先使用内存缓存，这是为了性能稳定。

## BossBar 与 ActionBar

BossBar 支持变量:

- `%points%`
- `%max%`
- `%cost%`
- `%cost_display%`
- `%percent%`

默认行为:

- BossBar 只在飞行时显示
- ActionBar 默认开启
- 自动回能默认关闭
- 扣除值默认使用红色常态显示，扣费后短时间在红色与亮黄色之间跳动

## 存储说明

- `yaml` 模式适合单服或测试服
- `mysql` 模式适合多子服共享飞行点数
- 插件会自动创建 MySQL 数据表

## 兼容性

- 运行端: Purpur `1.21.10`
- 权限插件: LuckPerms
- 变量插件: PlaceholderAPI
- 系统环境: 可运行于 Windows 和 Ubuntu，只要 Java 与服务端版本满足要求即可

## 安装

将构建产物放入每个 Purpur 子服的 `plugins` 目录:

```text
target/mmm-flight-1.6.0.jar
```

首次启动后会自动生成:

- `config.yml`
- `lang/zh_CN.yml`
