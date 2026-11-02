(() => {
  "use strict";

  // WebViews antigos, como alguns Galaxy S9, ainda não implementam replaceAll.
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
    requestId: 0,
    liveSnapshotReady: false,
    autoScrollDone: false,
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
    todayToolbar: document.querySelector("#today-toolbar"),
    stickyLeagueName: document.querySelector("#sticky-league-name"),
    liveOnlyToggle: document.querySelector("#live-only-toggle"),
  };

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
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
    return value === state.today || value === addDays(state.today, 1);
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
      ? `<img class="${className}" src="${escapeHtml(src)}" data-fallback="${escapeHtml(initials(name))}" data-logo-class="${escapeHtml(className)}" alt="" loading="lazy" referrerpolicy="no-referrer">`
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
    if (match?.status === "scheduled") return 2;
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
  const FRONTEND_CACHE_VERSION = "v18";
  const LIVE_POLL_INTERVAL_MS = 45_000;
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
      refs.empty.classList.add("is-hidden");
      refs.error.classList.add("is-hidden");
      refs.todayToolbar.classList.add("is-hidden");
      refs.content.replaceChildren();
    }
  }

  async function loadDate(date) {
    const requestId = ++state.requestId;
    state.date = date;
    state.leagueSlug = "";
    state.liveOnly = false;
    state.liveSnapshotReady = date !== state.today;
    state.autoScrollDone = false;
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

  function localLiveLabel(match) {
    if (match?.status !== "live") return null;
    const now = Date.now();
    const additionMatch = typeof match.clockDisplay === "string" ? match.clockDisplay.match(/^(\d+)\+(\d+)'$/) : null;
    const officialSeconds = Number(match.clockSeconds);

    // Quando a API já informa o acréscimo, ele é a âncora do relógio.
    // Assim, `45+3` começa em 45+3 e avança para 45+4, 45+5 etc.
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
        match._liveClockSyncedAt = now;
      }
      const elapsedSinceSync = Math.max(0, Math.floor((now - match._liveClockSyncedAt) / 1000));
      const displayedExtraMinutes = Math.max(addedMinutes, Math.floor((match._liveClockBaseSeconds + elapsedSinceSync - regulationMinutes * 60) / 60));
      return `Ao vivo ${regulationMinutes}+${displayedExtraMinutes}'`;
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
      match._liveClockSyncedAt = now;
    }

    const elapsedSinceSync = Math.max(0, Math.floor((now - match._liveClockSyncedAt) / 1000));
    // A API já entrega clockSeconds como minuto absoluto da partida.
    // No segundo tempo, 56m continua sendo 56m — nunca 45 + 56.
    const totalSeconds = match._liveClockBaseSeconds + elapsedSinceSync;
    return `Ao vivo ${Math.floor(totalSeconds / 60)}'`;
  }

  function displayStatusLabel(match) {
    if (match?.status === "scheduled") return formatMatchWeekday(match.date);
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

  function matchCard(match) {
    const matchDate = String(match.date || "");
    const date = matchDate.length >= 10 ? `${matchDate.slice(8, 10)}/${matchDate.slice(5, 7)}` : "—";
    const statusLabel = displayStatusLabel(match);
    const live = match.status === "live" || match.status === "halftime";
    return `<button type="button" class="match-card" data-match-id="${escapeHtml(match.id)}" data-league-name="${escapeHtml(match.league)}">
      <div class="match-league">${logoOrFallback(match.leagueLogo, match.league, "match-league-logo")}<span>${escapeHtml(match.league)}</span></div>
      <div class="match-grid">
        <div class="team-column">${logoOrFallback(match.home?.logo, match.home?.name, "team-logo")}<span class="team-name" title="${escapeHtml(match.home?.shortName)}">${escapeHtml(match.home?.shortName)}</span></div>
        <div class="score-column">
          <span class="score" data-live-field="score">${escapeHtml(match.score || match.kickoff || "—")}</span>
          <span class="match-date">${escapeHtml(date)}</span>
          <span class="status-pill${live ? " is-live" : ""}" data-live-field="status">${matchStatusMarkup(match, statusLabel, live)}</span>
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
            .filter((match) => match.status === "live" || match.status === "halftime")
            .map((match) => ({
              id: match.id,
              score: match.score,
              status: match.status,
              statusLabel: match.statusLabel,
              clockSeconds: match.clockSeconds,
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
      const previous = {
        score: current.score,
        status: current.status,
        statusLabel: current.statusLabel,
        clock: current.clock,
        clockDisplay: current.clockDisplay,
        period: current.period,
      };
      Object.assign(current, update);
      if (!card) {
        needsRender = true;
        return;
      }
      if (current.status !== "live") {
        delete current._liveClockBaseSeconds;
        delete current._liveClockSyncedAt;
      }
      const scoreValue = current.score || current.kickoff || "—";
      const statusValue = displayStatusLabel(current);
      const live = current.status === "live" || current.status === "halftime";
      const score = card.querySelector('[data-live-field="score"]');
      const status = card.querySelector('[data-live-field="status"]');
      if (score && score.textContent !== scoreValue) score.textContent = scoreValue;
      const statusChanged = previous.status !== current.status || previous.statusLabel !== current.statusLabel || previous.clock !== current.clock || previous.clockDisplay !== current.clockDisplay || previous.period !== current.period;
      if (status && statusChanged) {
        status.classList.toggle("is-live", live);
        status.innerHTML = matchStatusMarkup(current, statusValue, live);
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
      if (liveTarget) {
        state.autoScrollDone = true;
        scrollToMatch(liveTarget);
      } else if (state.liveSnapshotReady && !state.autoScrollDone && state.date === state.today) {
        const nextTarget = chooseAutoScrollMatch(state.data?.matches || []);
        if (nextTarget) {
          state.autoScrollDone = true;
          scrollToMatch(nextTarget);
        }
      }
    }
  }

  async function primeLiveSnapshot(context, generation) {
    try {
      const payload = await fetchLiveGames(context);
      if (generation !== liveStreamGeneration) return;
      state.liveSnapshotReady = true;
      applyLiveUpdates({
        matches: (payload.matches || [])
          .filter((match) => match.status === "live" || match.status === "halftime")
          .map((match) => ({
            id: match.id,
            score: match.score,
            status: match.status,
            statusLabel: match.statusLabel,
            clockSeconds: match.clockSeconds,
            clockDisplay: match.clockDisplay,
            period: match.period,
            match,
          })),
      });
      if (!state.autoScrollDone && state.date === state.today) {
        const target = chooseAutoScrollMatch(state.data?.matches || []);
        if (target) {
          state.autoScrollDone = true;
          scrollToMatch(target);
        }
      }
    } catch (error) {
      console.warn("Não foi possível carregar o snapshot ao vivo", error);
      if (generation === liveStreamGeneration) {
        state.liveSnapshotReady = true;
        if (!state.autoScrollDone && state.date === state.today) {
          const nextTarget = chooseAutoScrollMatch(state.data?.matches || []);
          if (nextTarget) {
            state.autoScrollDone = true;
            scrollToMatch(nextTarget);
          }
        }
      }
    }
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

  function chooseAutoScrollMatch(matches) {
    const ordered = chronologicalMatches(matches)
      .map((match) => ({ match, timestamp: kickoffTimestamp(match) }))
      .filter((item) => Number.isFinite(item.timestamp));
    if (!ordered.length) return null;

    // A partida ao vivo sempre tem prioridade, independentemente do horário.
    const liveTarget = ordered.find(({ match }) => match.status === "live" || match.status === "halftime");
    if (liveTarget) return liveTarget.match;

    // Sem partida ao vivo, escolhe o próximo jogo agendado mais próximo de agora.
    const now = Date.now();
    const nextScheduled = ordered.find(({ match, timestamp }) => match.status === "scheduled" && timestamp >= now);
    if (nextScheduled) return nextScheduled.match;

    // Se todos os jogos já passaram, mantém o último agendado como fallback.
    const lastScheduled = [...ordered].reverse().find(({ match }) => match.status === "scheduled");
    return lastScheduled?.match || ordered[0].match;
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
    else if (state.liveSnapshotReady) scrollToNearestTodayMatch(selected);
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
    const channels = isSupportedDate(String(match.date || "")) ? (match.channels || []) : [];
    refs.broadcastList.innerHTML = channels.length
      ? channels.map((channel) => `<li class="broadcast-item"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9v6M8 6v12M12 3v18M16 7v10M20 10v4" /></svg>${escapeHtml(channel)}</li>`).join("")
      : `<li class="broadcast-item broadcast-unavailable">Canais não informados para esta data.</li>`;
    refs.matchModal.classList.remove("is-hidden");
    document.body.classList.add("modal-open");
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
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") stopLiveStream();
    else startLiveStream();
  });

  document.addEventListener("change", (event) => {
    if (event.target === refs.liveOnlyToggle) {
      state.liveOnly = event.target.checked;
      renderContent();
    }
  });

  document.addEventListener("click", (event) => {
    const target = event.target.closest("button");
    if (!target) return;
    if (target.dataset.date) {
      loadDate(target.dataset.date);
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
