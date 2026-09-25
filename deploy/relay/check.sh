#!/usr/bin/env bash
# Proves the relay on VPS_1 from this machine, over the internet (r45):
#   - a credential minted from the box's secret gets an allocation;
#   - the peers FoD lives on are refused: loopback, docker's bridge, the box itself;
#   - a wrong password gets nothing.
# Needs podman (or docker, DOCKER=docker) for coturn's own turnutils_uclient.
# The secret never leaves the box: the credential is minted there.
set -uo pipefail
here=$(dirname "$(readlink -f "$0")")
. "$here/../vps.sh"
R="${DOCKER:-podman} run --rm --network host --entrypoint turnutils_uclient docker.io/coturn/coturn:4.7.0"
mint() { vps "U=\"\$(( \$(date +%s) + 600 )):check$RANDOM\"; . /opt/lachassegalerie/relay/.env; printf '%s %s' \"\$U\" \"\$(printf %s \"\$U\" | openssl dgst -sha1 -hmac \"\$TURN_SECRET\" -binary | base64)\""; }
fail=0
for peer in 127.0.0.1 172.17.0.1 "$VPS_IP"; do
  read -r U P < <(mint)
  # Captured first: under pipefail, grep -q closing the pipe early would fail the test
  out=$(timeout 8 $R -u "$U" -w "$P" -e "$peer" -n 1 -c "$VPS_IP" 2>&1)
  if echo "$out" | grep -q '403 (Forbidden IP)'; then
    echo "  ok   peer $peer refused"
  else
    echo "  FAIL peer $peer was NOT refused"; fail=1
  fi
done
# A public peer: the allocation and the permission succeed, then uclient waits for
# an echo nobody sends, so it is killed; what matters is no error before that
read -r U P < <(mint)
out=$(timeout 6 $R -u "$U" -w "$P" -e 1.1.1.1 -n 1 -c "$VPS_IP" 2>&1)
if echo "$out" | grep -qiE 'error|cannot'; then echo "  FAIL public peer: $(echo "$out" | grep -iE 'error|cannot' | head -1)"; fail=1
else echo "  ok   allocation with a minted credential, public peer allowed"; fi
out=$(timeout 8 $R -u "$U" -w wrong -e 1.1.1.1 -n 1 -c "$VPS_IP" 2>&1)
if echo "$out" | grep -q 'Cannot complete Allocation'; then echo "  ok   wrong password refused"
else echo "  FAIL wrong password was not refused"; fail=1; fi
exit $fail
