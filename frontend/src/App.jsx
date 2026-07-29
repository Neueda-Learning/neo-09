import React, { useCallback, useEffect, useState } from "react";
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

const POLL_MS = 2000;
const HEALTH_MS = 10000;

const SCREENS = [
  { id: "cases", label: "Cases" },
  { id: "sla", label: "SLA & Breach" },
  { id: "detail", label: "Detail" },
  { id: "config", label: "Configuration" },
];

/**
 * The identity box is driven by `/info`, so the same image still takes its team and service
 * identity from environment configuration rather than hard-coded copy.
 */
export default function App() {
  const [screen, setScreen] = useState("cases");
  const [queue, setQueue] = useState({ totalOpen: 0, breached: 0, cases: [] });
  const [sla, setSla] = useState({ referenceNow: null, byPriority: [], breachedCases: [] });
  const [slaLoading, setSlaLoading] = useState(true);
  const [slaError, setSlaError] = useState(null);
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState(null);
  const [applicantNames, setApplicantNames] = useState({});
  const [selectedCaseId, setSelectedCaseId] = useState(null);
  const [configVersion, setConfigVersion] = useState(null);
  const [detail, setDetail] = useState(null);
  const [applicant, setApplicant] = useState(null);
  const [applicantLoading, setApplicantLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState(null);
  const [detailError, setDetailError] = useState(null);
  const [transitionError, setTransitionError] = useState(null);
  const [transitionLoading, setTransitionLoading] = useState(false);
  const [applicantError, setApplicantError] = useState(null);
  const [health, setHealth] = useState(null);
  const [info, setInfo] = useState(null);

  const reload = useCallback(async () => {
    try {
      setQueue(await api.queue());
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadSla = useCallback(async () => {
    try {
      setSla(await api.sla());
      setSlaError(null);
    } catch (e) {
      setSlaError(e.message);
    } finally {
      setSlaLoading(false);
    }
  }, []);

  const loadApplicant = useCallback(async (caseId) => {
    setApplicantLoading(true);
    setApplicantError(null);
    try {
      setApplicant(await api.getApplicant(caseId));
    } catch (applicantFailure) {
      setApplicant(null);
      setApplicantError(applicantFailure.message);
    } finally {
      setApplicantLoading(false);
    }
  }, []);

  const openCase = useCallback(async (caseId) => {
    setSelectedCaseId(caseId);
    setScreen("detail");
    setDetailLoading(true);
    setDetailError(null);
    setTransitionError(null);
    setApplicant(null);
    loadApplicant(caseId);
    try {
      setDetail(await api.getCase(caseId));
    } catch (e) {
      setDetail(null);
      setDetailError(e.message);
    } finally {
      setDetailLoading(false);
    }
  }, [loadApplicant]);

  const openConfig = useCallback((version = null) => {
    setConfigVersion(version);
    setScreen("config");
  }, []);

  useEffect(() => {
    const normalized = query.trim();
    if (!normalized) {
      setSearchResults([]);
      setSearchError(null);
      setSearchLoading(false);
      return undefined;
    }

    let active = true;
    const id = setTimeout(async () => {
      setSearchLoading(true);
      try {
        const matches = await api.searchCases({
          query: normalized,
          limit: 10,
        });
        if (active) {
          setSearchResults(matches);
          setSearchError(null);
        }
      } catch (searchFailure) {
        if (active) {
          setSearchResults([]);
          setSearchError(searchFailure.message);
        }
      } finally {
        if (active) setSearchLoading(false);
      }
    }, 300);

    return () => {
      active = false;
      clearTimeout(id);
    };
  }, [query]);

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
            return [
              supportCase.caseId,
              application.applicant?.fullName ?? "—",
            ];
          } catch {
            return [supportCase.caseId, "—"];
          }
        }),
      );
      if (active) setApplicantNames(Object.fromEntries(entries));
    };

    if (visibleCases.length === 0) {
      setApplicantNames({});
    } else {
      hydrateNames();
    }
    return () => {
      active = false;
    };
  }, [visibleCaseKey]);

  useEffect(() => {
    reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload]);

  useEffect(() => {
    reloadSla();
    const id = setInterval(reloadSla, POLL_MS);
    return () => clearInterval(id);
  }, [reloadSla]);

  const refreshHealth = useCallback(async () => {
    try {
      const [h, i] = await Promise.all([api.health(), api.info()]);
      setHealth(h);
      setInfo(i);
    } catch {
      setHealth(null);
    }
  }, []);

  useEffect(() => {
    refreshHealth();
    const id = setInterval(refreshHealth, HEALTH_MS);
    return () => clearInterval(id);
  }, [refreshHealth]);

  const up = !error && health?.status === "UP";

  const transitionCase = useCallback(
    async ({ action, actor, note }) => {
      if (!selectedCaseId) return;
      setTransitionLoading(true);
      setTransitionError(null);
      try {
        const updated = await api.transitionCase(selectedCaseId, {
          action,
          actor,
          note,
        });
        setDetail(updated);
        await reload();
      } catch (failure) {
        setTransitionError(failure.message);
      } finally {
        setTransitionLoading(false);
      }
    },
    [reload, selectedCaseId],
  );

  return (
    <AppShell
      side={
        <>
          <SideBrand
            brand={info?.team ?? "Team"}
            product={info?.service ?? "Module"}
            meta={info ? `${info.serviceId} · ${info.domain}` : undefined}
          />
          <SideNav
            items={SCREENS}
            active={screen}
            onSelect={(nextScreen) => {
              if (nextScreen === "config") setConfigVersion(null);
              setScreen(nextScreen);
            }}
          />
          {/* Health and refresh lived in the top bar; with the bar gone they belong beside the
              menu rather than inside it — a menu item that is not a screen is a trap. */}
          <div className="app-side-status">
            <StatusPill tone={up ? "positive" : "negative"}>
              {up ? "Up" : "Down"}
            </StatusPill>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                reload();
                refreshHealth();
              }}
            >
              Refresh
            </Button>
          </div>
        </>
      }
      footer="Customer support · cases come from the orchestrator, never from this UI"
    >
      {screen === "cases" && (
        <CaseBoardScreen
          queue={queue}
          query={query}
          onQueryChange={setQuery}
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
          onBack={() => setScreen("cases")}
          onRetry={() => loadApplicant(selectedCaseId)}
          error={detailError}
          transitionError={transitionError}
          transitionLoading={transitionLoading}
          onTransition={transitionCase}
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
