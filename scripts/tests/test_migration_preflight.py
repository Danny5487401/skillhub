"""Offline behavior tests for the read-only migration preflight."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "scripts/migration-preflight.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
PREFIX = "server/skillhub-app/src/main/resources/db/migration/"


class PreflightTest(unittest.TestCase):
    def test_status_and_rename_boundaries(self):
        path = PREFIX + "V001__new.sql"
        self.assertEqual(m.introduced_version(dict(filename=path, status="added")), 1)
        for status in ("modified", "removed", "copied"):
            self.assertIsNone(m.introduced_version(dict(filename=path, status=status)))
        self.assertIsNone(m.introduced_version(dict(filename=path, status="renamed",
                                                     previous_filename=PREFIX + "V1__old.sql")))
        self.assertEqual(m.introduced_version(dict(filename=path, status="renamed",
                                                     previous_filename=PREFIX + "V2__old.sql")), 1)
        for path in ("tests/V1__fake.sql", PREFIX + "V1_2__other.sql", PREFIX + "V1__new.sql/no"):
            self.assertIsNone(m.introduced_version(dict(filename=path, status="added")))

    def test_multiple_collisions_sorted_and_single_pr_not_collision(self):
        class API:
            def pages(self, path, limit=None):
                if path.startswith("/pulls?"):
                    return [dict(number=n, head=dict(sha=str(n))) for n in (9, 2)]
                return [dict(filename=PREFIX + f"V{v}__a.sql", status="added") for v in (20, 3, 3)]
            def get(self, path):
                return dict(head=dict(sha=path.split("/")[-1]), state="open", changed_files=3)
        collisions, errors, count = m.scan(API())
        self.assertEqual(list(collisions), [3, 20])
        self.assertEqual(errors, [])
        self.assertEqual(count, 2)
        report = m.summary(collisions, errors, count)
        self.assertLess(report.index("PR #2"), report.index("PR #9"))
        self.assertEqual(report.count("PR #2:"), 2)

    def test_incomplete_never_reports_clearance(self):
        report = m.summary({}, ["API HTTP 403"], 3)
        self.assertIn("scan incomplete", report)
        self.assertNotIn("No cross-PR", report)

    def test_untrusted_filename_is_text(self):
        attack = PREFIX + "V1__</pre><script>alert(1)</script>\n::error::x.sql"
        report = m.summary({1: {2: [attack], 3: [PREFIX + "V1__a.sql"]}}, [], 2)
        self.assertNotIn("<script>", report)
        self.assertIn("&lt;script&gt;", report)
        self.assertIn("&#10;", report)

    def test_pagination_and_limit(self):
        class API(m.GitHub):
            def __init__(self):
                self.calls = []
            def get(self, path):
                self.calls.append(path)
                return [dict()] * (100 if "page=1" in path else 1)
        api = API()
        self.assertEqual(len(api.pages("/pulls?state=open")), 101)
        self.assertEqual(api.calls[1], "/pulls?state=open&per_page=100&page=2")
        with self.assertRaises(m.ScanError):
            API().pages("/pulls/1/files", limit=100)


if __name__ == "__main__":
    unittest.main()
