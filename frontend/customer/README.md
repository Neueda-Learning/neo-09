# Customer support app

This is a separate Vite app. It does not replace or change the module operator UI in
`frontend/`.

## Ports

- Original module UI: `http://127.0.0.1:5173`
- Customer UI: `http://127.0.0.1:5174`

The Customer Vite server uses `strictPort`, so it fails instead of silently taking another
project's port.

## Environments

```bash
cd frontend/customer
npm run dev:local
npm run dev:dev
npm run dev:prod
```

The environment can also be changed from the selector in the page header.

API bases default to:

- local: `http://localhost:8080`
- dev: `http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-09`
- prod: `http://neobank-prod-294820685.ap-southeast-1.elb.amazonaws.com/neo-09`

Copy `.env.example` to `.env` to override them.

For this capstone demo, the Customer Vite server includes a small Mock Orchestrator endpoint at
`POST /demo-api/cases`. It accepts only the customer fields, builds the complete UC00 envelope,
calls the selected neo-09 environment, then returns the real created Case. It does not add an
endpoint or back door to the neo-09 backend.

Production writes are blocked by default. Set `CUSTOMER_DEMO_ALLOW_PROD=true` only when a deliberate
production demo is required.
