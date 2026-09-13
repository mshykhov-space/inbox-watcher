#!/usr/bin/env python3
"""One-time helper: obtain a Gmail `gmail.readonly` refresh token via a loopback OAuth flow.

Prereqs:
  - OAuth consent screen published ("In production") with scope gmail.readonly
  - a Desktop-app OAuth client (loopback redirect is allowed, nothing to register)

Usage:
  GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... python3 scripts/get-refresh-token.py

Opens the browser once, you approve access, and it prints GOOGLE_REFRESH_TOKEN.
The token is printed to YOUR terminal only - paste it into .env yourself.
"""
import http.server
import json
import os
import secrets
import socketserver
import sys
import urllib.parse
import urllib.request
import webbrowser

CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID")
CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET")
if not CLIENT_ID or not CLIENT_SECRET:
    sys.exit("Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in the environment first.")

SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
PORT = 8765
REDIRECT = f"http://localhost:{PORT}"
STATE = secrets.token_urlsafe(16)

auth_url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode(
    {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",
        "prompt": "consent",
        "state": STATE,
    }
)

captured = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        captured["code"] = query.get("code", [None])[0]
        captured["state"] = query.get("state", [None])[0]
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"Done. Close this tab and return to the terminal.")

    def log_message(self, *args):
        pass


print("Opening browser for consent. If you see 'unverified app': Advanced -> Go to ... (unsafe).")
webbrowser.open(auth_url)
with socketserver.TCPServer(("localhost", PORT), Handler) as httpd:
    httpd.handle_request()

if not captured.get("code") or captured.get("state") != STATE:
    sys.exit("Failed to capture the authorization code (state mismatch or user cancelled).")

body = urllib.parse.urlencode(
    {
        "code": captured["code"],
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "redirect_uri": REDIRECT,
        "grant_type": "authorization_code",
    }
).encode()

with urllib.request.urlopen("https://oauth2.googleapis.com/token", data=body) as resp:
    tokens = json.load(resp)

refresh_token = tokens.get("refresh_token")
if not refresh_token:
    sys.exit(
        "No refresh_token returned. Ensure the consent screen is 'In production' and you used "
        f"prompt=consent. Raw response: {tokens}"
    )

print("\n=== paste this line into your .env ===")
print("GOOGLE_REFRESH_TOKEN=" + refresh_token)
