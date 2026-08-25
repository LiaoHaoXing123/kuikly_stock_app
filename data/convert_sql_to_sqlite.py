# -*- coding: utf-8 -*-
"""
kuikly_stock_app 数据更新脚本（方案 B：SQLite 打进 APK）

把 mock_data/kuikly_stock_demo.sql (MySQL dump) 转成 SQLite，
并（--deploy 时）把 stock.db 复制到 shared/src/commonMain/assets/ 供 APK 打包。

== 这就是"单独的数据修改点" ==
更新数据流程：
  1. 准备好新的 kuikly_stock_demo.sql（用 mock_data/akshare_fetch_all.py 抓取生成）
  2. python data/convert_sql_to_sqlite.py --deploy
  3. 重新构建/安装 APK

依赖：仅 Python 标准库（sqlite3 / re）
"""
import os
import re
import sys
import shutil
import sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))                 # kuikly_stock_app/data
APP_ROOT = os.path.dirname(HERE)                                  # kuikly_stock_app
# SQL dump 默认位置：仓库外 sibling 的 mock_data（你机器上的 work/ 结构）
DEFAULT_SQL = os.path.normpath(os.path.join(HERE, "..", "..", "mock_data", "kuikly_stock_demo.sql"))
DEFAULT_DB = os.path.join(HERE, "stock.db")
ASSETS_DB = os.path.join(APP_ROOT, "shared", "src", "commonMain", "assets", "stock.db")

SQLITE_DDL = [
    "CREATE TABLE IF NOT EXISTS stock_info (code TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL DEFAULT '', industry TEXT, plate TEXT, list_date TEXT)",
    "CREATE TABLE IF NOT EXISTS stock_realtime (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, price REAL, change REAL, change_percent REAL, open REAL, pre_close REAL, high REAL, low REAL, volume INTEGER, amount REAL, turnover_rate REAL, volume_ratio REAL, limit_up REAL, limit_down REAL, pe_ttm REAL, pb REAL, total_market_cap REAL, circulate_market_cap REAL, update_time TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS stock_daily_kline (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, trade_date TEXT NOT NULL, open REAL, close REAL, high REAL, low REAL, volume INTEGER, amount REAL, UNIQUE(code, trade_date))",
    "CREATE TABLE IF NOT EXISTS stock_minute (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, trade_date TEXT NOT NULL, time TEXT NOT NULL, price REAL, avg_price REAL, volume INTEGER, UNIQUE(code, trade_date, time))",
    "CREATE TABLE IF NOT EXISTS stock_order_book (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, update_time TEXT NOT NULL, bid1_price REAL, bid1_vol INTEGER, bid2_price REAL, bid2_vol INTEGER, bid3_price REAL, bid3_vol INTEGER, bid4_price REAL, bid4_vol INTEGER, bid5_price REAL, bid5_vol INTEGER, ask1_price REAL, ask1_vol INTEGER, ask2_price REAL, ask2_vol INTEGER, ask3_price REAL, ask3_vol INTEGER, ask4_price REAL, ask4_vol INTEGER, ask5_price REAL, ask5_vol INTEGER, commission_ratio REAL)",
    "CREATE TABLE IF NOT EXISTS stock_indicator (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, trade_date TEXT NOT NULL, ma5 REAL, ma10 REAL, ma20 REAL, dif REAL, dea REAL, macd REAL, rsi6 REAL, kdj_k REAL, kdj_d REAL, kdj_j REAL, UNIQUE(code, trade_date))",
]

INSERT_RE = re.compile(r"^INSERT INTO `(\w+)` VALUES \((.*)\);\s*$")


def parse_values(body):
    values, i, n = [], 0, len(body)
    while i < n:
        ch = body[i]
        if ch in " ,":
            i += 1
            continue
        if ch == "'":
            buf, i = [], i + 1
            while i < n:
                c = body[i]
                if c == "\\" and i + 1 < n and body[i + 1] in ("'", "\\"):
                    buf.append(body[i + 1]); i += 2
                elif c == "'":
                    if i + 1 < n and body[i + 1] == "'":
                        buf.append("'"); i += 2
                    else:
                        i += 1; break
                else:
                    buf.append(c); i += 1
            values.append("".join(buf))
        else:
            j = i
            while j < n and body[j] != ",":
                j += 1
            tok = body[i:j].strip()
            if tok.upper() == "NULL":
                values.append(None)
            elif tok.isdigit() or (tok.startswith("-") and tok[1:].isdigit()):
                values.append(int(tok))
            elif _is_num(tok):
                values.append(float(tok))
            else:
                values.append(tok)
            i = j
    return values


def _is_num(s):
    try:
        float(s); return True
    except ValueError:
        return False


def convert(sql_path, db_path):
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    if os.path.exists(db_path):
        os.remove(db_path)
    conn = sqlite3.connect(db_path)
    try:
        cur = conn.cursor()
        for ddl in SQLITE_DDL:
            cur.execute(ddl)
        counts, batch = {}, {}
        with open(sql_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line.startswith("INSERT INTO"):
                    continue
                m = INSERT_RE.match(line)
                if not m:
                    continue
                table, body = m.group(1), m.group(2)
                vals = parse_values(body)
                batch.setdefault(table, []).append(vals)
                if len(batch[table]) >= 2000:
                    _flush(cur, table, batch[table])
                    counts[table] = counts.get(table, 0) + 2000
                    batch[table] = []
        for t, rows in batch.items():
            if rows:
                _flush(cur, t, rows)
                counts[t] = counts.get(t, 0) + len(rows)
        conn.commit()
        for ddl in SQLITE_DDL:
            tn = re.search(r"CREATE TABLE IF NOT EXISTS (\w+)", ddl).group(1)
            cur.execute(f"CREATE INDEX IF NOT EXISTS idx_{tn}_code ON {tn}(code)")
        conn.commit()
        return counts
    finally:
        conn.close()


def _flush(cur, table, rows):
    nc = len(rows[0])
    cur.executemany(f"INSERT OR REPLACE INTO {table} VALUES ({','.join(['?']*nc)})", rows)


def main():
    sql = DEFAULT_SQL
    deploy = "--deploy" in sys.argv
    for i, a in enumerate(sys.argv):
        if a == "--sql" and i + 1 < len(sys.argv):
            sql = sys.argv[i + 1]
    print(f"[1] 解析 SQL: {sql}")
    counts = convert(sql, DEFAULT_DB)
    print(f"    -> {DEFAULT_DB}")
    for t, c in sorted(counts.items()):
        print(f"    {t:<20} {c:>6} rows")
    if deploy:
        os.makedirs(os.path.dirname(ASSETS_DB), exist_ok=True)
        shutil.copy2(DEFAULT_DB, ASSETS_DB)
        print(f"[2] 已复制到 assets 供 APK 打包: {ASSETS_DB}")
    else:
        print(f"[2] 跳过 assets 复制（加 --deploy 一并复制到 assets 供打包）")


if __name__ == "__main__":
    main()
