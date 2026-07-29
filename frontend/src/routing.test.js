import assert from "node:assert/strict";
import test from "node:test";

import {
  boardUrl,
  normalizeBasePath,
  readBoardFilters,
  readRoute,
} from "./routing.js";

test("case detail routes decode the caseId under a deployment prefix", () => {
  assert.deepEqual(
    readRoute(
      { pathname: "/neo-09/cases/case%2Fspecial", search: "" },
      "/neo-09",
    ),
    { screen: "detail", caseId: "case/special", configVersion: null },
  );
});

test("configuration routes select only a valid positive version", () => {
  assert.deepEqual(
    readRoute({ pathname: "/config", search: "?version=3" }),
    { screen: "config", caseId: null, configVersion: 3 },
  );
  assert.equal(
    readRoute({ pathname: "/config", search: "?version=invalid" }).configVersion,
    null,
  );
});

test("unknown paths safely resolve to the case board", () => {
  assert.equal(readRoute({ pathname: "/unknown", search: "" }).screen, "cases");
});

test("board filters round-trip through a prefixed URL", () => {
  const url = boardUrl(
    { query: " app-1001 ", status: "OPEN", priority: "P1" },
    normalizeBasePath("/neo-09/"),
  );
  assert.equal(url, "/neo-09/cases?q=app-1001&status=OPEN&priority=P1");
  assert.deepEqual(readBoardFilters(url.slice(url.indexOf("?"))), {
    query: "app-1001",
    status: "OPEN",
    priority: "P1",
  });
});

test("cleared filters restore the default queue URL", () => {
  assert.equal(
    boardUrl({ query: " ", status: "", priority: "All" }),
    "/cases",
  );
});
