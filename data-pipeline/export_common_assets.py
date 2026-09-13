# 从 SQLite 导出 iOS 和 Web 使用的同口径快照。

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = REPO_ROOT / "shared" / "src" / "commonMain" / "assets" / "stock.db"
ASSETS_DIR = REPO_ROOT / "shared" / "src" / "commonMain" / "assets"

OUTPUTS = ("index_list.json", "sector_list.json", "fundflow_list.json")

def _num(v):

    if v is None or v == "":
        return None
    return v

def export_index(conn: sqlite3.Connection) -> list:

    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT i.code, i.symbol, i.name, i.market,
               r.price, r.change, r.change_percent, r.open, r.pre_close,
               r.high, r.low, r.volume, r.amount, r.update_time
        FROM index_info i
        LEFT JOIN index_realtime r ON r.code = i.code
        ORDER BY i.code ASC
        """
    ).fetchall()
    out = []
    for r in rows:
        out.append({
            "code": r["code"],
            "symbol": _num(r["symbol"]),
            "name": _num(r["name"]),
            "market": _num(r["market"]),
            "price": _num(r["price"]),
            "change": _num(r["change"]),
            "change_percent": _num(r["change_percent"]),
            "open": _num(r["open"]),
            "pre_close": _num(r["pre_close"]),
            "high": _num(r["high"]),
            "low": _num(r["low"]),
            "volume": _num(r["volume"]),
            "amount": _num(r["amount"]),
            "update_time": _num(r["update_time"]),
        })
    return out

def export_sector(conn: sqlite3.Connection) -> dict:

    conn.row_factory = sqlite3.Row

    stock_board: dict[str, str] = {}
    stock_fetch: dict[str, str] = {}
    for r in conn.execute(
        """SELECT s.code AS code, b.board_code AS board_code, b.fetch_date AS fetch_date
           FROM stock_info s JOIN sector_board b ON b.board_name = s.industry"""
    ).fetchall():
        code, fd = r["code"], r["fetch_date"] or ""
        if code in stock_fetch and stock_fetch[code] >= fd:
            continue
        stock_fetch[code] = fd
        stock_board[code] = r["board_code"]

    used_codes = set(stock_board.values())

    boards = []
    members: dict[str, list] = {}
    for b in conn.execute(
        """SELECT board_code, board_name, change_percent, change_amount, total_mv,
                  turnover, up_count, down_count, leader, leader_change, fetch_date
           FROM sector_board ORDER BY board_code ASC"""
    ).fetchall():
        if b["board_code"] not in used_codes:
            continue
        boards.append({
            "board_code": b["board_code"],
            "board_name": b["board_name"],
            "change_percent": _num(b["change_percent"]),
            "change_amount": _num(b["change_amount"]),
            "total_mv": _num(b["total_mv"]),
            "turnover": _num(b["turnover"]),
            "up_count": b["up_count"] or 0,
            "down_count": b["down_count"] or 0,
            "leader": _num(b["leader"]),
            "leader_change": _num(b["leader_change"]),
            "fetch_date": b["fetch_date"] or "",
        })
        rows = conn.execute(
            """SELECT code, name, price, change_percent FROM sector_member
               WHERE board_code=? AND fetch_date=(
                   SELECT MAX(fetch_date) FROM sector_member WHERE board_code=?)
               ORDER BY code ASC""",
            (b["board_code"], b["board_code"]),
        ).fetchall()

        members[b["board_code"]] = [
            [m["code"], _num(m["name"]), _num(m["price"]), _num(m["change_percent"])]
            for m in rows
        ]

    return {"boards": boards, "members": members, "stock_board": stock_board}

def export_fund_flow(conn: sqlite3.Connection) -> dict:

    conn.row_factory = sqlite3.Row
    out: dict[str, list] = {}
    for r in conn.execute(
        """SELECT code, trade_date, main_net, main_ratio, super_net, big_net,
                  mid_net, small_net, source
           FROM stock_fund_flow ORDER BY code ASC, trade_date DESC"""
    ).fetchall():
        out.setdefault(r["code"], []).append([
            r["trade_date"],
            _num(r["main_net"]),
            _num(r["main_ratio"]),
            _num(r["super_net"]),
            _num(r["big_net"]),
            _num(r["mid_net"]),
            _num(r["small_net"]),
            r["source"] or "",
        ])
    return out

def main() -> int:
    ap = argparse.ArgumentParser(description="导出双端共用的内置 JSON 资产")
    ap.add_argument("--db", default=str(DEFAULT_DB), help="stock.db 路径")
    ap.add_argument("--dry-run", action="store_true", help="只统计体积，不写文件")
    args = ap.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"[ERR] 找不到数据库：{db_path}", file=sys.stderr)
        return 1

    conn = sqlite3.connect(str(db_path))
    try:
        payloads = {
            "index_list.json": export_index(conn),
            "sector_list.json": export_sector(conn),
            "fundflow_list.json": export_fund_flow(conn),
        }
    finally:
        conn.close()

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    total = 0
    for name in OUTPUTS:
        data = payloads[name]

        text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
        size = len(text.encode("utf-8"))
        total += size
        if name == "index_list.json":
            detail = f"{len(data)} 个指数"
        elif name == "sector_list.json":
            detail = (f"{len(data['boards'])} 个板块 / {sum(len(v) for v in data['members'].values())} 条成分股"
                      f" / {len(data['stock_board'])} 条个股映射")
        else:
            detail = f"{len(data)} 只个股 / {sum(len(v) for v in data.values())} 条资金流"
        print(f"{name:20s} {size / 1024:8.1f} KB   {detail}")
        if not args.dry_run:
            (ASSETS_DIR / name).write_text(text, encoding="utf-8")

    print(f"{'合计':20s} {total / 1024:8.1f} KB")
    if args.dry_run:
        print("(--dry-run：未写入文件)")
    else:
        print(f"已写入 {ASSETS_DIR}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
