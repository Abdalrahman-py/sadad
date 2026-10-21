"""Tests for tools/apk_budget.py. Run: python3 -m unittest tools/test_apk_budget.py"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import apk_budget  # noqa: E402

PROFILE = "assets/dexopt/baseline.prof"


class ApkBudgetTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def apk(self, name="app.apk", profile=True, padding=0):
        """A tiny zip standing in for an APK; `padding` bytes of incompressible data grow it."""
        path = os.path.join(self.dir.name, name)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
            z.writestr("classes.dex", "dex")
            if profile:
                z.writestr(PROFILE, "prof")
            if padding:
                z.writestr("assets/padding.bin", os.urandom(padding))
        return path

    def budget_file(self, text):
        path = os.path.join(self.dir.name, "apk-budget.txt")
        with open(path, "w") as f:
            f.write(text)
        return path

    def run_script(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = apk_budget.main(list(args))
        return code, out.getvalue() + err.getvalue()

    def test_an_apk_under_budget_with_the_profile_passes(self):
        apk = self.apk()
        budget = self.budget_file("%d\n" % (os.path.getsize(apk) + 100))
        code, output = self.run_script(apk, "--budget-file", budget)
        self.assertEqual(code, 0, output)

    def test_an_apk_exactly_at_budget_passes(self):
        apk = self.apk()
        budget = self.budget_file(str(os.path.getsize(apk)))
        code, output = self.run_script(apk, "--budget-file", budget)
        self.assertEqual(code, 0, output)

    def test_an_apk_over_budget_fails_and_names_both_numbers(self):
        apk = self.apk(padding=4000)
        actual = os.path.getsize(apk)
        budget = self.budget_file("1000")
        code, output = self.run_script(apk, "--budget-file", budget)
        self.assertNotEqual(code, 0)
        self.assertIn(str(actual), output)
        self.assertIn("1000", output)

    def test_an_apk_without_the_baseline_profile_fails(self):
        apk = self.apk(profile=False)
        budget = self.budget_file(str(os.path.getsize(apk) + 100))
        code, output = self.run_script(apk, "--budget-file", budget)
        self.assertNotEqual(code, 0)
        self.assertIn(PROFILE, output)

    def test_a_missing_file_fails(self):
        budget = self.budget_file("1000")
        missing = os.path.join(self.dir.name, "nope.apk")
        code, output = self.run_script(missing, "--budget-file", budget)
        self.assertNotEqual(code, 0)
        self.assertIn("nope.apk", output)

    def test_a_file_that_is_not_a_zip_fails(self):
        path = os.path.join(self.dir.name, "broken.apk")
        with open(path, "wb") as f:
            f.write(b"not a zip")
        budget = self.budget_file("1000")
        code, _ = self.run_script(path, "--budget-file", budget)
        self.assertNotEqual(code, 0)

    def test_a_budget_file_that_is_not_one_whole_number_fails(self):
        apk = self.apk()
        for text in ("", "abc", "1000 2000", "-5", "0", "1.5"):
            with self.subTest(text=text):
                budget = self.budget_file(text)
                code, _ = self.run_script(apk, "--budget-file", budget)
                self.assertNotEqual(code, 0)

    def test_the_committed_budget_is_one_positive_number(self):
        self.assertGreater(apk_budget.read_budget(apk_budget.DEFAULT_BUDGET_FILE), 0)


if __name__ == "__main__":
    unittest.main()
