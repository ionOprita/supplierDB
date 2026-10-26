import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs, urlsplit

from openpyxl import load_workbook


SCRIPT_PATH = Path(__file__).with_name("alibaba-bot.py")
SPEC = importlib.util.spec_from_file_location("alibaba_bot", SCRIPT_PATH)
bot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bot)

EXPECTED_SEARCH_TEXTS = [
    "cordless chainsaw",
    "cordless electric chain saw",
    "gasoline generator",
    "petrol generator",
]
EXPECTED_COLUMNS = [
    "companyId", "url", "name", "city", "country", "verified",
    "manufacturer_type", "years", "staff", "factory_size(m\u00b2)", "worth(USD)",
    "rating", "reviews",
]


def read_workbook_rows(path):
    workbook = load_workbook(path, read_only=True, data_only=True)
    try:
        return list(workbook.active.iter_rows(values_only=True))
    finally:
        workbook.close()


class AlibabaBotTests(unittest.TestCase):
    def setUp(self):
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        self.output_dir = Path(temporary_directory.name) / "output"
        patches = [
            mock.patch.object(bot, "OUTPUT_DIR", str(self.output_dir)),
            mock.patch.object(
                bot, "PROFILE_DIR_FALLBACK", str(Path(temporary_directory.name) / "profile")
            ),
            mock.patch("builtins.print"),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def test_single_configured_search_creates_one_workbook(self):
        phrase = "only configured search"
        data = [{"companyId": "single-id", "url": "https://supplier.example/", "name": phrase}]
        with (
            mock.patch.object(bot, "SEARCH_TEXTS", [phrase]),
            mock.patch.object(bot, "scrape", return_value=data) as scrape_mock,
        ):
            self.assertEqual(bot.main(), 0)

        scrape_mock.assert_called_once_with(phrase)
        self.assertEqual(len(list(self.output_dir.glob("*.xlsx"))), 1)
        rows = read_workbook_rows(bot.build_output_path(1, phrase))
        self.assertEqual(rows[0], tuple(EXPECTED_COLUMNS))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1][:3], ("single-id", "https://supplier.example/", phrase))

    def test_search_url_round_trips_plain_text_without_changing_filters(self):
        for phrase in [
            "baby play mat",
            'C++ & tools?color=red#mat / 100% "safe"',
            "m\u0103nu\u0219i \u5a74\u513f",
        ]:
            with self.subTest(phrase=phrase):
                url = urlsplit(bot.build_search_url(phrase))
                query = parse_qs(url.query)
                self.assertEqual(url.scheme, "https")
                self.assertEqual(url.netloc, "www.alibaba.com")
                self.assertEqual(url.path, "/search/page")
                self.assertEqual(url.fragment, "")
                self.assertEqual(query["SearchText"], [phrase])
                self.assertEqual(query["SearchScene"], ["suppliers"])
                self.assertEqual(query["verifiedManufactory"], ["true"])
                self.assertEqual(
                    set(query), {"spm", "SearchScene", "SearchText", "verifiedManufactory"}
                )

    def test_output_names_handle_collisions_unicode_and_long_phrases(self):
        cases = [
            (1, "Baby & Mat", "alibaba_01_baby_mat_suppliers.xlsx"),
            (2, "Baby / Mat", "alibaba_02_baby_mat_suppliers.xlsx"),
            (3, "\u5a74\u513f <>:\"/\\|?*", "alibaba_03_search_suppliers.xlsx"),
            (4, "a" * 100, "alibaba_04_" + "a" * 80 + "_suppliers.xlsx"),
        ]
        paths = []
        for index, phrase, expected_name in cases:
            with self.subTest(phrase=phrase):
                path = Path(bot.build_output_path(index, phrase))
                self.assertEqual(path.parent, self.output_dir)
                self.assertEqual(path.name, expected_name)
                paths.append(path)
        self.assertEqual(len(set(paths)), len(cases))

    def test_batch_exports_four_separate_workbooks_in_order_with_per_file_dedup(self):
        completed_searches = []

        def scrape(phrase):
            index = len(completed_searches) + 1
            if completed_searches:
                previous_path = bot.build_output_path(index - 1, completed_searches[-1])
                self.assertTrue(Path(previous_path).is_file())
            completed_searches.append(phrase)
            return [
                {"companyId": "shared-id", "url": "https://supplier.example/", "name": phrase},
                {"companyId": "shared-id", "url": "https://supplier.example/", "name": "duplicate"},
            ]

        with (
            mock.patch.object(bot, "SEARCH_TEXTS", EXPECTED_SEARCH_TEXTS),
            mock.patch.object(bot, "scrape", side_effect=scrape) as scrape_mock,
        ):
            self.assertEqual(bot.main(), 0)

        self.assertEqual(scrape_mock.call_args_list, [mock.call(p) for p in EXPECTED_SEARCH_TEXTS])
        self.assertEqual(len(list(self.output_dir.glob("*.xlsx"))), 4)
        for index, phrase in enumerate(EXPECTED_SEARCH_TEXTS, start=1):
            with self.subTest(phrase=phrase):
                rows = read_workbook_rows(bot.build_output_path(index, phrase))
                self.assertEqual(rows[0], tuple(EXPECTED_COLUMNS))
                self.assertEqual(len(rows), 2)
                self.assertEqual(rows[1][:3], ("shared-id", "https://supplier.example/", phrase))

    def test_failed_second_search_does_not_prevent_third_export(self):
        phrases = EXPECTED_SEARCH_TEXTS[:3]
        with (
            mock.patch.object(bot, "SEARCH_TEXTS", phrases),
            mock.patch.object(bot, "scrape", side_effect=[[], RuntimeError("navigation failed"), []])
            as scrape_mock,
        ):
            self.assertEqual(bot.main(), 1)

        self.assertEqual(scrape_mock.call_args_list, [mock.call(p) for p in phrases])
        self.assertTrue(Path(bot.build_output_path(1, phrases[0])).is_file())
        self.assertFalse(Path(bot.build_output_path(2, phrases[1])).exists())
        self.assertTrue(Path(bot.build_output_path(3, phrases[2])).is_file())
        self.assertEqual(len(list(self.output_dir.glob("*.xlsx"))), 2)

    def test_empty_search_result_creates_headers_only_workbook(self):
        phrase = "empty result"
        with (
            mock.patch.object(bot, "SEARCH_TEXTS", [phrase]),
            mock.patch.object(bot, "scrape", return_value=[]),
        ):
            self.assertEqual(bot.main(), 0)

        rows = read_workbook_rows(bot.build_output_path(1, phrase))
        self.assertEqual(rows, [tuple(EXPECTED_COLUMNS)])

    def test_invalid_search_configuration_stops_before_setup_or_scraping(self):
        for phrases in [[], ["  "], ["valid phrase", ""], ["valid phrase", None], "one phrase"]:
            with self.subTest(phrases=phrases):
                with (
                    mock.patch.object(bot, "SEARCH_TEXTS", phrases),
                    mock.patch.object(bot.importlib, "import_module") as import_module,
                    mock.patch.object(bot, "scrape") as scrape_mock,
                ):
                    self.assertEqual(bot.main(), 1)
                import_module.assert_not_called()
                scrape_mock.assert_not_called()
                self.assertFalse(self.output_dir.exists())

    def test_missing_excel_dependency_stops_before_scraping(self):
        executable = r"C:\Program Files\Python 3.14\python.exe"
        with (
            mock.patch("builtins.print") as print_mock,
            mock.patch.object(bot.sys, "executable", executable),
            mock.patch.object(bot, "scrape") as scrape_mock,
            mock.patch.object(
                bot.importlib, "import_module", side_effect=ModuleNotFoundError("openpyxl")
            ) as import_module,
        ):
            self.assertEqual(bot.main(), 1)

        import_module.assert_called_once_with("openpyxl")
        requirements_path = Path(bot.__file__).with_name("requirements.txt")
        print_mock.assert_any_call(
            f'Install dependencies with: & "{executable}" -m pip install -r "{requirements_path}"'
        )
        scrape_mock.assert_not_called()
        self.assertFalse(self.output_dir.exists())

    def test_scrape_closes_browser_when_navigation_fails(self):
        driver = mock.Mock()
        phrase = "chainsaw + oil"
        with (
            mock.patch.object(bot, "make_driver", return_value=driver),
            mock.patch.object(bot, "robust_go", return_value=False) as robust_go,
            mock.patch.object(bot, "run_flow") as run_flow,
        ):
            with self.assertRaises(RuntimeError):
                bot.scrape(phrase)

        robust_go.assert_called_once_with(driver, bot.build_search_url(phrase))
        run_flow.assert_not_called()
        driver.quit.assert_called_once_with()

    def test_scrape_closes_browser_when_collection_fails(self):
        driver = mock.Mock()
        with (
            mock.patch.object(bot, "make_driver", return_value=driver),
            mock.patch.object(bot, "robust_go", return_value=True),
            mock.patch.object(bot, "run_flow", side_effect=RuntimeError("collection failed")),
        ):
            with self.assertRaisesRegex(RuntimeError, "collection failed"):
                bot.scrape("chainsaw")

        driver.quit.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
