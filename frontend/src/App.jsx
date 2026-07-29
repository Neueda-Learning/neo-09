import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  AppShell,
  Button,
  SideBrand,
  SideNav,
  StatusPill,
} from "./design-system";
import CaseBoardScreen from "./components/CaseBoardScreen.jsx";
import CaseConfigScreen from "./components/CaseConfigScreen.jsx";
import CaseDetailScreen from "./components/CaseDetailScreen.jsx";
import SlaBoardScreen from "./components/SlaBoardScreen.jsx";
import { api } from "./api.js";
import {
  boardUrl,
  localPath,
  normalizeBasePath,
  readBoardFilters,
  readRoute,
} from "./routing.js";

const POLL_MS = 2000;
const HEALTH_MS = 10000;
const BASE_PATH = normalizeBasePath(import.meta.env.BASE_URL);

function NavIcon({ name }) {
  const paths = {
    cases: (
      <>
        <rect x="3" y="4" width="18" height="16" rx="3" />
        <path d="M7 9h10M7 14h6" />
      </>
    ),
    sla: (
      <>
        <path d="M12 3v9l5 3" />
        <circle cx="12" cy="12" r="9" />
      </>
    ),
    config: (
      <>
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.1A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.13.4.36.75.68 1 .3.24.7.38 1.1.4h.1v4h-.1A1.7 1.7 0 0 0 19.4 15Z" />
      </>
    ),
  };

  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      {paths[name]}
    </svg>
  );
}

const SCREENS = [
  { id: "cases", label: "Case board", hint: "Queue and search", icon: <NavIcon name="cases" /> },
  { id: "sla", label: "SLA oversight", hint: "Load and breaches", icon: <NavIcon name="sla" /> },
  { id: "config", label: "Configuration", hint: "Policy and history", icon: <NavIcon name="config" /> },
];

export default function App() {
  const initialRoute = useRef(readRoute(window.location, BASE_PATH)).current;
  const initialFilters = useRef(readBoardFilters(window.location.search)).current;
  const [screen, setScreen] = useState(initialRoute.screen);
  const [queue, setQueue] = useState({ totalOpen: 0, breached: 0, cases: [] });
  const [sla, setSla] = useState({
    referenceNow: null,
    byPriority: [],
    breachedCases: [],
    csatByCategory: [],
  });
  const [slaLoading, setSlaLoading] = useState(true);
  const [slaError, setSlaError] = useState(null);
  const [query, setQuery] = useState(initialFilters.query);
  const [searchStatus, setSearchStatus] = useState(initialFilters.status);
  const [priority, setPriority] = useState(initialFilters.priority);
  const [searchResults, setSearchResults] = useState([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState(null);
  const [applicantNames, setApplicantNames] = useState({});
  const [selectedCaseId, setSelectedCaseId] = useState(initialRoute.caseId);
  const [configVersion, setConfigVersion] = useState(initialRoute.configVersion);
  const [detail, setDetail] = useState(null);
  const [applicant, setApplicant] = useState(null);
  const [applicantLoading, setApplicantLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState(null);
  const [detailError, setDetailError] = useState(null);
  const [transitionError, setTransitionError] = useState(null);
  const [transitionLoading, setTransitionLoading] = useState(false);
  const [supervisorError, setSupervisorError] = useState(null);
  const [supervisorLoading, setSupervisorLoading] = useState(false);
  const [csatError, setCsatError] = useState(null);
  const [csatLoading, setCsatLoading] = useState(false);
  const [applicantError, setApplicantError] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [suggestionLoading, setSuggestionLoading] = useState(false);
  const [suggestionError, setSuggestionError] = useState(null);
  const [health, setHealth] = useState(null);
  const [info, setInfo] = useState(null);
  const boardUrlRef = useRef(boardUrl(initialFilters, BASE_PATH));

  const applyRoute = useCallback((route) => {
    setScreen(route.screen);
    setSelectedCaseId(route.caseId);
    setConfigVersion(route.configVersion);
  }, []);

  const navigate = useCallback(
    (path, { replace = false, state = {} } = {}) => {
      window.history[replace ? "replaceState" : "pushState"](
        state,
        "",
        localPath(path, BASE_PATH),
      );
      applyRoute(readRoute(window.location, BASE_PATH));
      window.scrollTo({ top: 0, behavior: "auto" });
    },
    [applyRoute],
  );

  useEffect(() => {
    const onPopState = (event) => {
      applyRoute(readRoute(window.location, BASE_PATH));
      window.requestAnimationFrame(() => {
        window.scrollTo({ top: event.state?.scrollY ?? 0, behavior: "auto" });
      });
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, [applyRoute]);

  useEffect(() => {
    if (screen !== "cases") return;
    const nextUrl = boardUrl(
      { query, status: searchStatus, priority },
      BASE_PATH,
    );
    boardUrlRef.current = nextUrl;
    window.history.replaceState(
      { ...window.history.state, scrollY: window.scrollY },
      "",
      nextUrl,
    );
  }, [priority, query, screen, searchStatus]);

  const reload = useCallback(async () => {
    try {
      setQueue(await api.queue());
      setError(null);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadSla = useCallback(async () => {
    try {
      setSla(await api.sla());
      setSlaError(null);
    } catch (failure) {
      setSlaError(failure.message);
    } finally {
      setSlaLoading(false);
    }
  }, []);

  const loadApplicant = useCallback(async (caseId) => {
    if (!caseId) return;
    setApplicantLoading(true);
    setApplicantError(null);
    try {
      setApplicant(await api.getApplicant(caseId));
    } catch (failure) {
      setApplicant(null);
      setApplicantError(failure.message);
    } finally {
      setApplicantLoading(false);
    }
  }, []);

  useEffect(() => {
    if (screen !== "detail" || !selectedCaseId) return;
    let active = true;
    setDetail(null);
    setApplicant(null);
    setDetailLoading(true);
    setApplicantLoading(true);
    setDetailError(null);
    setApplicantError(null);
    setTransitionError(null);
    setSupervisorError(null);
    setCsatError(null);
    setSuggestions([]);
    setSuggestionLoading(false);
    setSuggestionError(null);

    api.getCase(selectedCaseId)
      .then((result) => {
        if (!active) return;
        setDetail(result);
        if (result.category === "OTHER") {
          setSuggestionLoading(true);
          api.suggestCategory(selectedCaseId)
            .then((matches) => {
              if (active) setSuggestions(matches);
            })
            .catch((failure) => {
              if (active) setSuggestionError(failure.message);
            })
            .finally(() => {
              if (active) setSuggestionLoading(false);
            });
        }
      })
      .catch((failure) => {
        if (active) setDetailError(failure.message);
      })
      .finally(() => {
        if (active) setDetailLoading(false);
      });

    api.getApplicant(selectedCaseId)
      .then((result) => {
        if (active) setApplicant(result);
      })
      .catch((failure) => {
        if (active) setApplicantError(failure.message);
      })
      .finally(() => {
        if (active) setApplicantLoading(false);
      });

    return () => {
      active = false;
    };
  }, [screen, selectedCaseId]);

  const openCase = useCallback((caseId) => {
    const returnTo =
      window.location.pathname + window.location.search + window.location.hash;
    window.history.replaceState(
      { ...window.history.state, scrollY: window.scrollY },
      "",
      returnTo,
    );
    navigate(`/cases/${encodeURIComponent(caseId)}`, {
      state: {
        returnTo,
        returnLabel: screen === "sla" ? "SLA oversight" : "case board",
      },
    });
  }, [navigate, screen]);

  const backFromCase = useCallback(() => {
    if (window.history.state?.returnTo) {
      window.history.back();
    } else {
      window.history.pushState({}, "", boardUrlRef.current);
      applyRoute(readRoute(window.location, BASE_PATH));
    }
  }, [applyRoute]);

  const openConfig = useCallback(
    (version = null) => {
      const queryString = version == null ? "" : `?version=${version}`;
      navigate(`/config${queryString}`);
    },
    [navigate],
  );

  useEffect(() => {
    const normalized = query.trim();
    if (!normalized && !searchStatus) {
      setSearchResults([]);
      setSearchError(null);
      setSearchLoading(false);
      return undefined;
    }

    let active = true;
    const id = window.setTimeout(async () => {
      setSearchLoading(true);
      try {
        const matches = await api.searchCases({
          query: normalized || undefined,
          status: searchStatus || undefined,
          limit: 10,
        });
        if (active) {
          setSearchResults(matches);
          setSearchError(null);
        }
      } catch (failure) {
        if (active) {
          setSearchResults([]);
          setSearchError(failure.message);
        }
      } finally {
        if (active) setSearchLoading(false);
      }
    }, 300);

    return () => {
      active = false;
      window.clearTimeout(id);
    };
  }, [query, searchStatus]);

  const filteringCases = Boolean(query.trim() || searchStatus);
  const visibleCases = filteringCases ? searchResults : queue.cases;
  const visibleCaseKey = visibleCases
    .map((supportCase) => `${supportCase.caseId}:${supportCase.applicationId}`)
    .join("|");

  useEffect(() => {
    let active = true;
    const hydrateNames = async () => {
      const entries = await Promise.all(
        visibleCases.slice(0, 10).map(async (supportCase) => {
          try {
            const application = await api.getApplicant(supportCase.caseId);
            return [supportCase.caseId, application.applicant?.fullName ?? "—"];
          } catch {
            return [supportCase.caseId, "—"];
          }
        }),
      );
      if (active) setApplicantNames(Object.fromEntries(entries));
    };

    if (visibleCases.length === 0) setApplicantNames({});
    else hydrateNames();
    return () => {
      active = false;
    };
  }, [visibleCaseKey]);

  useEffect(() => {
    reload();
    const id = window.setInterval(reload, POLL_MS);
    return () => window.clearInterval(id);
  }, [reload]);

  useEffect(() => {
    reloadSla();
    const id = window.setInterval(reloadSla, POLL_MS);
    return () => window.clearInterval(id);
  }, [reloadSla]);

  const refreshHealth = useCallback(async () => {
    try {
      const [healthResult, infoResult] = await Promise.all([api.health(), api.info()]);
      setHealth(healthResult);
      setInfo(infoResult);
    } catch {
      setHealth(null);
    }
  }, []);

  useEffect(() => {
    refreshHealth();
    const id = window.setInterval(refreshHealth, HEALTH_MS);
    return () => window.clearInterval(id);
  }, [refreshHealth]);

  const transitionCase = useCallback(
    async ({ action, actor, note }) => {
      if (!selectedCaseId) return false;
      setTransitionLoading(true);
      setTransitionError(null);
      try {
        const updated = await api.transitionCase(selectedCaseId, { action, actor, note });
        setDetail(updated);
        await Promise.all([reload(), reloadSla()]);
        return true;
      } catch (failure) {
        setTransitionError(failure.message);
        return false;
      } finally {
        setTransitionLoading(false);
      }
    },
    [reload, reloadSla, selectedCaseId],
  );

  const superviseCase = useCallback(
    async ({ action, reason, supervisor, assignee }) => {
      if (!selectedCaseId) return false;
      setSupervisorLoading(true);
      setSupervisorError(null);
      try {
        const updated = await api.superviseCase(selectedCaseId, {
          action,
          reason,
          supervisor,
          assignee,
        });
        setDetail(updated);
        await Promise.all([reload(), reloadSla()]);
        return true;
      } catch (failure) {
        setSupervisorError(failure.message);
        return false;
      } finally {
        setSupervisorLoading(false);
      }
    },
    [reload, reloadSla, selectedCaseId],
  );

  const recordCsat = useCallback(
    async ({ score, comment }) => {
      if (!selectedCaseId) return false;
      setCsatLoading(true);
      setCsatError(null);
      try {
        const updated = await api.recordCsat(selectedCaseId, { score, comment });
        setDetail(updated);
        await reloadSla();
        return true;
      } catch (failure) {
        setCsatError(failure.message);
        return false;
      } finally {
        setCsatLoading(false);
      }
    },
    [reloadSla, selectedCaseId],
  );

  const up = !error && health?.status === "UP";
  const activeNav = screen === "detail" ? "cases" : screen;

  return (
    <AppShell
      wide
      side={
        <>
          <SideBrand
            brand="Neo Bank"
            product="Customer Support"
            meta={info ? `${info.team ?? "Operations"} · ${info.domain}` : "Customer case management"}
          />
          <SideNav
            items={SCREENS}
            active={activeNav}
            onSelect={(nextScreen) => {
              if (nextScreen === "cases") {
                window.history.pushState({}, "", boardUrlRef.current);
                applyRoute(readRoute(window.location, BASE_PATH));
              } else {
                navigate(`/${nextScreen}`);
              }
            }}
          />
          <div className="app-side-status">
            <div>
              <span className="app-side-status__label">Service status</span>
              <StatusPill tone={up ? "positive" : "negative"}>
                {up ? "Operational" : "Unavailable"}
              </StatusPill>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                reload();
                reloadSla();
                refreshHealth();
              }}
            >
              Refresh
            </Button>
          </div>
        </>
      }
      footer="Customer Support · application data is fetched live and never copied into this service"
    >
      {screen === "cases" && (
        <CaseBoardScreen
          queue={queue}
          query={query}
          onQueryChange={setQuery}
          searchStatus={searchStatus}
          onSearchStatusChange={setSearchStatus}
          priority={priority}
          onPriorityChange={setPriority}
          searchResults={searchResults}
          searchLoading={searchLoading}
          searchError={searchError}
          applicantNames={applicantNames}
          error={error}
          loading={loading}
          info={info}
          onOpenCase={openCase}
        />
      )}
      {screen === "sla" && (
        <SlaBoardScreen
          sla={sla}
          loading={slaLoading}
          error={slaError}
          info={info}
          onOpenCase={openCase}
        />
      )}
      {screen === "detail" && (
        <CaseDetailScreen
          caseId={selectedCaseId}
          backLabel={window.history.state?.returnLabel ?? "case board"}
          onBack={backFromCase}
          onRetry={() => loadApplicant(selectedCaseId)}
          error={detailError}
          transitionError={transitionError}
          transitionLoading={transitionLoading}
          onTransition={transitionCase}
          supervisorError={supervisorError}
          supervisorLoading={supervisorLoading}
          onSupervisor={superviseCase}
          csatError={csatError}
          csatLoading={csatLoading}
          onRecordCsat={recordCsat}
          applicantError={applicantError}
          applicantLoading={applicantLoading}
          loading={detailLoading}
          detail={detail}
          applicant={applicant}
          suggestions={suggestions}
          suggestionLoading={suggestionLoading}
          suggestionError={suggestionError}
          onViewConfig={openConfig}
        />
      )}
      {screen === "config" && (
        <CaseConfigScreen info={info} initialVersion={configVersion} />
      )}
    </AppShell>
  );
}
