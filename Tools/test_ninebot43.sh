#!/bin/bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
work_dir="$(mktemp -d /private/tmp/carwithin-ninebot43.XXXXXX)"
trap 'rm -rf "$work_dir"' EXIT
cd "$project_root"
xcrun clang -c App/ikcp.c -o "$work_dir/ikcp.o"
xcrun swiftc -swift-version 5 -import-objc-header App/ikcp.h \
  Sources/TDSCarKitCore/{BMSFamily,ANTBMSCardConfiguration,ANTBMSProtocol,AnnexB,Binary,Localization,VideoCodec}.swift \
  App/{InstrumentVideoCanvas,NinebotH264Encoder,NinebotKCPVideo,NinebotOutput,NinebotProtocol,NinebotUDPSocket,TailingH264Decoder}.swift \
  Tools/Ninebot43Smoke.swift "$work_dir/ikcp.o" -o "$work_dir/ninebot43-test"
# The production sender uses the same fixed port as the receiver on each IP.
# Both endpoints belong to this Mac. The dashboard stand-in always binds 127.0.0.1.
local_sender="$(/sbin/ifconfig | awk '$1 == "inet" && $2 !~ /^127\./ && !address { address=$2 } END { print address }')"
"$work_dir/ninebot43-test" "$local_sender"
