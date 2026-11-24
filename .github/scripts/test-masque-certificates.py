import io
import runpy
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path


check = runpy.run_path(Path(__file__).with_name("check-masque-certificates.py"))["check_validity"]


class CertificateValidityTest(unittest.TestCase):
    def test_warning_and_error_boundaries(self):
        now = datetime(2026, 11, 24, tzinfo=timezone.utc)
        yesterday = now - timedelta(days=1)
        tomorrow = now + timedelta(days=1)
        for start, end, result, annotation in (
            (yesterday, now + timedelta(days=101), True, ""),
            (yesterday, now + timedelta(days=100), True, "::warning::"),
            (yesterday, now, False, "::error::"),
            (tomorrow, now + timedelta(days=101), False, "::error::"),
        ):
            with self.subTest(start=start, end=end), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(result, check("test-gateway", start, end, now, 100))
                self.assertTrue(output.getvalue().startswith(annotation) if annotation else not output.getvalue())


if __name__ == "__main__":
    unittest.main()
