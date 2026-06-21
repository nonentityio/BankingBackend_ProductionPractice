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
build/install/BankingBackend/bin/BankingBackend
```

On Heroku, `DATABASE_URL` is provided by Heroku Postgres.

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
