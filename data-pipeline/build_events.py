#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_events.py —— 用 AKShare 拉取除权除息 + 财报披露事件，写入 stock.db

新增两张表：
  stock_dividend   除权除息事件（code, event_date, type, content, source）
  stock_earnings   财报披露事件（code, event_date, type, content, source）

用法：
  python build_events.py                  # 拉取默认关注列表
  python build_events.py --codes 000002   # 只拉指定股票
  python build_events.py --dry-run        # 只建表，不拉网络
"""
import os
import re
import sys
import time
import json
import argparse
import sqlite3
from datetime import datetime
from pathlib import Path

if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if sys.stderr and hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ---------------------------------------------------------------------------
# 网络补丁（与 build_stock_db.py 一致）
# ---------------------------------------------------------------------------
_USE_PROXY = os.environ.get("USE_PROXY")
_PROXY_VARS = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
               "ALL_PROXY", "all_proxy")
_PUSH2_RE = re.compile(r"(https?://)(?:\d+\.)?push2\.eastmoney\.com")


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
            url = _PUSH2_RE.sub(r"\1push2delay.eastmoney.com", url)
        return _orig_request(self, method, url, *args, **kwargs)

    requests.Session.request = _patched_request


if _USE_PROXY:
    for _v in _PROXY_VARS:
        os.environ.pop(_v, None)
_install_network_patch()

# ---------------------------------------------------------------------------
# 路径与默认配置
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("STOCK_DB", BASE_DIR / "stock.db"))
DEFAULT_CODES = [
    # 银行
    "000001", "600036", "601398",
    # 地产
    "000002", "600048",
    # 白酒
    "600519", "000858",
    # 新能源
    "300750", "002594", "601012",
    # 科技
    "688981", "002475",
    # 医药
    "600276", "603259",
    # 消费
    "000333", "000651",
    # 券商/金融
    "600030", "300059", "601318",
    # 能源
    "601857",
]

DDL = [
    """CREATE TABLE IF NOT EXISTS stock_dividend (
        code TEXT NOT NULL,
        event_date TEXT NOT NULL,
        type TEXT NOT NULL DEFAULT '',
        content TEXT NOT NULL DEFAULT '',
        source TEXT,
        PRIMARY KEY (code, event_date, type)
    )""",
    """CREATE TABLE IF NOT EXISTS stock_earnings (
        code TEXT NOT NULL,
        event_date TEXT NOT NULL,
        type TEXT NOT NULL DEFAULT '',
        content TEXT NOT NULL DEFAULT '',
        source TEXT,
        PRIMARY KEY (code, event_date, type)
    )""",
]


def ensure_schema(conn):
    cur = conn.cursor()
    for ddl in DDL:
        cur.execute(ddl)
    conn.commit()


def _fmt_date(val):
    """把 pandas Timestamp / NaT / str 统一成 YYYY-MM-DD，无效返回 None。"""
    if val is None:
        return None
    try:
        import pandas as pd
        if pd.isna(val):
            return None
    except Exception:
        pass
    if hasattr(val, "strftime"):
        return val.strftime("%Y-%m-%d")
    s = str(val).strip()
    if not s or s.lower() in ("nan", "nat", "none"):
        return None
    # 截取前 10 位
    return s[:10] if len(s) >= 10 else None


def _report_type(report_date: str) -> str:
    """从报告期日期推断财报类型。"""
    if not report_date:
        return "财报"
    mmdd = report_date[5:] if len(report_date) >= 10 else report_date
    if mmdd == "12-31":
        return "年报"
    if mmdd == "06-30":
        return "中报"
    if mmdd == "09-30":
        return "三季报"
    if mmdd == "03-31":
        return "一季报"
    return "财报"


def fetch_dividend(code: str):
    """拉取除权除息事件，返回 list of (event_date, type, content)."""
    import akshare as ak
    results = []
    try:
        df = ak.stock_dividend_cninfo(symbol=code)
        if df is None or df.empty:
            return results
        for _, row in df.iterrows():
            ex_date = _fmt_date(row.get("除权日"))
            if not ex_date:
                continue
            div_type = str(row.get("分红类型", "")).strip() or "分红"
            desc = str(row.get("实施方案分红说明", "")).strip()
            report = str(row.get("报告时间", "")).strip()
            content = desc if desc else div_type
            if report:
                content = f"{report} {content}".strip()
            results.append((ex_date, div_type, content))
    except Exception as e:
        print(f"  [WARN] dividend {code} failed: {e}")
    return results


def fetch_earnings(code: str):
    """拉取财报事件，返回 list of (event_date, type, content)."""
    import akshare as ak
    results = []
    try:
        df = ak.stock_financial_abstract_ths(symbol=code, indicator="按报告期")
        if df is None or df.empty:
            return results
        for _, row in df.iterrows():
            report_date = _fmt_date(row.get("报告期"))
            if not report_date:
                continue
            rtype = _report_type(report_date)
            net_profit = str(row.get("净利润", "")).strip()
            revenue = str(row.get("营业总收入", "")).strip()
            parts = []
            if net_profit and net_profit.lower() not in ("false", "nan", ""):
                parts.append(f"净利润{net_profit}")
            if revenue and revenue.lower() not in ("false", "nan", ""):
                parts.append(f"营收{revenue}")
            content = " ".join(parts) if parts else rtype
            results.append((report_date, rtype, content))
    except Exception as e:
        print(f"  [WARN] earnings {code} failed: {e}")
    return results


def upsert_events(conn, table: str, code: str, events):
    cur = conn.cursor()
    sql = f"INSERT OR REPLACE INTO {table} (code, event_date, type, content, source) VALUES (?, ?, ?, ?, ?)"
    for event_date, etype, content in events:
        cur.execute(sql, (code, event_date, etype, content, "AKShare"))
    conn.commit()
    return len(events)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--codes", nargs="*", default=None,
                        help="股票代码列表，默认读环境变量或默认列表")
    parser.add_argument("--dry-run", action="store_true", help="只建表不拉网络")
    args = parser.parse_args()

    codes = args.codes or os.environ.get("EVENT_CODES", "").split(",")
    codes = [c.strip() for c in codes if c.strip()] or DEFAULT_CODES

    print(f"DB: {DB_PATH}")
    print(f"Codes: {codes}")

    conn = sqlite3.connect(str(DB_PATH))
    ensure_schema(conn)
    print("Schema ensured: stock_dividend, stock_earnings")

    if args.dry_run:
        print("dry-run: skip network fetch")
        conn.close()
        return

    total_div = 0
    total_earn = 0
    for i, code in enumerate(codes, 1):
        print(f"\n[{i}/{len(codes)}] {code}")
        div_events = fetch_dividend(code)
        if div_events:
            n = upsert_events(conn, "stock_dividend", code, div_events)
            total_div += n
            print(f"  dividend: {n} rows (latest: {div_events[-1][0]})")
        else:
            print("  dividend: 0 rows")

        earn_events = fetch_earnings(code)
        if earn_events:
            n = upsert_events(conn, "stock_earnings", code, earn_events)
            total_earn += n
            print(f"  earnings: {n} rows (latest: {earn_events[0][0]})")
        else:
            print("  earnings: 0 rows")

        time.sleep(0.5)  # 礼貌限速

    # 更新 data_source 表
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    cur = conn.cursor()
    cur.execute("INSERT OR REPLACE INTO data_source (table_name, source, note, fetched_at) VALUES (?, ?, ?, ?)",
                ("stock_dividend", "AKShare", "除权除息事件（stock_dividend_cninfo）", now))
    cur.execute("INSERT OR REPLACE INTO data_source (table_name, source, note, fetched_at) VALUES (?, ?, ?, ?)",
                ("stock_earnings", "AKShare", "财报披露事件（stock_financial_abstract_ths，按报告期）", now))
    conn.commit()

    print(f"\n=== DONE ===")
    print(f"stock_dividend: {total_div} rows")
    print(f"stock_earnings: {total_earn} rows")

    # 验证
    cur.execute("SELECT COUNT(*) FROM stock_dividend")
    print(f"DB stock_dividend total: {cur.fetchone()[0]}")
    cur.execute("SELECT COUNT(*) FROM stock_earnings")
    print(f"DB stock_earnings total: {cur.fetchone()[0]}")
    conn.close()


if __name__ == "__main__":
    main()
