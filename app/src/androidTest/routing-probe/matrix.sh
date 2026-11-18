# Runs RoutingRulesTest once per carrier, transport and mode on a connected device.
# Install first: gradlew :app:installStableDebug :app:installStableDebugAndroidTest
# then from this folder: python make_hello.py && sh matrix.sh
export MSYS_NO_PATHCONV=1
A=${ADB:-adb}
for f in probe.sh tls.sh hello_api.ipify.org.bin; do $A push "$f" /data/local/tmp/$f >/dev/null; done
$A shell appops set com.whitedns.whiteaesther ACTIVATE_VPN allow
while read -r case; do
  [ -z "$case" ] && continue
  ARGS="-e routing true"; for kv in $case; do ARGS="$ARGS -e ${kv%%=*} ${kv#*=}"; done
  $A logcat -c < /dev/null
  $A shell am instrument -w $ARGS -e class com.whitedns.whiteaesther.service.RoutingRulesTest \
    com.whitedns.whiteaesther.test/androidx.test.runner.AndroidJUnitRunner < /dev/null > /dev/null 2>&1
  $A logcat -d -s RoutingRulesTest:I < /dev/null | grep -E "FAIL|SUMMARY" | sed 's/^.*RoutingRulesTest: //'
done <<'CASES'
carrier=aether transport=auto
carrier=aether transport=h3
carrier=aether transport=h2
carrier=aether transport=wg
carrier=aether transport=wiw
carrier=aether transport=mim
carrier=aether transport=wom
carrier=auto
carrier=aether transport=auto split=except
carrier=auto split=except
carrier=aether transport=h2 mode=proxy
carrier=aether transport=wg mode=proxy
carrier=psiphon
carrier=aether transport=h2 second=psiphon
carrier=tor
carrier=aether transport=h2 second=tor
CASES
