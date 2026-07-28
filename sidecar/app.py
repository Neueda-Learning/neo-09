from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os

PORT = 8080

class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            return self._send(200, {"status": "UP"})
        if self.path == "/info":
            return self._send(200, {
                "team": "Neueda-Learning",
                "service": "neo-09 sidecar",
                "serviceId": "sidecar",
                "domain": "support",
                "version": 1,
                "mockedDependencies": []
            })
        if self.path.startswith("/api/v1/applications/"):
            application_id = self.path.rsplit("/", 1)[-1]
            if application_id == "app-1001":
                return self._send(200, {
                    "applicationId": "app-1001",
                    "channel": "MOBILE_APP",
                    "submittedAt": "2026-07-15T09:00:00Z",
                    "applicant": {
                        "fullName": "Maria Nowak",
                        "dateOfBirth": "1990-03-12",
                        "email": "maria@example.test",
                        "mobile": "+48123123123",
                        "nationality": "PL",
                        "countryOfResidence": "PL",
                        "taxResidencies": ["PL"],
                        "residentialStatus": "RENTING",
                        "currentAddress": {
                            "line1": "42 Hanbury St",
                            "line2": None,
                            "city": "London",
                            "postcode": "E1 5JP",
                            "country": "GB"
                        },
                        "monthsAtAddress": 18,
                        "dependants": 0
                    },
                    "identityDocument": None,
                    "employment": None,
                    "finances": None,
                    "product": {
                        "productCode": "CREDIT_CARD_REWARDS",
                        "requestedCreditLimit": 3000
                    },
                    "delivery": {
                        "useCurrentAddress": True,
                        "address": {
                            "line1": "42 Hanbury St",
                            "line2": None,
                            "city": "London",
                            "postcode": "E1 5JP",
                            "country": "GB"
                        }
                    },
                    "consents": None
                })
            if application_id == "app-1999":
                self.send_response(404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                return self.wfile.write(json.dumps({"message": "application not found — link may be stale"}).encode("utf-8"))
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            return self.wfile.write(json.dumps({"message": "application not found — link may be stale"}).encode("utf-8"))
        self._send(404, {"message": "not found"})

    def log_message(self, format, *args):
        return

HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
