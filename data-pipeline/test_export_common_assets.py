# 从 SQLite 导出 iOS 和 Web 使用的同口径快照。

import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_common_assets as ex

ASSETS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonMain", "assets"
)
DB = os.path.join(ASSETS, "stock.db")
SAMPLE_CODES = ["600519", "000001", "600036", "002594", "601318", "300750", "000002", "300124"]

def _conn():
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    return con

def _load(name):
    with open(os.path.join(ASSETS, name), encoding="utf-8") as f:
        return json.load(f)

def test_index_rows_match_sqlite():

    con = _conn()
    try:
        sql = con.execute(
            """SELECT i.code, i.name, i.market, r.price, r.change, r.change_percent, r.open,
                      r.pre_close, r.high, r.low, r.volume, r.amount
               FROM index_info i LEFT JOIN index_realtime r ON r.code=i.code ORDER BY i.code ASC"""
        ).fetchall()
        rows = ex.export_index(con)
    finally:
        con.close()

    assert len(rows) == len(sql), "指数条数不一致"
    for j, s in zip(rows, sql):
        assert j["code"] == s["code"]
        assert j["name"] == s["name"]
        assert j["market"] == s["market"]
        for k in ("price", "change", "change_percent", "open", "pre_close",
                  "high", "low", "volume", "amount"):
            assert j[k] == s[k], f"{s['code']}.{k} 不一致"

def test_index_asset_is_fresh():

    assert _load("index_list.json") == _load_export(ex.export_index)

def test_sector_board_mapping_matches_join():

    con = _conn()
    try:
        rows = con.execute(
            """SELECT s.code AS code, b.board_code AS board_code, b.fetch_date AS fetch_date
               FROM stock_info s JOIN sector_board b ON b.board_name = s.industry"""
        ).fetchall()
        payload = ex.export_sector(con)
    finally:
        con.close()

    expect, fetch = {}, {}
    for r in rows:
        code, fd = r["code"], r["fetch_date"] or ""
        if code in fetch and fetch[code] >= fd:
            continue
        fetch[code] = fd
        expect[code] = r["board_code"]

    assert payload["stock_board"] == expect, "个股 -> 板块映射与 JOIN 不一致"

    assert {b["board_code"] for b in payload["boards"]} == set(expect.values())

def test_sector_members_match_sqlite():

    con = _conn()
    try:
        payload = ex.export_sector(con)
        for board_code, member_rows in payload["members"].items():
            sql = con.execute(
                """SELECT code, name, price, change_percent FROM sector_member
                   WHERE board_code=? AND fetch_date=(
                       SELECT MAX(fetch_date) FROM sector_member WHERE board_code=?)
                   ORDER BY code ASC""",
                (board_code, board_code),
            ).fetchall()
            assert member_rows == [[m["code"], m["name"], m["price"], m["change_percent"]] for m in sql], \
                f"板块 {board_code} 成分股不一致"
    finally:
        con.close()

def test_sector_asset_matches_export():
    assert _load("sector_list.json") == _load_export(ex.export_sector)

def test_fund_flow_matches_sqlite():

    con = _conn()
    try:
        payload = ex.export_fund_flow(con)
        for code, rows in payload.items():
            sql = con.execute(
                """SELECT trade_date, main_net, main_ratio, super_net, big_net,
                          mid_net, small_net, source
                   FROM stock_fund_flow WHERE code=? ORDER BY trade_date DESC""",
                (code,),
            ).fetchall()
            expect = [[r[k] for k in ("trade_date", "main_net", "main_ratio", "super_net",
                                      "big_net", "mid_net", "small_net", "source")] for r in sql]
            assert rows == expect, f"资金流 {code} 不一致"
    finally:
        con.close()

def test_fund_flow_asset_matches_export():
    assert _load("fundflow_list.json") == _load_export(ex.export_fund_flow)

def test_sector_code_for_known_stocks():

    payload = _load("sector_list.json")
    con = _conn()
    try:
        for code in SAMPLE_CODES:
            sql = con.execute(
                """SELECT b.board_code FROM stock_info s JOIN sector_board b ON b.board_name=s.industry
                   WHERE s.code=? ORDER BY b.fetch_date DESC LIMIT 1""",
                (code,),
            ).fetchone()
            if sql is None:
                continue
            assert payload["stock_board"].get(code) == sql["board_code"], f"{code} 的板块映射不一致"
    finally:
        con.close()

def _load_export(fn):
    con = _conn()
    try:
        return fn(con)
    finally:
        con.close()

if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"PASS  {name}")
            except AssertionError as e:
                failures += 1
                print(f"FAIL  {name}: {e}")
    print()
    print("全部通过" if failures == 0 else f"{failures} 项失败")
    raise SystemExit(1 if failures else 0)
