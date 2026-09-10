#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
官方行业板块数据源验证探针（官方板块联动 L1 前置：落地代码前先验证数据源）。

对齐 probe_fund_flow.py 的思路——不假设第三方数据源成熟稳定，先验证四件事：
  1. 板块列表：stock_board_industry_name_em() 是否自带涨跌幅/领涨股/总市值/换手率？
     列名、单位（涨跌幅是百分数还是小数？市值是元还是亿？）与板块总数各是多少？
  2. 成分股：stock_board_industry_cons_em(symbol=板块名) 的列名/数量/是否含实时涨跌幅？
  3. 更新时效：板块列表是否带日期/时间列？是当日快照还是历史沉淀？
  4. 失败降级：非法板块名返回空 df 还是抛异常？

只读探测，不写库、不改 schema。输出结构化文本，供人工/AI 判定后再决定 L1 落地字段与转换。
用法： data-pipeline/venv/Scripts/python.exe data-pipeline/probe_sector.py
"""
import sys
import time
import traceback

# 先复用 build_stock_db 的网络补丁（强制直连 + push2delay 镜像改写），否则东财通道被系统代理/原主机断开
try:
    import build_stock_db  # noqa: E402  # 仅执行其顶层补丁与配置，不触发 main()
except Exception as e:  # pragma: no cover
    print(f"[WARN] 无法 import build_stock_db 网络补丁: {e}")

try:
    import akshare as ak
    import pandas as pd
except Exception as e:  # pragma: no cover
    print(f"[FATAL] 无法 import akshare/pandas: {e}")
    sys.exit(2)

print(f"akshare 版本: {ak.__version__}")
print("=" * 72)


def dump_df(df, title, head_n=6):
    """统一打印 DataFrame 的结构：行数 / 列名 / dtypes / 前 N 行。"""
    print(f"\n--- {title} ---")
    if df is None:
        print("  [空] 返回 None")
        return None
    if not isinstance(df, pd.DataFrame):
        print(f"  [异常类型] 返回 {type(df)}")
        return None
    if df.empty:
        print("  [空] 返回空 DataFrame（0 行）")
        return df
    print(f"  行数: {len(df)}")
    print(f"  列名: {list(df.columns)}")
    print(f"  dtypes:\n{df.dtypes.to_string()}")
    head = df.head(head_n)
    print(f"  前 {min(head_n, len(df))} 行:")
    for _, r in head.iterrows():
        print("    " + " | ".join(f"{c}={r[c]}" for c in df.columns))
    return df


def find_col(cols, *keywords):
    """在列名里找第一个包含全部关键字的列（容忍不同 akshare 版本的中文列名差异）。"""
    for c in cols:
        s = str(c)
        if all(k in s for k in keywords):
            return c
    return None


def main():
    # ============ 1) 板块列表 ============
    print("### 1) 行业板块列表 stock_board_industry_name_em() ###")
    boards = None
    try:
        boards = ak.stock_board_industry_name_em()
    except Exception as e:
        print(f"  [异常] {type(e).__name__}: {str(e)[:160]}")
    boards = dump_df(boards, "板块列表", head_n=8)

    board_name_col = None
    board_code_col = None
    first_board_name = None
    if isinstance(boards, pd.DataFrame) and not boards.empty:
        cols = list(boards.columns)
        board_name_col = find_col(cols, "板块名称") or find_col(cols, "名称")
        board_code_col = find_col(cols, "板块代码") or find_col(cols, "代码")
        # 单位标定：涨跌幅是百分数还是小数？市值量级？
        chg_col = find_col(cols, "涨跌幅")
        mv_col = find_col(cols, "总市值") or find_col(cols, "市值")
        turn_col = find_col(cols, "换手")
        lead_col = find_col(cols, "领涨")
        print("\n  [关键列探测]")
        print(f"    板块名称列 = {board_name_col}")
        print(f"    板块代码列 = {board_code_col}")
        print(f"    涨跌幅列   = {chg_col}")
        print(f"    总市值列   = {mv_col}")
        print(f"    换手率列   = {turn_col}")
        print(f"    领涨股列   = {lead_col}")
        if chg_col:
            try:
                sample = pd.to_numeric(boards[chg_col], errors="coerce").dropna()
                if not sample.empty:
                    lo, hi = float(sample.min()), float(sample.max())
                    guess = "像百分数(如 3.2 表示 3.2%)" if abs(hi) > 1.5 or abs(lo) > 1.5 else "疑似小数(如 0.032)"
                    print(f"    涨跌幅取值范围 [{lo}, {hi}] → {guess}")
            except Exception:
                pass
        if mv_col:
            try:
                v = pd.to_numeric(boards[mv_col], errors="coerce").dropna()
                if not v.empty:
                    mx = float(v.max())
                    unit = "元" if mx >= 1e10 else ("亿" if mx < 1e5 else "?")
                    print(f"    总市值最大值 {mx} → 猜测单位 {unit}")
            except Exception:
                pass
        if board_name_col:
            first_board_name = str(boards.iloc[0][board_name_col])

    # ============ 2) 成分股 ============
    print("\n### 2) 板块成分股 stock_board_industry_cons_em(symbol=板块名) ###")
    if first_board_name:
        print(f"  取样板块（列表第 1 行）: {first_board_name}")
        cons = None
        try:
            cons = ak.stock_board_industry_cons_em(symbol=first_board_name)
        except Exception as e:
            print(f"  [异常] {type(e).__name__}: {str(e)[:160]}")
        cons = dump_df(cons, f"{first_board_name} 成分股", head_n=6)
        if isinstance(cons, pd.DataFrame) and not cons.empty:
            ccols = list(cons.columns)
            print("\n  [成分股关键列探测]")
            print(f"    代码列   = {find_col(ccols, '代码')}")
            print(f"    名称列   = {find_col(ccols, '名称')}")
            print(f"    涨跌幅列 = {find_col(ccols, '涨跌幅')}")
            print(f"    最新价列 = {find_col(ccols, '最新价') or find_col(ccols, '现价')}")
    else:
        print("  [跳过] 未能从板块列表拿到板块名，无法探测成分股")

    # ============ 3) 板块历史 K 线（可选，判断“板块涨跌趋势”是否可得）============
    print("\n### 3) 板块历史行情 stock_board_industry_hist_em()（可选）###")
    if first_board_name:
        hist = None
        try:
            hist = ak.stock_board_industry_hist_em(
                symbol=first_board_name, period="日k", adjust=""
            )
        except TypeError:
            # 不同 akshare 版本参数名差异，退化为仅传 symbol
            try:
                hist = ak.stock_board_industry_hist_em(symbol=first_board_name)
            except Exception as e:
                print(f"  [异常] {type(e).__name__}: {str(e)[:160]}")
        except Exception as e:
            print(f"  [异常] {type(e).__name__}: {str(e)[:160]}")
        dump_df(hist, f"{first_board_name} 日K", head_n=4)
    else:
        print("  [跳过] 无板块名")

    # ============ 4) 失败降级 ============
    print("\n### 4) 失败降级：非法板块名 ###")
    try:
        bad = ak.stock_board_industry_cons_em(symbol="不存在的板块XYZ")
        if bad is None:
            print("  非法板块名 → 返回 None")
        elif isinstance(bad, pd.DataFrame):
            print(f"  非法板块名 → 返回 DataFrame，空={bad.empty}，行数={len(bad)}")
        else:
            print(f"  非法板块名 → 返回 {type(bad)}")
    except Exception as e:
        print(f"  非法板块名 → 抛异常 {type(e).__name__}: {str(e)[:120]}")

    print("\n" + "=" * 72)
    print("探针结束。请据此确认：板块列表列名/涨跌幅单位/市值单位/时效、成分股列名、"
          "以及降级行为，再决定 L1（SQLite 表 + StockDb expect/actual + UI + AI 提示词）字段与转换。")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
