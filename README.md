# AutoShopBuyer · 自动商店采购

> 打开商店后自动搜索指定商品、批量购买，并把买到的东西直接存进 Refined Storage 网络。客户端模组，适配 **SDM Shop Rework** + **Refined Storage**。

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-informational)
![Forge](https://img.shields.io/badge/Forge-47.3.33-informational)
![Side](https://img.shields.io/badge/side-client-blue)

---

## 简介

`AutoShopBuyer` 解决的是「在商店里一格一格点购买」这种重复劳动：给它一个商品名和一个目标数量，它会循环执行

> 换出无线合成终端 → 打开商店 → 搜索商品 → 算出本轮能买多少 → 连续购买 → 等物品进背包 → 存入 RS → 下一轮

直到买够数量（或背包/网络塞满）为止。所有购买动作走的都是模组原生的购买数据包，和手动点击等价。

## 功能

- **按名称搜索商品**：支持模糊匹配，也可以写 `minecraft:quartz` 这种完整 ID（商品名带冒号时用 `count` 形式传参）
- **批量购买**：一轮最多填满 N 个空槽位（默认 36），单轮一次下满
- **自动回收**：买到的物品自动通过无线合成终端存入 Refined Storage 网络
- **目标数量**：`0` 表示无限买，直到手动停止
- **断点保护**：玩家移动超过阈值、玩家自己打开了其他界面、鱼竿/终端丢失、背包塞满，都会安全中止
- **GUI + 命令双入口**：`B` 键打开界面，或 `/autobuy` 系列命令

## 环境要求

- Minecraft **1.20.1** + Forge **47.3.33**（47.x）+ Java **17**
- **SDM Shop Rework**（`sdmshoprework`）：提供商店数据与购买数据包
- **Refined Storage** + **Refined Storage Addons**（`refinedstorageaddons`）：提供无线合成终端，用于自动存货
- 背包里需要有无线合成终端；商店需要通过终端/界面打开

> 这些模组不随本仓库分发。构建前请把对应 jar 放进 `libs/`（`libs/` 已被 git 忽略）。

## 安装

1. 装好 Forge 与上述依赖模组。
2. 把 `autoshopbuyer-1.0.0.jar` 放进 `mods/`。
3. 进游戏后按 `B` 能弹出界面即为加载成功。

## 使用

### 界面

按 `B` 打开采购界面，填入：

- **商品名**：商店里显示的名字，支持关键字
- **数量**：`0` = 一直买
- **间隔(tick)**：买完一批后等多久再开下一轮（默认 10）
- **GUI 停留(tick)**：每次打开商店界面后停留多久操作（默认 5）

点「开始」即开始采购，「停止」立即中止。

### 命令

```
/autobuy start <商品名>              # 无限量购买
/autobuy start count <数量> <商品名>  # 买到指定数量
/autobuy stop                        # 停止当前任务
/autobuy status                      # 查看进度（已买/已存/当前状态）
/autobuy gui                         # 打开界面
/autobuy interval <1-200>            # 设置轮次间隔
/autobuy guistay <1-200>             # 设置界面停留时间
/autobuy overload                    # 重新加载配置文件
```

## 配置

### Forge 配置（`config/autoshopbuyer-common.toml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `batchSize` | 36 | 每批最多填几个空槽（1–36） |
| `ticksBetweenBatches` | 1 | 两批之间等待刻数 |
| `waitItemsTicks` | 3 | 购买后等待物品进背包的刻数 |
| `moveTolerance` | 1.0 | 玩家移动超过该距离（格）即中止任务 |

### 文件配置（`config/AGen/autoshopbuyer.json`）

优先级高于 Forge 配置，用于快速调参：

```json
{
  "defaultIntervalTicks": 10,
  "guiStayInterval": 5,
  "ticksBetweenBatches": 1,
  "waitItemsTicks": 3
}
```

## 实现说明

状态机分三段，任何一段失败都会走恢复流程：

```
CHECK_TERMINAL → SWAP_TERMINAL → SEARCH_ITEM → CALCULATE → BUYING
      → WAIT_ITEMS → DEPOSITING → (下一轮 | COMPLETE)
                                  ↘ RECOVERY_DEPOSIT（背包已满但还有目标物品）
```

- 商店标签页与条目通过反射读取 `net.sixik.sdmshoprework.common.shop.ShopBase#CLIENT`，
  购买发送 `SendBuyShopEntryC2S` 数据包；
- 存档用无线合成终端打开 RS 合成网格，把目标物品塞进去（`RSNetworkHelper` 负责识别
  `refinedstorageaddons:wireless_crafting_grid` 与创造版）。

## 构建

```bash
# 先把 sdmshoprework / refinedstorage / refinedstorageaddons 的 jar 放进 libs/
./gradlew build
# 产物：build/libs/autoshopbuyer-1.0.0.jar
```

## 目录结构

```
src/main/java/com/agen/autoshopbuyer/
├── AutoShopBuyerMod.java     # 入口：配置注册、按键、tick 回调
├── ShopBuyerManager.java     # 任务生命周期 + /autobuy 命令
├── ShopBuyerTask.java        # 采购状态机（核心）
├── ShopBuyerScreen.java      # 采购界面（含商品列表）
├── RSNetworkHelper.java      # Refined Storage 终端识别
└── Config.java               # Forge 配置 + JSON 文件配置
```

## 注意

自动采购属于自动化行为，请确认服务器规则允许后再使用；价格与库存仍以服务端为准，买不起或售罄时会自动结束本轮。

## 许可

All Rights Reserved（保留所有权利）。
