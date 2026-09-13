# Gmail OAuth setup

Inbox Watcher reads mail through the Gmail API with the `gmail.readonly` scope. It does not send, modify, or delete Gmail messages.

1. In a Google Cloud project, enable the Gmail API and configure the OAuth consent screen for the account that will authorize access.
2. Create an OAuth client appropriate for the way you obtain a refresh token. During authorization, request `https://www.googleapis.com/auth/gmail.readonly` and offline access.
3. Complete the consent flow, then save the resulting client ID, client secret, and refresh token in the local `.env` file.

Set:

```dotenv
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GOOGLE_REFRESH_TOKEN=...
```

Google documents the [Gmail API scopes](https://developers.google.com/gmail/api/auth/scopes) and [OAuth 2.0 authorization flow](https://developers.google.com/identity/protocols/oauth2). Treat the refresh token as a password: do not commit it or put it in container images.
