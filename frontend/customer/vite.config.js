import { randomUUID } from "node:crypto";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const TARGETS = {
  local: process.env.CUSTOMER_LOCAL_API_BASE || "http://127.0.0.1:8080",
  dev:
    process.env.CUSTOMER_DEV_API_BASE ||
    "http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-09",
  prod:
    process.env.CUSTOMER_PROD_API_BASE ||
    "http://neobank-prod-294820685.ap-southeast-1.elb.amazonaws.com/neo-09",
};

const CATEGORIES = new Set([
  "APPLICATION_STATUS",
  "CARD_NOT_ARRIVED",
  "AGREEMENT_QUESTION",
  "DATA_CORRECTION",
  "COMPLAINT",
  "OTHER",
]);

function mockOrchestrator() {
  return {
    name: "neo-09-customer-demo-orchestrator",
    configureServer(server) {
      server.middlewares.use("/demo-api/cases", async (request, response) => {
        if (request.method !== "POST") {
          return json(response, 405, { message: "method not allowed" });
        }

        try {
          const body = await readJson(request);
          const environment = body.environment || "local";
          const target = TARGETS[environment];
          if (!target) {
            return json(response, 400, { message: "unknown environment" });
          }
          if (
            environment === "prod" &&
            process.env.CUSTOMER_DEMO_ALLOW_PROD !== "true"
          ) {
            return json(response, 403, {
              message:
                "Prod demo writes are disabled. Start with CUSTOMER_DEMO_ALLOW_PROD=true to enable them explicitly.",
            });
          }

          const category = body.request?.category?.trim();
          const description = body.request?.description?.trim();
          if (!CATEGORIES.has(category)) {
            return json(response, 400, {
              message: "Choose a valid support category.",
            });
          }
          if (!description) {
            return json(response, 400, {
              message: "Tell us what happened.",
            });
          }

          const requestId = body.requestId || randomUUID();
          const applicationId = "SIM-01";
          const envelope = {
            applicationId,
            correlationId: `customer-${requestId}`,
            command: "open-case",
            application: mariaApplication(applicationId),
            outputs: {
              approvedLimit: 3000,
              apr: 24.9,
            },
            request: {
              category,
              description,
              channel: "WEB",
            },
          };

          const executeResponse = await fetch(
            `${target}/api/v1/support/execute`,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(envelope),
            },
          );
          const acknowledgement = await safeJson(executeResponse);
          if (!executeResponse.ok) {
            return json(response, executeResponse.status, {
              message:
                acknowledgement?.message ||
                `neo-09 returned HTTP ${executeResponse.status}`,
              environment,
            });
          }

          const createdCase = await findCreatedCase(
            target,
            applicationId,
            description,
          );
          return json(response, 202, {
            ...acknowledgement,
            caseId: createdCase?.caseId ?? null,
            case: createdCase,
            environment,
            demo: true,
            simulatedBy: "customer-demo-orchestrator",
          });
        } catch (error) {
          return json(response, 502, {
            message: `Mock orchestrator could not reach neo-09: ${error.message}`,
          });
        }
      });
    },
  };
}

async function findCreatedCase(target, applicationId, description) {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const searchResponse = await fetch(
      `${target}/api/v1/support/cases?q=${encodeURIComponent(applicationId)}&limit=10`,
    );
    if (searchResponse.ok) {
      const cases = await searchResponse.json();
      const match =
        cases.find((supportCase) => supportCase.description === description) ??
        null;
      if (match?.priority || attempt === 5) return match;
    }
    await new Promise((resolve) => setTimeout(resolve, 120));
  }
  return null;
}

function mariaApplication(applicationId) {
  return {
    applicationId,
    channel: "WEB",
    submittedAt: new Date().toISOString(),
    applicant: {
      fullName: "Maria Nowak",
      dateOfBirth: "1996-04-11",
      email: "maria.nowak@example.com",
      mobile: "+447700900123",
      nationality: "PL",
      countryOfResidence: "GB",
      taxResidencies: ["GB"],
      residentialStatus: "RENTING",
      currentAddress: {
        line1: "42 Hanbury Street",
        line2: null,
        city: "London",
        postcode: "E1 5JP",
        country: "GB",
      },
      monthsAtAddress: 14,
      dependants: 0,
    },
    identityDocument: {
      type: "PASSPORT",
      documentId: "DEMO-REDACTED",
      issuingCountry: "PL",
      expiryDate: "2031-02-28",
    },
    employment: {
      status: "PERMANENT",
      employerName: "Trellis Health Ltd",
      monthsInEmployment: 11,
    },
    finances: {
      annualIncome: 34000,
      monthlyHousingCost: 1000,
      existingCreditCommitments: 180,
    },
    product: {
      productCode: "CREDIT_CARD_REWARDS",
      requestedCreditLimit: 3000,
    },
    delivery: {
      useCurrentAddress: true,
      address: null,
    },
    consents: {
      termsAccepted: true,
      paperlessStatements: true,
      marketingConsent: false,
    },
  };
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 64_000) reject(new Error("request body is too large"));
    });
    request.on("end", () => {
      try {
        resolve(JSON.parse(body || "{}"));
      } catch {
        reject(new Error("request body is not valid JSON"));
      }
    });
    request.on("error", reject);
  });
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function json(response, status, body) {
  response.statusCode = status;
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify(body));
}

export default defineConfig({
  plugins: [react(), mockOrchestrator()],
  server: {
    host: "127.0.0.1",
    port: 5174,
    strictPort: true,
    fs: {
      allow: ["../.."],
    },
  },
});
