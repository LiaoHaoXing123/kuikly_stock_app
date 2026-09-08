# data-pipeline — 行情数据自动构建（方案A）

本项目让 App 每天自动拿到最新 A 股行情，而无需重装 APK。

## 链路

```
GitHub Actions（每天 07:30 UTC = 北京 15:30，周一~五）
        │  build_stock_db.py：AKShare 抓数 -> SQLite stock.db（九张表：6 个股 + 3 指数）
        │  只保留最近 30 天（K线/分时/指标/指数K线自动剪枝），并标注每张表的数据来源
        ▼
GitHub Release（滚动 tag: data-latest） 上传 stock.db + version.json
        │  稳定下载 URL：
        │  https://github.com/LiaoHaoXing123/kuikly_stock_app/releases/download/data-latest/stock.db
        ▼
App DataUpdateWorker（WorkManager）：
        · 启动兜底：OneTimeWorkRequest 立即比对 version.json，有更新就下载
        · 每日首刷：PeriodicWorkRequest（约 16:30，best-effort）
        ▼
StockDb.refreshFromFile()  关闭旧只读连接 -> 原子替换 filesDir/stock.db -> 重新打开
```

## 构建产物

- `stock.db`：App 读取的 SQLite 库。九张表见 `build_stock_db.py` 的 `DDL`（6 张个股 + 3 张指数：`index_info` / `index_realtime` / `index_daily_kline`）。
  每张表多一列 `source` 标注来源；另有一张 `data_source` 汇总表。
  来源标注：stock_info=交易所+东方财富，stock_realtime=东方财富，
  stock_daily_kline=腾讯，stock_minute=新浪，stock_order_book=东方财富，
  stock_indicator=本地计算，index_info/index_realtime/index_daily_kline=东方财富。
  注意指数代码与个股代码存在重叠（如 000001 既是上证指数又是平安银行），
  因此指数独立成表、用表名做命名空间，App 层禁止与 stock_* 表混查。
- `version.json`：`{ schema_version, updated_at, latest_trade_date, build,
  prune_days, kline_days_back, sources, db_bytes, counts }`。App 据此判断是否有新数据。

## 本地构建 / 自检

```bash
cd data-pipeline
pip install -r requirements.txt
python build_stock_db.py --dry-run   # 不拉网络，用样例自检 schema/剪枝
python build_stock_db.py             # 全量构建（需联网 + akshare）
```

可用环境变量调整：`KLINE_CODES` / `MINUTE_CODES` / `ORDERBOOK_CODES` / `INDEX_KLINE_CODES`
（关注列表，逗号分隔；指数默认 10 只宽基代表）、`KLINE_DAYS_BACK`（默认 60，用于算指标）、`PRUNE_DAYS`（默认 30）。

指数三表抓取失败只告警、不中断个股构建（软失败）；质量门对指数只做软告警、不拦截发布。

> 注：脚本内置 `push2→push2delay` 主机改写 + 强制直连。GitHub Actions 跑在境外，
> 东财/腾讯/新浪全局可达，改写后同样可用；本机因 TLS 被拦，靠该补丁才能拉数。

## 关于"一个月"

`PRUNE_DAYS=30`：`stock_daily_kline` / `stock_minute` / `stock_indicator` / `index_daily_kline` 只保留最近 30 个自然日；
`stock_realtime` / `stock_order_book` 每代码保留最新一条（快照性质）。这样库体稳定、不随日积月累膨胀。
