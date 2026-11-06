(() => {
  "use strict";

  if (!String.prototype.replaceAll) {
    String.prototype.replaceAll = function(search, replacement) {
      return this.split(search).join(replacement);
    };
  }

  const state = {
    today: "",
    date: "",
    tab: "todos",
    leagueSlug: "",
    data: null,
    leagueCatalog: [],
    selectedMatch: null,
    liveOnly: false,
    iptvMode: "xtream",
    iptvPoll: null,
    requestId: 0,
    liveSnapshotReady: false,
    autoScrollDone: false,
    autoScrollRequested: false,
    pendingScrollMode: "",
  };

  const refs = {
    dateRail: document.querySelector("#date-rail"),
    leagueRail: document.querySelector("#league-rail"),
    leagueChips: document.querySelector("#league-chips"),
    leagueList: document.querySelector("#league-list"),
    loading: document.querySelector("#loading-state"),
    empty: document.querySelector("#empty-state"),
    error: document.querySelector("#error-state"),
    content: document.querySelector("#matches-content"),
    leagueDrawer: document.querySelector("#league-drawer"),
    matchModal: document.querySelector("#match-modal"),
    modalLeague: document.querySelector("#match-modal-league"),
    modalTitle: document.querySelector("#match-modal-title"),
    modalMeta: document.querySelector("#match-modal-meta"),
    broadcastList: document.querySelector("#broadcast-list"),
    broadcastBackButton: document.querySelector("#broadcast-back-button"),
    todayToolbar: document.querySelector("#today-toolbar"),
    stickyLeagueName: document.querySelector("#sticky-league-name"),
    liveOnlyToggle: document.querySelector("#live-only-toggle"),
    iptvDialog: document.querySelector("#iptv-dialog"),
    iptvStatusLine: document.querySelector("#iptv-status-line"),
    iptvLoginForm: document.querySelector("#iptv-login-form"),
    iptvConnectedActions: document.querySelector("#iptv-connected-actions"),
    iptvXtreamFields: document.querySelector("#iptv-xtream-fields"),
    iptvM3uFields: document.querySelector("#iptv-m3u-fields"),
  };

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  function nativeIptv() { return window.CardapioIPTV || null; }
  function formatIptvExpiry(value) {
    const raw = String(value ?? "").trim();
    if (!raw || /^(?:0|null|undefined|none|unlimited|ilimitado)$/i.test(raw)) return "";
    const dmy = raw.match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$/);
    if (dmy) return `${dmy[1].padStart(2, "0")}/${dmy[2].padStart(2, "0")}/${dmy[3]}`;
    const isoDate = raw.match(/^(\d{4})-(\d{2})-(\d{2})(?:$|[T ])/);
    if (isoDate) return `${isoDate[3]}/${isoDate[2]}/${isoDate[1]}`;
    const compactDate = raw.match(/^(\d{4})(\d{2})(\d{2})$/);
    if (compactDate) return `${compactDate[3]}/${compactDate[2]}/${compactDate[1]}`;
    const isTimestamp = /^\d{9,13}$/.test(raw);
    let timestamp = isTimestamp ? Number(raw) : Date.parse(raw);
    if (Number.isFinite(timestamp) && isTimestamp && timestamp < 100000000000) timestamp *= 1000;
    if (!Number.isFinite(timestamp)) return raw;
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) return raw;
    try {
      return new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric", timeZone: "America/Sao_Paulo" }).format(date);
    } catch (_) {
      return date.toLocaleDateString("pt-BR");
    }
  }
  function readIptvStatus() {
    try { return JSON.parse(nativeIptv()?.getStatusJson?.() || "{}"); } catch (_) { return {}; }
  }
  function syncIptvUi() {
    const status = readIptvStatus();
    const count = Array.isArray(status.channels) ? status.channels.length : 0;
    if (status.status === "loading") refs.iptvStatusLine.textContent = "Carregando canais...";
    else if (status.status === "error") refs.iptvStatusLine.textContent = status.error || "Não foi possível carregar a lista.";
    else if (status.status === "connected") {
      const connections = status.active >= 0 && status.max >= 0 ? ` · Conexões: ${status.active}/${status.max}` : "";
      const expiryValue = formatIptvExpiry(status.expiry);
      const expiry = ` · Validade: ${expiryValue || "Ilimitado"}`;

      refs.iptvStatusLine.textContent = `${count} canais${connections}${expiry}`;
    } else refs.iptvStatusLine.textContent = "Nenhuma lista conectada.";
    const connected = status.status === "connected";
    refs.iptvLoginForm.classList.toggle("is-hidden", connected);
    refs.iptvConnectedActions.classList.toggle("is-hidden", !connected);
    if (state.selectedMatch && !refs.matchModal.classList.contains("is-hidden")) showMatch(state.selectedMatch);
    if (status.status !== "loading" && state.iptvPoll) { clearInterval(state.iptvPoll); state.iptvPoll = null; }
  }
  function beginIptvPoll() {
    if (state.iptvPoll) clearInterval(state.iptvPoll);
    syncIptvUi(); state.iptvPoll = setInterval(syncIptvUi, 700);
  }
  function showIptv() { syncIptvUi(); refs.iptvDialog.classList.remove("is-hidden"); document.body.classList.add("modal-open"); }
  function hideIptv() { refs.iptvDialog.classList.add("is-hidden"); if (state.iptvPoll) { clearInterval(state.iptvPoll); state.iptvPoll = null; } if (refs.matchModal.classList.contains("is-hidden") && refs.leagueDrawer.classList.contains("is-hidden")) document.body.classList.remove("modal-open"); }
  function streamForChannel(channel) {
    try {
      const raw = nativeIptv()?.findStreams?.(channel);
      if (raw) return JSON.parse(raw) || [];
      const one = JSON.parse(nativeIptv()?.findStream?.(channel) || "{}");
      return one.url ? [one] : [];
    } catch (_) { return []; }
  }
  function suggestedStreamsForMatch(match) {
    try {
      const raw = nativeIptv()?.findSuggestedStreams?.(match.home?.name || "", match.away?.name || "");
      return JSON.parse(raw || "[]") || [];
    } catch (_) { return []; }
  }
  function broadcastMarkup(channel) {
    const streams = streamForChannel(channel); const playable = streams.length > 0;
    const attrs = playable ? ` data-play-options="${escapeHtml(JSON.stringify(streams))}" data-play-title="${escapeHtml(channel)}"` : "";
    return `<li class="broadcast-item${playable ? " is-playable" : ""}"${attrs}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9v6M8 6v12M12 3v18M16 7v10M20 10v4" /></svg><span>${escapeHtml(channel)}</span>${playable ? '<span class="broadcast-live-dot" title="Disponível na sua lista"></span>' : ""}</li>`;
  }

  function streamOptionMarkup(stream, title) {
    const name = stream.name || title;
    const marker = stream.suggested
      ? '<span class="broadcast-suggested-tag">SUGERIDO</span>'
      : '<span class="broadcast-live-dot" title="Disponível na sua lista"></span>';
    return `<li class="broadcast-item is-playable${stream.suggested ? " is-suggested" : ""}" data-stream-option data-stream-search="${escapeHtml(normalizeStreamSearch(name))}" data-play-url="${escapeHtml(stream.url || "")}" data-play-title="${escapeHtml(name)}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9v6M8 6v12M12 3v18M16 7v10M20 10v4" /></svg><span>${escapeHtml(name)}</span>${marker}</li>`;
  }

  function normalizeStreamSearch(value) {
    return String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().replace(/\s+/g, " ");
  }

  function showStreamChoices(playable, streams) {
    const panel = refs.matchModal.querySelector(".match-modal-panel");
    const currentHeight = panel.getBoundingClientRect().height;
    const availableHeight = Math.max(220, window.innerHeight - 12);
    panel.style.height = `${Math.min(availableHeight, Math.max(currentHeight, 470))}px`;
    panel.classList.add("has-stream-choices");
    refs.broadcastList.classList.add("has-stream-choices");
    refs.broadcastBackButton.classList.remove("is-hidden");
    const needsSearch = streams.length > 4;
    const searchMarkup = needsSearch
      ? `<li class="broadcast-search-row"><input id="broadcast-search" type="search" autocomplete="off" placeholder="Pesquisar canal na lista" aria-label="Pesquisar canal na lista"></li>`
      : "";
    refs.broadcastList.innerHTML = `<li class="broadcast-variant-heading">Selecione o canal disponível</li>${searchMarkup}${streams.map((stream) => streamOptionMarkup(stream, playable.dataset.playTitle || "")).join("")}<li id="broadcast-no-results" class="broadcast-no-results is-hidden">Nenhum canal encontrado.</li>`;
    const input = document.querySelector("#broadcast-search");
    if (input) input.addEventListener("input", () => {
      const query = normalizeStreamSearch(input.value);
      let visibleCount = 0;
      refs.broadcastList.querySelectorAll("[data-stream-option]").forEach((option) => {
        const visible = !query || option.dataset.streamSearch.includes(query);
        option.classList.toggle("is-hidden", !visible);
        if (visible) visibleCount += 1;
      });
      document.querySelector("#broadcast-no-results")?.classList.toggle("is-hidden", visibleCount > 0);
    });
  }

  function getInitialDate() {
    const formatted = new Intl.DateTimeFormat("pt-BR", {
      timeZone: "America/Sao_Paulo",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
    const [day, month, year] = formatted.split("/");
    return `${year}-${month}-${day}`;
  }

  function dateWindow(center, past = 2, total = 10) {
    const [year, month, day] = center.split("-").map(Number);
    const start = new Date(Date.UTC(year, month - 1, day - past));
    return Array.from({ length: total }, (_, index) => {
      const date = new Date(start.getTime() + index * 86400000);
      return [date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate()]
        .map((part, position) => (position === 0 ? String(part) : String(part).padStart(2, "0")))
        .join("-");
    });
  }

  function addDays(value, days) {
    const [year, month, day] = value.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day + days));
    return [date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate()]
      .map((part, position) => (position === 0 ? String(part) : String(part).padStart(2, "0")))
      .join("-");
  }

  function isSupportedDate(value) {
    const supportedDates = Array.from({ length: 2 }, (_, offset) => addDays(state.today, offset));
    return supportedDates.indexOf(value) !== -1;
  }

  function dateFromYmd(value) {
    const [year, month, day] = value.split("-").map(Number);
    return new Date(Date.UTC(year, month - 1, day, 15, 0, 0));
  }

  function formatDayLabel(value) {
    const [year, month, day] = value.split("-").map(Number);
    const weekday = new Intl.DateTimeFormat("pt-BR", {
      timeZone: "America/Sao_Paulo",
      weekday: "short",
    }).format(dateFromYmd(value)).replaceAll(".", "");
    return {
      weekday: weekday.charAt(0).toUpperCase() + weekday.slice(1),
      day: `${String(day).padStart(2, "0")}/${String(month).padStart(2, "0")}`,
    };
  }

  function formatMatchWeekday(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value))) return "—";
    const reference = /^\d{4}-\d{2}-\d{2}$/.test(String(state.today)) ? state.today : getInitialDate();
    const matchDay = dateFromYmd(value).getTime();
    const referenceDay = dateFromYmd(reference).getTime();
    const difference = Math.round((matchDay - referenceDay) / 86400000);
    if (difference === -1) return "Ontem";
    if (difference === 0) return "Hoje";
    if (difference === 1) return "Amanhã";
    const weekday = new Intl.DateTimeFormat("pt-BR", {
      timeZone: "America/Sao_Paulo",
      weekday: "long",
    }).format(dateFromYmd(value)).replace(/-feira$/, "");
    return weekday.charAt(0).toUpperCase() + weekday.slice(1);
  }

  function initials(name) {
    const parts = String(name || "?").trim().split(/\s+/).filter(Boolean);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
  }

  function logoOrFallback(src, name, className) {
    return src
      ? `<img class="${className}" src="${escapeHtml(src)}" data-fallback="${escapeHtml(initials(name))}" data-logo-class="${escapeHtml(className)}" alt="" loading="eager" decoding="async" referrerpolicy="no-referrer">`
      : `<span class="${className}-fallback ${className}" aria-hidden="true">${escapeHtml(initials(name))}</span>`;
  }

  function attachLogoFallbacks(root = document) {
    root.querySelectorAll("img[data-fallback]").forEach((image) => {
      image.addEventListener("error", () => {
        const className = image.dataset.logoClass || "team-logo";
        const fallback = document.createElement("span");
        fallback.className = `${className}-fallback ${className}`;
        fallback.setAttribute("aria-hidden", "true");
        fallback.textContent = image.dataset.fallback || "?";
        image.replaceWith(fallback);
      }, { once: true });
    });
  }

  function kickoffTimestamp(match) {
    const timestamp = Date.parse(match?.kickoffIso || "");
    return Number.isFinite(timestamp) ? timestamp : Number.POSITIVE_INFINITY;
  }

  function matchPhaseRank(match) {
    if (match?.status === "finished") return 0;
    if (match?.status === "live" || match?.status === "halftime") return 1;
    // Adiados entram na mesma faixa dos agendados e ordenam por horário.
    if (match?.status === "scheduled" || match?.status === "postponed") return 2;
    return 3;
  }

  function chronologicalMatches(matches) {
    return [...matches].sort((a, b) => {
      const timeA = kickoffTimestamp(a);
      const timeB = kickoffTimestamp(b);
      if (timeA !== timeB) return timeA - timeB;
      return String(a.id ?? "").localeCompare(String(b.id ?? ""));
    });
  }

  function sortedMatches(matches) {
    return [...matches].sort((a, b) => {
      const phaseA = matchPhaseRank(a);
      const phaseB = matchPhaseRank(b);
      if (phaseA !== phaseB) return phaseA - phaseB;
      const timeA = kickoffTimestamp(a);
      const timeB = kickoffTimestamp(b);
      if (timeA !== timeB) return timeA - timeB;
      return String(a.id ?? "").localeCompare(String(b.id ?? ""));
    });
  }

  function leagueOrder(league) {
    return Number(league.priority ?? 999);
  }

  function shortLeague(name) {
    if (name.includes("Série A") && name.includes("Brasileiro")) return "Brasileirão";
    if (name.includes("Série B")) return "Série B";
    if (name.includes("Série C")) return "Série C";
    if (name.includes("Copa do Brasil")) return "Copa do Brasil";
    if (name.includes("Libertadores")) return "Libertadores";
    if (name.includes("Sudamericana")) return "Sul-Americana";
    if (name.includes("Premier")) return "Premier League";
    if (name.includes("Champions")) return "UCL";
    if (name.includes("Copa del Rey")) return "Copa do Rei";
    if (name.includes("Europa League")) return "Europa League";
    if (name.includes("Liga Portugal")) return "Liga Portugal";
    if (name.includes("Argentina")) return "Argentina";
    return name;
  }

  const FRONTEND_CACHE_TTL = 30 * 60 * 1000;
  const FRONTEND_CACHE_VERSION = "v25";
  const LIVE_POLL_INTERVAL_MS = 5_000;
  let liveStream = null;
  let liveStreamGeneration = 0;
  let liveWatchdogTimer = null;
  let liveClockTimer = null;

  function frontendCacheKey(date, league = "") {
    return `cardapio-fc:${FRONTEND_CACHE_VERSION}:games:${date}:${league || "all"}`;
  }

  function frontendCacheIsUsable(payload, cachedAt) {
    return Boolean(payload) && Number.isFinite(cachedAt) && Date.now() - cachedAt < FRONTEND_CACHE_TTL;
  }

  function nonLivePayload(payload) {
    return {
      ...payload,
      matches: (payload.matches || []).filter((match) => match.status !== "live" && match.status !== "halftime"),
    };
  }

  function readFrontendCache(date, league = "") {
    try {
      const raw = localStorage.getItem(frontendCacheKey(date, league));
      if (!raw) return null;
      const entry = JSON.parse(raw);
      return frontendCacheIsUsable(entry.payload, Number(entry.cachedAt)) ? nonLivePayload(entry.payload) : null;
    } catch (error) {
      console.warn("Não foi possível ler o cache local", error);
      return null;
    }
  }

  function writeFrontendCache(date, league, payload) {
    try {
      localStorage.setItem(frontendCacheKey(date, league), JSON.stringify({ cachedAt: Date.now(), payload: nonLivePayload(payload) }));
    } catch (error) {
      console.warn("Não foi possível gravar o cache local", error);
    }
  }

  async function fetchGames(date, league = "", forceRefresh = false) {
    if (!forceRefresh) {
      const cached = readFrontendCache(date, league);
      if (cached) return { payload: cached, fromCache: true };
    }
    const query = league ? `&league=${encodeURIComponent(league)}` : "";
    const response = await fetch(`/api/games?date=${encodeURIComponent(date)}${query}`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const payload = await response.json();
    writeFrontendCache(date, league, payload);
    return { payload, fromCache: false };
  }

  async function fetchLiveGames(context) {
    const query = `?date=${encodeURIComponent(context.date)}${context.league ? `&league=${encodeURIComponent(context.league)}` : ""}`;
    const response = await fetch(`/api/games/live${query}`, {
      headers: { Accept: "application/json", "Cache-Control": "no-cache" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }

  function setLoading(isLoading) {
    if (isLoading) stopLiveStream();
    refs.loading.classList.toggle("is-hidden", !isLoading);
    if (isLoading) {
      const viewportHeight = window.visualViewport?.height || window.innerHeight || 800;
      const estimatedHeader = 170;
      const estimatedCard = 184;
      const gap = 10;
      const skeletonCount = Math.max(4, Math.ceil(Math.max(0, viewportHeight - estimatedHeader) / (estimatedCard + gap)) + 1);
      refs.loading.innerHTML = Array.from({ length: skeletonCount }, () => '<div class="skeleton-card"></div>').join("");
      refs.empty.classList.add("is-hidden");
      refs.error.classList.add("is-hidden");
      refs.todayToolbar.classList.add("is-hidden");
      refs.content.replaceChildren();
    }
  }

  async function loadDate(date, scrollMode = "") {
    const requestId = ++state.requestId;
    state.date = date;
    state.leagueSlug = "";
    state.liveOnly = false;
    state.liveSnapshotReady = date !== state.today;
    state.autoScrollDone = false;
    // Hoje sempre procura o ao vivo mais relevante ou o próximo jogo;
    // qualquer outra data usa o comportamento de catálogo: voltar ao topo.
    state.pendingScrollMode = date === state.today ? "today-nearest" : "top";
    refs.liveOnlyToggle.checked = false;
    renderDateRail();
    setLoading(true);
    try {
      const { payload: data } = await fetchGames(date);
      if (requestId !== state.requestId) return;
      state.data = data;
      state.leagueCatalog = data.leagues || state.leagueCatalog;
      setLoading(false);
      renderAll();
    } catch (error) {
      if (requestId !== state.requestId) return;
      console.error(error);
      setLoading(false);
      refs.error.classList.remove("is-hidden");
      refs.empty.classList.add("is-hidden");
      refs.content.replaceChildren();
    }
  }

  async function loadLeagueWindow(selectedSlug = "") {
    const requestId = ++state.requestId;
    const dates = dateWindow(state.today, 2, 10);
    const catalog = [...(state.leagueCatalog.length ? state.leagueCatalog : state.data?.leagues || [])]
      .sort((a, b) => leagueOrder(a) - leagueOrder(b));
    const slug = selectedSlug || state.leagueSlug || catalog[0]?.slug || "";
    state.date = "window";
    state.leagueSlug = slug;
    state.liveOnly = false;
    state.liveSnapshotReady = false;
    state.autoScrollDone = false;
    refs.liveOnlyToggle.checked = false;
    setLoading(true);
    try {
      const results = await Promise.all(dates.map((date) => fetchGames(date, slug)));
      const payloads = results.map((result) => result.payload);
      if (requestId !== state.requestId) return;

      const dedupedMatches = [...new Map(payloads.flatMap((payload) => payload.matches || []).map((match) => [String(match.id), match])).values()];
      state.data = {
        date: "window",
        matches: dedupedMatches,
        leagues: catalog,
      };
      setLoading(false);
      renderAll();
    } catch (error) {
      if (requestId !== state.requestId) return;
      console.error(error);
      setLoading(false);
      refs.error.classList.remove("is-hidden");
      refs.empty.classList.add("is-hidden");
      refs.content.replaceChildren();
    }
  }

  function renderDateRail() {
    refs.dateRail.classList.toggle("is-hidden", state.tab === "ligas");
    const dates = dateWindow(state.today, 2, 10);
    refs.dateRail.innerHTML = dates.map((date) => {
      const label = formatDayLabel(date);
      const active = date === state.date;
      const isToday = date === state.today;
      const weekdayLabel = isToday ? "Hoje" : date === addDays(state.today, 1) ? "Amanhã" : label.weekday;
      return `<button type="button" class="date-button${active ? " is-active" : ""}${isToday ? " is-today" : ""}" data-date="${date}" aria-pressed="${active}">
        <span class="date-weekday">${escapeHtml(weekdayLabel)}</span>
        <span class="date-day">${escapeHtml(label.day)}</span>
      </button>`;
    }).join("");
  }

  function scrollActiveLeagueChip(behavior = "smooth") {
    if (state.tab !== "ligas" || !state.leagueSlug) return;
    const activeChip = refs.leagueChips.querySelector(`[data-league="${CSS.escape(String(state.leagueSlug))}"]`);
    if (!activeChip) return;
    requestAnimationFrame(() => activeChip.scrollIntoView({ behavior, block: "nearest", inline: "center" }));
  }

  function renderLeagueRail() {
    const leagues = [...(state.data?.leagues || [])].sort((a, b) => leagueOrder(a) - leagueOrder(b));
    refs.leagueRail.classList.toggle("is-hidden", state.tab !== "ligas");
    if (state.tab === "ligas" && !state.leagueSlug) state.leagueSlug = leagues[0]?.slug || "";
    refs.leagueChips.innerHTML = leagues.map((league) => `<button type="button" class="chip${state.leagueSlug === league.slug ? " is-active" : ""}" data-league="${escapeHtml(league.slug)}" aria-pressed="${state.leagueSlug === league.slug}">
        ${logoOrFallback(league.logo, league.label, "chip-logo")}<span>${escapeHtml(shortLeague(league.label))}</span>
      </button>`).join("");
    attachLogoFallbacks(refs.leagueChips);
    scrollActiveLeagueChip();
  }

  function renderLeagueDrawer() {
    const leagues = [...(state.data?.leagues || [])].sort((a, b) => leagueOrder(a) - leagueOrder(b));
    refs.leagueList.innerHTML = leagues.map((league) => `<button type="button" class="league-option${state.leagueSlug === league.slug ? " is-active" : ""}" data-league="${escapeHtml(league.slug)}" aria-pressed="${state.leagueSlug === league.slug}">
        ${logoOrFallback(league.logo, league.label, "league-option-logo")}<span class="league-option-name">${escapeHtml(league.label)}</span>
      </button>`).join("");
    attachLogoFallbacks(refs.leagueList);
  }

  function liveIndicator() {
    return `<svg class="live-indicator" viewBox="0 0 16 16" aria-hidden="true"><circle class="live-indicator-dot" cx="8" cy="8" r="3"></circle><circle class="live-indicator-ring" cx="8" cy="8" r="6"></circle></svg>`;
  }

  function officialClockSeconds(match) {
    const numeric = Number(match?.clockSeconds);
    const candidates = [match?.clockDisplay, match?.clock, match?.statusLabel];
    let textual = NaN;
    for (const candidate of candidates) {
      const text = String(candidate || "").trim();
      const iso = text.match(/^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/i);
      if (iso && iso.slice(1).some(Boolean)) {
        textual = Number(iso[1] || 0) * 3600 + Number(iso[2] || 0) * 60 + Number(iso[3] || 0);
        break;
      }
      const minuteSecond = text.match(/(?:Ao vivo\s+)?(\d{1,3})(?::(\d{1,2}))?\s*['’′]?/i);
      if (minuteSecond) {
        textual = Number(minuteSecond[1]) * 60 + Number(minuteSecond[2] || 0);
        break;
      }
    }
    const explicitClockText = [match?.clockDisplay, match?.clock].some((candidate) => /\d{1,3}:\d{1,2}/.test(String(candidate || "")));
    if (explicitClockText && Number.isFinite(textual)) return textual;
    if (Number.isFinite(numeric) && numeric === 0 && Number.isFinite(textual) && textual > 0) return textual;
    if (Number.isFinite(numeric) && numeric >= 0) return numeric;
    return textual;
  }

  function formatLiveClock(totalSeconds) {
    const safeSeconds = Math.max(0, Math.floor(totalSeconds));
    const minutes = Math.floor(safeSeconds / 60);
    const seconds = String(safeSeconds % 60).padStart(2, "0");
    return `${minutes}:${seconds}`;
  }

  function localLiveLabel(match) {
    if (match?.status !== "live") return null;
    const now = Date.now();
    const additionMatch = typeof match.clockDisplay === "string" ? match.clockDisplay.match(/^(\d+)['’′]?\+(\d+)['’′]?$/) : null;
    const stoppageClockText = [match.clockDisplay, match.clock].map((value) => String(value || "").trim()).find((value) => /^(?:Ao vivo\s+)?\d{1,3}:\d{1,2}\s*(?:\(\s*\+\d{1,2}\s*\)|\+\s*\d{1,2})$/i.test(value));
    const stoppageClockMatch = stoppageClockText?.match(/^(?:Ao vivo\s+)?(\d{1,3}):(\d{1,2})\s*(?:\(\s*\+(\d{1,2})\s*\)|\+\s*(\d{1,2}))$/i);
    const officialSeconds = officialClockSeconds(match);
    const apiSyncedAt = Number(match.clockSyncedAt);
    const apiSyncAnchor = Number.isFinite(apiSyncedAt) ? apiSyncedAt : 0;

    // Formato da API/Google: "93:27 (+5)". Preserve os segundos e continue
    // o relógio localmente entre atualizações, sem voltar ao clock=90:00.
    if (stoppageClockMatch) {
      const baseSeconds = Number(stoppageClockMatch[1]) * 60 + Number(stoppageClockMatch[2]);
      const addedMinutes = Number(stoppageClockMatch[3] || stoppageClockMatch[4]);
      const period = Number(match.period) === 2 ? 2 : 1;
      if (
        match._liveClockBaseSeconds !== baseSeconds
        || match._liveClockPeriod !== period
        || !Number.isFinite(match._liveClockSyncedAt)
      ) {
        match._liveClockBaseSeconds = baseSeconds;
        match._liveClockPeriod = period;
        match._liveClockSyncedAt = apiSyncAnchor ? Math.min(now, apiSyncAnchor) : now;
      }
      const elapsedSinceSync = Math.max(0, Math.floor((now - match._liveClockSyncedAt) / 1000));
      const totalSeconds = match._liveClockBaseSeconds + elapsedSinceSync;
      return `Ao vivo ${formatLiveClock(totalSeconds)} (+${addedMinutes})`;
    }

    if (additionMatch) {
      const regulationMinutes = Number(additionMatch[1]);
      const addedMinutes = Number(additionMatch[2]);
      const additionBaseSeconds = (regulationMinutes + addedMinutes) * 60;
      const period = Number(match.period) === 2 ? 2 : 1;
      if (
        match._liveClockBaseSeconds !== additionBaseSeconds
        || match._liveClockPeriod !== period
        || !Number.isFinite(match._liveClockSyncedAt)
      ) {
        match._liveClockBaseSeconds = additionBaseSeconds;
        match._liveClockPeriod = period;
        match._liveClockSyncedAt = apiSyncAnchor ? Math.min(now, apiSyncAnchor) : now;
      }
      const elapsedSinceSync = Math.max(0, Math.floor((now - match._liveClockSyncedAt) / 1000));
      const totalSeconds = match._liveClockBaseSeconds + elapsedSinceSync;
      return `Ao vivo ${formatLiveClock(totalSeconds)} (+${addedMinutes})`;
    }

    if (!Number.isFinite(officialSeconds)) {
      return match.statusLabel || "Ao vivo";
    }

    const period = Number(match.period) === 2 ? 2 : 1;
    if (
      match._liveClockBaseSeconds !== officialSeconds
      || match._liveClockPeriod !== period
      || !Number.isFinite(match._liveClockSyncedAt)
    ) {
      match._liveClockBaseSeconds = officialSeconds;
      match._liveClockPeriod = period;
      match._liveClockSyncedAt = apiSyncAnchor ? Math.min(now, apiSyncAnchor) : now;
    }

    const elapsedSinceSync = Math.max(0, Math.floor((now - match._liveClockSyncedAt) / 1000));
    // A API já entrega clockSeconds como minuto absoluto da partida.
    // No segundo tempo, 56m continua sendo 56m — nunca 45 + 56.
    const totalSeconds = match._liveClockBaseSeconds + elapsedSinceSync;
    return `Ao vivo ${formatLiveClock(totalSeconds)}`;
  }

  function isStarting(match) {
    if (match?.status !== "scheduled" || String(match.date || "") !== state.today) return false;
    const kickoff = kickoffTimestamp(match);
    return Number.isFinite(kickoff) && kickoff <= Date.now();
  }

  function displayStatusLabel(match) {
    if (match?.status === "scheduled") return isStarting(match) ? "A começar" : formatMatchWeekday(match.date);
    if (match?.status === "postponed") {
      // No dia original, informa o destino; no dia de destino, mantém o
      // padrão Hoje/Amanhã/data usado pelos jogos agendados.
      if (match.postponedTargetDate && match.date === match.postponedTargetDate) return formatMatchWeekday(match.date);
      if (match.postponedTargetDate) return `Adiado para ${formatMatchWeekday(match.postponedTargetDate).toLowerCase()}`;
    }
    return localLiveLabel(match) || match?.statusLabel || "—";
  }

  function updateLiveClockLabels() {
    let hasLiveMatch = false;
    refs.content.querySelectorAll('.match-card[data-match-id]').forEach((card) => {
      const match = (state.data?.matches || []).find((item) => String(item.id) === String(card.dataset.matchId));
      if (!match || match.status !== "live") return;
      const statusText = card.querySelector('[data-live-field="status"] .status-text');
      const label = localLiveLabel(match);
      if (statusText && label && statusText.textContent !== label) statusText.textContent = label;
      hasLiveMatch = true;
    });
    if (!hasLiveMatch && liveClockTimer) {
      window.clearInterval(liveClockTimer);
      liveClockTimer = null;
    }
  }

  function ensureLiveClockTimer() {
    updateLiveClockLabels();
    if (!liveClockTimer) liveClockTimer = window.setInterval(updateLiveClockLabels, 1000);
  }

  function matchStatusMarkup(match, statusLabel, live) {
    return `${live ? liveIndicator() : ""}<span class="status-text">${escapeHtml(statusLabel)}</span>`;
  }

  function matchDateLabel(match) {
    const matchDate = String(match.date || "");
    if (matchUsesDayPill(match)) {
      return formatMatchWeekday(matchDate);
    }
    return matchDate.length >= 10 ? `${matchDate.slice(8, 10)}/${matchDate.slice(5, 7)}` : "—";
  }

  function matchUsesDayPill(match) {
    const live = match.status === "live" || match.status === "halftime";
    const matchDate = String(match.date || "");
    return live && (matchDate === state.today || matchDate === addDays(state.today, 1));
  }

  function matchCard(match) {
    const date = matchDateLabel(match);
    const statusLabel = displayStatusLabel(match);
    const live = match.status === "live" || match.status === "halftime";
    return `<button type="button" class="match-card" data-match-id="${escapeHtml(match.id)}" data-league-name="${escapeHtml(match.league)}">
      <div class="match-league">${logoOrFallback(match.leagueLogo, match.league, "match-league-logo")}<span>${escapeHtml(match.league)}</span></div>
      <div class="match-grid">
        <div class="team-column">${logoOrFallback(match.home?.logo, match.home?.name, "team-logo")}<span class="team-name" title="${escapeHtml(match.home?.shortName)}">${escapeHtml(match.home?.shortName)}</span></div>
        <div class="score-column">
          <span class="score" data-live-field="score">${escapeHtml(match.score || match.kickoff || "—")}</span>
          <span class="match-date${matchUsesDayPill(match) ? " is-day-pill" : ""}">${escapeHtml(date)}</span>
          <span class="status-pill${live ? " is-live" : ""}${isStarting(match) ? " is-starting" : ""}" data-live-field="status">${matchStatusMarkup(match, statusLabel, live)}</span>
        </div>
        <div class="team-column">${logoOrFallback(match.away?.logo, match.away?.name, "team-logo")}<span class="team-name" title="${escapeHtml(match.away?.shortName)}">${escapeHtml(match.away?.shortName)}</span></div>
      </div>
    </button>`;
  }

  function updateStickyLeague() {
    if (refs.todayToolbar.classList.contains("is-hidden")) return;
    const cards = [...refs.content.querySelectorAll(".match-card")];
    if (!cards.length) return;
    const marker = refs.todayToolbar.getBoundingClientRect().bottom + 18;
    let current = cards[0];
    cards.forEach((card) => {
      if (card.getBoundingClientRect().top <= marker) current = card;
    });
    refs.stickyLeagueName.textContent = current.dataset.leagueName || "—";
  }

  function liveStreamContext() {
    if (state.tab === "ligas" && state.leagueSlug) return { date: state.today, league: state.leagueSlug };
    if (state.tab === "todos" && state.date === state.today) return { date: state.today, league: "" };
    return null;
  }

  function stopLiveStream() {
    liveStreamGeneration += 1;
    if (liveStream) {
      liveStream.close();
      liveStream = null;
    }
    if (liveWatchdogTimer) {
      window.clearTimeout(liveWatchdogTimer);
      liveWatchdogTimer = null;
    }
  }

  function armLiveWatchdog(generation) {
    if (liveWatchdogTimer) window.clearTimeout(liveWatchdogTimer);
    liveWatchdogTimer = window.setTimeout(async () => {
      if (generation !== liveStreamGeneration) return;
      const context = liveStreamContext();
      if (!context) return;
      try {
        const payload = await fetchLiveGames(context);
        if (generation !== liveStreamGeneration) return;
        applyLiveUpdates({
          matches: (payload.matches || [])
            .filter((match) => match.status === "live" || match.status === "halftime" || isStarting(match))
            .map((match) => ({
              id: match.id,
              score: match.score,
              status: match.status,
              statusLabel: match.statusLabel,
              clockSeconds: match.clockSeconds,
              clock: match.clock,
              clockSyncedAt: match.clockSyncedAt,
              clockDisplay: match.clockDisplay,
              period: match.period,
              match,
            })),
        });
      } catch (error) {
        console.warn("Não foi possível recuperar a atualização ao vivo", error);
      } finally {
        if (generation === liveStreamGeneration) armLiveWatchdog(generation);
      }
    }, LIVE_POLL_INTERVAL_MS);
  }

  function applyLiveUpdates(payload) {
    const incoming = payload?.matches || [];
    const hadLiveBefore = (state.data?.matches || []).some((match) => match.status === "live" || match.status === "halftime");
    let needsRender = false;
    incoming.forEach((update) => {
      let current = (state.data?.matches || []).find((match) => String(match.id) === String(update.id));
      if (!current && update.match) {
        state.data.matches.push(update.match);
        needsRender = true;
        return;
      }
      if (!current) return;

      const card = refs.content.querySelector(`[data-match-id="${CSS.escape(String(update.id))}"]`);
      Object.assign(current, update);
      if (!card) {
        needsRender = true;
        return;
      }
      if (current.status !== "live") {
        delete current._liveClockBaseSeconds;
        delete current._liveClockSyncedAt;
        delete current._liveClockSourceAt;
      }
      const scoreValue = current.score || current.kickoff || "—";
      const statusValue = displayStatusLabel(current);
      const dateValue = matchDateLabel(current);
      const live = current.status === "live" || current.status === "halftime";
      const score = card.querySelector('[data-live-field="score"]');
      const date = card.querySelector(".match-date");
      const status = card.querySelector('[data-live-field="status"]');
      if (score && score.textContent !== scoreValue) score.textContent = scoreValue;
      if (date) {
        date.classList.toggle("is-day-pill", matchUsesDayPill(current));
        if (date.textContent !== dateValue) date.textContent = dateValue;
      }
      if (status) {
        const starting = isStarting(current);
        status.classList.toggle("is-live", live);
        status.classList.toggle("is-starting", starting);
        if (status.querySelector(".status-text")?.textContent !== statusValue) {
          status.innerHTML = matchStatusMarkup(current, statusValue, live);
        }
      }
    });
    if (needsRender) {
      renderContent();
      return;
    }
    ensureLiveClockTimer();
    updateStickyLeague();
    if (!hadLiveBefore) {
      const liveTarget = chooseAutoScrollMatch((state.data?.matches || []).filter((match) => match.status === "live" || match.status === "halftime"));
      if (liveTarget && !(state.date === state.today && state.pendingScrollMode === "today-nearest")) {
        state.autoScrollDone = true;
        scrollToMatch(liveTarget);
      }
    }
  }

  async function primeLiveSnapshot(context, generation) {
    try {
      const payload = await fetchLiveGames(context);
      if (generation !== liveStreamGeneration) return;
      state.liveSnapshotReady = true;
      applyLiveUpdates({
        matches: (payload.matches || []).map((match) => ({
            id: match.id,
            score: match.score,
            status: match.status,
            statusLabel: match.statusLabel,
            clockSeconds: match.clockSeconds,
            clock: match.clock,
            clockSyncedAt: match.clockSyncedAt,
            clockDisplay: match.clockDisplay,
            period: match.period,
            kickoffIso: match.kickoffIso,
            kickoff: match.kickoff,
            date: match.date,
            match,
          })),
      });
      completePendingTodayScroll();
    } catch (error) {
      console.warn("Não foi possível carregar o snapshot ao vivo", error);
      if (generation === liveStreamGeneration) {
        state.liveSnapshotReady = true;
        completePendingTodayScroll();
      }
    }
  }

  function completePendingTodayScroll() {
    if (state.date !== state.today || !state.liveSnapshotReady || state.pendingScrollMode !== "today-nearest") return;
    state.pendingScrollMode = "";
    state.autoScrollDone = true;
    scrollToNearestTodayMatch(state.data?.matches || []);
  }

  function startLiveStream() {
    stopLiveStream();
    const context = liveStreamContext();
    if (!context || document.visibilityState === "hidden") return;
    const generation = liveStreamGeneration;
    primeLiveSnapshot(context, generation);
    const query = `?date=${encodeURIComponent(context.date)}${context.league ? `&league=${encodeURIComponent(context.league)}` : ""}`;
    liveStream = new EventSource(`/api/games/stream${query}`);
    liveStream.onmessage = (event) => {
      try {
        applyLiveUpdates(JSON.parse(event.data));
        armLiveWatchdog(generation);
      } catch (error) {
        console.warn("Atualização ao vivo inválida", error);
      }
    };
    armLiveWatchdog(generation);
    liveStream.onerror = () => {
      if (liveStream) {
        liveStream.close();
        liveStream = null;
      }
      if (generation === liveStreamGeneration) {
        window.setTimeout(() => {
          if (generation === liveStreamGeneration) startLiveStream();
        }, 5000);
      }
    };
  }

  function scrollToMatch(match) {
    const card = refs.content.querySelector(`[data-match-id="${CSS.escape(String(match.id))}"]`);
    if (!card) return;

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const headerBottom = document.querySelector(".app-header")?.getBoundingClientRect().bottom || 0;
        const top = Math.max(0, window.scrollY + card.getBoundingClientRect().top - headerBottom - 12);
        window.scrollTo({ top, behavior: "smooth" });
      });
    });
  }

  function scrollToTop() {
    requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: "smooth" }));
  }

  function chooseAutoScrollMatch(matches) {
    const ordered = chronologicalMatches(matches)
      .map((match) => ({ match, timestamp: kickoffTimestamp(match) }));
    if (!ordered.length) return null;

    // A partida ao vivo sempre tem prioridade, independentemente do horário.
    const liveTarget = ordered.find(({ match }) => match.status === "live" || match.status === "halftime");
    if (liveTarget) return liveTarget.match;

    // Sem partida ao vivo, escolhe o próximo jogo agendado mais próximo de agora.
    const now = Date.now();
    const waitingToStart = ordered
      .filter(({ match, timestamp }) => isStarting(match) && Number.isFinite(timestamp) && timestamp <= now)
      .sort((a, b) => b.timestamp - a.timestamp)[0];
    if (waitingToStart) return waitingToStart.match;

    const nextScheduled = ordered.find(({ match, timestamp }) => match.status === "scheduled" && Number.isFinite(timestamp) && timestamp > now);
    if (nextScheduled) return nextScheduled.match;

    // Sem partidas em andamento ou futuras, não rola até partidas encerradas/passadas.
    return null;
  }

  function scrollToNearestTodayMatch(matches) {
    if (state.date !== state.today || !matches.length) return;
    const target = chooseAutoScrollMatch(matches);
    if (target) scrollToMatch(target);
  }

  function scrollToFirstLeagueDayMatch(matches) {
    if (state.tab !== "ligas" || !matches.length) return;
    const target = chooseAutoScrollMatch(matches);
    if (target) scrollToMatch(target);
  }

  function renderContent() {
    const isLeaguesTab = state.tab === "ligas";
    const orderMatches = isLeaguesTab ? chronologicalMatches : sortedMatches;
    const allMatches = orderMatches(state.data?.matches || []);
    let selected = state.leagueSlug ? allMatches.filter((match) => match.leagueSlug === state.leagueSlug) : allMatches;
    const isToday = !isLeaguesTab && state.date === state.today;
    if (!isLeaguesTab && isToday && state.liveOnly) selected = selected.filter((match) => match.status === "live" || match.status === "halftime");

    refs.todayToolbar.classList.toggle("is-hidden", isLeaguesTab || !isToday || !selected.length);
    refs.empty.textContent = isToday && state.liveOnly ? "Nenhum jogo ao vivo agora." : "Nenhum jogo nesta data.";
    refs.empty.classList.toggle("is-hidden", selected.length > 0);
    if (!selected.length) {
      refs.content.replaceChildren();
      startLiveStream();
      return;
    }

    refs.content.innerHTML = `<div class="match-list global-match-list">${orderMatches(selected).map(matchCard).join("")}</div>`;
    attachLogoFallbacks(refs.content);
    updateStickyLeague();
    if (isLeaguesTab) scrollToFirstLeagueDayMatch(selected);
    else if (state.pendingScrollMode) {
      const scrollMode = state.pendingScrollMode;
      if (scrollMode === "today-nearest" && !state.liveSnapshotReady) {
        state.autoScrollRequested = false;
      } else {
        state.pendingScrollMode = "";
        state.autoScrollRequested = false;
        state.autoScrollDone = true;
        if (scrollMode === "top") scrollToTop();
        else scrollToNearestTodayMatch(selected);
      }
    } else if (state.autoScrollRequested) {
      state.autoScrollRequested = false;
      state.autoScrollDone = true;
      scrollToNearestTodayMatch(selected);
    }
    startLiveStream();
    ensureLiveClockTimer();
  }

  function renderAll() {
    renderLeagueRail();
    renderLeagueDrawer();
    renderContent();
  }

  function showLeagueDrawer() {
    renderLeagueDrawer();
    refs.leagueDrawer.classList.remove("is-hidden");
    document.body.classList.add("modal-open");
  }

  function hideLeagueDrawer() {
    refs.leagueDrawer.classList.add("is-hidden");
    if (refs.matchModal.classList.contains("is-hidden")) document.body.classList.remove("modal-open");
  }

  function showMatch(match) {
    state.selectedMatch = match;
    refs.modalLeague.textContent = match.league || "";
    refs.modalTitle.textContent = `${match.home?.name || "?"} x ${match.away?.name || "?"}`;
    refs.modalMeta.textContent = `${match.kickoff || "—"}${match.venue ? ` · ${match.venue}` : ""}`;
    resetBroadcastChoices();
    refs.broadcastList.innerHTML = matchBroadcastMarkup(match);
    refs.matchModal.classList.remove("is-hidden");
    document.body.classList.add("modal-open");
  }

  function matchBroadcastMarkup(match) {
    const supportedDate = isSupportedDate(String(match.date || ""));
    const channels = supportedDate ? (match.channels || []) : [];
    const suggested = supportedDate ? suggestedStreamsForMatch(match) : [];
    const suggestionsMarkup = suggested.map((stream) => streamOptionMarkup({ ...stream, suggested: true }, stream.name || "")).join("");
    const channelsMarkup = channels.map(broadcastMarkup).join("");
    return suggestionsMarkup || channelsMarkup
      ? `${suggestionsMarkup}${channelsMarkup}`
      : `<li class="broadcast-item broadcast-unavailable">Canais não informados para esta data.</li>`;
  }

  function resetBroadcastChoices() {
    const panel = refs.matchModal.querySelector(".match-modal-panel");
    panel.classList.remove("has-stream-choices");
    panel.style.height = "";
    refs.broadcastList.classList.remove("has-stream-choices");
    refs.broadcastBackButton.classList.add("is-hidden");
  }

  function backToInitialChannels() {
    if (!state.selectedMatch) return;
    resetBroadcastChoices();
    refs.broadcastList.innerHTML = matchBroadcastMarkup(state.selectedMatch);
  }

  function hideMatch() {
    state.selectedMatch = null;
    refs.matchModal.classList.add("is-hidden");
    if (refs.leagueDrawer.classList.contains("is-hidden")) document.body.classList.remove("modal-open");
  }

  function setTab(tab) {
    state.tab = tab;
    if (tab === "todos") state.leagueSlug = "";
    document.querySelectorAll("[data-tab]").forEach((button) => button.classList.toggle("is-active", button.dataset.tab === tab));
    renderDateRail();
    if (tab === "ligas") {
      const catalog = [...(state.leagueCatalog.length ? state.leagueCatalog : state.data?.leagues || [])]
        .sort((a, b) => leagueOrder(a) - leagueOrder(b));
      state.leagueSlug = state.leagueSlug || catalog[0]?.slug || "";
      renderLeagueRail();
      renderLeagueDrawer();
      loadLeagueWindow(state.leagueSlug);
    } else loadDate(state.date === "window" ? state.today : state.date);
  }

  function selectLeague(slug) {
    hideLeagueDrawer();
    if (state.tab === "ligas") {
      state.leagueSlug = slug;
      renderLeagueRail();
      renderLeagueDrawer();
      loadLeagueWindow(slug);
      return;
    }
    state.leagueSlug = slug;
    renderAll();
  }

  window.addEventListener("scroll", updateStickyLeague, { passive: true });
  function cancelAutoScrollForManualInteraction(event) {
    if (event.target === refs.liveOnlyToggle || refs.liveOnlyToggle.contains(event.target)) return;
    state.autoScrollRequested = false;
    state.autoScrollDone = true;
    // Interrompe uma rolagem suave que ainda esteja em andamento.
    window.scrollTo({ top: window.scrollY, behavior: "auto" });
  }
  window.addEventListener("wheel", cancelAutoScrollForManualInteraction, { passive: true });
  window.addEventListener("touchstart", cancelAutoScrollForManualInteraction, { passive: true });
  window.addEventListener("pointerdown", cancelAutoScrollForManualInteraction, { passive: true });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") stopLiveStream();
    else startLiveStream();
  });

  document.addEventListener("change", (event) => {
    if (event.target === refs.liveOnlyToggle) {
      state.liveOnly = event.target.checked;
      state.autoScrollRequested = true;
      state.pendingScrollMode = "today-nearest";
      renderContent();
    }
  });

  document.addEventListener("click", (event) => {
    const target = event.target.closest("button");
    const playable = event.target.closest(".broadcast-item.is-playable");
    if (playable?.dataset.playOptions) {
      let streams = [];
      try { streams = JSON.parse(playable.dataset.playOptions) || []; } catch (_) { streams = []; }
      if (streams.length > 1) {
        showStreamChoices(playable, streams);
      } else if (streams[0]?.url) {
        nativeIptv()?.playStream?.(streams[0].url, streams[0].name || playable.dataset.playTitle || "");
      }
      return;
    }
    if (playable?.dataset.playUrl) {
      nativeIptv()?.playStream?.(playable.dataset.playUrl, playable.dataset.playTitle || "");
      return;
    }
    if (!target) return;
    if (target.dataset.action === "back-to-channels") { backToInitialChannels(); return; }
    if (target.dataset.iptvMode) {
      state.iptvMode = target.dataset.iptvMode;
      document.querySelectorAll("[data-iptv-mode]").forEach((button) => button.classList.toggle("is-active", button.dataset.iptvMode === state.iptvMode));
      refs.iptvXtreamFields.classList.toggle("is-hidden", state.iptvMode !== "xtream");
      refs.iptvM3uFields.classList.toggle("is-hidden", state.iptvMode !== "m3u");
      return;
    }
    if (target.dataset.action === "open-iptv") { showIptv(); return; }
    if (target.dataset.action === "close-iptv") { hideIptv(); return; }
    if (target.dataset.action === "connect-iptv") {
      if (state.iptvMode === "m3u") nativeIptv()?.connectM3u?.(document.querySelector("#iptv-m3u")?.value || "");
      else nativeIptv()?.connectXtream?.(document.querySelector("#iptv-server")?.value || "", document.querySelector("#iptv-user")?.value || "", document.querySelector("#iptv-password")?.value || "");
      beginIptvPoll(); return;
    }
    if (target.dataset.action === "refresh-iptv") {
      nativeIptv()?.refresh?.();
      beginIptvPoll(); return;
    }
    if (target.dataset.action === "disconnect-iptv") { nativeIptv()?.disconnect?.(); syncIptvUi(); return; }
    if (target.dataset.date) {
      const scrollMode = target.dataset.date === state.today ? "today-nearest" : "top";
      loadDate(target.dataset.date, scrollMode);
      return;
    }
    if (target.dataset.tab) {
      setTab(target.dataset.tab);
      return;
    }
    if (target.dataset.league !== undefined) {
      selectLeague(target.dataset.league);
      return;
    }
    if (target.dataset.matchId) {
      const match = (state.data?.matches || []).find((item) => String(item.id) === String(target.dataset.matchId));
      if (match && isSupportedDate(String(match.date || ""))) showMatch(match);
      return;
    }
    if (target.dataset.action === "open-leagues") showLeagueDrawer();
    if (target.dataset.action === "close-leagues") hideLeagueDrawer();
    if (target.dataset.action === "close-match") hideMatch();
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    hideLeagueDrawer();
    hideMatch();
  });

  state.today = getInitialDate();
  state.date = state.today;
  renderDateRail();
  loadDate(state.today);
})();
