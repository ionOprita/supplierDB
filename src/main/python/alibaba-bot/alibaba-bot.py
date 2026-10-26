# scrapper_final_textheuristics.py
# Alibaba suppliers scraper — robust “Next until stop” pagination (cap 1000)
# Solid wait-for-cards routine to handle lazy rendering / virtualization.
# Includes reviews column + cleaned city/staff/factory_size/worth fields.

import importlib
import sys
import os, time, re, json, pandas as pd
import undetected_chromedriver as uc
from urllib.parse import quote_plus
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import (
    NoSuchElementException, TimeoutException, WebDriverException,
    ElementClickInterceptedException, ElementNotInteractableException
)

# ========== CONFIG ==========
SEARCH_TEXTS = [
    "cordless chainsaw",
    # "cordless electric chain saw",
    # "gasoline generator",
    # "petrol generator",
]

PROFILE_DIR_FALLBACK  = os.path.abspath("selenium_profile")
PROFILE_NAME_FALLBACK = "Default"

HEADLESS = False
WAIT_FOR_LOGIN = True

WAIT_SECS           = 25
PAGE_LOAD_TIMEOUT   = 60
SCROLL_PAUSE        = 0.6
MAX_SCROLL_ROUNDS   = 6
AFTER_NAV_PAUSE     = 5.0
MAX_PAGES_CAP       = 1000
OUTPUT_DIR          = r"C:\Users\Oprita\Desktop\Scrapper"
OUTPUT_COLUMNS = [
    "companyId","url","name","city","country","verified",
    "manufacturer_type","years","staff","factory_size(m²)","worth(USD)",
    "rating","reviews"
]
# ===========================

# Card + pagination
CARD_XPATH = '//*[@data-supplier-card="true"]'
CUR_PAGE_LABEL = '//span[contains(@class,"pagination-item") and @aria-current="page"]'

NEXT_XPATHS = [
    '//span[contains(@class,"pagination-item") and @aria-current="page"]/ancestor::a[1]/following-sibling::a[1][not(contains(@class,"disabled")) and not(@aria-disabled="true")]',
    '//*[@id="root"]//div[contains(@class,"searchx-pagination")]//a[span[contains(@class,"pagination-item") and contains(@class,"next")] and not(contains(@class,"disabled")) and not(@aria-disabled="true")]]',
    '//button[contains(@class,"pagination-item") and contains(@class,"next") and not(@disabled)]',
    '//a[contains(@class,"next") and not(contains(@class,"disabled"))]',
    '//*[@id="root"]//a[@aria-label="Next" and not(contains(@class,"disabled"))]',
]

# ---------- helpers ----------
def build_search_url(search_text):
    return (
        "https://www.alibaba.com/search/page"
        "?spm=a2700.supplier_search.the-new-header_fy23_pc_search_bar.keydown__Enter"
        f"&SearchScene=suppliers&SearchText={quote_plus(search_text)}&verifiedManufactory=true"
    )

def build_output_path(index, search_text):
    slug = re.sub(r"[^a-z0-9]+", "_", search_text.lower()).strip("_")[:80] or "search"
    filename = f"alibaba_{index:02d}_{slug}_suppliers.xlsx"
    return os.path.join(OUTPUT_DIR, filename)

def make_driver(user_data_dir, profile_name, label):
    print(f"\n[driver] launching Chrome with profile: {label}")
    opts = uc.ChromeOptions()
    if HEADLESS:
        opts.add_argument("--headless=new")
    opts.add_argument(f"--user-data-dir={user_data_dir}")
    opts.add_argument(f"--profile-directory={profile_name}")
    opts.add_argument("--disable-blink-features=AutomationControlled")
    opts.add_argument("--no-first-run")
    opts.add_argument("--no-default-browser-check")
    opts.add_argument("--window-size=1500,1100")
    opts.add_argument("--lang=en-US,en")
    opts.add_argument("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
    d = uc.Chrome(options=opts)
    d.set_page_load_timeout(PAGE_LOAD_TIMEOUT)
    return d

def robust_go(driver, url, attempts=3):
    for _ in range(attempts):
        try:
            driver.get(url); time.sleep(2)
            if "alibaba.com" in (driver.current_url or "").lower():
                time.sleep(AFTER_NAV_PAUSE)
                return True
        except WebDriverException:
            pass
        try:
            driver.execute_script("window.location.href = arguments[0];", url); time.sleep(2)
            if "alibaba.com" in (driver.current_url or "").lower():
                time.sleep(AFTER_NAV_PAUSE)
                return True
        except WebDriverException:
            pass
        time.sleep(1)
    return False

def scroll_to_bottom(driver, max_rounds=MAX_SCROLL_ROUNDS, pause=SCROLL_PAUSE):
    last = driver.execute_script("return document.body.scrollHeight;")
    for _ in range(max_rounds):
        driver.execute_script("window.scrollTo(0, document.body.scrollHeight);")
        time.sleep(pause)
        new = driver.execute_script("return document.body.scrollHeight;")
        if new == last:
            break
        last = new

def scroll_nudge(driver):
    # gentle nudges to trigger virtual list mounts
    for y in (0, 600, 1200, 0):
        driver.execute_script("window.scrollTo(0, arguments[0]);", y)
        time.sleep(0.25)

def wait_for_cards(driver, timeout=18):
    """Actively wait for supplier tiles to mount; return list of elements."""
    end = time.time() + timeout
    last_count = -1
    while time.time() < end:
        cards = driver.find_elements(By.XPATH, CARD_XPATH)
        if cards:
            return cards
        # Nudge + quick bottom scroll burst
        scroll_nudge(driver)
        for _ in range(2):
            driver.execute_script("window.scrollTo(0, document.body.scrollHeight);"); time.sleep(0.4)
        # If count changes (even from 0), give another chance
        cnt = len(cards)
        if cnt != last_count:
            last_count = cnt
            time.sleep(0.4)
    # Final attempt
    return driver.find_elements(By.XPATH, CARD_XPATH)

def get_page_label(driver):
    try:
        el = driver.find_element(By.XPATH, CUR_PAGE_LABEL)
        txt = (el.text or "").strip()
        return int(re.search(r'\d+', txt).group(0)) if re.search(r'\d+', txt) else None
    except Exception:
        return None

def find_next(driver):
    for xp in NEXT_XPATHS:
        try:
            el = driver.find_element(By.XPATH, xp)
            if el.is_displayed():
                c = (el.get_attribute("class") or "").lower()
                if "disabled" in c or el.get_attribute("aria-disabled") == "true":
                    continue
                return el
        except Exception:
            continue
    return None

# ---- cleaners ----
def clean_city_trailing_comma(s: str) -> str:
    return (s or "").strip().rstrip(",")

def clean_staff_digits_from_inner(inner: str) -> str:
    m = re.search(r'(\d[\d,]*)\s*\+?\s*staff', inner or "", flags=re.I)
    if not m:
        m = re.search(r'\b(\d[\d,]*)\b(?=.*staff)', inner or "", flags=re.I)
    return re.sub(r'[^\d]', '', m.group(1)) if m else ""

def clean_factory_size_m2_from_inner(inner: str) -> str:
    m = re.search(r'(\d[\d,]*)\s*\+?\s*(?:m²|sqm|sq\.?\s*m)', inner or "", flags=re.I)
    if not m:
        m = re.search(r'\b(\d[\d,]*)\b(?=.*m²|sqm|sq\.?\s*m)', inner or "", flags=re.I)
    return re.sub(r'[^\d]', '', m.group(1)) if m else ""

def clean_worth_usd_from_inner(inner: str) -> str:
    m = re.search(r'US\s*\$\s*([\d,]+)\+?', inner or "", flags=re.I)
    if not m:
        m = re.search(r'\b([\d,]+)\b(?=.*US\s*\$)', inner or "", flags=re.I)
    return re.sub(r'[^\d]', '', m.group(1)) if m else ""

def clean_years(inner: str) -> str:
    m = re.search(r'(\d{1,3})\s*yr[s]?\b', inner or "", flags=re.I)
    return m.group(1) if m else ""

def clean_rating(inner: str) -> str:
    m = re.search(r'(\d+(?:\.\d+)?)\s*/\s*5\b', inner or "")
    return m.group(1) if m else ""

# ---------- robust locators for URL + name ----------
ANCHOR_XPS = [
    './/a[contains(@href,"company_profile/feedback")]',
    './/a[contains(@href,"company-profile/feedback")]',
]
NAME_XPS = [
    './/a[contains(@href,"company_profile/feedback")]/@title',
    './/a[contains(@href,"company_profile/feedback")]/preceding::span[@dir="auto" and @title][1]/@title',
    './/div[contains(@class,"card-title")]//a[@title]/@title',
    './/h3//a[@title]/@title',
]

def find_first(card, xps):
    for xp in xps:
        try:
            if xp.endswith("/@title"):
                real = xp.replace("/@title", "")
                el = card.find_element(By.XPATH, real)
                val = el.get_attribute("title") or ""
                if val.strip():
                    return el, val.strip()
                continue
            el = card.find_element(By.XPATH, xp)
            if el:
                return el, None
        except NoSuchElementException:
            continue
    return None, None

# ---------- VERIFIED detection (DOM-first, text-fallback) ----------
VERIFIED_DOM_XPS = [
    './/*[@aria-label and contains(translate(@aria-label,"VERIFIED","verified"),"verified")]',
    './/*[@title and contains(translate(@title,"VERIFIED","verified"),"verified")]',
    './/*[@data-title and contains(translate(@data-title,"VERIFIED","verified"),"verified")]',
    './/img[@alt and contains(translate(@alt,"VERIFIED","verified"),"verified")]',
    './/*[contains(translate(@class,"VERIFIED","verified"),"verified")]',
    './/*[@role="img" and (@aria-label or @title) and contains(translate(@aria-label,"VERIFIED","verified"),"verified")]',
]

def is_verified(card, inner_text):
    for xp in VERIFIED_DOM_XPS:
        try:
            el = card.find_element(By.XPATH, xp)
            if el and el.is_displayed():
                return True
        except NoSuchElementException:
            continue
        except Exception:
            continue
    if re.search(r'\bverified\b', inner_text or "", flags=re.I):
        return True
    return False

# ---------- reviews ----------
def extract_reviews(card):
    for xp in [
        './/span[contains(translate(.,"REVIEWS","reviews"),"reviews")]',
        './/a[contains(translate(.,"REVIEWS","reviews"),"reviews")]',
        './/*[contains(@class,"review")]',
    ]:
        try:
            el = card.find_element(By.XPATH, xp)
            txt = (el.text or "").strip()
            m = re.search(r'(\d+)\s*reviews', txt, flags=re.I)
            if m:
                return m.group(1)
        except NoSuchElementException:
            continue
    txt = (card.get_attribute("innerText") or "").strip()
    m = re.search(r'(\d+)\s*reviews', txt, flags=re.I)
    return m.group(1) if m else ""

# ---------- city & country (DOM-based, heuristic) ----------
def extract_city_country(card):
    try:
        flag = card.find_element(By.XPATH, './/img[contains(@alt,"countryFlag")]')

        try:
            prev_spans = flag.find_elements(By.XPATH, 'preceding-sibling::span[normalize-space()][position()<=5]')
        except Exception:
            prev_spans = []

        city_candidate = ""
        for sp in prev_spans:
            txt = (sp.text or "").strip()
            if not txt:
                continue
            if len(txt) <= 24 and not re.search(r'\b(ltd|co\.?|company|trading|toys|reviews?)\b', txt, re.I):
                city_candidate = txt
                break
            if txt.endswith(","):
                city_candidate = txt
                break

        country_candidate = ""
        try:
            next_span = flag.find_element(By.XPATH, 'following-sibling::span[normalize-space()][1]')
            country_candidate = (next_span.text or "").strip()
        except NoSuchElementException:
            pass

        if city_candidate or country_candidate:
            return clean_city_trailing_comma(city_candidate), country_candidate
    except NoSuchElementException:
        pass

    # Fallbacks
    try:
        city_el = card.find_element(By.XPATH, './/span[contains(@class,"city")][normalize-space()]')
        city = clean_city_trailing_comma(city_el.text.strip())
    except NoSuchElementException:
        city = ""

    try:
        country_el = card.find_element(By.XPATH, './/span[contains(@class,"country")]/span[contains(@class,"label")][normalize-space()]')
        country = country_el.text.strip()
    except NoSuchElementException:
        country = ""

    if not (city and country):
        inner = (card.get_attribute("innerText") or "").strip()
        header_text = " ".join(inner.splitlines()[:8])
        m_loc = re.search(r'([A-Za-z][A-Za-z\.\-\s&]+?),\s*([A-Z]{2})\b', header_text)
        if m_loc:
            city = city or clean_city_trailing_comma(m_loc.group(1).strip())
            country = country or m_loc.group(2).strip()

    return city, country

# ---------- extractor ----------
def extract_from_card(card):
    url, name = "", ""
    a, _ = find_first(card, ANCHOR_XPS)
    if a:
        url = (a.get_attribute("href") or "").strip()

    _, name_attr = find_first(card, NAME_XPS)
    if name_attr:
        name = name_attr
    if not name and a:
        t = a.get_attribute("title")
        if t: name = t.strip()

    if not url:
        return {}

    company_id = ""
    try:
        holder = card
        raw = holder.get_attribute("data-dot-params") or ""
        if not raw:
            holder = card.find_element(By.XPATH, "./ancestor-or-self::*[@data-dot-params][1]")
            raw = holder.get_attribute("data-dot-params") or ""
        if raw:
            m = re.search(r'"companyId"\s*:\s*"?(?P<cid>\d+)"?', raw)
            if m:
                company_id = m.group("cid")
    except Exception:
        pass

    city, country = extract_city_country(card)
    inner = (card.get_attribute("innerText") or "").strip()

    verified = "true" if is_verified(card, inner) else "false"
    years   = clean_years(inner)
    staff   = clean_staff_digits_from_inner(inner)
    size    = clean_factory_size_m2_from_inner(inner)
    worth   = clean_worth_usd_from_inner(inner)
    rating  = clean_rating(inner)
    reviews = extract_reviews(card)

    manufacturer_type = ""
    mtype = re.search(r'\b(Custom Manufacturer|Manufacturer|Trading Company|Factory|factory)\b', inner, flags=re.I)
    if mtype:
        mt = mtype.group(1)
        manufacturer_type = "Factory" if mt.lower() == "factory" else mt

    return {
        "companyId": company_id,
        "url": url,
        "name": name,
        "city": city,
        "country": country,
        "verified": verified,
        "manufacturer_type": manufacturer_type,
        "years": years,
        "staff": staff,                 # digits only
        "factory_size(m²)": size,      # digits only
        "worth(USD)": worth,           # digits only
        "rating": rating,
        "reviews": reviews,
    }

# ---------- main flow (NEXT-until-stop) ----------
def run_flow(driver):
    if WAIT_FOR_LOGIN:
        print("\n=== ACTION === Handle cookie/login/captcha if needed in Chrome.")
        input("Press ENTER here when the supplier list is visible…\n")

    rows, seen_keys = [], set()

    def first_card_key():
        try:
            first = wait_for_cards(driver, timeout=8)
            first = first[0] if first else None
            if not first:
                return ""
        except Exception:
            return ""
        d = extract_from_card(first)
        if d:
            return (d.get("companyId","") or "") + "|" + (d.get("url","") or "")
        return ""

    # Initial page
    scroll_to_bottom(driver)
    cards = wait_for_cards(driver, timeout=12)
    page_label = get_page_label(driver) or 1
    print(f"[page] label {page_label}")
    print(f"[page] found {len(cards)} cards.")

    for c in cards:
        d = extract_from_card(c)
        if not d: continue
        key = (d["companyId"] or "") + "|" + d["url"]
        if key in seen_keys: continue
        rows.append(d); seen_keys.add(key)

    prev_key = first_card_key()
    pages_done = 1

    while pages_done < MAX_PAGES_CAP:
        nxt = find_next(driver)
        if not nxt:
            print("[page] Next not found or disabled — stopping.")
            break

        try:
            driver.execute_script("arguments[0].scrollIntoView({block:'center'});", nxt)
            time.sleep(0.2)
            try:
                nxt.click()
            except (ElementClickInterceptedException, ElementNotInteractableException):
                driver.execute_script("arguments[0].click();", nxt)
        except Exception as e:
            print("[page] Next click failed — stopping.", e)
            break

        # Wait for page change (label or first-card key)
        changed = False
        for _ in range(80):  # ~24s max
            time.sleep(0.3)
            new_label = get_page_label(driver)
            new_key = first_card_key()
            if (new_label and new_label != page_label) or (new_key and new_key != prev_key):
                page_label = new_label or (page_label + 1)
                prev_key = new_key or prev_key
                changed = True
                break

        if not changed:
            print("[page] Page didn’t change after Next — stopping.")
            break

        # Ensure content mounts
        cards = wait_for_cards(driver, timeout=18)
        print(f"[page] label {page_label} | found {len(cards)} cards.")
        if not cards:
            # One more rescue attempt: hard scroll burst
            scroll_to_bottom(driver, max_rounds=8, pause=0.5)
            cards = wait_for_cards(driver, timeout=8)
            print(f"[page] rescue check | found {len(cards)} cards.")
            if not cards:
                print("[page] No cards found — stopping.")
                break

        added = 0
        for c in cards:
            d = extract_from_card(c)
            if not d: continue
            key = (d["companyId"] or "") + "|" + d["url"]
            if key in seen_keys: continue
            rows.append(d); seen_keys.add(key); added += 1
        pages_done += 1

    return rows

def scrape(search_text):
    driver = None
    try:
        os.makedirs(PROFILE_DIR_FALLBACK, exist_ok=True)
        driver = make_driver(PROFILE_DIR_FALLBACK, PROFILE_NAME_FALLBACK, "FALLBACK")
        if not robust_go(driver, build_search_url(search_text)):
            raise RuntimeError("nav failed")
        return run_flow(driver)
    finally:
        if driver:
            try: driver.quit()
            except: pass

def main():
    if (
        not isinstance(SEARCH_TEXTS, list)
        or not SEARCH_TEXTS
        or any(not isinstance(text, str) or not text.strip() for text in SEARCH_TEXTS)
    ):
        print("[config] SEARCH_TEXTS must be a non-empty list of non-blank search phrases.")
        return 1

    try:
        importlib.import_module("openpyxl")
    except ImportError as exc:
        print(f"[setup] Excel export requires openpyxl: {exc}")
        requirements_path = os.path.join(os.path.dirname(__file__), "requirements.txt")
        print(f'Install dependencies with: & "{sys.executable}" -m pip install -r "{requirements_path}"')
        return 1

    try:
        os.makedirs(OUTPUT_DIR, exist_ok=True)
    except OSError as exc:
        print(f"[setup] Unable to create output directory {OUTPUT_DIR}: {exc}")
        return 1

    successes = 0
    failures = []
    for index, search_text in enumerate(SEARCH_TEXTS, start=1):
        print(f"\n[search {index}/{len(SEARCH_TEXTS)}] {search_text}")
        try:
            data = scrape(search_text)
            df = pd.DataFrame(data, columns=OUTPUT_COLUMNS)
            df = df.drop_duplicates(subset=["companyId","url"], keep="first")
            output_path = build_output_path(index, search_text)
            df.to_excel(output_path, index=False, engine="openpyxl")
        except Exception as exc:
            failures.append(search_text)
            print(f"[failed] {search_text}: {exc}")
            continue
        successes += 1
        print(f"Saved {len(df)} rows to {output_path}")

    print(f"\nFinished: {successes} succeeded, {len(failures)} failed.")
    return 1 if failures else 0

if __name__ == "__main__":
    raise SystemExit(main())
