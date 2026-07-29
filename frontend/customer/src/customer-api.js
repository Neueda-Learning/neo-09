export const ENVIRONMENTS = {
  local: { label: "Local" },
  dev: { label: "Dev" },
  prod: { label: "Prod" },
};

const MODE_ENVIRONMENT = import.meta.env.MODE.endsWith("-prod")
  ? "prod"
  : import.meta.env.MODE.endsWith("-dev")
    ? "dev"
    : "local";

export const DEFAULT_ENVIRONMENT =
  import.meta.env.VITE_DEFAULT_ENVIRONMENT in ENVIRONMENTS
    ? import.meta.env.VITE_DEFAULT_ENVIRONMENT
    : MODE_ENVIRONMENT;

export async function submitCustomerCase(request, environment, requestId) {
  const response = await fetch("/demo-api/cases", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      environment,
      requestId,
      request: {
        category: request.category,
        description: request.description,
        channel: "WEB",
      },
    }),
  });

  if (!response.ok) {
    let message = "We could not submit your request. Please try again.";
    try {
      const body = await response.json();
      if (body.message) message = body.message;
    } catch {
      // Keep the customer-safe fallback for a non-JSON response.
    }
    throw new Error(message);
  }

  return response.json();
}
