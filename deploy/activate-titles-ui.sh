#!/usr/bin/env bash
# Run only from the existing game startup hook, after Manager UI readiness.
set -euo pipefail
umask 077
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
old='memorialtitles-forge-1.20.1-0.4.1-dev.jar'
new='memorialtitles-forge-1.20.1-0.4.2-dev.jar'
cd "$root"
if [[ -f "$stage/applied.txt" ]]; then exit 0; fi
payload() { (cd "$stage" && sha256sum --check SHA256SUMS); }
if [[ "${1:-}" == '--check-payload' ]]; then payload; exit; fi
if ! payload; then
  echo '[Titles UI staging] Payload verification failed; current files retained.' >&2
  exit 0
fi
if [[ ! -f "$root/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/applied.txt"
      || ! -f "$root/mods/pixelmcmanager-forge-1.20.1-0.5.2.jar" ]]; then
  echo '[Titles UI staging] Matching Manager update is pending; current Titles retained.'
  exit 0
fi
if ! sha256sum --check "$stage/expected-future.sha256"; then
  echo '[Titles UI staging] Baseline changed or previous update is pending; current files retained.' >&2
  exit 0
fi
shopt -s nullglob
titles=(mods/memorialtitles*.jar)
if [[ ${#titles[@]} -ne 1 || "${titles[0]:-}" != "mods/$old" || -e "mods/$new" ]]; then
  echo '[Titles UI staging] Unexpected title mod inventory; current files retained.' >&2
  exit 0
fi
backup="$stage/before-activation"
mkdir -p "$backup/mods" "$backup/automodpack"
cp -p -- "mods/$old" "$backup/mods/"
cp -p -- automodpack/automodpack-server.json "$backup/automodpack/automodpack-server.json"
rollback() {
  trap - ERR INT TERM
  set +e
  rm -f -- "mods/$new" mods/.titles-ui.new automodpack/.titles-ui.new
  cp -p -- "$backup/mods/$old" "mods/$old"
  cp -p -- "$backup/automodpack/automodpack-server.json" automodpack/automodpack-server.json
  if sha256sum --check "$stage/expected-future.sha256"; then
    echo '[Titles UI staging] Activation failed; previous files restored.' >&2
    exit 0
  fi
  echo '[Titles UI staging] Rollback verification failed; startup stopped for review.' >&2
  exit 1
}
trap rollback ERR INT TERM
cp -- "$stage/$new" mods/.titles-ui.new
mv -- mods/.titles-ui.new "mods/$new"
rm -- "mods/$old"
cp -- "$stage/automodpack-server.next.json" automodpack/.titles-ui.new
mv -- automodpack/.titles-ui.new automodpack/automodpack-server.json
date -u +%FT%TZ > "$stage/.applied.tmp"
mv -- "$stage/.applied.tmp" "$stage/applied.txt"
trap - ERR INT TERM
echo '[Titles UI staging] Titles 0.4.2-dev activated; AutoModpack regenerates at this startup.'
