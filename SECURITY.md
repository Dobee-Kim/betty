# Administrator operations

Reward distribution, game settlement and the demo BET top-up API require a valid bearer JWT, an active Redis session and a wallet that matches the server's `ADMIN_ADDRESS`. Matching ignores address letter case. The value must be a complete Ethereum address. An unset or malformed administrator address denies these operations.

Use the normal verified Web3Auth login to obtain the administrator session. A wallet address or role supplied in the request body does not grant administrator access. Keep `ADMIN_PRIVATE_KEY` and the JWT signing secret outside Git. Never put either value in frontend environment variables.

The existing `/api/v1/exchange/add` implementation transfers administrator-held BET without verifying a payment. Ordinary users cannot use that demo top-up endpoint. A real payment-confirmation flow is required before offering self-service credit purchases.

The reward balance lookup remains public. Existing internal scheduled settlement calls do not pass through HTTP authorization and continue to run under the server's trusted configuration.

Session lookup checks JWT integrity, expiry and the active Redis session. Logout revokes access to wallet lookup as well as the privileged APIs. Authentication headers and WebSocket query tokens are not written to application logs.

Run the focused authorization regression suite without a database, Redis server or blockchain connection:

```sh
cd backend
./gradlew test --tests '*AdminOperationsTest'
```

Production deployment, chain state, external logging configuration and payment-provider integration require separate operational verification. This source update does not rotate existing credentials or remove earlier logs.
