#!/usr/bin/env bash
# Measures the window after the store is reset: how long the cluster holds more than its capacity,
# how long a service has no advertiser, and how long until it is back to what it held before.
# See docs/design/lease-healing.md#measuring-it.
#
#   docker compose -f examples/docker/compose.yml up -d --build --wait
#   examples/docker/measure-window.sh [wipe|outage] [runs]
#
#   wipe     FLUSHALL, so the store comes back empty at once (the default)
#   outage   stop the store for longer than a lease TTL (35s), then start it: every claim has lapsed
#
# Needs a running compose project with the lease "inventory-db" (the shop app's), capacity 10.
set -euo pipefail

MODE=${1:-wipe}
RUNS=${2:-3}
LEASE=${LEASE:-inventory-db}
CAPACITY=${CAPACITY:-10}
OUTAGE=${OUTAGE:-35}
SETTLE_MAX=${SETTLE_MAX:-120}
COMPOSE=(docker compose -f "$(dirname "$0")/compose.yml")

redis() { "${COMPOSE[@]}" exec -T store redis-cli "$@"; }

# Prints "<claimed> <advertisers lacking>", where claimed is the sum of positive and negative amounts
# (a claim being given up still counts) on the lease, and lacking is how many of the advertised
# service keys seen at the start have no member now. Redis stores an amount as 4 big-endian bytes.
SAMPLE='
local claimed = 0
local all = redis.call("HGETALL", "henge:lease:" .. ARGV[1])
for i = 1, #all, 2 do
  if all[i] ~= "~epoch" then
    local b1, b2, b3, b4 = string.byte(all[i + 1], 1, 4)
    local v = ((b1 * 256 + b2) * 256 + b3) * 256 + b4
    if v >= 2147483648 then v = v - 4294967296 end
    if v < 0 then v = -v end
    claimed = claimed + v
  end
end
local lacking = 0
for i = 2, #ARGV do
  local n = redis.call("HLEN", ARGV[i])
  if redis.call("HEXISTS", ARGV[i], "~epoch") == 1 then n = n - 1 end
  if n <= 0 then lacking = lacking + 1 end
end
return {claimed, lacking}'

sample() { # args: advertised keys
    redis --raw EVAL "$SAMPLE" 0 "$LEASE" "$@" | tr '\n' ' '; echo
}

wait_ready() {
    until [ "$("${COMPOSE[@]}" ps --format '{{.Health}}' shop | grep -vc '^healthy$' || true)" = 0 ]; do sleep 1; done
}

for run in $(seq "$RUNS"); do
    wait_ready
    mapfile -t ADV < <(redis --raw --scan --pattern 'henge:adv:*')
    read -r before _ < <(sample "${ADV[@]}")
    if [ "${before:-0}" -le 0 ]; then
        echo "run $run: nothing is claimed on $LEASE, is the cluster up?" >&2; exit 1
    fi

    case $MODE in
        wipe) redis FLUSHALL >/dev/null ;;
        outage) "${COMPOSE[@]}" stop store >/dev/null 2>&1; sleep "$OUTAGE"; "${COMPOSE[@]}" start store >/dev/null 2>&1 ;;
        *) echo "unknown mode $MODE" >&2; exit 2 ;;
    esac
    start=$SECONDS

    peak=0 over=0 noadv=0 back=""
    while [ $((SECONDS - start)) -lt "$SETTLE_MAX" ]; do
        if out=$(sample "${ADV[@]}") && [ -n "$out" ]; then
            read -r claimed lacking <<<"$out"
            [ "$claimed" -gt "$peak" ] && peak=$claimed
            [ "$claimed" -gt "$CAPACITY" ] && over=$((over + 1))
            [ "$lacking" -gt 0 ] && noadv=$((noadv + 1))
            if [ -z "$back" ] && [ "$claimed" -ge "$before" ] && [ "$lacking" = 0 ]; then
                back=$((SECONDS - start))
            fi
        fi
        [ -n "$back" ] && [ $((SECONDS - start)) -ge $((back + 5)) ] && break
        sleep 1
    done

    recovered=never
    [ -n "$back" ] && recovered=${back}s
    printf 'run %d (%s): before=%s peak=%s capacity=%s over_capacity=%ss no_advertiser=%ss recovered=%s\n' \
        "$run" "$MODE" "$before" "$peak" "$CAPACITY" "$over" "$noadv" "$recovered"
done
