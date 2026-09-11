"""export_common_assets.py —— 把 SQLite 专属数据表导出为双端共用的内置 JSON 资产。

背景
----
Android 通过 SQLite 读取 stock.db 拿到指数、官方板块、资金流数据；
iOS / JS 没有 SQLite（见 shared/src/*Main/.../JsonBackedStockDb.kt），
因此这些数据在 iOS / JS 上一直返回 null / empty，指数页与板块卡、资金流卡只有空态。

本脚本把这三类表从 stock.db 导出成 JSON，落在 shared/src/commonMain/assets/，
两个平台都能通过 LocalDataService.loadAssetText() 同步读取，行为与 Android 对齐。

产物
----
index_list.json     指数快照（index_info LEFT JOIN index_realtime）
sector_list.json    官方行业板块 + 成分股（sector_board / sector_member）
fundflow_list.json  个股资金流（stock_fund_flow）

数据量大的两张表用「紧凑数组」编码（而不是对象数组），
把重复的键名省掉，体积约为对象编码的 6 成：

    sector_list.json  {"boards":[...], "members": {"BK1452": [["000001","平安银行",11.59,0.26], ...]}}
    fundflow_list.json{"600519": [["2026-09-09", -641850339.1, -15.73, null, ...], ...]}

用法
----
    venv/Scripts/python.exe export_common_assets.py
    venv/Scripts/python.exe export_common_assets.py --db path/to/stock.db
    venv/Scripts/python.exe export_common_assets.py --dry-run   # 只统计，不写文件

注意
----
数据源是 stock.db，每日流水线（run_daily）刷新 stock.db 后需要重跑本脚本，
否则 JSON 资产会停留在上一次导出的快照。
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = REPO_ROOT / "shared" / "src" / "commonMain" / "assets" / "stock.db"
ASSETS_DIR = REPO_ROOT / "shared" / "src" / "commonMain" / "assets"

# (输出文件名, 生成函数名)
OUTPUTS = ("index_list.json", "sector_list.json", "fundflow_list.json")


def _num(v):
    """空串/None -> None，数字 -> float/int 原样。"""
    if v is None or v == "":
        return None
    return v


def export_index(conn: sqlite3.Connection) -> list:
    """指数：index_info 为主体，LEFT JOIN index_realtime 取最新行情。

    与 Android StockDb.indexDetail / listIndices 同源同口径：
    即使没有 realtime 行也要保留该指数（列表页要能看到），只是行情字段为 null。
    """
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
    """官方行业板块 + 成分股 + 「个股 -> 板块」直连映射。

    为什么需要 stock_board 直连映射
    ------------------------------
    Android 的 sectorOfStock 用 `stock_info.industry = sector_board.board_name` 做 JOIN，
    而 iOS/JS 手里只有 stock_list.json。问题是 stock_list.json 是一份较早的裁剪快照，
    它的 industry 词表已经过时（例如同一只股票在旧快照里是"银行Ⅱ"、在新库里是"股份制银行Ⅲ"），
    直接按名称匹配会选到粒度不同的另一个板块，两端结果对不上。

    所以这里在导出时就把 JOIN 结果固化成 code -> board_code 的映射，
    iOS/JS 查板块不再依赖 industry 词表，结果与 Android 逐字段一致。
    板块与成分股也只导出被映射引用到的那些，避免带出无成分股的空板块。

    stock_board 同时保留了 sector_board 中该板块的最新快照（fetch_date 最大者），
    与 Android 的 `ORDER BY b.fetch_date DESC LIMIT 1` 同义。
    """
    conn.row_factory = sqlite3.Row

    # 1) 个股 -> 板块（同 Android 的 JOIN；同名板块取最新快照）
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

    # 2) 只导出被引用到的板块，以及这些板块的最新成分股
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
        # 紧凑数组：[代码, 名称, 最新价, 涨跌幅%]
        members[b["board_code"]] = [
            [m["code"], _num(m["name"]), _num(m["price"]), _num(m["change_percent"])]
            for m in rows
        ]

    return {"boards": boards, "members": members, "stock_board": stock_board}


def export_fund_flow(conn: sqlite3.Connection) -> dict:
    """个股资金流，按 code 分组，日期倒序（与 Android 的 ORDER BY trade_date DESC 一致）。"""
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
        # 不缩进：这些是打包进 App 的资产，缩进会让体积膨胀 30% 以上
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
