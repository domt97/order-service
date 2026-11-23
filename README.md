# order-service

Order bounded context of the OMS (multi-merchant marketplace): checkouts and orders, one order per store
(ADR 0007 in [oms-workspace](https://github.com/domt97/oms-workspace)). Built so far: checkout intake and placement
(orders per store, daily caps, the stock saga with inventory-service). Store accept/reject, delivery and COD are next.

Port 8068, Postgres database `orders` (schema `orders`). How to build, run and change it: [`CLAUDE.md`](CLAUDE.md).
