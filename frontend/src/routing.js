export function normalizeBasePath(baseUrl = "/") {
  return baseUrl === "/" ? "" : baseUrl.replace(/\/$/, "");
}

export function localPath(path, basePath = "") {
  return `${basePath}${path}` || "/";
}

export function readRoute(location, basePath = "") {
  let path = location.pathname;
  if (basePath && path.startsWith(basePath)) {
    path = path.slice(basePath.length) || "/";
  }

  const detailMatch = path.match(/^\/cases\/([^/]+)\/?$/);
  if (detailMatch) {
    return {
      screen: "detail",
      caseId: decodeURIComponent(detailMatch[1]),
      configVersion: null,
    };
  }
  if (/^\/sla\/?$/.test(path)) {
    return { screen: "sla", caseId: null, configVersion: null };
  }
  if (/^\/config\/?$/.test(path)) {
    const version = Number(new URLSearchParams(location.search).get("version"));
    return {
      screen: "config",
      caseId: null,
      configVersion: Number.isInteger(version) && version > 0 ? version : null,
    };
  }
  return { screen: "cases", caseId: null, configVersion: null };
}

export function readBoardFilters(search = "") {
  const params = new URLSearchParams(search);
  return {
    query: params.get("q") ?? "",
    status: params.get("status") ?? "",
    priority: params.get("priority") ?? "All",
  };
}

export function boardUrl({ query, status, priority }, basePath = "") {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  if (status) params.set("status", status);
  if (priority !== "All") params.set("priority", priority);
  const suffix = params.toString();
  return localPath(`/cases${suffix ? `?${suffix}` : ""}`, basePath);
}
