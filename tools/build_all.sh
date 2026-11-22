#!/bin/sh
# Build Angel OS from source: overlays, apps, boot animation, then the system image.
# usage: tools/build_all.sh PUBLISHER_GSI.img.xz VERSION_CODE "Angel OS 1.0" https://your-angel-server:8787
set -e
R="$(cd "$(dirname "$0")/.." && pwd)"; W="$R/work"; PY="${PY:-$R/.venv/bin/python}"
mkdir -p "$W/overlays" "$W/apps" "$W/bootanim" "$W/wallpapers" "$W/sysapps/angel" "$W/public"
for o in "$R"/overlays/*/; do n=$(basename "$o"); "$PY" "$R/tools/make_overlay.py" "$o" "$W/overlays/$n.apk"; done
"$PY" "$R/apps/angelicons/make_icons.py"
for a in angelstore:AngelStore angelisland:AngelIsland angelicons:AngelIcons angelupdate:AngelUpdate; do
  "$R/tools/build_app.sh" "$R/apps/${a%%:*}" "$W/apps/${a##*:}.apk"; done
cp "$R/media/make_bootanim.py" "$W/bootanim/" && "$PY" "$W/bootanim/make_bootanim.py"
cp "$R/media/angel-home.png" "$R/media/angel-lock.png" "$W/wallpapers/"
cp "$R/media/privapp-permissions-angel.xml" "$W/sysapps/"
[ -f "$W/keys/ota_ec.pem" ] || { mkdir -p "$W/keys"; openssl ecparam -name prime256v1 -genkey -noout -out "$W/keys/ota_ec.pem"; chmod 600 "$W/keys/ota_ec.pem"; echo "NEW OTA signing key: $W/keys/ota_ec.pem  (back it up!)"; }
openssl ec -in "$W/keys/ota_ec.pem" -pubout -out "$W/sysapps/angel/ota_key.pem" 2>/dev/null
"$PY" "$R/tools/build_public.py" "$1" "$W/public/angelos-$2.img" "$2" "$3" "$4"
