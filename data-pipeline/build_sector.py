#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_sector.py —— 官方行业板块联动（L1）：东财官方行业板块快照采集。

数据源（已由 probe_sector.py 实测，2026-09-10）：
  * stock_board_industry_name_em()：496 个官方行业板块，列名
      排名/板块名称/板块代码(BKxxxx)/最新价/涨跌额/涨跌幅/总市值/换手率/
      上涨家数/下跌家数/领涨股票/领涨股票-涨跌幅
      - 涨跌幅为百分数（如 3.2 表示 3.2%）；总市值单位为元
      - 实时快照（收盘后抓取 = 当日收盘数据），无日期列，用抓取日标注
  * stock_board_industry_cons_em(symbol=板块名)：成分股，列名
      序号/代码/名称/最新价/涨跌幅/涨跌额/成交量/成交额/振幅/最高/最低/今开/昨收/换手率/市盈率-动态/市净率
  * 已知约束：
      - 板块历史日K stock_board_industry_hist_em 本机不可达（RemoteDisconnected），L1 不依赖
      - 非法板块名抛 IndexError（需 try/except）

写入 build_stock_db.OUT_DB（stock.db）：
  sector_board  板块行情快照（board_code PK + fetch_date）
  sector_member 成分股快照（board_code+code PK + fetch_date），只抓 stock_info 中出现过的行业

质量门：板块列表 >= 50；按库内行业匹配到的板块，成分股覆盖率 >= 80%，否则阻止发布。
用法（在 data-pipeline 目录，venv）：
  venv/Scripts/python.exe build_sector.py            # 全量
  venv/Scripts/python.exe build_sector.py --dry-run  # 只自检 schema
"""
import sys
import time
import json
import argparse
import math
import sqlite3
from datetime import datetime, timedelta

# 复用 build_stock_db 的网络补丁（强制直连 + push2delay 改写）与常量
try:
    import build_stock_db as bsd  # noqa: E402
    OUT_DB = bsd.OUT_DB
    REQUEST_INTERVAL = bsd.REQUEST_INTERVAL
except Exception as _e:  # pragma: no cover
    print(f"[FATAL] 无法 import build_stock_db（需要同目录）: {_e}")
    sys.exit(2)

try:
    import akshare as ak
    import pandas as pd
except Exception as e:  # pragma: no cover
    print(f"[FATAL] 无法 import akshare/pandas: {e}")
    sys.exit(2)

SECTOR_BOARD_DDL = """CREATE TABLE IF NOT EXISTS sector_board (
    board_code TEXT NOT NULL,          -- BKxxxx
    board_name TEXT NOT NULL,
    latest_price REAL,
    change_amount REAL,
    change_percent REAL,               -- 百分数（3.2 = 3.2%）
    total_mv REAL,                     -- 元
    turnover REAL,                     -- %
    up_count INTEGER,
    down_count INTEGER,
    leader TEXT,                       -- 领涨股名称
    leader_change REAL,                -- 领涨股涨跌幅（%）
    fetch_date TEXT NOT NULL,          -- 抓取日 YYYY-MM-DD
    PRIMARY KEY (board_code, fetch_date)
)"""

SECTOR_MEMBER_DDL = """CREATE TABLE IF NOT EXISTS sector_member (
    board_code TEXT NOT NULL,
    code TEXT NOT NULL,
    name TEXT,
    price REAL,
    change_percent REAL,               -- 百分数
    fetch_date TEXT NOT NULL,
    PRIMARY KEY (board_code, code, fetch_date)
)"""

SRC = {"sector_board": "东方财富·官方行业板块", "sector_member": "东方财富·官方行业板块成分股"}
SRC_NOTE = {
    "sector_board": "板块行情快照：涨跌幅为百分数、总市值单位元；实时快照用抓取日标注时效",
    "sector_member": "成分股快照：含最新价/涨跌幅（百分数）；仅抓库内行业匹配到的板块",
}

QUALITY_MIN_BOARDS = 50
QUALITY_MIN_MEMBER_COVERAGE = 0.8
QUALITY_MAX_AGE_DAYS = 4


def _num(v):
    try:
        value = float(v)
        return value if math.isfinite(value) else None
    except (TypeError, ValueError):
        return None


def _int(v):
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return None


def _pick(row, *keys):
    for k in keys:
        if k in row:
            return row[k]
    return None


def fetch_boards():
    """板块列表 -> list[dict]。异常时返回空列表。"""
    try:
        df = ak.stock_board_industry_name_em()
    except Exception as e:
        print(f"  [异常] 板块列表: {type(e).__name__}: {str(e)[:120]}")
        return []
    if df is None or df.empty:
        return []
    rows = []
    for _, r in df.iterrows():
        bc = str(_pick(r, "板块代码", "代码") or "").strip()
        bn = str(_pick(r, "板块名称", "名称") or "").strip()
        if not bc or not bn:
            continue
        rows.append({
            "board_code": bc,
            "board_name": bn,
            "latest_price": _num(_pick(r, "最新价")),
            "change_amount": _num(_pick(r, "涨跌额")),
            "change_percent": _num(_pick(r, "涨跌幅")),
            "total_mv": _num(_pick(r, "总市值")),
            "turnover": _num(_pick(r, "换手率")),
            "up_count": _int(_pick(r, "上涨家数")),
            "down_count": _int(_pick(r, "下跌家数")),
            "leader": str(_pick(r, "领涨股票", "领涨股") or "").strip() or None,
            "leader_change": _num(_pick(r, "领涨股票-涨跌幅", "领涨股-涨跌幅")),
        })
    return rows


def fetch_members(board_name, retries=2, base_wait=1.0):
    """单板块成分股 -> list[dict]。非法/失败返回 []。"""
    for attempt in range(retries):
        try:
            df = ak.stock_board_industry_cons_em(symbol=board_name)
        except Exception as e:
            if attempt < retries - 1:
                time.sleep(base_wait)
                continue
            print(f"  [异常] {board_name} 成分: {type(e).__name__}: {str(e)[:100]}")
            return []
        if df is None or df.empty:
            return []
        rows = []
        for _, r in df.iterrows():
            code = str(_pick(r, "代码") or "").strip()
            if not code:
                continue
            rows.append({
                "code": code,
                "name": str(_pick(r, "名称") or "").strip() or None,
                "price": _num(_pick(r, "最新价", "现价")),
                "change_percent": _num(_pick(r, "涨跌幅")),
            })
        return rows
    return []


def write_boards(conn, rows, fetch_date):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO sector_board
            (board_code, board_name, latest_price, change_amount, change_percent,
             total_mv, turnover, up_count, down_count, leader, leader_change, fetch_date)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(board_code, fetch_date) DO UPDATE SET
            board_name=excluded.board_name, latest_price=excluded.latest_price,
            change_amount=excluded.change_amount, change_percent=excluded.change_percent,
            total_mv=excluded.total_mv, turnover=excluded.turnover,
            up_count=excluded.up_count, down_count=excluded.down_count,
            leader=excluded.leader, leader_change=excluded.leader_change
    """, [(
        r["board_code"], r["board_name"], r["latest_price"], r["change_amount"], r["change_percent"],
        r["total_mv"], r["turnover"], r["up_count"], r["down_count"], r["leader"], r["leader_change"],
        fetch_date,
    ) for r in rows])
    conn.commit()
    return len(rows)


def write_members(conn, rows, fetch_date):
    if not rows:
        return 0
    cur = conn.cursor()
    cur.executemany("""
        INSERT INTO sector_member (board_code, code, name, price, change_percent, fetch_date)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(board_code, code, fetch_date) DO UPDATE SET
            name=excluded.name, price=excluded.price, change_percent=excluded.change_percent
    """, [(r["board_code"], r["code"], r["name"], r["price"], r["change_percent"], fetch_date) for r in rows])
    conn.commit()
    return len(rows)


def write_data_source(conn):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    cur = conn.cursor()
    for table, source, note in [("sector_board", SRC["sector_board"], SRC_NOTE["sector_board"]),
                                ("sector_member", SRC["sector_member"], SRC_NOTE["sector_member"])]:
        cur.execute("""
            INSERT INTO data_source (table_name, source, note, fetched_at)
            VALUES (?,?,?,?)
            ON CONFLICT(table_name) DO UPDATE SET
                source=excluded.source, note=excluded.note, fetched_at=excluded.fetched_at
        """, (table, source, note, ts))
    conn.commit()


def prune(conn, fetch_date):
    """只保留最近 QUALITY_MAX_AGE_DAYS 天的快照。"""
    cutoff = (datetime.now() - timedelta(days=QUALITY_MAX_AGE_DAYS)).strftime("%Y-%m-%d")
    cur = conn.cursor()
    n1 = cur.execute("DELETE FROM sector_board WHERE fetch_date < ?", (cutoff,)).rowcount
    n2 = cur.execute("DELETE FROM sector_member WHERE fetch_date < ?", (cutoff,)).rowcount
    conn.commit()
    return n1 + n2


def verify(conn, codes, fetch_date):
    cur = conn.cursor()
    n_boards, latest = cur.execute(
        "SELECT COUNT(*), MAX(fetch_date) FROM sector_board").fetchone()
    n_members = cur.execute("SELECT COUNT(*) FROM sector_member WHERE fetch_date=?", (fetch_date,)).fetchone()[0]
    matched = cur.execute(
        "SELECT COUNT(DISTINCT board_code) FROM sector_member WHERE fetch_date=?", (fetch_date,)).fetchone()[0]
    print(f"\n[验证] sector_board: {n_boards} 个板块 / 最新 {latest} | sector_member: {n_members} 行 / {matched} 板块")
    cur.execute("SELECT board_name, leader, change_percent, up_count, down_count "
                "FROM sector_board WHERE fetch_date=? ORDER BY change_percent DESC LIMIT 5", (fetch_date,))
    print("  涨幅前 5 板块:")
    for bn, lead, cp, up, down in cur.fetchall():
        print(f"    {bn} {cp}% 领涨 {lead} 涨{up}跌{down}")
    return n_boards, n_members, matched


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只建表自检，不拉网络")
    ap.add_argument("--no-manifest", action="store_true", help="由主构建器统一生成 version.json")
    args = ap.parse_args()

    fetch_date = datetime.now().strftime("%Y-%m-%d")
    conn = sqlite3.connect(OUT_DB)
    try:
        conn.execute(SECTOR_BOARD_DDL)
        conn.execute(SECTOR_MEMBER_DDL)
        conn.commit()

        if args.dry_run:
            print(f"== 自检模式：schema 就绪（{OUT_DB}），未拉网络 ==")
            verify(conn, None, fetch_date)
            return

        print("=" * 66)
        print(f"官方行业板块采集（东财）-> {OUT_DB}  抓取日 {fetch_date}")
        print("=" * 66)

        # 1) 板块列表
        boards = fetch_boards()
        if not boards:
            print("[!] 板块列表为空，无法继续")
            sys.exit(3)
        write_boards(conn, boards, fetch_date)
        print(f"  板块列表 → {len(boards)} 个")

        # 2) 成分股：只抓库内行业出现过的板块
        cur = conn.cursor()
        industries = [r[0] for r in cur.execute(
            "SELECT DISTINCT industry FROM stock_info WHERE industry IS NOT NULL AND industry <> ''")]
        name_to_board = {b["board_name"]: b["board_code"] for b in boards}
        matched = [(ind, name_to_board[ind]) for ind in industries if ind in name_to_board]
        print(f"  库内行业 {len(industries)} 个，与官方板块匹配 {len(matched)} 个")

        member_total = 0
        covered = 0
        for i, (ind_name, board_code) in enumerate(matched, 1):
            members = fetch_members(ind_name)
            if members:
                rows = [dict(m, board_code=board_code) for m in members]
                member_total += write_members(conn, rows, fetch_date)
                covered += 1
                print(f"  [{i}/{len(matched)}] {ind_name} ({board_code}) → {len(members)} 只")
            else:
                print(f"  [{i}/{len(matched)}] {ind_name} ({board_code}) → ✗ 空")
            time.sleep(REQUEST_INTERVAL)

        pruned = prune(conn, fetch_date)
        write_data_source(conn)
        n_boards, n_members, n_matched = verify(conn, None, fetch_date)
        print(f"  剪枝删除 {pruned} 行（保留最近 {QUALITY_MAX_AGE_DAYS} 天快照）")

        # 质量门
        ok_boards = n_boards >= QUALITY_MIN_BOARDS
        ok_members = (covered / max(1, len(matched))) >= QUALITY_MIN_MEMBER_COVERAGE if matched else True
        print(f"\n[质量门] 板块 {n_boards} >= {QUALITY_MIN_BOARDS} | "
              f"匹配板块成分覆盖率 {covered}/{len(matched)} (>= {QUALITY_MIN_MEMBER_COVERAGE * 100:.0f}%) | "
              f"{'✅ 通过' if (ok_boards and ok_members) else '❌ 未通过'}")
        if not (ok_boards and ok_members):
            print("[!] 数据质量未达标，本次板块产物不宜发布。")
            sys.exit(3)

        if not args.no_manifest:
            meta = json.loads(bsd.OUT_VERSION.read_text(encoding="utf-8")) if bsd.OUT_VERSION.exists() else {}
            meta.setdefault("counts", {})["sector_board"] = n_boards
            meta.setdefault("counts", {})["sector_member"] = n_members
            meta["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            bsd.SRC.update(SRC)
            bsd.write_version(meta)
        print("✅ 板块表构建完成")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
