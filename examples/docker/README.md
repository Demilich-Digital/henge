# The shop on Docker Compose

Three replicas of the shop app on a shared Redis store, behind Caddy. See
[At small scale](../../docs/guide/04-splitting.md#at-small-scale-docker-compose-and-caddy) for why each
piece is there.

```bash
./gradlew :examples:shop-app:bootJar
docker compose -f examples/docker/compose.yml up -d --build
curl 'localhost:8088/api/stock?sku=lantern'
```

- Docker's health check and Caddy's both use `/actuator/health/readiness`. A node that has not reached
  the store is alive and not ready, so it is kept out of rotation until it has.
- `POST /_henge/*` through Caddy is a 404; the replicas reach each other over the Compose network.
- Try `docker compose -f examples/docker/compose.yml stop store`, then restart a replica: it stays up,
  reports not ready, and joins by itself when the store returns.
