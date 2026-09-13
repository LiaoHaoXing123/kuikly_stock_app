import sqlite3
import unittest
from datetime import datetime
import build_fund_flow as f

class FundFlowPipelineTest(unittest.TestCase):
    def test_unrelated_and_stale_rows_do_not_count_as_coverage(self):
        conn = sqlite3.connect(":memory:")
        self.addCleanup(conn.close)
        conn.execute(f.FUND_FLOW_DDL)
        for code, date in [("000001", "2026-09-09"), ("000002", "2026-08-01"), ("600000", "2026-09-09")]:
            conn.execute("INSERT INTO stock_fund_flow(code,trade_date) VALUES (?,?)", (code, date))
        coverage, _ = f.requested_coverage(conn, ["000001", "000002", "000003"], datetime(2026, 9, 10))
        self.assertEqual(1 / 3, coverage)
    def test_missing_main_value_is_not_zero_flow(self):
        self.assertEqual([], f.sina_rows("000001", [{"opendate": "2026-09-09", "r0_ratio": "0.2"}]))

    def test_nonfinite_values_are_rejected(self):
        self.assertEqual([], f.sina_rows("000001", [{"opendate": "2026-09-09", "r0_net": "NaN", "r0_ratio": "0.2"}]))
        self.assertIsNone(f._num(float("inf")))

    def test_ratio_conversion_keeps_negative_sign(self):
        row = f.sina_rows("000001", [{"opendate": "2026-09-09", "r0_net": "-123", "r0_ratio": "-0.1573"}])[0]
        self.assertEqual((-123.0, -15.73), row[2:4])

if __name__ == "__main__":
    unittest.main()
