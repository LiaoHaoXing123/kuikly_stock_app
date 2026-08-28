#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
build_stock_db.py —— 用 AKShare 直接把行情数据构建成 App 用的 SQLite stock.db（方案A）

与 backend/scripts/sql_to_sqlite.py 的关系：
  旧链路 = AKShare -> MySQL -> mysqldump -> .sql -> sql_to_sqlite.py -> stock.db（依赖本地 MySQL，不适合 CI）
  本脚本 = AKShare -> SQLite stock.db（纯 Python，无 MySQL 依赖，可直接在 GitHub Actions 跑）

特性：
  1. 六张表一次构建（与 App StockDb.kt 读取的 schema 完全一致，额外增加 source 列）
  2. 数据来源标注：每表加 source 列 + 新增 data_source 汇总表（App 可读"数据来源"）
  3. 自动剪枝：K线/分时/指标只保留最近 PRUNE_DAYS（默认 30）天，库体稳定不膨胀
  4. 幂等：全部 INSERT OR REPLACE（按表唯一键去重），重复运行安全
  5. 产出 stock.db + version.json（App 借此判断是否要下载更新）

网络说明（沿用 akshare_fetch_all.py 的实测结论）：
  本机 push2 系列主机对 Python requests 的 TLS 连接被重置，仅 push2delay 放行；
  脚本把 push2 系主机改写为 push2delay 再调标准 AKShare 接口；日K走腾讯、分时走新浪。
  GitHub Actions 跑在境外，同样改写为 push2delay 也全局可达，因此保持一致。

用法：
  python build_stock_db.py                       # 全量构建（默认关注列表）
  python build_stock_db.py --dry-run             # 只建库+插样例，不拉网络（自检 schema/剪枝）
  相关环境变量：STOCK_DB / KLINE_CODES / MINUTE_CODES / ORDERBOOK_CODES / KLINE_DAYS_BACK / PRUNE_DAYS
"""

import os
import sys
import time
import json
import argparse
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

# Windows 控制台默认 GBK，打印 ✓ 等 Unicode 会抛 UnicodeEncodeError；强制 UTF-8 输出
if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if sys.stderr and hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ---------------------------------------------------------------------------
# 网络补丁：1) 强制直连（无视系统/注册表代理） 2) push2 -> push2delay 主机改写
# ---------------------------------------------------------------------------
_USE_PROXY = os.environ.get("USE_PROXY")
_PROXY_VARS = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
               "ALL_PROXY", "all_proxy")
_HOST_REWRITE = {
    "push2.eastmoney.com": "push2delay.eastmoney.com",
    "82.push2.eastmoney.com": "push2delay.eastmoney.com",
    "70.push2.eastmoney.com": "push2delay.eastmoney.com",
}


def _install_network_patch():
    import requests
    _orig_init = requests.Session.__init__

    def _patched_init(self, *args, **kwargs):
        _orig_init(self, *args, **kwargs)
        self.trust_env = bool(_USE_PROXY)

    requests.Session.__init__ = _patched_init

    _orig_request = requests.Session.request

    def _patched_request(self, method, url, *args, **kwargs):
        if not _USE_PROXY:
            for old, new in _HOST_REWRITE.items():
                if old in url:
                    url = url.replace(old, new)
                    break
        return _orig_request(self, method, url, *args, **kwargs)

    requests.Session.request = _patched_request


if _USE_PROXY:
    for _v in _PROXY_VARS:
        os.environ.pop(_v, None)
    os.environ["HTTP_PROXY"] = _USE_PROXY
    os.environ["HTTPS_PROXY"] = _USE_PROXY
else:
    for _v in _PROXY_VARS:
        os.environ.pop(_v, None)
    os.environ["NO_PROXY"] = "*"
    os.environ["no_proxy"] = "*"

_install_network_patch()

# 需要网络时才 import akshare（--dry-run 可离线自检，避免强制装 akshare）
try:
    import akshare as ak  # noqa: E402
    _AKSHARE_OK = True
except Exception as _e:  # pragma: no cover
    _AKSHARE_OK = False
    _AKSHARE_ERR = str(_e)

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
OUT_DB = Path(os.environ.get("STOCK_DB", str(BASE_DIR / "stock.db")))
OUT_VERSION = Path(os.environ.get("VERSION_JSON", str(BASE_DIR / "version.json")))
OUT_SQL = Path(os.environ.get("SQL_FILE", str(BASE_DIR / "stock.sql")))

REQUEST_INTERVAL = float(os.environ.get("REQUEST_INTERVAL", "0.4"))
KLINE_DAYS_BACK = int(os.environ.get("KLINE_DAYS_BACK", "60"))   # 抓回多少天用于算指标（需 > 20）
PRUNE_DAYS = int(os.environ.get("PRUNE_DAYS", "30"))             # 只保留最近 N 天（一个月）

# 数据来源标注
SRC = {
    "stock_info": "交易所+东方财富",
    "stock_realtime": "东方财富",
    "stock_daily_kline": "腾讯",
    "stock_minute": "新浪",
    "stock_order_book": "东方财富",
    "stock_indicator": "本地计算",
}
SRC_NOTE = {
    "stock_info": "基础信息：深交所/上交所代码简称，行业由东方财富行业板块补齐",
    "stock_realtime": "沪深A股实时行情快照（东方财富，push2delay 通道）",
    "stock_daily_kline": "日K线，前复权 qfq（腾讯接口）",
    "stock_minute": "1分钟分时线（新浪接口）",
    "stock_order_book": "五档盘口（东方财富，push2delay 通道）",
    "stock_indicator": "MA/MACD/RSI6/KDJ 由日K本地计算",
}

# 关注列表（K线/分时/盘口需要逐只拉取；realtime 是全市场快照，无需列表）
DEFAULT_HOT = [
    "000001", "000002", "000063", "000333", "000338",
    "600000", "600036", "600519", "600900",
    "300001", "300750",
]
DEFAULT_KLINE = [
    "000001", "000002", "000063", "000333", "000338", "000651", "000725",
    "000858", "000963",
    "600000", "600028", "600030", "600031", "600036", "600048", "600050",
    "600276", "600309", "600438", "600460", "600519", "600585", "600690",
    "600887", "600900", "600941", "601012", "601088", "601166", "601288",
    "601318", "601398", "601601", "601628", "601668", "601728", "601766",
    "601857", "601888", "601899", "601939", "601988",
    "300001", "300750", "300760", "002415", "002475", "002594", "002230",
    "688981", "688012", "688599", "600886",
]


def code_list(env: str, default) -> list:
    raw = os.environ.get(env)
    if raw:
        return [s.strip() for s in raw.split(",") if s.strip()]
    return list(default)


# ---------------------------------------------------------------------------
# SQLite schema（对齐 App StockDb.kt 读取的字段；额外增加 source 列 + data_source 表）
# ---------------------------------------------------------------------------
DDL = [
    """CREATE TABLE IF NOT EXISTS stock_info (
        code TEXT NOT NULL PRIMARY KEY,
        name TEXT NOT NULL DEFAULT '',
        industry TEXT,
        plate TEXT,
        list_date TEXT,
        source TEXT
    )""",
    """CREATE TABLE IF NOT EXISTS stock_realtime (
        code TEXT NOT NULL PRIMARY KEY,
        price REAL NOT NULL DEFAULT 0,
        change REAL NOT NULL DEFAULT 0,
        change_percent REAL NOT NULL DEFAULT 0,
        open REAL NOT NULL DEFAULT 0,
        pre_close REAL NOT NULL DEFAULT 0,
        high REAL NOT NULL DEFAULT 0,
        low REAL NOT NULL DEFAULT 0,
        volume INTEGER NOT NULL DEFAULT 0,
        amount REAL NOT NULL DEFAULT 0,
        turnover_rate REAL NOT NULL DEFAULT 0,
        volume_ratio REAL NOT NULL DEFAULT 0,
        limit_up REAL NOT NULL DEFAULT 0,
        limit_down REAL NOT NULL DEFAULT 0,
        pe_ttm REAL,
        pb REAL,
        total_market_cap REAL,
        circulate_market_cap REAL,
        update_time TEXT NOT NULL,
        source TEXT
    )""",
    """CREATE TABLE IF NOT EXISTS stock_daily_kline (
        code TEXT NOT NULL,
        trade_date TEXT NOT NULL,
        open REAL NOT NULL DEFAULT 0,
        close REAL NOT NULL DEFAULT 0,
        high REAL NOT NULL DEFAULT 0,
        low REAL NOT NULL DEFAULT 0,
        volume INTEGER NOT NULL DEFAULT 0,
        amount REAL NOT NULL DEFAULT 0,
        source TEXT,
        PRIMARY KEY (code, trade_date)
    )""",
    """CREATE TABLE IF NOT EXISTS stock_minute (
        code TEXT NOT NULL,
        trade_date TEXT NOT NULL,
        time TEXT NOT NULL,
        price REAL NOT NULL DEFAULT 0,
        avg_price REAL,
        volume INTEGER NOT NULL DEFAULT 0,
        source TEXT,
        PRIMARY KEY (code, trade_date, time)
    )""",
    """CREATE TABLE IF NOT EXISTS stock_order_book (
        code TEXT NOT NULL PRIMARY KEY,
        update_time TEXT NOT NULL,
        bid1_price REAL, bid1_vol INTEGER,
        bid2_price REAL, bid2_vol INTEGER,
        bid3_price REAL, bid3_vol INTEGER,
        bid4_price REAL, bid4_vol INTEGER,
        bid5_price REAL, bid5_vol INTEGER,
        ask1_price REAL, ask1_vol INTEGER,
        ask2_price REAL, ask2_vol INTEGER,
        ask3_price REAL, ask3_vol INTEGER,
        ask4_price REAL, ask4_vol INTEGER,
        ask5_price REAL, ask5_vol INTEGER,
        commission_ratio REAL,
        source TEXT
    )""",
    """CREATE TABLE IF NOT EXISTS stock_indicator (
        code TEXT NOT NULL,
        trade_date TEXT NOT NULL,
        ma5 REAL, ma10 REAL, ma20 REAL,
        dif REAL, dea REAL, macd REAL,
        rsi6 REAL, kdj_k REAL, kdj_d REAL, kdj_j REAL,
        source TEXT,
        PRIMARY KEY (code, trade_date)
    )""",
    """CREATE TABLE IF NOT EXISTS data_source (
        table_name TEXT NOT NULL PRIMARY KEY,
        source TEXT NOT NULL,
        note TEXT,
        fetched_at TEXT NOT NULL
    )""",
]


def ensure_schema(conn: sqlite3.Connection):
    cur = conn.cursor()
    for ddl in DDL:
        cur.execute(ddl)
    conn.commit()


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
def tx_symbol(code):
    return ("sh" if str(code).startswith(("6", "9", "5")) else "sz") + str(code)


def _num(v, cast=float, default=None):
    try:
        if v is None or (isinstance(v, float) and pd.isna(v)):
            return default
        if pd.isna(v):
            return default
        return cast(v)
    except Exception:
        return default


def _nn(v):
    try:
        return None if v is None or (isinstance(v, float) and pd.isna(v)) else float(v)
    except Exception:
        return None


def retry(fn, times=3, wait=1.5, label=""):
    last = None
    for i in range(times):
        try:
            return fn()
        except Exception as e:
            last = e
            print(f"    [retry {i + 1}/{times}] {label}: {type(e).__name__}: {str(e)[:80]}")
            time.sleep(wait)
    raise last


# ---------------------------------------------------------------------------
# 表 1：stock_info
# ---------------------------------------------------------------------------
def fetch_stock_info():
    """股票基础信息。
    说明：code+name 主取东财全市场快照（push2delay，全球可达），覆盖沪深/创业板/科创板/北交所，
    避免依赖上交所 query.sse.com.cn（其境外常不可达，GitHub Actions runner 会连不上导致构建失败）。
    行业取东财板块（可达）；上市日期尽力而为（深交所可达，上交所境外常失败则留空）。
    """
    # 1) 主源：东财全市场快照 -> code + name（全市场，可达）
    try:
        spot = ak.stock_zh_a_spot_em()
        df = pd.DataFrame({
            "code": spot["代码"].astype(str).str.zfill(6),
            "name": spot["名称"].astype(str),
        })
        print(f"  [✓] 全市场快照取 code+name  {len(df)} 只")
    except Exception as e:
        print(f"  [警告] 全市场快照失败({type(e).__name__}:{str(e)[:60]})，回退深交所列表")
        df_sz = ak.stock_info_sz_name_code(symbol="A股列表")
        df = pd.DataFrame({
            "code": df_sz["A股代码"].astype(str).str.zfill(6),
            "name": df_sz["A股简称"].astype(str),
        })

    def plate_of(code):
        c = str(code)
        if c.startswith("30"):
            return "创业板"
        if c.startswith("68"):
            return "科创板"
        if c.startswith(("4", "8", "92")):
            return "北交所"
        if c.startswith(("6", "9")):
            return "沪市"
        return "深市"

    df["plate"] = df["code"].map(plate_of)
    df["industry"] = None
    df["list_date"] = None

    # 2) 上市日期尽力而为：深交所接口（可达）；上交所 query.sse.com.cn 境外常不可达 -> 留空（不崩溃）
    try:
        df_sz = ak.stock_info_sz_name_code(symbol="A股列表")
        ld = {}
        for _, r in df_sz.iterrows():
            c = str(r["A股代码"]).zfill(6)
            v = r.get("A股上市日期")
            if pd.notna(v):
                ld[c] = str(v)
        df["list_date"] = df["code"].map(ld)
        print(f"  [✓] 深交所上市日期覆盖 {df['list_date'].notna().sum()} 只")
    except Exception as e:
        print(f"  [警告] 深交所上市日期获取失败: {type(e).__name__}: {str(e)[:60]}")

    try:
        total_sh = 0
        for symbol, plate in (("主板A股", "沪市"), ("科创板", "科创板")):
            df_sh = ak.stock_info_sh_name_code(symbol=symbol)
            ld = {}
            for _, r in df_sh.iterrows():
                c = str(r["证券代码"]).zfill(6)
                v = r.get("上市日期")
                if pd.notna(v):
                    ld[c] = str(v)
            df["list_date"] = df["code"].map(ld).fillna(df["list_date"])
            total_sh += len(df_sh)
        print(f"  [✓] 上交所上市日期覆盖 {total_sh} 只")
    except Exception as e:
        print(f"  [警告] 上交所上市日期获取失败(境外可能不可达, 留空): {type(e).__name__}: {str(e)[:60]}")

    # 3) 行业补齐（东财板块，可达；失败跳过）
    try:
        print("  [..] 拉取东财行业板块补齐行业字段...")
        ind_map = {}
        boards = ak.stock_board_industry_name_em()
        names = boards["板块名称"].tolist()
        for i, bname in enumerate(names, 1):
            try:
                cons = ak.stock_board_industry_cons_em(symbol=bname)
                for c in cons["代码"].astype(str):
                    ind_map[c] = bname
            except Exception:
                pass
            if i % 20 == 0:
                print(f"      板块进度 {i}/{len(names)}")
            time.sleep(0.15)
        if ind_map:
            df["industry"] = df["code"].map(ind_map)
            print(f"  [✓] 行业覆盖 {df['industry'].notna().sum()} 只")
    except Exception as e:
        print(f"  [警告] 行业板块补齐失败（跳过）: {type(e).__name__}: {str(e)[:60]}")

    df["code"] = df["code"].astype(str)
    df = df.dropna(subset=["code"]).drop_duplicates(subset=["code"], keep="first")

    src = SRC["stock_info"]
    rows = []
    for _, r in df.iterrows():
        rows.append((
            r["code"], str(r["name"]) if pd.notna(r["name"]) else "",
            str(r["industry"]) if pd.notna(r["industry"]) else "",
            str(r["plate"]) if pd.notna(r["plate"]) else "",
            str(r["list_date"]) if pd.notna(r["list_date"]) else None,
            src,
        ))
    return rows


def write_stock_info(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_info (code, name, industry, plate, list_date, source)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(code) DO UPDATE SET
            name=excluded.name, industry=excluded.industry,
            plate=excluded.plate, list_date=excluded.list_date, source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------
# 表 2：stock_realtime（东方财富全市场快照，push2delay）
# ---------------------------------------------------------------------------
def limit_ratio(code, name):
    if str(code).startswith(("30", "68")):
        return 0.20
    if str(code).startswith(("4", "8", "92")):
        return 0.30
    if "ST" in str(name).upper():
        return 0.05
    return 0.10


def fetch_realtime():
    df = ak.stock_zh_a_spot_em()
    print(f"  [✓] spot_em 返回 {len(df)} 只")
    col = {"代码": "code", "名称": "name", "最新价": "price", "涨跌幅": "change_percent",
           "涨跌额": "change", "今开": "open", "昨收": "pre_close", "最高": "high",
           "最低": "low", "成交量": "volume", "成交额": "amount", "换手率": "turnover_rate",
           "量比": "volume_ratio", "市盈率-动态": "pe_ttm", "市净率": "pb",
           "总市值": "total_market_cap", "流通市值": "circulate_market_cap"}
    df = df.rename(columns={k: v for k, v in col.items() if k in df.columns})
    for c in ["price", "change", "change_percent", "open", "pre_close", "high", "low",
              "volume", "amount", "turnover_rate", "volume_ratio", "pe_ttm", "pb",
              "total_market_cap", "circulate_market_cap"]:
        if c not in df.columns:
            df[c] = None
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df["volume"] = df["volume"].fillna(0) * 100  # 手 -> 股
    df["limit_up"] = df.apply(lambda r: round(float(r["pre_close"]) * (1 + limit_ratio(r["code"], r.get("name", ""))), 2), axis=1)
    df["limit_down"] = df.apply(lambda r: round(float(r["pre_close"]) * (1 - limit_ratio(r["code"], r.get("name", ""))), 2), axis=1)
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    src = SRC["stock_realtime"]

    rows = []
    for _, r in df.iterrows():
        code = str(r["code"]).zfill(6)
        if len(code) != 6:
            continue
        rows.append((
            code, _num(r["price"], float, 0.0), _num(r["change"], float, 0.0),
            _num(r["change_percent"], float, 0.0), _num(r["open"], float, 0.0),
            _num(r["pre_close"], float, 0.0), _num(r["high"], float, 0.0),
            _num(r["low"], float, 0.0), int(_num(r["volume"], float, 0.0)),
            _num(r["amount"], float, 0.0), _num(r["turnover_rate"], float, 0.0),
            _num(r["volume_ratio"], float, 0.0), _num(r["limit_up"], float, 0.0),
            _num(r["limit_down"], float, 0.0), _num(r["pe_ttm"]), _num(r["pb"]),
            _num(r["total_market_cap"]), _num(r["circulate_market_cap"]), ts, src,
        ))
    return rows


def write_realtime(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_realtime (
            code, price, change, change_percent, open, pre_close, high, low,
            volume, amount, turnover_rate, volume_ratio, limit_up, limit_down,
            pe_ttm, pb, total_market_cap, circulate_market_cap, update_time, source)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(code) DO UPDATE SET
            price=excluded.price, change=excluded.change, change_percent=excluded.change_percent,
            open=excluded.open, pre_close=excluded.pre_close, high=excluded.high, low=excluded.low,
            volume=excluded.volume, amount=excluded.amount, turnover_rate=excluded.turnover_rate,
            volume_ratio=excluded.volume_ratio, limit_up=excluded.limit_up, limit_down=excluded.limit_down,
            pe_ttm=excluded.pe_ttm, pb=excluded.pb, total_market_cap=excluded.total_market_cap,
            circulate_market_cap=excluded.circulate_market_cap, update_time=excluded.update_time,
            source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------
# 表 3：stock_daily_kline（腾讯日K，qfq）
# ---------------------------------------------------------------------------
def fetch_kline(code, start_date, end_date):
    df = retry(
        lambda: ak.stock_zh_a_hist_tx(symbol=tx_symbol(code), start_date=start_date,
                                      end_date=end_date, adjust="qfq"),
        times=3, wait=1.5, label=f"kline {code}")
    if df is None or df.empty:
        return None
    df = df.rename(columns={"date": "trade_date"})
    return df


def kline_rows(code, df):
    src = SRC["stock_daily_kline"]
    rows = []
    for _, r in df.iterrows():
        rows.append((
            code, str(r["trade_date"]),
            float(r["open"]) if pd.notna(r["open"]) else 0.0,
            float(r["close"]) if pd.notna(r["close"]) else 0.0,
            float(r["high"]) if pd.notna(r["high"]) else 0.0,
            float(r["low"]) if pd.notna(r["low"]) else 0.0,
            int(float(r["volume"])) if pd.notna(r["volume"]) else 0,
            float(r["amount"]) if pd.notna(r["amount"]) else 0.0,
            src,
        ))
    # sz000 开头（深市主板）AKShare 返回的仍是"手"，其余已是"股"；统一转成股
    is_sz000 = tx_symbol(code).startswith("sz000")
    if is_sz000:
        rows = [(c, d, o, cl, h, l, int(v) * 100, a, s)
                for (c, d, o, cl, h, l, v, a, s) in rows]
    return rows


def write_kline(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_daily_kline (code, trade_date, open, close, high, low, volume, amount, source)
        VALUES (?,?,?,?,?,?,?,?,?)
        ON CONFLICT(code, trade_date) DO UPDATE SET
            open=excluded.open, close=excluded.close, high=excluded.high, low=excluded.low,
            volume=excluded.volume, amount=excluded.amount, source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------
# 表 4：stock_minute（新浪1分钟线）
# ---------------------------------------------------------------------------
def fetch_minute(code):
    df = retry(
        lambda: ak.stock_zh_a_minute(symbol=tx_symbol(code), period="1"),
        times=3, wait=1.5, label=f"minute {code}")
    if df is None or df.empty:
        return None
    df["dt"] = pd.to_datetime(df["day"])
    df["trade_date"] = df["dt"].dt.strftime("%Y-%m-%d")
    df["time"] = df["dt"].dt.strftime("%H:%M")
    df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0)
    df["amount"] = pd.to_numeric(df["amount"], errors="coerce").fillna(0.0)
    df["close"] = pd.to_numeric(df["close"], errors="coerce")
    g = df.groupby("trade_date")
    df["cum_amount"] = g["amount"].cumsum()
    df["cum_volume"] = g["volume"].cumsum()
    df["avg_price"] = (df["cum_amount"] / df["cum_volume"].replace(0, np.nan)).round(3)
    return df


def minute_rows(code, df):
    src = SRC["stock_minute"]
    rows = []
    for _, r in df.iterrows():
        if pd.isna(r["close"]):
            continue
        rows.append((code, r["trade_date"], r["time"],
                     float(r["close"]),
                     float(r["avg_price"]) if pd.notna(r["avg_price"]) else None,
                     int(r["volume"]), src))
    return rows


def write_minute(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_minute (code, trade_date, time, price, avg_price, volume, source)
        VALUES (?,?,?,?,?,?,?)
        ON CONFLICT(code, trade_date, time) DO UPDATE SET
            price=excluded.price, avg_price=excluded.avg_price,
            volume=excluded.volume, source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------
# 表 5：stock_order_book（东财五档盘口，push2delay）
# ---------------------------------------------------------------------------
def fetch_order_book(code):
    df = retry(lambda: ak.stock_bid_ask_em(symbol=code), times=3, wait=1.5, label=f"orderbook {code}")
    if df is None or df.empty:
        return None
    return dict(zip(df["item"], df["value"]))


def orderbook_row(code, kv):
    def g(key):
        v = kv.get(key)
        try:
            if v is None or v == "" or (isinstance(v, float) and pd.isna(v)):
                return None
            return float(v)
        except Exception:
            return None

    def hand(key):  # AKShare 已换成股，表字段要求手
        v = g(key)
        return None if v is None else int(round(v / 100))

    bid = [hand(f"buy_{k}_vol") for k in range(1, 6)]
    ask = [hand(f"sell_{k}_vol") for k in range(1, 6)]
    bv = sum(v for v in bid if v)
    av = sum(v for v in ask if v)
    commission = None
    if bv + av > 0:
        commission = round((bv - av) / (bv + av) * 100, 3)
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return (
        code, ts,
        g("buy_1"), bid[0], g("buy_2"), bid[1], g("buy_3"), bid[2],
        g("buy_4"), bid[3], g("buy_5"), bid[4],
        g("sell_1"), ask[0], g("sell_2"), ask[1], g("sell_3"), ask[2],
        g("sell_4"), ask[3], g("sell_5"), ask[4],
        commission, SRC["stock_order_book"],
    )


def write_order_book(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_order_book (
            code, update_time,
            bid1_price, bid1_vol, bid2_price, bid2_vol, bid3_price, bid3_vol,
            bid4_price, bid4_vol, bid5_price, bid5_vol,
            ask1_price, ask1_vol, ask2_price, ask2_vol, ask3_price, ask3_vol,
            ask4_price, ask4_vol, ask5_price, ask5_vol, commission_ratio, source)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(code) DO UPDATE SET
            update_time=excluded.update_time,
            bid1_price=excluded.bid1_price, bid1_vol=excluded.bid1_vol,
            bid2_price=excluded.bid2_price, bid2_vol=excluded.bid2_vol,
            bid3_price=excluded.bid3_price, bid3_vol=excluded.bid3_vol,
            bid4_price=excluded.bid4_price, bid4_vol=excluded.bid4_vol,
            bid5_price=excluded.bid5_price, bid5_vol=excluded.bid5_vol,
            ask1_price=excluded.ask1_price, ask1_vol=excluded.ask1_vol,
            ask2_price=excluded.ask2_price, ask2_vol=excluded.ask2_vol,
            ask3_price=excluded.ask3_price, ask3_vol=excluded.ask3_vol,
            ask4_price=excluded.ask4_price, ask4_vol=excluded.ask4_vol,
            ask5_price=excluded.ask5_price, ask5_vol=excluded.ask5_vol,
            commission_ratio=excluded.commission_ratio, source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------
# 表 6：stock_indicator（本地计算）
# ---------------------------------------------------------------------------
def compute_indicators(df):
    df = df.sort_values("trade_date").copy()
    close = df["close"].astype(float)
    high = df["high"].astype(float)
    low = df["low"].astype(float)

    df["ma5"] = close.rolling(5).mean()
    df["ma10"] = close.rolling(10).mean()
    df["ma20"] = close.rolling(20).mean()

    ema12 = close.ewm(span=12, adjust=False).mean()
    ema26 = close.ewm(span=26, adjust=False).mean()
    dif = ema12 - ema26
    dea = dif.ewm(span=9, adjust=False).mean()
    df["dif"], df["dea"] = dif, dea
    df["macd"] = (dif - dea) * 2

    delta = close.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)
    rs = (gain.ewm(alpha=1 / 6, adjust=False).mean()
          / loss.ewm(alpha=1 / 6, adjust=False).mean().replace(0, np.nan))
    df["rsi6"] = 100 - 100 / (1 + rs)

    low9 = low.rolling(9).min()
    high9 = high.rolling(9).max()
    rsv = ((close - low9) / (high9 - low9) * 100).fillna(50.0)
    k_vals, d_vals = [50.0] * len(df), [50.0] * len(df)
    for i in range(1, len(df)):
        k_vals[i] = 2 / 3 * k_vals[i - 1] + 1 / 3 * rsv.iloc[i]
        d_vals[i] = 2 / 3 * d_vals[i - 1] + 1 / 3 * k_vals[i]
    df["kdj_k"] = k_vals
    df["kdj_d"] = d_vals
    df["kdj_j"] = [3 * k - 2 * d for k, d in zip(k_vals, d_vals)]
    return df


def indicator_rows(code, df):
    src = SRC["stock_indicator"]
    rows = []
    for _, r in df.iterrows():
        rows.append((code, str(r["trade_date"]),
                     _nn(r["ma5"]), _nn(r["ma10"]), _nn(r["ma20"]),
                     _nn(r["dif"]), _nn(r["dea"]), _nn(r["macd"]),
                     _nn(r["rsi6"]), _nn(r["kdj_k"]), _nn(r["kdj_d"]), _nn(r["kdj_j"]),
                     src))
    return rows


def write_indicator(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_indicator (
            code, trade_date, ma5, ma10, ma20, dif, dea, macd, rsi6,
            kdj_k, kdj_d, kdj_j, source)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(code, trade_date) DO UPDATE SET
            ma5=excluded.ma5, ma10=excluded.ma10, ma20=excluded.ma20,
            dif=excluded.dif, dea=excluded.dea, macd=excluded.macd,
            rsi6=excluded.rsi6, kdj_k=excluded.kdj_k, kdj_d=excluded.kdj_d,
            kdj_j=excluded.kdj_j, source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


# ---------------------------------------------------------------------------
# data_source 汇总表 + 剪枝
# ---------------------------------------------------------------------------
def _tables():
    return ["stock_info", "stock_realtime", "stock_daily_kline",
            "stock_minute", "stock_order_book", "stock_indicator"]


def write_data_source(conn):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    cur = conn.cursor()
    rows = [(t, SRC[t], SRC_NOTE.get(t), ts) for t in _tables()]
    cur.executemany("""
        INSERT INTO data_source (table_name, source, note, fetched_at)
        VALUES (?,?,?,?)
        ON CONFLICT(table_name) DO UPDATE SET
            source=excluded.source, note=excluded.note, fetched_at=excluded.fetched_at
    """, rows)
    conn.commit()


def prune(conn):
    """只保留最近 PRUNE_DAYS 天；realtime/orderbook 保留最新一条。"""
    cutoff = (datetime.now() - timedelta(days=PRUNE_DAYS)).strftime("%Y-%m-%d")
    cur = conn.cursor()
    total = 0
    for t in ["stock_daily_kline", "stock_minute", "stock_indicator"]:
        cur.execute(f"DELETE FROM {t} WHERE trade_date < ?", (cutoff,))
        total += cur.rowcount
    # 盘口/实时各保留一条最新（按 code 唯一化后天然只有一条，这里防御性清理）
    cur.execute("""DELETE FROM stock_order_book WHERE rowid NOT IN (
        SELECT MAX(rowid) FROM stock_order_book GROUP BY code)""")
    total += cur.rowcount
    conn.commit()
    return total


# ---------------------------------------------------------------------------
# 验证
# ---------------------------------------------------------------------------
def verify(conn):
    cur = conn.cursor()
    print("\n[验证] 各表行数：")
    for t in _tables() + ["data_source"]:
        cur.execute(f"SELECT COUNT(*) FROM {t}")
        print(f"  {t:20s} {cur.fetchone()[0]:>8} 行")
    cur.execute("SELECT table_name, source FROM data_source ORDER BY table_name")
    print("  数据来源: " + " | ".join(f"{r[0]}={r[1]}" for r in cur.fetchall()))
    cur.execute("SELECT MAX(trade_date) FROM stock_daily_kline")
    latest = cur.fetchone()[0]
    print(f"  最新K线交易日: {latest}")
    return latest


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def build(dry_run=False):
    if OUT_DB.exists():
        OUT_DB.unlink()
    conn = sqlite3.connect(OUT_DB)
    try:
        ensure_schema(conn)

        if dry_run:
            print("== 自检模式（不拉网络）：写入样例数据验证 schema/剪枝 ==")
            write_stock_info(conn, [("000001", "平安银行", "银行Ⅱ", "深市", "1991-04-03", SRC["stock_info"])])
            write_realtime(conn, [("000001", 10.0, 0.10, 1.0, 9.9, 9.9, 10.2, 9.8,
                                   100, 1000000, 1.5, 1.1, 10.89, 8.91, 5.0, 0.8,
                                   100000000, 80000000, "2026-08-28 15:30:00", SRC["stock_realtime"])])
            dates = []
            d = datetime.now() - timedelta(days=90)
            while len(dates) < 60:
                if d.weekday() < 5:
                    dates.append(d.strftime("%Y-%m-%d"))
                d += timedelta(days=1)
            kdf = pd.DataFrame({
                "code": "000001",
                "trade_date": dates,
                "open": [10 + i * 0.01 for i in range(len(dates))],
                "close": [10 + i * 0.02 for i in range(len(dates))],
                "high": [10.5] * len(dates), "low": [9.5] * len(dates),
                "volume": [100000] * len(dates), "amount": [1e6] * len(dates),
            })
            write_kline(conn, kline_rows("000001", kdf))
            write_kline(conn, [("000001", "2020-01-01", 1, 1, 1, 1, 100, 1, SRC["stock_daily_kline"])])
            pruned = prune(conn)
            print(f"    剪枝删除 {pruned} 行")
            cur = conn.cursor()
            cur.execute("SELECT COUNT(*) FROM stock_daily_kline WHERE trade_date < ?",
                        ((datetime.now() - timedelta(days=PRUNE_DAYS)).strftime("%Y-%m-%d"),))
            print(f"    剪枝后仍存在的过期行: {cur.fetchone()[0]}")
            verify(conn)
            print("== dry-run 通过 ==")
            return {}

        if not _AKSHARE_OK:
            print(f"[错误] 未安装 akshare（{_AKSHARE_ERR}），无法构建。")
            print("       pip install -r requirements.txt")
            sys.exit(1)

        kline_codes = code_list("KLINE_CODES", DEFAULT_KLINE)
        minute_codes = code_list("MINUTE_CODES", DEFAULT_HOT)
        orderbook_codes = code_list("ORDERBOOK_CODES", DEFAULT_HOT)

        print("=" * 66)
        print(f"AKShare {ak.__version__} -> SQLite {OUT_DB}")
        print(f"日K抓回 {KLINE_DAYS_BACK} 天，保留最近 {PRUNE_DAYS} 天")
        print("=" * 66)

        end_date = datetime.now().strftime("%Y%m%d")
        start_date = (datetime.now() - timedelta(days=KLINE_DAYS_BACK)).strftime("%Y%m%d")

        print("\n[1/6] stock_info（交易所 + 行业板块）")
        n1 = write_stock_info(conn, retry(fetch_stock_info, times=2, wait=2, label="stock_info"))
        print(f"  ✓ {n1} 只")

        print("\n[2/6] stock_realtime（东方财富全市场快照）")
        n2 = write_realtime(conn, retry(fetch_realtime, times=2, wait=2, label="stock_realtime"))
        print(f"  ✓ {n2} 行")

        print(f"\n[3/6] stock_daily_kline（腾讯日K，{start_date} 起，{len(kline_codes)} 只）")
        n3 = 0
        kmap = {}
        for i, code in enumerate(kline_codes, 1):
            try:
                df = fetch_kline(code, start_date, end_date)
            except Exception as e:
                print(f"  [{i}/{len(kline_codes)}] {code} ✗ {type(e).__name__}: {str(e)[:50]}")
                continue
            if df is None or df.empty:
                print(f"  [{i}/{len(kline_codes)}] {code} 无数据")
                continue
            kmap[code] = df
            n3 += write_kline(conn, kline_rows(code, df))
            print(f"  [{i}/{len(kline_codes)}] {code} → {len(df)} 行")
            time.sleep(REQUEST_INTERVAL)

        print(f"\n[6/6] stock_indicator（本地计算，{len(kmap)} 只）")
        n6 = 0
        for code, df in kmap.items():
            ind = compute_indicators(df)
            n6 += write_indicator(conn, indicator_rows(code, ind))

        print(f"\n[4/6] stock_minute（新浪1分钟线，{len(minute_codes)} 只）")
        n4 = 0
        for i, code in enumerate(minute_codes, 1):
            try:
                df = fetch_minute(code)
            except Exception as e:
                print(f"  [{i}/{len(minute_codes)}] {code} ✗ {type(e).__name__}: {str(e)[:50]}")
                continue
            if df is None or df.empty:
                print(f"  [{i}/{len(minute_codes)}] {code} 无数据")
                continue
            n4 += write_minute(conn, minute_rows(code, df))
            print(f"  [{i}/{len(minute_codes)}] {code} → {len(df)} 行")
            time.sleep(REQUEST_INTERVAL)

        print(f"\n[5/6] stock_order_book（东财五档盘口，{len(orderbook_codes)} 只）")
        n5 = 0
        for i, code in enumerate(orderbook_codes, 1):
            try:
                kv = fetch_order_book(code)
            except Exception as e:
                print(f"  [{i}/{len(orderbook_codes)}] {code} ✗ {type(e).__name__}: {str(e)[:50]}")
                continue
            if kv is None:
                continue
            n5 += write_order_book(conn, [orderbook_row(code, kv)])
            print(f"  [{i}/{len(orderbook_codes)}] {code} → 1 行")
            time.sleep(REQUEST_INTERVAL)

        print(f"\n[剪枝] 只保留最近 {PRUNE_DAYS} 天")
        pruned = prune(conn)
        print(f"  删除过期行 {pruned} 条")
        write_data_source(conn)

        latest = verify(conn)
        # 剪枝后重新统计，保证 version.json 的 counts 是"实际保留量"而非写入量
        cur = conn.cursor()
        counts = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in _tables()}
        print(f"\n✅ 全部完成: {counts['stock_info']} info, {counts['stock_realtime']} realtime, "
              f"{counts['stock_daily_kline']} kline, {counts['stock_minute']} minute, "
              f"{counts['stock_order_book']} orderbook, {counts['stock_indicator']} indicator")
        return {"latest_trade_date": latest, "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "counts": counts}
    finally:
        conn.close()


def export_sql(db_path: Path, sql_path: Path) -> int:
    """把 SQLite 导出为可读 SQL（CREATE + INSERT），便于在 NP17 等工具里直接查看数据。"""
    conn = sqlite3.connect(db_path)
    try:
        with open(sql_path, "w", encoding="utf-8") as f:
            for line in conn.iterdump():
                f.write(line + "\n")
    finally:
        conn.close()
    sz = sql_path.stat().st_size
    print(f"  [✓] 已导出 SQL: {sql_path.name}  ({sz / 1024 / 1024:.2f} MB)")
    return sz


def write_version(meta: dict):
    version = {
        "schema_version": "1",
        "updated_at": meta.get("updated_at", datetime.now().strftime("%Y-%m-%d %H:%M:%S")),
        "latest_trade_date": meta.get("latest_trade_date"),
        "build": int(os.environ.get("BUILD_NUMBER",
                    meta.get("updated_at", "").replace("-", "").replace(":", "").replace(" ", "")) or "0"),
        "prune_days": PRUNE_DAYS,
        "kline_days_back": KLINE_DAYS_BACK,
        "sources": SRC,
        "db_bytes": OUT_DB.stat().st_size if OUT_DB.exists() else 0,
        "counts": meta.get("counts", {}),
    }
    OUT_VERSION.write_text(json.dumps(version, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  已写出 {OUT_VERSION.name}")


def main():
    parser = argparse.ArgumentParser(description="AKShare -> SQLite stock.db 构建")
    parser.add_argument("--dry-run", action="store_true", help="不拉网络，只用样例数据自检 schema/剪枝")
    args = parser.parse_args()

    meta = build(dry_run=args.dry_run)
    if meta and not args.dry_run:
        write_version(meta)
        print(f"  库文件 {OUT_DB.stat().st_size / 1024 / 1024:.2f} MB")
        export_sql(OUT_DB, OUT_SQL)


if __name__ == "__main__":
    main()
