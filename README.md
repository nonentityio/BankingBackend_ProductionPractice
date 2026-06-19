# Banking Backend

Separate demo banking backend.

This service is not the payment processing system. It works as a client-facing backend:

- registers demo clients;
- creates demo accounts;
- shows account balance;
- creates transfers;
- calls `PaymentOperations`;
- checks payment status;
- cancels payment before final status.

## Run

First run `PaymentOperations` on port `8080`.

Then run this service:

```bash
cd /Users/amirhanordobaev/Downloads/BankingBackend
./gradlew stage
PAYMENT_SERVICE_URL=http://localhost:8080 build/install/BankingBackend/bin/BankingBackend
```

Banking backend URL:

```text
http://localhost:8090
```

## API

Register client:

```bash
curl -X POST http://localhost:8090/clients/register \
  -H "Content-Type: application/json" \
  -d '{"clientName":"Aidar"}'
```

Get accounts:

```bash
curl http://localhost:8090/clients/client-a/accounts
```

Create transfer:

```bash
curl -X POST http://localhost:8090/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccount":"BANKA-100-200",
    "toAccount":"BANKB-300-400",
    "amount":"100.00",
    "currency":"KGS"
  }'
```

Create held transfer for cancellation demo:

```bash
curl -X POST http://localhost:8090/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccount":"BANKA-100-200",
    "toAccount":"HOLD-300-400",
    "amount":"50.00",
    "currency":"KGS"
  }'
```

Get transfer:

```bash
curl http://localhost:8090/transfers/{paymentId}
```

Cancel transfer:

```bash
curl -X POST http://localhost:8090/transfers/{paymentId}/cancel
```
