from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from html import unescape
from pathlib import Path
from typing import Any
import json
import os
import re
import sqlite3
import threading
import time
import urllib3

try:
    from lxml import html as lxml_html
except ImportError:  # pragma: no cover - o ambiente de produção normalmente já fornece lxml
    lxml_html = None

import requests
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles


BASE_DIR = Path(__file__).resolve().parent
TZ = timezone(timedelta(hours=-3))
API_BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
REQUEST_TIMEOUT = 15
LIVE_REFRESH_INTERVAL_SECONDS = 45
SQLITE_CACHE_TTL = 24 * 60 * 60
CACHE_NAMESPACE = "v20"
SQLITE_CACHE_PATH = BASE_DIR / "games_cache.sqlite3"
CHANNEL_SCRAPER_TTL = 4 * 60 * 60
CHANNEL_SCRAPER_URLS = (
    "https://www.futebolnatv.com.br/jogos-hoje",
    "https://www.futebolnatv.com.br/jogos-amanha",
)


def init_sqlite_cache() -> None:
    with sqlite3.connect(SQLITE_CACHE_PATH) as connection:
        connection.execute("CREATE TABLE IF NOT EXISTS games_cache (cache_key TEXT PRIMARY KEY, cached_at REAL NOT NULL, payload TEXT NOT NULL)")
        connection.commit()


def match_is_live(match: dict[str, Any]) -> bool:
    return match.get("status") in {"live", "halftime"}


def match_has_started(match: dict[str, Any]) -> bool:
    if match_is_live(match) or match.get("status") == "finished":
        return True
    if match.get("status") != "scheduled":
        return False
    try:
        kickoff = datetime.fromisoformat(str(match.get("kickoffIso", "")).replace("Z", "+00:00"))
        return kickoff <= datetime.now(timezone.utc)
    except (TypeError, ValueError):
        return False


def sqlite_payload_is_usable(payload: dict[str, Any], cached_at: float) -> bool:
    return time.time() - cached_at < SQLITE_CACHE_TTL


def payload_needs_live_refresh(payload: dict[str, Any] | None) -> bool:
    """Retorna True quando o cache contém uma partida que já deveria estar em andamento."""
    for match in (payload or {}).get("matches") or []:
        if match.get("status") in {"scheduled", "live", "halftime"} and match_has_started(match):
            return True
    return False


def sqlite_cache_get(cache_key: str) -> dict[str, Any] | None:
    storage_key = f"{CACHE_NAMESPACE}:{cache_key}"
    try:
        with sqlite3.connect(SQLITE_CACHE_PATH) as connection:
            row = connection.execute("SELECT cached_at, payload FROM games_cache WHERE cache_key = ?", (storage_key,)).fetchone()
        if not row:
            return None
        payload = json.loads(row[1])
        return payload if sqlite_payload_is_usable(payload, float(row[0])) else None
    except (OSError, sqlite3.Error, TypeError, ValueError, json.JSONDecodeError):
        return None


def sqlite_cache_set(cache_key: str, payload: dict[str, Any]) -> None:
    storage_key = f"{CACHE_NAMESPACE}:{cache_key}"
    non_live = {
        **payload,
        "matches": [
            match for match in payload.get("matches", [])
            if not match_is_live(match)
        ],
    }
    try:
        with sqlite3.connect(SQLITE_CACHE_PATH) as connection:
            connection.execute(
                "INSERT INTO games_cache(cache_key, cached_at, payload) VALUES(?, ?, ?) ON CONFLICT(cache_key) DO UPDATE SET cached_at=excluded.cached_at, payload=excluded.payload",
                (storage_key, time.time(), json.dumps(non_live, ensure_ascii=False)),
            )
            connection.commit()
    except (OSError, sqlite3.Error, TypeError, ValueError):
        return


LEAGUES: list[dict[str, Any]] = [
    {"slug": "bra.1", "label": "Brasileiro Série A", "priority": 1},
    {"slug": "conmebol.libertadores", "label": "CONMEBOL Libertadores", "priority": 2},
    {"slug": "bra.copa_do_brazil", "label": "Copa do Brasil", "priority": 3},
    {"slug": "bra.2", "label": "Brasileiro Série B", "priority": 4},
    {"slug": "conmebol.sudamericana", "label": "CONMEBOL Sudamericana", "priority": 5},
    {"slug": "eng.1", "label": "Premier League", "priority": 6},
    {"slug": "uefa.champions", "label": "Champions League", "priority": 7},
    {"slug": "esp.1", "label": "La Liga", "priority": 8},
    {"slug": "ita.1", "label": "Italia Serie A", "priority": 9},
    {"slug": "bra.3", "label": "Brasileiro Série C", "priority": 10},
    {"slug": "esp.copa_del_rey", "label": "Copa del Rey", "priority": 11},
    {"slug": "uefa.europa", "label": "Europa League", "priority": 12},
    {"slug": "fra.1", "label": "Ligue 1", "priority": 13},
    {"slug": "ger.1", "label": "Bundesliga", "priority": 14},
    {"slug": "por.1", "label": "Liga Portugal", "priority": 15},
    {"slug": "arg.1", "label": "Liga Argentina", "priority": 16},
    {"slug": "mex.1", "label": "Liga MX", "priority": 17},
    {"slug": "usa.1", "label": "MLS", "priority": 18},
]
LEAGUE_BY_SLUG = {league["slug"]: league for league in LEAGUES}

REQUEST_HEADERS = {
    "Accept": "application/json",
    "Cache-Control": "no-cache",
    "Pragma": "no-cache",
    "User-Agent": "CardapioFC-Vanilla/1.0",
}
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


class Cache:
    def __init__(self) -> None:
        self.items: dict[str, tuple[float, dict[str, Any]]] = {}

    def get(self, key: str) -> dict[str, Any] | None:
        entry = self.items.get(key)
        if not entry:
            return None
        if datetime.now().timestamp() - entry[0] > 45:
            self.items.pop(key, None)
            return None
        return entry[1]

    def set(self, key: str, value: dict[str, Any]) -> None:
        self.items[key] = (datetime.now().timestamp(), value)


cache = Cache()
try:
    init_sqlite_cache()
except (OSError, sqlite3.Error):
    # O runtime Python do APK pode manter os módulos em uma área somente leitura.
    # O cache é opcional; as consultas continuam funcionando sem ele.
    pass


def today_sao_paulo() -> str:
    return datetime.now(TZ).strftime("%Y-%m-%d")


def date_offset(ymd: str, days: int) -> str:
    value = datetime.strptime(ymd, "%Y-%m-%d").date() + timedelta(days=days)
    return value.isoformat()


def channel_lookup_dates() -> tuple[str, str]:
    today = today_sao_paulo()
    return today, date_offset(today, 1)


def is_channel_lookup_date(ymd: str) -> bool:
    try:
        return ymd in channel_lookup_dates()
    except ValueError:
        return False


def normalize_name(value: str) -> str:
    replacements = str.maketrans("áàãâäéèêëíìîïóòõôöúùûüçÁÀÃÂÄÉÈÊËÍÌÎÏÓÒÕÔÖÚÙÛÜÇ", "aaaaaeeeeiiiiooooouuuucAAAAAEEEEIIIIOOOOOUUUUC")
    return re.sub(r"[^a-z0-9]+", " ", str(value).translate(replacements).lower()).strip()


def includes_team(full: str, needle: str) -> bool:
    full_normalized = normalize_name(full)
    return needle in full_normalized or all(part in full_normalized for part in needle.split())


CHANNEL_KEYWORDS: tuple[tuple[str, str], ...] = (
    ("nosso futebol", "Nosso Futebol"),
    ("disney+", "Disney+"),
    ("paramount", "Paramount+"),
    ("premiere", "Premiere"),
    ("sportv", "SporTV"),
    ("cazetv", "CazéTV"),
    ("cazétv", "CazéTV"),
    ("cazé", "CazéTV"),
    ("cazé tv", "CazéTV"),
    ("caze tv", "CazéTV"),
    ("zapping", "Zapping"),
    ("globo", "TV Globo"),
    ("record", "Record"),
    ("band", "Band"),
    ("espn", "ESPN"),
    ("star", "Disney+"),
    ("disney", "Disney+"),
    ("max", "Max"),
    ("hbo", "Max"),
    ("tnt", "TNT"),
    ("space", "Space"),
    ("prime", "Prime Video"),
    ("dazn", "DAZN"),
    ("goat", "Canal GOAT"),
)


def channel_keyword_pattern(keyword: str) -> re.Pattern[str]:
    escaped = re.escape(keyword).replace(r"\ ", r"\s+")
    return re.compile(rf"(?<!\w){escaped}(?:(?:\s*[-/]?\s*)(\d{{1,2}}))?(?!\w)", re.IGNORECASE)


def normalize_channel_label(value: Any) -> str:
    cleaned = re.sub(r"\s+", " ", str(value or "")).strip()
    if not cleaned:
        return ""
    upper = cleaned.upper()
    # O Futebol na TV diferencia os subcanais e envia estes nomes no markup.
    # Preserve a identificação em vez de reduzir tudo a ESPN/SporTV/Premiere.
    for prefix, display in (("SPORTV", "SporTV"), ("PREMIERE", "Premiere"), ("ESPN", "ESPN")):
        if upper == prefix or upper.startswith(prefix + " "):
            suffix = cleaned[len(prefix):].strip()
            if suffix.upper() == "FC":
                suffix = "FC"
            elif suffix.upper() == "CLUBES":
                suffix = "Clubes"
            elif suffix.isdigit():
                suffix = str(int(suffix))
            return f"{display} {suffix}".strip()
    for keyword, channel_name in CHANNEL_KEYWORDS:
        match = channel_keyword_pattern(keyword).search(cleaned)
        if match:
            number = match.group(1)
            return f"{channel_name} {int(number)}" if number else channel_name
    return cleaned


def channels_from_espn(broadcasts: Any) -> list[str]:
    channels: list[str] = []
    for broadcast in broadcasts or []:
        if not isinstance(broadcast, dict):
            continue
        names = broadcast.get("names") or []
        if isinstance(names, str):
            names = [names]
        for name in names:
            value = normalize_channel_label(name)
            if value and not is_non_channel_service(value) and value not in channels:
                channels.append(value)
    return channels


def channel_identity(value: str) -> str:
    return normalize_name(value).replace(" disney ", " disney plus ").strip()


NON_CHANNEL_SERVICE_IDENTITIES = {"youtube", "youtube tv", "onefootball", "ppv", "ppv onefootball"}


def is_non_channel_service(value: str) -> bool:
    identity = channel_identity(value)
    return identity in NON_CHANNEL_SERVICE_IDENTITIES or identity.startswith("ppv ") or " onefootball" in identity


def combine_channel_sources(espn_channels: list[str], scraped_channels: list[str]) -> list[str]:
    """Combina ESPN e scraper, mantendo somente nomes que representam canais."""
    all_channels: list[str] = []
    for channel in [*espn_channels, *scraped_channels]:
        if is_non_channel_service(channel):
            continue
        identity = channel_identity(channel)
        if not identity or identity in {channel_identity(item) for item in all_channels}:
            continue
        all_channels.append(channel)
    return all_channels


class FallbackScraper:
    """Busca canais na grade pública e mantém o resultado em memória por quatro horas."""

    def __init__(self) -> None:
        self._cache: dict[str, list[str]] = {}
        self._detail_urls: dict[str, str] = {}
        self._last_update = 0.0
        self._last_attempt = 0.0
        self._refreshing = False
        self._condition = threading.Condition()

    @staticmethod
    def _text_candidates(source: str) -> list[str]:
        if lxml_html is not None:
            try:
                tree = lxml_html.fromstring(source)
                nodes = tree.xpath("//*")
                candidates: list[str] = []
                seen: set[str] = set()
                for node in nodes:
                    text = re.sub(r"\s+", " ", node.text_content()).strip()
                    normalized = normalize_name(text)
                    if " x " not in normalized or len(normalized) > 700 or normalized in seen:
                        continue
                    seen.add(normalized)
                    candidates.append(text)
                return candidates
            except (TypeError, ValueError):
                pass

        without_code = re.sub(r"<(script|style|noscript)\b[^>]*>.*?</\1>", " ", source, flags=re.IGNORECASE | re.DOTALL)
        chunks = re.split(r"</(?:li|article|section|tr|div|p|a|h[1-6])\b[^>]*>", without_code, flags=re.IGNORECASE)
        candidates = []
        seen: set[str] = set()
        for chunk in chunks:
            text = unescape(re.sub(r"<[^>]+>", " ", chunk))
            text = re.sub(r"\s+", " ", text).strip()
            normalized = normalize_name(text)
            if " x " in normalized and len(normalized) <= 700 and normalized not in seen:
                seen.add(normalized)
                candidates.append(text)
        return candidates

    @staticmethod
    def _channels_from_text(text: str) -> list[str]:
        channels: list[str] = []
        located_channels: list[tuple[int, str]] = []
        for keyword, channel_name in CHANNEL_KEYWORDS:
            for match in channel_keyword_pattern(keyword).finditer(text):
                number = match.group(1)
                value = f"{channel_name} {int(number)}" if number else channel_name
                located_channels.append((match.start(), value))
        for _, value in sorted(located_channels, key=lambda item: item[0]):
            if value not in channels:
                channels.append(value)

        # O site também publica canais fora da lista fixa (por exemplo,
        # XSPORTS, FANATIZ e SPORTYNET. Nesses casos, extrai o trecho imediatamente
        # associado à frase de transmissão do jogo e descarta serviços/modalidades.
        transmission_phrases = re.findall(
            r"(?:transmitid[oa]|transmissão ao vivo|acompanhar|fica por conta de)\s+(?:por\s+|pelo\s+|pela\s+|de\s+)?([^.!?]+)",
            text,
            flags=re.IGNORECASE,
        )
        for phrase in transmission_phrases:
            for raw_channel in re.split(r"\s+(?:e|ou)\s+|[,;]|\s+/\s+", phrase, flags=re.IGNORECASE):
                candidate = re.sub(r"^[^A-ZÀ-Ý0-9+]+|[^A-ZÀ-Ý0-9+ -]+$", "", raw_channel.strip())
                if not candidate or not re.fullmatch(r"[A-ZÀ-Ý0-9][A-ZÀ-Ý0-9+ -]*", candidate):
                    continue
                value = normalize_channel_label(candidate)
                if value and not is_non_channel_service(value) and value not in channels:
                    channels.append(value)

        specific_channels = [channel for channel in channels if not is_non_channel_service(channel)]
        if specific_channels:
            channels = specific_channels
        return channels

    @staticmethod
    def _structured_matches(source: str) -> list[tuple[str, list[str], str | None]]:
        """Extrai times, canais e link da marcação atual do Futebol na TV."""
        if lxml_html is None:
            # Chaquopy não inclui lxml no APK. O markup dos jogos é simples e
            # permite este fallback sem dependências nativas adicionais.
            def plain(fragment: str) -> str:
                return re.sub(r"\s+", " ", unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()

            result: list[tuple[str, list[str], str | None]] = []
            articles = re.findall(r"<article\b[^>]*class=[\"'][^\"']*\bmatch\b[^\"']*[\"'][^>]*>(.*?)</article>", source, flags=re.IGNORECASE | re.DOTALL)
            for article in articles:
                team_fragments = re.findall(r"<[^>]*class=[\"'][^\"']*\bm-name\b[^\"']*[\"'][^>]*>(.*?)</[^>]+>", article, flags=re.IGNORECASE | re.DOTALL)
                teams = [plain(fragment) for fragment in team_fragments if plain(fragment)]
                aria_match = re.search(r"aria-label=[\"']([^\"']+\s+x\s+[^\"']+)[\"']", article, flags=re.IGNORECASE)
                if len(teams) < 2 and aria_match:
                    teams = [part.strip() for part in re.split(r"\s+x\s+", unescape(aria_match.group(1)), maxsplit=1, flags=re.IGNORECASE)]
                if len(teams) < 2:
                    continue
                channels: list[str] = []
                channels_match = re.search(r"<div\b[^>]*class=[\"'][^\"']*\bm-channels\b[^\"']*[\"'][^>]*>(.*?)</div>", article, flags=re.IGNORECASE | re.DOTALL)
                if channels_match:
                    for raw in re.findall(r"<b\b[^>]*>(.*?)</b>", channels_match.group(1), flags=re.IGNORECASE | re.DOTALL):
                        value = normalize_channel_label(plain(raw))
                        if value and not is_non_channel_service(value) and value not in channels:
                            channels.append(value)
                link_match = re.search(r"<a\b[^>]*class=[\"'][^\"']*\bm-link\b[^\"']*[\"'][^>]*href=[\"']([^\"']+)", article, flags=re.IGNORECASE | re.DOTALL)
                result.append((f"{teams[0]} x {teams[1]}", channels, link_match.group(1) if link_match else None))
            return result
        try:
            tree = lxml_html.fromstring(source)
        except (TypeError, ValueError):
            return []
        result: list[tuple[str, list[str], str | None]] = []
        articles = tree.xpath('//article[contains(concat(" ", normalize-space(@class), " "), " match ")]')
        for article in articles:
            teams = [re.sub(r"\s+", " ", node.text_content()).strip() for node in article.xpath('.//*[contains(concat(" ", normalize-space(@class), " "), " m-name ")]')]
            aria_label = str((article.xpath('.//a[contains(@class, "m-link")]/@aria-label') or [""])[0]).strip()
            if len(teams) < 2 and " x " in normalize_name(aria_label):
                teams = [part.strip() for part in re.split(r"\s+x\s+", aria_label, maxsplit=1, flags=re.IGNORECASE)]
            if len(teams) < 2:
                continue
            channel_nodes = article.xpath('.//*[contains(concat(" ", normalize-space(@class), " "), " m-channels ")]//span')
            channels: list[str] = []
            for node in channel_nodes:
                raw = " ".join(str(item).strip() for item in node.xpath('.//b//text()') if str(item).strip())
                value = normalize_channel_label(raw)
                if value and not is_non_channel_service(value) and value not in channels:
                    channels.append(value)
            hrefs = article.xpath('.//a[contains(@class, "m-link")]/@href')
            detail_url = str(hrefs[0]) if hrefs else None
            result.append((f"{teams[0]} x {teams[1]}", channels, detail_url))
        return result

    def _fetch_detail_channels(self, url: str) -> list[str]:
        if not url:
            return []
        if url.startswith("/"):
            url = "https://www.futebolnatv.com.br" + url
        try:
            response = requests.get(url, headers={**REQUEST_HEADERS, "Accept": "text/html,application/xhtml+xml"}, timeout=REQUEST_TIMEOUT)
            if not response.ok:
                return []
            structured = self._structured_matches(response.text)
            if structured:
                return structured[0][1]
            return self._channels_from_text(response.text)
        except requests.RequestException:
            return []

    def _fetch_schedule(self, force: bool = False) -> None:
        now = time.time()
        with self._condition:
            cache_is_fresh = bool(self._cache) and now - self._last_update < CHANNEL_SCRAPER_TTL
            if not force and cache_is_fresh:
                return
            if self._refreshing:
                self._condition.wait(timeout=REQUEST_TIMEOUT * len(CHANNEL_SCRAPER_URLS) + 2)
                return
            if not force and now - self._last_attempt < 60:
                return
            self._refreshing = True
            self._last_attempt = now

        new_cache: dict[str, list[str]] = {}
        new_detail_urls: dict[str, str] = {}
        headers = {
            **REQUEST_HEADERS,
            "Accept": "text/html,application/xhtml+xml",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CardapioFC/1.0",
        }
        try:
            for url in CHANNEL_SCRAPER_URLS:
                try:
                    response = requests.get(url, headers=headers, timeout=REQUEST_TIMEOUT)
                    if not response.ok:
                        continue
                    structured = self._structured_matches(response.text)
                    if structured:
                        for matchup, channels, detail_url in structured:
                            key = normalize_name(matchup)
                            new_cache[key] = channels
                            if detail_url:
                                new_detail_urls[key] = detail_url
                    else:
                        for text in self._text_candidates(response.text):
                            channels = self._channels_from_text(text)
                            if channels:
                                new_cache[normalize_name(text)] = channels
                except requests.RequestException as error:
                    print(f"[Scraper] Erro ao buscar {url}: {error}")
            if new_cache:
                with self._condition:
                    self._cache = new_cache
                    self._detail_urls = new_detail_urls
                    self._last_update = time.time()
        finally:
            with self._condition:
                self._refreshing = False
                self._condition.notify_all()

    def refresh(self) -> bool:
        self._fetch_schedule()
        with self._condition:
            return bool(self._cache)

    def get_channels(self, home: str, away: str) -> list[str]:
        self._fetch_schedule()
        home_parts = [part for part in normalize_name(home).split() if len(part) > 2]
        away_parts = [part for part in normalize_name(away).split() if len(part) > 2]
        best_channels: list[str] = []
        highest_score = 0
        with self._condition:
            cache_items = list(self._cache.items())
        for raw_text, channels in cache_items:
            home_score = any(part in raw_text for part in home_parts)
            away_score = any(part in raw_text for part in away_parts)
            score = int(home_score) + int(away_score)
            if score == 2 and score > highest_score:
                best_channels = channels
                highest_score = score
                best_key = raw_text
        if not best_channels and highest_score == 2:
            with self._condition:
                detail_url = self._detail_urls.get(best_key)
            best_channels = self._fetch_detail_channels(detail_url or "")
        return list(best_channels)


fallback_scraper = FallbackScraper()


def preload_channel_schedule() -> None:
    try:
        if fallback_scraper.refresh():
            print("[Scraper] Grade de hoje e amanhã pré-carregada em cache")
        else:
            print("[Scraper] Não foi possível pré-carregar a grade de canais")
    except Exception as error:  # pragma: no cover - proteção do ciclo de inicialização
        print(f"[Scraper] Falha no pré-carregamento: {error}")


def channels_for_match(slug: str, home: str, away: str, match_date: str, broadcasts: Any = None) -> list[str]:
    espn_channels = channels_from_espn(broadcasts)
    if not is_channel_lookup_date(match_date):
        return ["Canal não informado"]

    scraped_channels = fallback_scraper.get_channels(home, away)
    combined_channels = combine_channel_sources(espn_channels, scraped_channels)
    if espn_channels and scraped_channels and combined_channels != espn_channels:
        print(
            f"[Canais] ESPN + scraper para {home} x {away}; "
            f"resultado={combined_channels}"
        )
    return combined_channels or ["Canal não informado"]


def image_url(value: Any) -> str | None:
    if isinstance(value, str) and value:
        return value.replace("http://", "https://")
    return None


def league_logo(payload: dict[str, Any]) -> str | None:
    league_objects = payload.get("leagues") or []
    if not league_objects:
        return None
    logos = league_objects[0].get("logos") or []
    dark = next((logo for logo in logos if "dark" in (logo.get("rel") or [])), None)
    selected = dark or (logos[0] if logos else None)
    return image_url(selected.get("href")) if selected else None


def map_status(raw: dict[str, Any]) -> tuple[str, str]:
    name = str(raw.get("name") or "").upper()
    state = str(raw.get("state") or "")
    description = raw.get("description")
    if "HALFTIME" in name or description == "Halftime":
        return "halftime", "Intervalo"
    if state == "in" or any(token in name for token in ("IN_PROGRESS", "FIRST_HALF", "SECOND_HALF")):
        return "live", "Ao vivo"
    if state == "post" or raw.get("completed") or "FULL_TIME" in name or description == "Full Time":
        return "finished", "Terminou"
    if state == "pre" or "SCHEDULED" in name or description == "Scheduled":
        return "scheduled", "Agendado"
    return "other", str(raw.get("shortDetail") or description or "—")


ADDED_TIME_RE = re.compile(r"(?<!\d)(\d{1,3})\s*\+\s*(\d{1,2})\s*(?:['’′]|min(?:utos?)?)?", re.IGNORECASE)


def normalize_clock_display(value: Any) -> str | None:
    text = str(value or "").strip()
    if not text:
        return None
    match = ADDED_TIME_RE.search(text)
    if match:
        return f"{int(match.group(1))}+{int(match.group(2))}'"
    return text


def status_clock_display(status_block: dict[str, Any], status_type: dict[str, Any]) -> str | None:
    candidates = (
        status_block.get("displayClock"),
        status_type.get("shortDetail"),
        status_type.get("detail"),
        status_type.get("description"),
    )
    normalized = [normalize_clock_display(candidate) for candidate in candidates]
    added = next((value for value in normalized if value and "+" in value), None)
    return added or next((value for value in normalized if value), None)


def numeric_clock_seconds(status_block: dict[str, Any]) -> float | None:
    value = status_block.get("clock")
    if isinstance(value, (int, float)):
        return float(value)
    try:
        parsed = float(str(value).strip())
        return parsed if parsed >= 0 else None
    except (TypeError, ValueError):
        return None


def status_period(raw: dict[str, Any]) -> int | None:
    value = raw.get("period")
    if isinstance(value, (int, float)):
        return int(value)
    try:
        parsed = int(str(value).strip())
        if parsed > 0:
            return parsed
    except (TypeError, ValueError):
        pass
    name = str(raw.get("name") or "").upper()
    if "SECOND_HALF" in name:
        return 2
    if "FIRST_HALF" in name:
        return 1
    return None


def format_kickoff(iso: str) -> str:
    try:
        value = datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(TZ)
        return value.strftime("%H:%M")
    except (ValueError, TypeError):
        return "—"


def competitor(raw: dict[str, Any]) -> dict[str, Any]:
    team = raw.get("team") or {}
    return {
        "id": str(team.get("id") or ""),
        "name": str(team.get("displayName") or team.get("name") or "?"),
        "shortName": str(team.get("shortDisplayName") or team.get("displayName") or "?"),
        "abbreviation": str(team.get("abbreviation") or ""),
        "logo": image_url(team.get("logo")),
        "score": str(raw.get("score")) if raw.get("score") is not None else None,
    }


def parse_league(slug: str, payload: dict[str, Any], ymd: str, include_channels: bool = True) -> tuple[list[dict[str, Any]], str | None]:
    league = LEAGUE_BY_SLUG[slug]
    logo = league_logo(payload)
    matches: list[dict[str, Any]] = []
    for event in payload.get("events") or []:
        competitions = event.get("competitions") or []
        competition = competitions[0] if competitions else {}
        competitors = competition.get("competitors") or []
        home_raw = next((item for item in competitors if item.get("homeAway") == "home"), competitors[0] if competitors else {})
        away_raw = next((item for item in competitors if item.get("homeAway") == "away"), competitors[1] if len(competitors) > 1 else {})
        home = competitor(home_raw)
        away = competitor(away_raw)
        status_block = event.get("status") or {}
        status_type = status_block.get("type") or {}
        status, status_label = map_status(status_type)
        period = status_period(status_type)
        iso = str(competition.get("date") or event.get("date") or "")
        kickoff = format_kickoff(iso)
        clock = status_clock_display(status_block, status_type)
        clock_seconds = numeric_clock_seconds(status_block)
        if status == "live" and clock:
            status_label = f"Ao vivo {clock}"
        elif status == "scheduled":
            status_label = kickoff
        show_score = status in {"live", "halftime", "finished"}
        venue = (competition.get("venue") or {}).get("fullName")
        matches.append({
            "id": str(event.get("id") or f"{slug}-{home['id']}-{away['id']}"),
            "leagueSlug": slug,
            "league": league["label"],
            "leagueLogo": logo,
            "date": ymd,
            "kickoffIso": iso,
            "kickoff": kickoff,
            "status": status,
            "statusLabel": status_label,
            "clock": clock,
            "clockDisplay": clock if status == "live" and "+" in str(clock or "") else None,
            "clockSeconds": clock_seconds,
            "period": period,
            "home": home,
            "away": away,
            "score": f"{home['score']}-{away['score']}" if show_score and home["score"] is not None and away["score"] is not None else None,
            "venue": venue,
            "channels": channels_for_match(slug, home["name"], away["name"], ymd, competition.get("broadcasts")) if include_channels else [],
        })
    return matches, logo


def next_calendar_phase(payload: dict[str, Any] | None) -> dict[str, Any] | None:
    league_objects = (payload or {}).get("leagues") or []
    league_meta = league_objects[0] if league_objects and isinstance(league_objects[0], dict) else {}
    calendar = league_meta.get("calendar")
    if isinstance(calendar, dict):
        entries = calendar.get("entries") or []
    elif isinstance(calendar, list):
        first = calendar[0] if calendar else None
        if isinstance(first, dict) and isinstance(first.get("entries"), list):
            entries = first.get("entries") or []
        elif all(isinstance(item, dict) for item in calendar):
            entries = calendar
        else:
            entries = []
    else:
        entries = []
    now = datetime.now(timezone.utc)
    for phase in entries:
        try:
            start = datetime.fromisoformat(str(phase["startDate"]).replace("Z", "+00:00"))
            end = datetime.fromisoformat(str(phase["endDate"]).replace("Z", "+00:00"))
        except (KeyError, TypeError, ValueError):
            continue
        if end > now:
            return {"label": phase.get("label") or "", "detail": phase.get("detail") or "", "start": start, "end": end}
    return None


def phase_fallback_matches(league: dict[str, Any], base_payload: dict[str, Any] | None, include_channels: bool) -> tuple[list[dict[str, Any]], str | None]:
    phase = next_calendar_phase(base_payload)
    if not phase:
        return [], None
    current = max(datetime.now(timezone.utc), phase["start"])
    end = phase["end"]
    first_batch: list[dict[str, Any]] = []
    logo: str | None = None
    for _ in range(8):
        if current > end:
            break
        query_date = current.astimezone(TZ).strftime("%Y-%m-%d")
        _, payload = fetch_league(league, query_date)
        if payload is not None:
            parsed, parsed_logo = parse_league(league["slug"], payload, query_date, include_channels=include_channels)
            logo = logo or parsed_logo
            if parsed:
                first_batch.extend(parsed)
                for offset in range(1, 4):
                    extra_day = current + timedelta(days=offset)
                    if extra_day > end:
                        break
                    extra_ymd = extra_day.astimezone(TZ).strftime("%Y-%m-%d")
                    _, extra_payload = fetch_league(league, extra_ymd)
                    if extra_payload is None:
                        continue
                    extra_parsed, extra_logo = parse_league(league["slug"], extra_payload, extra_ymd, include_channels=include_channels)
                    logo = logo or extra_logo
                    first_batch.extend(extra_parsed)
                break
        current += timedelta(days=2)
    unique = {str(match["id"]): match for match in first_batch}
    return sorted(unique.values(), key=iso_sort_key), logo


def fetch_league(league: dict[str, Any], ymd: str) -> tuple[str, dict[str, Any] | None]:
    compact = ymd.replace("-", "")
    url = f"{API_BASE}/{league['slug']}/scoreboard?dates={compact}&limit=80"
    urls = [url, url.replace("https://", "http://", 1)]
    for attempt in range(2):
        for candidate in urls:
            try:
                try:
                    response = requests.get(
                        candidate,
                        headers=REQUEST_HEADERS,
                        timeout=REQUEST_TIMEOUT,
                    )
                except requests.RequestException:
                    response = requests.get(
                        candidate,
                        headers=REQUEST_HEADERS,
                        timeout=REQUEST_TIMEOUT,
                        verify=False,
                    )
                if response.ok:
                    payload = response.json()
                    if isinstance(payload, dict):
                        return league["slug"], payload
            except (requests.RequestException, ValueError):
                continue
        if attempt == 0:
            time.sleep(0.5)
    return league["slug"], None


def iso_sort_key(match: dict[str, Any]) -> str:
    return str(match.get("kickoffIso") or "9999-12-31T23:59:59Z")


def fetch_event_summary(slug: str, match: dict[str, Any]) -> dict[str, Any] | None:
    event_id = str(match.get("id") or "")
    if not event_id:
        return None
    url = f"{API_BASE}/{slug}/summary?event={event_id}&_={int(time.time())}"
    try:
        response = requests.get(url, headers=REQUEST_HEADERS, timeout=REQUEST_TIMEOUT)
        if not response.ok:
            return None
        payload = response.json()
        competition = (payload.get("header") or {}).get("competitions") or []
        competition = competition[0] if competition else {}
        status_block = competition.get("status") or {}
        status_type = status_block.get("type") or {}
        status, status_label = map_status(status_type)
        period = status_period(status_type)
        clock = status_clock_display(status_block, status_type)
        clock_seconds = numeric_clock_seconds(status_block)
        if status == "live" and clock:
            status_label = f"Ao vivo {clock}"
        elif status == "scheduled":
            status_label = match.get("kickoff") or status_label

        scores: dict[str, str | None] = {}
        summary_competitors = competition.get("competitors") or []
        for item in summary_competitors:
            team_id = str(item.get("id") or (item.get("team") or {}).get("id") or "")
            if team_id:
                scores[team_id] = str(item.get("score")) if item.get("score") is not None else None
        home = {**(match.get("home") or {})}
        away = {**(match.get("away") or {})}
        if str(home.get("id") or "") in scores:
            home["score"] = scores[str(home.get("id"))]
        if str(away.get("id") or "") in scores:
            away["score"] = scores[str(away.get("id"))]
        show_score = status in {"live", "halftime", "finished"}
        score = f"{home.get('score')}-{away.get('score')}" if show_score and home.get("score") is not None and away.get("score") is not None else None
        return {
            **match,
            "status": status,
            "statusLabel": status_label,
            "clock": clock,
            "clockDisplay": clock if status == "live" and "+" in str(clock or "") else None,
            "clockSeconds": clock_seconds if status == "live" else None,
            "period": period,
            "home": home,
            "away": away,
            "score": score,
        }
    except (requests.RequestException, ValueError, TypeError, KeyError):
        return None


def refresh_live_matches(payload: dict[str, Any]) -> dict[str, Any]:
    matches = list(payload.get("matches") or [])
    live_indexes = [
        index for index, match in enumerate(matches)
        if match.get("status") in {"live", "halftime"}
        or (match.get("status") == "scheduled" and match_has_started(match))
    ]
    if not live_indexes:
        return payload
    with ThreadPoolExecutor(max_workers=min(4, len(live_indexes))) as executor:
        futures = {
            executor.submit(fetch_event_summary, matches[index].get("leagueSlug") or "", matches[index]): index
            for index in live_indexes
        }
        for future in as_completed(futures):
            index = futures[future]
            updated = future.result()
            if updated:
                matches[index] = updated
    return {**payload, "matches": sorted(matches, key=iso_sort_key)}


def load_day(ymd: str) -> dict[str, Any]:
    memory_key = f"{CACHE_NAMESPACE}:all:{ymd}"
    cached = cache.get(memory_key)
    if cached is not None and not payload_needs_live_refresh(cached):
        return cached
    sqlite_cached = sqlite_cache_get(f"all:{ymd}")
    if sqlite_cached is not None and not payload_needs_live_refresh(sqlite_cached):
        cache.set(memory_key, sqlite_cached)
        return sqlite_cached

    matches: list[dict[str, Any]] = []
    logos: dict[str, str | None] = {}
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(fetch_league, league, ymd) for league in LEAGUES]
        for future in as_completed(futures):
            slug, payload = future.result()
            if payload is None:
                continue
            parsed, logo = parse_league(slug, payload, ymd)
            matches.extend(parsed)
            logos[slug] = logo

    matches.sort(key=iso_sort_key)
    output_leagues = [
        {
            **league,
            "logo": logos.get(league["slug"]),
        }
        for league in LEAGUES
    ]
    result = {"date": ymd, "matches": matches, "leagues": output_leagues}
    cache.set(memory_key, result)
    sqlite_cache_set(f"all:{ymd}", result)
    return result


def fetch_fresh_day(ymd: str, slug: str | None = None, include_channels: bool = True) -> dict[str, Any]:
    leagues = [LEAGUE_BY_SLUG[slug]] if slug else LEAGUES
    matches: list[dict[str, Any]] = []
    logos: dict[str, str | None] = {}
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(fetch_league, league, ymd) for league in leagues]
        for future in as_completed(futures):
            fetched_slug, payload = future.result()
            if payload is None:
                continue
            parsed, logo = parse_league(fetched_slug, payload, ymd, include_channels=include_channels)
            matches.extend(parsed)
            logos[fetched_slug] = logo
    matches.sort(key=iso_sort_key)
    output_leagues = [
        {**league, "logo": logos.get(league["slug"])}
        for league in leagues
    ]
    return {"date": ymd, "matches": matches, "leagues": output_leagues}


def live_games_stream(ymd: str, slug: str | None = None):
    last_signature = ""
    while True:
        payload = refresh_live_matches(fetch_fresh_day(ymd, slug, include_channels=False))
        compact = [
            {
                "id": match.get("id"),
                "score": match.get("score"),
                "status": match.get("status"),
                "statusLabel": match.get("statusLabel") if match.get("status") != "live" else None,
                "clockSeconds": match.get("clockSeconds") if match.get("status") == "live" else None,
                "clockDisplay": match.get("clock") if match.get("status") == "live" and "+" in str(match.get("clock") or "") else None,
                "period": match.get("period"),
                "match": match if match.get("status") in {"live", "halftime"} else None,
            }
            for match in payload["matches"]
        ]
        signature = json.dumps(
            [{key: value for key, value in match.items() if key not in {"clockSeconds", "clockDisplay", "match"}} for match in compact],
            sort_keys=True,
            ensure_ascii=False,
        )
        if signature != last_signature:
            yield f"data: {json.dumps({'matches': compact}, ensure_ascii=False)}\n\n"
            last_signature = signature
        else:
            yield ": heartbeat\n\n"
        time.sleep(LIVE_REFRESH_INTERVAL_SECONDS)


def load_league_day(ymd: str, slug: str) -> dict[str, Any]:
    cache_key = f"{ymd}:{slug}"
    cached = cache.get(cache_key)
    if cached is not None and not payload_needs_live_refresh(cached):
        return cached
    sqlite_cached = sqlite_cache_get(f"league:{slug}:{ymd}")
    if sqlite_cached is not None and not payload_needs_live_refresh(sqlite_cached):
        cache.set(cache_key, sqlite_cached)
        return sqlite_cached

    league = LEAGUE_BY_SLUG[slug]
    _, payload = fetch_league(league, ymd)
    matches: list[dict[str, Any]] = []
    logo: str | None = None
    if payload is not None:
        matches, logo = parse_league(slug, payload, ymd)
        if not matches:
            fallback_matches, fallback_logo = phase_fallback_matches(LEAGUE_BY_SLUG[slug], payload, include_channels=True)
            matches.extend(fallback_matches)
            logo = logo or fallback_logo
    matches.sort(key=iso_sort_key)
    result = {
        "date": ymd,
        "matches": matches,
        "leagues": [{**league, "logo": logo}],
    }
    cache.set(cache_key, result)
    sqlite_cache_set(f"league:{slug}:{ymd}", result)
    return result


app = FastAPI(title="Cardápio FC — Vanilla")
if (BASE_DIR / "static").is_dir():
    app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")


@app.on_event("startup")
def warm_channel_schedule() -> None:
    threading.Thread(target=preload_channel_schedule, name="channel-scraper-preload", daemon=True).start()


@app.middleware("http")
async def disable_asset_cache(request: Request, call_next):
    response = await call_next(request)
    if request.url.path == "/" or request.url.path == "/sw.js" or request.url.path.startswith("/static/"):
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
    return response


@app.get("/", response_class=FileResponse)
def index() -> str:
    return str(BASE_DIR / "static" / "index.html")


@app.get("/sw.js", response_class=Response)
def service_worker() -> Response:
    return Response(
        content="self.addEventListener('install',e=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));",
        media_type="application/javascript",
        headers={"Cache-Control": "no-store"},
    )


@app.get("/api/games/live")
def live_games(
    date: str = Query(default_factory=today_sao_paulo, pattern=r"^\d{4}-\d{2}-\d{2}$"),
    league: str | None = Query(default=None),
) -> dict[str, Any]:
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError as error:
        raise HTTPException(status_code=400, detail="Data inválida") from error
    if league is not None and league not in LEAGUE_BY_SLUG:
        raise HTTPException(status_code=400, detail="Liga inválida")
    return refresh_live_matches(fetch_fresh_day(date, league, include_channels=False))


@app.get("/api/games/stream")
def games_stream(
    date: str = Query(default_factory=today_sao_paulo, pattern=r"^\d{4}-\d{2}-\d{2}$"),
    league: str | None = Query(default=None),
) -> StreamingResponse:
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError as error:
        raise HTTPException(status_code=400, detail="Data inválida") from error
    if league is not None and league not in LEAGUE_BY_SLUG:
        raise HTTPException(status_code=400, detail="Liga inválida")
    return StreamingResponse(
        live_games_stream(date, league),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
    )


@app.get("/api/games")
def games(
    date: str = Query(default_factory=today_sao_paulo, pattern=r"^\d{4}-\d{2}-\d{2}$"),
    league: str | None = Query(default=None),
) -> dict[str, Any]:
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError as error:
        raise HTTPException(status_code=400, detail="Data inválida") from error
    if league is not None:
        if league not in LEAGUE_BY_SLUG:
            raise HTTPException(status_code=400, detail="Liga inválida")
        return load_league_day(date, league)
    return load_day(date)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8000")),
    )
