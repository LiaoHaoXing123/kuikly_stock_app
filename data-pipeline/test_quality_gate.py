import os
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_stock_db as b

GOOD_COUNTS = {
    "stock_info": 5200,
    "stock_realtime": 5200,
    "stock_daily_kline": 300,
    "stock_minute": 200,
    "stock_order_book": 100,
    "stock_indicator": 200,
}
GOOD_DATE = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

def test_pass_when_all_ok():
    assert b.collect_quality_issues(GOOD_COUNTS, GOOD_DATE) == []

def test_fail_when_too_few_stocks():
    c = dict(GOOD_COUNTS); c["stock_info"] = 50
    assert any("stock_info" in i for i in b.collect_quality_issues(c, GOOD_DATE))

def test_fail_when_no_realtime():
    c = dict(GOOD_COUNTS); c["stock_realtime"] = 0
    assert any("stock_realtime" in i for i in b.collect_quality_issues(c, GOOD_DATE))

def test_fail_when_empty_date():
    assert any("latest_trade_date" in i for i in b.collect_quality_issues(GOOD_COUNTS, None))

def test_fail_when_date_too_old():
    old = (datetime.now() - timedelta(days=40)).strftime("%Y-%m-%d")
    assert any("天" in i for i in b.collect_quality_issues(GOOD_COUNTS, old))

def test_fail_when_no_kline():
    c = dict(GOOD_COUNTS); c["stock_daily_kline"] = 0
    assert any("stock_daily_kline" in i for i in b.collect_quality_issues(c, GOOD_DATE))

def test_fail_when_no_indicator():
    c = dict(GOOD_COUNTS); c["stock_indicator"] = 0
    assert any("stock_indicator" in i for i in b.collect_quality_issues(c, GOOD_DATE))

def test_index_warn_when_missing():
    c = dict(GOOD_COUNTS)
    warns = b.collect_index_warnings(c)
    assert any("index_realtime" in i for i in warns)
    assert any("index_daily_kline" in i for i in warns)

def test_index_warn_quiet_when_ok():
    c = dict(GOOD_COUNTS, index_realtime=300, index_daily_kline=500)
    assert b.collect_index_warnings(c) == []

def test_index_missing_does_not_block_publish():

    assert b.collect_quality_issues(GOOD_COUNTS, GOOD_DATE) == []

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
