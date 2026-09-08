#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""指数三表 pipeline 离线测试：用桩 AKShare 数据跑通 抓取→行转换→写入→剪枝 全链路。

不依赖网络（CI/沙箱均可跑）。真实接口的列名以 AKShare 官方文档为准，
此处桩数据按文档列名构造；若官方改列名，fetch 层的防御式映射会抛错告警而非静默写错。

用法：
  python data-pipeline/test_index_pipeline.py
"""
import os
import sys
import sqlite3
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_stock_db as b  # noqa: E402
import pandas as pd  # noqa: E402

RECENT = [(datetime.now() - timedelta(days=d)).strftime("%Y-%m-%d") for d in (3, 2, 1)]


def _spot_df():
    # 按 stock_zh_index_spot_em 文档列名构造；附一行坏代码验证过滤
    return pd.DataFrame([
        {"序号": 1, "代码": "000001", "名称": "上证指数", "最新价": 3800.5, "涨跌幅": 0.40,
         "涨跌额": 15.2, "成交量": 350000000, "成交额": 5e11, "振幅": 0.8,
         "最高": 3810.0, "最低": 3780.0, "今开": 3790.0, "昨收": 3785.3,
         "量比": 1.1, "换手率": 1.5},
        {"序号": 2, "代码": "399001", "名称": "深证成指", "最新价": 12500.0, "涨跌幅": -0.20,
         "涨跌额": -25.0, "成交量": 400000000, "成交额": 6e11, "振幅": 0.9,
         "最高": 12580.0, "最低": 12400.0, "今开": 12520.0, "昨收": 12525.0,
         "量比": 1.0, "换手率": 2.1},
        {"序号": 3, "代码": "ABC", "名称": "坏行", "最新价": 1.0, "涨跌幅": 0.0,
         "涨跌额": 0.0, "成交量": 0, "成交额": 0, "振幅": 0.0,
         "最高": 1.0, "最低": 1.0, "今开": 1.0, "昨收": 1.0,
         "量比": 0.0, "换手率": 0.0},
    ])


def _hist_df(dates):
    # 按 index_zh_a_hist 文档列名构造（成交量单位：手）
    return pd.DataFrame([
        {"日期": d, "开盘": 3790.0 + i, "收盘": 3800.0 + i, "最高": 3810.0,
         "最低": 3780.0, "成交量": 350000000, "成交额": 5e11, "振幅": 0.8,
         "涨跌幅": 0.4, "涨跌额": 15.0, "换手率": 1.5}
        for i, d in enumerate(dates)
    ])


def _conn():
    conn = sqlite3.connect(":memory:")
    b.ensure_schema(conn)
    return conn


def test_idx_market_and_symbol():
    assert b.idx_market("000001") == "沪市" and b.idx_symbol("000001") == "sh000001"
    assert b.idx_market("399001") == "深市" and b.idx_symbol("399001") == "sz399001"
    assert b.idx_market("899050") == "北交所" and b.idx_symbol("899050") == "sh899050"
    assert b.idx_market("000300") == "沪市"


def test_push2_rewrite_covers_numbered_mirrors():
    for host in ("push2.eastmoney.com", "48.push2.eastmoney.com",
                 "80.push2.eastmoney.com", "82.push2.eastmoney.com"):
        url = f"https://{host}/api/qt/clist/get?x=1"
        out = b._PUSH2_RE.sub(r"\1push2delay.eastmoney.com", url)
        assert out == "https://push2delay.eastmoney.com/api/qt/clist/get?x=1", out
    # 非 push2 主机不受影响
    other = "https://quote.eastmoney.com/center/hszs.html"
    assert b._PUSH2_RE.sub(r"\1push2delay.eastmoney.com", other) == other


def test_index_info_and_realtime_rows():
    conn = _conn()
    try:
        df = _spot_df()
        n1 = b.write_index_info(conn, b.index_info_rows(df))
        assert n1 == 2, f"坏代码行应被过滤，实际写入 {n1}"
        cur = conn.cursor()
        cur.execute("SELECT code, symbol, name, market FROM index_info ORDER BY code")
        rows = cur.fetchall()
        assert rows[0] == ("000001", "sh000001", "上证指数", "沪市"), rows[0]
        assert rows[1][0] == "399001" and rows[1][3] == "深市", rows[1]

        n2 = b.write_index_realtime(conn, b.index_realtime_rows(df))
        assert n2 == 2
        cur.execute("SELECT price, change_percent, volume, limit_up, pe_ttm "
                    "FROM index_realtime WHERE code='000001'")
        price, pct, vol, lup, pe = cur.fetchone()
        assert price == 3800.5 and pct == 0.40
        assert vol == 350000000 * 100, "成交量应 手->股 ×100"
        assert lup == 0.0 and pe is None, "指数无涨跌停/PE"
    finally:
        conn.close()


def test_index_kline_main_source():
    conn = _conn()
    try:
        real_hist = b.ak.index_zh_a_hist
        b.ak.index_zh_a_hist = lambda **kw: _hist_df(RECENT)
        try:
            df = b.fetch_index_kline("000001", "20200101", "20300101")
        finally:
            b.ak.index_zh_a_hist = real_hist
        assert df is not None and len(df) == 3
        n = b.write_index_kline(conn, b.index_kline_rows("000001", df))
        assert n == 3
        cur = conn.cursor()
        cur.execute("SELECT trade_date, close, volume FROM index_daily_kline "
                    "ORDER BY trade_date LIMIT 1")
        d, c, v = cur.fetchone()
        assert d == RECENT[0] and c == 3800.0 and v == 350000000 * 100
    finally:
        conn.close()


def test_index_kline_tx_fallback_and_range_filter():
    conn = _conn()
    try:
        real_hist, real_tx = b.ak.index_zh_a_hist, b.ak.stock_zh_index_daily_tx

        def _boom(**kw):
            raise RuntimeError("主源挂了")

        # 兜底返回全历史（含过期日期），应被截断到 [start, end]
        old = (datetime.now() - timedelta(days=90)).strftime("%Y-%m-%d")
        b.ak.index_zh_a_hist = _boom
        b.ak.stock_zh_index_daily_tx = lambda **kw: _hist_df([old] + RECENT)
        try:
            lo = (datetime.now() - timedelta(days=10)).strftime("%Y%m%d")
            hi = datetime.now().strftime("%Y%m%d")
            df = b.fetch_index_kline("000001", lo, hi)
        finally:
            b.ak.index_zh_a_hist, b.ak.stock_zh_index_daily_tx = real_hist, real_tx
        assert df is not None and len(df) == 3, f"兜底应截断到范围内，实际 {0 if df is None else len(df)}"
        assert old not in set(df["trade_date"])
    finally:
        conn.close()


def test_normalize_accepts_english_columns():
    df = pd.DataFrame([{"date": "2026-09-01", "open": 1, "close": 2, "high": 3,
                        "low": 0.5, "volume": 100, "amount": 200}])
    out = b._normalize_index_kline(df)
    assert list(out.columns)[:7] == ["trade_date", "open", "close", "high", "low",
                                    "volume", "amount"]
    assert out.iloc[0]["trade_date"] == "2026-09-01"


def test_prune_covers_index_kline():
    conn = _conn()
    try:
        b.write_index_kline(conn, [("000001", "2020-01-01", 1, 1, 1, 1, 100, 1, "x")])
        b.write_index_kline(conn, [(("000001", datetime.now().strftime("%Y-%m-%d"),
                                     1, 1, 1, 1, 100, 1, "x"))])
        assert b.prune(conn) >= 1
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) FROM index_daily_kline WHERE trade_date < ?",
                    ((datetime.now() - timedelta(days=b.PRUNE_DAYS)).strftime("%Y-%m-%d"),))
        assert cur.fetchone()[0] == 0, "过期指数K线应被剪枝"
        cur.execute("SELECT COUNT(*) FROM index_daily_kline")
        assert cur.fetchone()[0] == 1, "近期指数K线应保留"
    finally:
        conn.close()


if __name__ == "__main__":
    fns = [(k, v) for k, v in globals().items() if k.startswith("test_") and callable(v)]
    for name, fn in fns:
        try:
            fn()
            print(f"PASS {name}")
        except AssertionError as e:
            print(f"FAIL {name}: {e}")
            sys.exit(1)
    print(f"ALL {len(fns)} TESTS PASSED")
