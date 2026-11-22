#!/bin/sh
# Live view of an mtkclient log: shows progress lines, hides the reconnect hint spam.
f="${1:-$(cd "$(dirname "$0")/.." && pwd)/work/recovery/flash.log}"
tail -n 50 -F "$f" | tr -u '\r' '\n' | grep --line-buffered -v -e '^$' -e 'Hint' -e 'Power off' -e 'For brom' -e 'For preloader' -e 'If it is' -e '^\.*$'
