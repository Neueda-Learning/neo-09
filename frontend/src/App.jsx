import React, { useCallback, useEffect, useState } from "react";
import {
  AppShell,
  Button,
  SideBrand,
  SideNav,
  StatusPill,
} from "./design-system";
import CaseBoardScreen from "./components/CaseBoardScreen.jsx";
import CaseDetailScreen from "./components/CaseDetailScreen.jsx";
import { api } from "./api.js";

const POLL_MS = 2000;
const HEALTH_MS = 10000;

const SCREENS = [
  { id: "cases", label: "Cases" },
  { id: "detail", label: "Detail" },
];

/**
 * The identity box is driven by `/info`, so the same image still takes its team and service
 * identity from environment configuration rather than hard-coded copy.
 */
export default function App() {
  const [screen, setScreen] = useState("cases");
  const [queue, setQueue] = useState({ totalOpen: 0, breached: 0, cases: [] });
  const [selectedCaseId, setSelectedCaseId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [applicant, setApplicant] = useState(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState(null);
  const [detailError, setDetailError] = useState(null);
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

  const openCase = useCallback(async (caseId) => {
    setSelectedCaseId(caseId);
    setScreen("detail");
    setDetailLoading(true);
    setDetailError(null);
    setApplicantError(null);
    try {
      const record = await api.getCase(caseId);
      setDetail(record);
      try {
        setApplicant(await api.getApplicant(caseId));
      } catch (applicantFailure) {
        setApplicant(null);
        setApplicantError(applicantFailure.message);
      }
    } catch (e) {
      setDetail(null);
      setApplicant(null);
      setDetailError(e.message);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload]);

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

  return (
    <AppShell
      side={
        <>
          <SideBrand
            brand={info?.team ?? "Team"}
            product={info?.service ?? "Module"}
            meta={info ? `${info.serviceId} · ${info.domain}` : undefined}
          />
          <SideNav items={SCREENS} active={screen} onSelect={setScreen} />
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
          error={error}
          loading={loading}
          info={info}
          onOpenCase={openCase}
        />
      )}
      {screen === "detail" && (
        <CaseDetailScreen
          caseId={selectedCaseId}
          onBack={() => setScreen("cases")}
          error={detailError}
          applicantError={applicantError}
          loading={detailLoading}
          detail={detail}
          applicant={applicant}
        />
      )}
    </AppShell>
  );
}
