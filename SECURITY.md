# Administrator operations

Reward distribution, game settlement and the demo BET top-up API require a valid bearer JWT, an active Redis session and a wallet that matches the server's `ADMIN_OPERATOR_ADDRESS`. Matching ignores address letter case. The value must be a complete Ethereum address. An unset or malformed operator address denies these operations. `ADMIN_ADDRESS` remains the treasury wallet and allowance target; it does not grant HTTP operator access.

Use the normal verified Web3Auth login to obtain the administrator session. A wallet address or role supplied in the request body does not grant administrator access. Keep `ADMIN_PRIVATE_KEY` and the JWT signing secret outside Git. Never put either value in frontend environment variables.

The existing `/api/v1/exchange/add` implementation transfers administrator-held BET without verifying a payment. Ordinary users cannot use that demo top-up endpoint. A real payment-confirmation flow is required before offering self-service credit purchases.

The reward balance lookup remains public. Existing internal scheduled settlement calls do not pass through HTTP authorization and continue to run under the server's trusted configuration.

Each login issues a JWT with a fresh random session ID. Redis stores that ID for the wallet, never the raw bearer token. Session lookup checks JWT integrity, expiry and an exact match with the active ID. Login replaces the previous session, and logging in again cannot revive a logged-out token. Wallet APIs, privileged APIs and new WebSocket handshakes share this check. Display WebSocket messages also recheck the active ID and the game/team authorized during the handshake. A game-only handshake cannot write to a display. Already-connected sockets are not proactively disconnected and existing broker subscriptions can remain until the connection closes, but revoked display reads and writes are rejected. Authentication headers and WebSocket query tokens are not written to application logs.

Deploying this change rejects existing Boolean-backed Redis sessions and older JWTs without a session ID. Existing users must log in once again. Configure the intended Web3Auth operator wallet separately through `ADMIN_OPERATOR_ADDRESS` without changing the treasury wallet.

Run the focused authorization regression suite without a database, Redis server or blockchain connection:

```sh
cd backend
./gradlew test --tests '*AdminOperationsTest' --tests '*SessionLifecycleTest'
```

Production deployment, chain state, external logging configuration and payment-provider integration require separate operational verification. This source update does not rotate existing credentials or remove earlier logs.

## Web3Auth identity boundary

Before any deployment, configure the actual provider contract outside Git. `WEB3AUTH_JWKS_URL` must be the trusted provider HTTPS JWKS endpoint. `WEB3AUTH_EXPECTED_ISSUER` must exactly match that provider's HTTPS issuer. `WEB3AUTH_CLIENT_ID` must be this application's client ID, matching the intended frontend app. `WEB3AUTH_JWS_ALGORITHM` must be explicitly set to the provider's supported RS256 or ES256 algorithm. There are no issuer, audience or algorithm defaults. Do not infer those values from an incoming token.

The verifier accepts only that configured algorithm, exact issuer, exactly one audience equal to this app's client ID, and a future expiry. Missing or invalid configuration refuses startup. A token signed by the trusted JWKS for another app or issuer is rejected before wallet extraction. Multi-audience tokens are deliberately not supported. Confirm the actual provider's token contract before enabling `ADMIN_OPERATOR_ADDRESS`; no live provider or real token was used in the local tests.
