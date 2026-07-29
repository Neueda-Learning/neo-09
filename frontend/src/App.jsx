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

const SCREENS = [
  { id: "cases", label: "Case board", hint: "Queue and search" },
  { id: "sla", label: "SLA oversight", hint: "Load and breaches" },
  { id: "config", label: "Configuration", hint: "Policy and history" },
];

export default function App() {
  const initialRoute = useRef(readRoute(window.location, BASE_PATH)).current;
  const initialFilters = useRef(readBoardFilters(window.location.search)).current;
  const [screen, setScreen] = useState(initialRoute.screen);
  const [queue, setQueue] = useState({ totalOpen: 0, breached: 0, cases: [] });
  const [sla, setSla] = useState({ referenceNow: null, byPriority: [], breachedCases: [] });
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
  const [applicantError, setApplicantError] = useState(null);
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

    api.getCase(selectedCaseId)
      .then((result) => {
        if (active) setDetail(result);
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
    if (!normalized) {
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
          query: normalized,
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

  const visibleCases = query.trim() ? searchResults : queue.cases;
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

  const up = !error && health?.status === "UP";
  const activeNav = screen === "detail" ? "cases" : screen;

  return (
    <AppShell
      wide
      side={
        <>
          <SideBrand
            brand={info?.team ?? "Operations"}
            product="Support Control"
            meta={info ? `${info.serviceId} · ${info.domain}` : "Customer case management"}
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
      footer="Support Control · application data is fetched live and never copied into this service"
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
          applicantError={applicantError}
          applicantLoading={applicantLoading}
          loading={detailLoading}
          detail={detail}
          applicant={applicant}
          onViewConfig={openConfig}
        />
      )}
      {screen === "config" && (
        <CaseConfigScreen info={info} initialVersion={configVersion} />
      )}
    </AppShell>
  );
}
