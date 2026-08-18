# Banking Backend

Client-facing demo banking backend.

This service is separate from `PaymentOperations`. It stores banking clients, phone-based accounts, and transfer records in PostgreSQL, then calls `PaymentOperations` to conduct the payment operation.

## Data Model

- `bank_clients` - mock banking clients with phone and PIN.
- `bank_accounts` - accounts linked to phone numbers and bank codes.
- `bank_transfers` - transfer records with status from `PaymentOperations`.

Seed users are created automatically on startup:

| Phone | PIN | Bank |
|---|---:|---|
| `+996700111222` | `1111` | `ELDIK` |
| `+996700333444` | `2222` | `ELDIK`, `ELDIK2` |
| `+996700555666` | `3333` | `ELDIK2` |

## Run

Run `PaymentOperations` first, then run this service with PostgreSQL:

```bash
cd /Users/amirhanordobaev/Downloads/BankingBackend
./gradlew stage
PGHOST=localhost PGPORT=55432 PGDATABASE=banking PGUSER=banking PGPASSWORD=banking \
PAYMENT_SERVICE_URL=http://localhost:8080 \
PAYMENT_SERVICE_TOKEN=local-dev-payment-token \
build/install/BankingBackend/bin/BankingBackend
```

On Heroku, `DATABASE_URL` is provided by Heroku Postgres.

## Heroku Cost Control

The default Heroku database pool is smaller than the local one, but it is still enough for the demo banking flow.
This reduces idle database connections without slowing down the active payment scenario.

Recommended config:

```bash
heroku config:set \
  PG_POOL_SIZE=6 \
  PG_WAIT_QUEUE_SIZE=1024 \
  JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseStringDeduplication" \
  -a <banking-backend-app>
```

Before a live demonstration:

```bash
heroku ps:scale web=1 -a <banking-backend-app>
```

When the stand is not needed:

```bash
heroku ps:scale web=0 -a <banking-backend-app>
```

For a short load test, temporarily raise the pool:

```bash
heroku config:set PG_POOL_SIZE=10 -a <banking-backend-app>
```

After the load test:

```bash
heroku config:set PG_POOL_SIZE=6 -a <banking-backend-app>
```

## API

Login:

```bash
curl -X POST http://localhost:8090/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"+996700111222","pin":"1111"}'
```

Internal transfer by phone:

```bash
curl -X POST http://localhost:8090/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccount":"ELDIK-996700111222",
    "receiverPhone":"+996700333444",
    "receiverBank":"ELDIK",
    "amount":"100.00",
    "currency":"KGS",
    "category":"TRANSFER"
  }'
```

Interbank transfer by phone:

```bash
curl -X POST http://localhost:8090/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccount":"ELDIK-996700111222",
    "receiverPhone":"+996700333444",
    "receiverBank":"ELDIK2",
    "amount":"100.00",
    "currency":"KGS",
    "category":"TRANSFER"
  }'
```

Get transfer:

```bash
curl http://localhost:8090/transfers/{paymentId}
```

Cancel transfer before final status:

```bash
curl -X POST http://localhost:8090/transfers/{paymentId}/cancel
```
