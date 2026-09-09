#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
资金流向数据源验证探针（P3 步骤② L1 前置：落地代码前先验证数据源）。

对齐方案「硬性纠正②」——不假设第三方数据源成熟稳定，先验证四件事：
  1. 覆盖率：我们的 K 线关注列表里，有多少只能取到日频资金流？
  2. 更新时效：最新一条资金流日期 vs 最新 K 线交易日，是当日还是 T+1？
  3. 单位标定：主力净流入「净额」的量级（元 / 万元）与「净占比」是否为百分数？
  4. 失败降级：非法/停牌代码返回空 df 还是抛异常？

只读探测，不写库、不改 schema。输出结构化文本，供人工/AI 判定后再决定 L1 落地。
用法： data-pipeline/venv/Scripts/python.exe data-pipeline/probe_fund_flow.py
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

# 覆盖率抽样：跨市场代表（沪主板 / 深主板 / 创业板 / 科创板 / 深中小 / 北交所）。
# market 取值对齐 akshare stock_individual_fund_flow：sh / sz / bj。
SAMPLES = [
    ("600519", "sh", "贵州茅台·沪主板"),
    ("600000", "sh", "浦发银行·沪主板"),
    ("000001", "sz", "平安银行·深主板"),
    ("000002", "sz", "万科A·深主板"),
    ("300750", "sz", "宁德时代·创业板"),
    ("688981", "sh", "中芯国际·科创板"),
    ("002415", "sz", "海康威视·深中小"),
    ("899050", "bj", "北证50成分?·北交所探测"),
]

# 失败降级：非法/不存在代码
BAD_SAMPLES = [
    ("999999", "sz", "非法代码"),
    ("000000", "sz", "全零代码"),
]


def market_of(code: str) -> str:
    """与 build_stock_db.tx_symbol 同源的市场判定，供 L1 复用参考。"""
    c = str(code)
    if c.startswith(("4", "8")):
        return "bj"
    return "sh" if c.startswith(("6", "9", "5")) else "sz"


def probe_one(code, market, label):
    print(f"\n--- {label}  code={code} market={market} ---")
    try:
        df = ak.stock_individual_fund_flow(stock=code, market=market)
    except Exception as e:
        print(f"  [异常] {type(e).__name__}: {str(e)[:160]}")
        return None
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
    # 最新 3 行
    tail = df.tail(3)
    print("  最新 3 行:")
    for _, r in tail.iterrows():
        print("    " + " | ".join(f"{c}={r[c]}" for c in df.columns))
    return df


def main():
    print("### 1) 覆盖率 + 2) 时效 + 3) 单位标定 ###")
    ok = 0
    latest_dates = []
    main_amounts = []
    main_ratios = []
    first_cols = None
    for code, market, label in SAMPLES:
        df = probe_one(code, market, label)
        time.sleep(0.5)
        if df is None or df.empty:
            continue
        ok += 1
        if first_cols is None:
            first_cols = list(df.columns)
        # 日期列：优先「日期」
        date_col = next((c for c in df.columns if "日期" in str(c)), df.columns[0])
        try:
            latest_dates.append((code, str(df.iloc[-1][date_col])))
        except Exception:
            pass
        # 主力净流入-净额 / 净占比 列名探测
        amt_col = next((c for c in df.columns if "主力" in str(c) and ("净额" in str(c) or "净流入" == str(c).replace("主力", ""))), None)
        if amt_col is None:
            amt_col = next((c for c in df.columns if "主力" in str(c) and "占" not in str(c)), None)
        ratio_col = next((c for c in df.columns if "主力" in str(c) and "占" in str(c)), None)
        try:
            if amt_col:
                v = float(df.iloc[-1][amt_col])
                main_amounts.append((code, amt_col, v))
        except Exception:
            pass
        try:
            if ratio_col:
                v = float(df.iloc[-1][ratio_col])
                main_ratios.append((code, ratio_col, v))
        except Exception:
            pass

    print("\n" + "=" * 72)
    print(f"[覆盖率] {ok}/{len(SAMPLES)} 只样本取到非空资金流")
    print("[时效] 各样本最新资金流日期：")
    for code, d in latest_dates:
        print(f"    {code}: {d}")
    print("[单位标定] 主力净流入-净额（原始值，判断 元/万元 量级）：")
    for code, col, v in main_amounts:
        mag = abs(v)
        unit_guess = "元(约 %.2f 亿)" % (v / 1e8) if mag >= 1e6 else ("万元(约 %.2f 万)" % v if mag >= 1 else "?")
        print(f"    {code} [{col}] = {v}  → 猜测单位 {unit_guess}")
    print("[单位标定] 主力净流入-净占比（判断是否百分数）：")
    for code, col, v in main_ratios:
        print(f"    {code} [{col}] = {v}  → {'像百分数(%)' if abs(v) <= 100 else '疑似非百分数'}")

    print("\n### 4) 失败降级 ###")
    for code, market, label in BAD_SAMPLES:
        probe_one(code, market, label)
        time.sleep(0.5)

    print("\n" + "=" * 72)
    print("探针结束。请据此确认：列名/单位/时效/降级行为，再决定 L1 字段与转换。")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
