#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_fund_flow.py —— P3 步骤② L1：日级主力资金流采集（新浪主源 + 东财降级）。

数据源（已实测验证，2026-09-09）：
  * 新浪 MoneyFlow.ssl_qsfx_zjlrqs（主源，稳定可用）：
      - 参数 daima={sh|sz|bj}{code}，返回 JSON 数组
      - 仅主力档：r0_net（主力净流入-净额，单位=元）、r0_ratio（主力净占比，小数）
      - 非法代码返回 []；最新日期=当日
  * 东方财富 stock_individual_fund_flow（降级源，五档齐全）：
      - 通过 akshare 调用；本机短时高频请求会被 WAF 临时封禁（RemoteDisconnected）
      - 能连则写入五档（超大/大/中/小），失败只告警并标注新浪主力档

写入 build_stock_db.OUT_DB（stock.db）的 stock_fund_flow 表：
  code, trade_date, main_net(元), main_ratio(%), 五档可空列, source
质量门：覆盖率 >= 80% 且最新日期不早于最近交易日 - 3 天，否则阻止发布。

用法（在 data-pipeline 目录，venv）：
  venv/Scripts/python.exe build_fund_flow.py            # 全量采集（默认 KLINE_CODES）
  venv/Scripts/python.exe build_fund_flow.py --codes 600519,000001
  venv/Scripts/python.exe build_fund_flow.py --dry-run  # 只自检 schema，不拉网络
"""
import os
import re
import sys
import time
import json
import argparse
import sqlite3
import urllib.request
import urllib.parse
from datetime import datetime, timedelta
from pathlib import Path

# 复用 build_stock_db 的网络补丁（强制直连 + push2delay 改写）与常量
try:
    import build_stock_db as bsd  # noqa: E402
    OUT_DB = bsd.OUT_DB
    PRUNE_DAYS = bsd.PRUNE_DAYS
    KLINE_CODES = bsd.code_list("KLINE_CODES", bsd.DEFAULT_KLINE)
    REQUEST_INTERVAL = bsd.REQUEST_INTERVAL
except Exception as _e:  # pragma: no cover
    print(f"[FATAL] 无法 import build_stock_db（需要同目录）: {_e}")
    sys.exit(2)

import pandas as pd  # noqa: E402

SINA_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/MoneyFlow.ssl_qsfx_zjlrqs"
SINA_HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
    "Referer": "https://finance.sina.com.cn/",
}

FUND_FLOW_DDL = """CREATE TABLE IF NOT EXISTS stock_fund_flow (
    code TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    main_net REAL NOT NULL DEFAULT 0,       -- 主力净流入-净额（元）
    main_ratio REAL NOT NULL DEFAULT 0,     -- 主力净流入-净占比（%，如 -15.73）
    super_net REAL, super_ratio REAL,       -- 超大单（东财，可空）
    big_net REAL, big_ratio REAL,           -- 大单
    mid_net REAL, mid_ratio REAL,           -- 中单
    small_net REAL, small_ratio REAL,       -- 小单
    source TEXT,
    PRIMARY KEY (code, trade_date)
)"""

SRC = {"stock_fund_flow": "新浪(主力)+东财(五档降级)"}
SRC_NOTE = {
    "stock_fund_flow": "日级主力资金流：r0_net 元 / r0_ratio 百分数；五档列仅东财通道可用时填充",
}

QUALITY_MIN_COVERAGE = 0.8   # 覆盖率低于 80% 阻止发布
QUALITY_MAX_AGE_DAYS = 4     # 最新日期距今天数超过 4 天视为时效异常（周末/节假日放宽）


def _try_import_akshare():
    try:
        import akshare as ak  # noqa: F401
        return ak
    except Exception:
        return None


_AK = _try_import_akshare()


def fetch_sina(code, retries=3, base_wait=1.0):
    """新浪主力资金流（主源）。返回 list[dict] 或 []（非法代码）。带指数退避重试。"""
    market = _market_of(code)
    params = urllib.parse.urlencode({
        "page": "1", "num": "60", "sort": "opendate", "asc": "0",
        "daima": f"{market}{code}",
    })
    url = SINA_URL + "?" + params
    last_err = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=SINA_HEADERS)
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read().decode("utf-8", errors="replace")
            if not raw or raw.strip() == "":
                return []
            return json.loads(raw)
        except Exception as e:
            last_err = e
            if attempt < retries - 1:
                time.sleep(base_wait * (2 ** attempt))
    raise last_err


def _market_of(code):
    """独立市场判定（备胎，避免依赖 build_stock_db 内部符号）。"""
    c = str(code)
    if c.startswith(("4", "8")):
        return "bj"
    return "sh" if c.startswith(("6", "9", "5")) else "sz"


def sina_rows(code, data):
    """新浪 JSON -> 行元组。r0_ratio 是小数（如 -0.1573）→ 存百分数（-15.73）。"""
    rows = []
    for item in data:
        try:
            d = str(item.get("opendate", "")).strip()
            if not d or len(d) != 10:
                continue
            main_net = float(item.get("r0_net") or 0.0)
            ratio_raw = item.get("r0_ratio")
            main_ratio = round(float(ratio_raw) * 100.0, 2) if ratio_raw is not None else 0.0
            rows.append((code, d, main_net, main_ratio, None, None, None, None, None, None, None, None, SRC["stock_fund_flow"]))
        except (TypeError, ValueError):
            continue
    return rows


def fetch_em(code):
    """东财五档资金流（降级源，软失败）。返回 DataFrame 或 None。"""
    if _AK is None:
        return None
    try:
        df = _AK.stock_individual_fund_flow(stock=code, market=_market_of(code))
        return df if df is not None and not df.empty else None
    except Exception:
        return None


def em_rows(code, df):
    """东财 DataFrame -> 行元组（五档齐全）。列名见 akshare：日期/主力净流入-净额/-净占比/超大单..."""
    src = "东财(五档)"
    rows = []
    for _, r in df.iterrows():
        try:
            d = str(r["日期"]).strip()
            if len(d) != 10:
                continue
            # 列名用模糊匹配（akshare 版本间有差异）
            def pick(*keys):
                for k in keys:
                    if k in r:
                        return r[k]
                return None

            main_net = float(pick("主力净流入-净额") or 0.0)
            main_ratio = float(pick("主力净流入-净占比") or 0.0)
            super_net = _num(pick("超大单净流入-净额"))
            super_ratio = _num(pick("超大单净流入-净占比"))
            big_net = _num(pick("大单净流入-净额"))
            big_ratio = _num(pick("大单净流入-净占比"))
            mid_net = _num(pick("中单净流入-净额"))
            mid_ratio = _num(pick("中单净流入-净占比"))
            small_net = _num(pick("小单净流入-净额"))
            small_ratio = _num(pick("小单净流入-净占比"))
            rows.append((code, d, main_net, main_ratio, super_net, super_ratio,
                         big_net, big_ratio, mid_net, mid_ratio, small_net, small_ratio, src))
        except (TypeError, ValueError):
            continue
    return rows


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def write_fund_flow(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO stock_fund_flow
            (code, trade_date, main_net, main_ratio, super_net, super_ratio,
             big_net, big_ratio, mid_net, mid_ratio, small_net, small_ratio, source)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(code, trade_date) DO UPDATE SET
            main_net=excluded.main_net, main_ratio=excluded.main_ratio,
            super_net=excluded.super_net, super_ratio=excluded.super_ratio,
            big_net=excluded.big_net, big_ratio=excluded.big_ratio,
            mid_net=excluded.mid_net, mid_ratio=excluded.mid_ratio,
            small_net=excluded.small_net, small_ratio=excluded.small_ratio,
            source=excluded.source
    """, rows)
    conn.commit()
    return len(rows)


def prune(conn):
    cutoff = (datetime.now() - timedelta(days=PRUNE_DAYS)).strftime("%Y-%m-%d")
    cur = conn.cursor()
    cur.execute("DELETE FROM stock_fund_flow WHERE trade_date < ?", (cutoff,))
    conn.commit()
    return cur.rowcount


def write_data_source(conn):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    cur = conn.cursor()
    cur.execute("""
        INSERT INTO data_source (table_name, source, note, fetched_at)
        VALUES (?,?,?,?)
        ON CONFLICT(table_name) DO UPDATE SET
            source=excluded.source, note=excluded.note, fetched_at=excluded.fetched_at
    """, ("stock_fund_flow", SRC["stock_fund_flow"], SRC_NOTE["stock_fund_flow"], ts))
    conn.commit()


def verify(conn, codes):
    cur = conn.cursor()
    cur.execute("SELECT COUNT(DISTINCT code), COUNT(*), MAX(trade_date) FROM stock_fund_flow")
    n_codes, n_rows, latest = cur.fetchone()
    covered = [c for c in codes if cur.execute(
        "SELECT 1 FROM stock_fund_flow WHERE code=? LIMIT 1", (c,)).fetchone()]
    print(f"\n[验证] stock_fund_flow: {n_codes} 只 / {n_rows} 行 / 最新 {latest}")
    print(f"  覆盖率: {len(covered)}/{len(codes)} = {len(covered) / max(1, len(codes)) * 100:.1f}%")
    cur.execute("SELECT source, COUNT(*) FROM stock_fund_flow GROUP BY source")
    for s, n in cur.fetchall():
        print(f"  来源: {s} = {n} 行")
    return n_codes, n_rows, latest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--codes", default="", help="逗号分隔代码，默认 KLINE_CODES")
    ap.add_argument("--dry-run", action="store_true", help="只建表自检，不拉网络")
    ap.add_argument("--no-em", action="store_true", help="跳过东财降级通道")
    args = ap.parse_args()

    if args.codes:
        codes = [c.strip() for c in args.codes.split(",") if c.strip()]
    else:
        codes = list(KLINE_CODES)

    conn = sqlite3.connect(OUT_DB)
    try:
        conn.execute(FUND_FLOW_DDL)
        conn.commit()

        if args.dry_run:
            print(f"== 自检模式：schema 就绪（{OUT_DB}），未拉网络 ==")
            verify(conn, codes)
            return

        print("=" * 66)
        print(f"资金流采集: 新浪主源 + 东财降级 -> {OUT_DB}")
        print(f"覆盖 {len(codes)} 只（KLINE_CODES{' 自定义' if args.codes else ''}）")
        print("=" * 66)

        em_hits = 0
        total = 0
        for i, code in enumerate(codes, 1):
            src_rows = None
            try:
                data = fetch_sina(code)
                src_rows = sina_rows(code, data)
                if src_rows:
                    total += write_fund_flow(conn, src_rows)
                    print(f"  [{i}/{len(codes)}] {code} 新浪 → {len(src_rows)} 行")
            except Exception as e:
                print(f"  [{i}/{len(codes)}] {code} 新浪 ✗ {type(e).__name__}: {str(e)[:60]}")

            # 东财降级：只补五档，失败静默（WAF 封禁时保持新浪主力档）
            if not args.no_em and src_rows:
                try:
                    df = fetch_em(code)
                    if df is not None:
                        em_r = em_rows(code, df)
                        if em_r:
                            total += write_fund_flow(conn, em_r)
                            em_hits += 1
                            print(f"      └ 东财五档 → {len(em_r)} 行")
                except Exception:
                    pass
            time.sleep(REQUEST_INTERVAL)

        pruned = prune(conn)
        write_data_source(conn)
        n_codes, n_rows, latest = verify(conn, codes)
        print(f"  剪枝删除 {pruned} 行（保留最近 {PRUNE_DAYS} 天）")
        print(f"  东财五档命中 {em_hits}/{len(codes)}")

        # 质量门
        coverage = n_codes / max(1, len(codes))
        age = 999
        if latest:
            age = (datetime.now() - datetime.strptime(latest, "%Y-%m-%d")).days
        ok = coverage >= QUALITY_MIN_COVERAGE and age <= QUALITY_MAX_AGE_DAYS
        print(f"\n[质量门] 覆盖率 {coverage * 100:.1f}% (>= {QUALITY_MIN_COVERAGE * 100:.0f}%) | "
              f"最新 {latest} 距今 {age} 天 (<= {QUALITY_MAX_AGE_DAYS}) | {'✅ 通过' if ok else '❌ 未通过'}")
        if not ok:
            print("[!] 数据质量未达标，本次产物不宜发布。请检查数据源或覆盖列表。")
            sys.exit(3)
        print("✅ 资金流表构建完成")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
