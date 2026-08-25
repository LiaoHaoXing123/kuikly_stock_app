# 数据目录（方案 B：SQLite 打进 APK）

> **这里就是"单独的数据修改点"。** 更新数据只动这一个文件 / 跑一个命令。

## 文件

| 文件 | 作用 |
|---|---|
| `stock.db` | SQLite 数据库（6 张表全量）。**改数据就是改它**。 |
| `convert_sql_to_sqlite.py` | 从 `kuikly_stock_demo.sql` 重新生成 `stock.db`，并可一键复制到 `assets/` 供打包。 |

运行时 App 读的是 `shared/src/commonMain/assets/stock.db`（随 APK 打包）。本目录的 `stock.db` 是**源文件**，改完要同步过去。

## 更新数据（3 步）

```bash
cd kuikly_stock_app

# 1. 准备新的 SQL dump（默认路径在仓库外 sibling：../mock_data/kuikly_stock_demo.sql）
#    也可用 --sql 指定任意路径

# 2. 重新生成 stock.db 并同步到 assets（一条命令搞定）
python data/convert_sql_to_sqlite.py --deploy

# 3. 重新构建/安装 APK
```

不带 `--deploy` 只生成 `data/stock.db`，不动 assets。

## 数据规模（来自当前 dump）

- stock_info / stock_realtime：5212 只（全市场）
- stock_daily_kline / stock_indicator：4381 行，覆盖 11 只热门股
- stock_minute：21670 行，覆盖 11 只热门股
- stock_order_book：11 行，11 只热门股

> kline/minute/indicator/orderbook 只覆盖 11 只热门股（000001/000002/000063/000333/000338/600000/600036/600519/600900/300001/300750），是 dump 本身的限制，不是转换问题。要扩展需在抓取端补数据。
