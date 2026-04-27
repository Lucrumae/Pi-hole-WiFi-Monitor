#!/system/bin/sh
# ============================================================
# Pi-hole WiFi Monitor — Android 5–15+ (root / Magisk / toybox)
# ============================================================
# Every command: try → if fail → try next fallback → until success
# ============================================================

# ----------------------------------------------------------
# Configuration
# ----------------------------------------------------------
WIFI_SSID="YourSSID"
WIFI_PASS="YourPassword"
CUSTOM_IP="192.168.1.100"
CUSTOM_PREFIX="24"
CUSTOM_MASK="255.255.255.0"
DNS_PRIMARY="192.168.1.1"
DNS_SECONDARY="1.1.1.1"
PIHOLE_IP="192.168.1.1"
PIHOLE_PORT="53"
WIFI_IFACE="wlan0"

ENABLE_ETH_TETHER=0
ENABLE_USB_TETHER=0

MONITOR_INTERVAL=15
PIHOLE_WAIT_TIMEOUT=120
WIFI_CONNECT_TIMEOUT=30

LOGFILE="/data/local/tmp/pihole_monitor.log"

# ----------------------------------------------------------
# Logging
# ----------------------------------------------------------
_log() {
    _tag="$1"; shift
    _ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '???')"
    _msg="[${_ts}] [${_tag}] $*"
    echo "$_msg"
    echo "$_msg" >> "$LOGFILE" 2>/dev/null
}

log_info()     { _log "INFO"     "$@"; }
log_ok()       { _log "OK"       "$@"; }
log_warn()     { _log "WARN"     "$@"; }
log_fallback() { _log "FALLBACK" "$@"; }
log_error()    { _log "ERROR"    "$@"; }

# ----------------------------------------------------------
# Capability detection
# ----------------------------------------------------------
_HAS_IP=0
_HAS_IFCONFIG=0
_HAS_ROUTE=0
_HAS_WPA_CLI=0
_HAS_CMD=0
_HAS_SVC=0
_HAS_SETTINGS=0
_HAS_NDC=0
_HAS_SETPROP=0
_HAS_GETPROP=0
_HAS_NC=0
_HAS_PING=0
_HAS_AWK=0
_HAS_SED=0
_HAS_GREP=0
_HAS_DUMPSYS=0
_HAS_TOYBOX=0
_HAS_BUSYBOX=0
_HAS_NSLOOKUP=0
_HAS_IPTABLES=0
_HAS_CONTENT=0

detect_capabilities() {
    log_info "Detecting available commands..."

    command -v ip          >/dev/null 2>&1 && _HAS_IP=1
    command -v ifconfig    >/dev/null 2>&1 && _HAS_IFCONFIG=1
    command -v route       >/dev/null 2>&1 && _HAS_ROUTE=1
    command -v wpa_cli     >/dev/null 2>&1 && _HAS_WPA_CLI=1
    command -v cmd         >/dev/null 2>&1 && _HAS_CMD=1
    command -v svc         >/dev/null 2>&1 && _HAS_SVC=1
    command -v settings    >/dev/null 2>&1 && _HAS_SETTINGS=1
    command -v ndc         >/dev/null 2>&1 && _HAS_NDC=1
    command -v setprop     >/dev/null 2>&1 && _HAS_SETPROP=1
    command -v getprop     >/dev/null 2>&1 && _HAS_GETPROP=1
    command -v nc          >/dev/null 2>&1 && _HAS_NC=1
    command -v ping        >/dev/null 2>&1 && _HAS_PING=1
    command -v awk         >/dev/null 2>&1 && _HAS_AWK=1
    command -v sed         >/dev/null 2>&1 && _HAS_SED=1
    command -v grep        >/dev/null 2>&1 && _HAS_GREP=1
    command -v dumpsys     >/dev/null 2>&1 && _HAS_DUMPSYS=1
    command -v toybox      >/dev/null 2>&1 && _HAS_TOYBOX=1
    command -v busybox     >/dev/null 2>&1 && _HAS_BUSYBOX=1
    command -v nslookup    >/dev/null 2>&1 && _HAS_NSLOOKUP=1
    command -v iptables    >/dev/null 2>&1 && _HAS_IPTABLES=1
    command -v content     >/dev/null 2>&1 && _HAS_CONTENT=1

    log_info "ip=$_HAS_IP ifconfig=$_HAS_IFCONFIG route=$_HAS_ROUTE wpa_cli=$_HAS_WPA_CLI"
    log_info "cmd=$_HAS_CMD svc=$_HAS_SVC settings=$_HAS_SETTINGS ndc=$_HAS_NDC"
    log_info "setprop=$_HAS_SETPROP getprop=$_HAS_GETPROP nc=$_HAS_NC ping=$_HAS_PING"
    log_info "awk=$_HAS_AWK sed=$_HAS_SED grep=$_HAS_GREP dumpsys=$_HAS_DUMPSYS"
    log_info "toybox=$_HAS_TOYBOX busybox=$_HAS_BUSYBOX nslookup=$_HAS_NSLOOKUP"
    log_info "iptables=$_HAS_IPTABLES content=$_HAS_CONTENT"
}

# ----------------------------------------------------------
# Utility: sleep with fallback
# ----------------------------------------------------------
_sleep() {
    _dur="$1"
    sleep "$_dur" 2>/dev/null && return 0
    /system/bin/sleep "$_dur" 2>/dev/null && return 0
    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        busybox sleep "$_dur" 2>/dev/null && return 0
    fi
    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        toybox sleep "$_dur" 2>/dev/null && return 0
    fi
    # busy-wait fallback
    _i=0
    while [ "$_i" -lt "$_dur" ]; do
        cat /dev/null
        _i=$((_i + 1))
    done
}

# ----------------------------------------------------------
# Check: WiFi connected to target SSID
# ----------------------------------------------------------
wifi_is_connected() {
    # Method 1: cmd wifi status (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        _cmdw="$(cmd wifi status 2>/dev/null)"
        case "$_cmdw" in
            *"CONNECTED"*)
                case "$_cmdw" in *"$WIFI_SSID"*) return 0 ;; esac
                ;;
        esac
    fi

    # Method 2: dumpsys wifi (Android 5+)
    if [ "$_HAS_DUMPSYS" -eq 1 ]; then
        _dump="$(dumpsys wifi 2>/dev/null)"
        case "$_dump" in
            *"mNetworkInfo"*"state: CONNECTED"*)
                case "$_dump" in *"$WIFI_SSID"*) return 0 ;; esac
                ;;
        esac
    fi

    # Method 3: wpa_cli status (cross-version)
    if [ "$_HAS_WPA_CLI" -eq 1 ]; then
        _wpa="$(wpa_cli -i "$WIFI_IFACE" status 2>/dev/null)"
        case "$_wpa" in
            *"wpa_state=COMPLETED"*)
                case "$_wpa" in *"ssid=$WIFI_SSID"*) return 0 ;; esac
                ;;
        esac
    fi

    # Method 4: getprop (all versions, most basic)
    if [ "$_HAS_GETPROP" -eq 1 ]; then
        _state="$(getprop wlan.driver.status 2>/dev/null)"
        case "$_state" in
            *"ok"*|*"loaded"*)
                _wst="$(getprop wifi.interface.status 2>/dev/null)"
                case "$_wst" in *"CONNECTED"*|*"connected"*) return 0 ;; esac
                # fallback: check if interface has IP
                _hip="$(getprop dhcp.${WIFI_IFACE}.ipaddress 2>/dev/null)"
                if [ -n "$_hip" ] && [ "$_hip" != "0.0.0.0" ]; then
                    return 0
                fi
                ;;
        esac
    fi

    # Method 5: ip link + ip addr
    if [ "$_HAS_IP" -eq 1 ]; then
        _link="$(ip link show "$WIFI_IFACE" 2>/dev/null)"
        case "$_link" in
            *"state UP"*)
                _addr="$(ip addr show "$WIFI_IFACE" 2>/dev/null)"
                case "$_addr" in *"inet "*) return 0 ;; esac
                ;;
        esac
    fi

    # Method 6: ifconfig (legacy)
    if [ "$_HAS_IFCONFIG" -eq 1 ]; then
        _ifc="$(ifconfig "$WIFI_IFACE" 2>/dev/null)"
        case "$_ifc" in
            *"UP"*)
                case "$_ifc" in
                    *"inet addr:"*) return 0 ;;
                    *"inet "*) return 0 ;;
                esac
                ;;
        esac
    fi

    return 1
}

# ----------------------------------------------------------
# Check: WiFi interface has IP
# ----------------------------------------------------------
wifi_has_ip() {
    if [ "$_HAS_IP" -eq 1 ]; then
        _out="$(ip addr show "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"inet "*) return 0 ;; esac
    fi

    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        _out="$(toybox ifconfig "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"inet "*) return 0 ;; esac
    fi

    if [ "$_HAS_IFCONFIG" -eq 1 ]; then
        _out="$(ifconfig "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in
            *"inet addr:"*) return 0 ;;
            *"inet "*) return 0 ;;
        esac
    fi

    if [ "$_HAS_GETPROP" -eq 1 ]; then
        _hip="$(getprop dhcp.${WIFI_IFACE}.ipaddress 2>/dev/null)"
        if [ -n "$_hip" ] && [ "$_hip" != "0.0.0.0" ]; then
            return 0
        fi
    fi

    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        _out="$(busybox ifconfig "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"inet addr:"*) return 0 ;; esac
    fi

    return 1
}

# ----------------------------------------------------------
# Check: custom IP present on interface
# ----------------------------------------------------------
has_custom_ip() {
    if [ "$_HAS_IP" -eq 1 ]; then
        _out="$(ip addr show "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"$CUSTOM_IP"*) return 0 ;; esac
    fi

    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        _out="$(toybox ip addr show "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"$CUSTOM_IP"*) return 0 ;; esac
    fi

    if [ "$_HAS_IFCONFIG" -eq 1 ]; then
        _out="$(ifconfig "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"$CUSTOM_IP"*) return 0 ;; esac
        _out="$(ifconfig "${WIFI_IFACE}:0" 2>/dev/null)"
        case "$_out" in *"$CUSTOM_IP"*) return 0 ;; esac
    fi

    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        _out="$(busybox ip addr show "$WIFI_IFACE" 2>/dev/null)"
        case "$_out" in *"$CUSTOM_IP"*) return 0 ;; esac
    fi

    return 1
}

# ----------------------------------------------------------
# Get current IP of interface
# ----------------------------------------------------------
get_current_ip() {
    if [ "$_HAS_IP" -eq 1 ] && [ "$_HAS_AWK" -eq 1 ]; then
        _r="$(ip -4 addr show "$WIFI_IFACE" 2>/dev/null | awk '/inet / {split($2,a,"/"); print a[1]; exit}')"
        if [ -n "$_r" ]; then echo "$_r"; return 0; fi
    fi

    if [ "$_HAS_IP" -eq 1 ] && [ "$_HAS_GREP" -eq 1 ] && [ "$_HAS_SED" -eq 1 ]; then
        _r="$(ip -4 addr show "$WIFI_IFACE" 2>/dev/null | grep -o 'inet [0-9.]*' | sed 's/inet //' | head -1)"
        if [ -n "$_r" ]; then echo "$_r"; return 0; fi
    fi

    if [ "$_HAS_IFCONFIG" -eq 1 ] && [ "$_HAS_AWK" -eq 1 ]; then
        _r="$(ifconfig "$WIFI_IFACE" 2>/dev/null | awk '/inet addr:/ {split($2,a,":"); print a[2]; exit}')"
        if [ -n "$_r" ]; then echo "$_r"; return 0; fi
        _r="$(ifconfig "$WIFI_IFACE" 2>/dev/null | awk '/inet / {print $2; exit}')"
        if [ -n "$_r" ]; then echo "$_r"; return 0; fi
    fi

    if [ "$_HAS_GETPROP" -eq 1 ]; then
        _r="$(getprop dhcp.${WIFI_IFACE}.ipaddress 2>/dev/null)"
        if [ -n "$_r" ] && [ "$_r" != "0.0.0.0" ]; then echo "$_r"; return 0; fi
    fi

    if [ "$_HAS_BUSYBOX" -eq 1 ] && [ "$_HAS_AWK" -eq 1 ]; then
        _r="$(busybox ifconfig "$WIFI_IFACE" 2>/dev/null | awk '/inet addr:/ {split($2,a,":"); print a[2]; exit}')"
        if [ -n "$_r" ]; then echo "$_r"; return 0; fi
    fi

    echo ""
}

# ----------------------------------------------------------
# Check: port open on host
# ----------------------------------------------------------
check_port() {
    _host="$1"
    _port="$2"

    if [ "$_HAS_NC" -eq 1 ]; then
        nc -z -w 2 "$_host" "$_port" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        toybox nc -z -w 2 "$_host" "$_port" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        busybox nc -z -w 2 "$_host" "$_port" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_port" = "53" ] && [ "$_HAS_NSLOOKUP" -eq 1 ]; then
        nslookup -timeout=2 google.com "$_host" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_HAS_PING" -eq 1 ]; then
        ping -c 1 -W 2 "$_host" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_warn "Port check fell back to ping — host reachable but port $2 unconfirmed"
            return 0
        fi
    fi

    return 1
}

# ==========================================================
# 1. ENABLE WIFI
# ==========================================================
wifi_enable() {
    log_info "Enabling WiFi..."

    # cmd wifi set-wifi-enabled enabled (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi on via cmd wifi set-wifi-enabled"; _sleep 3; return 0; fi
    fi

    # svc wifi enable (Android 7+)
    if [ "$_HAS_SVC" -eq 1 ]; then
        svc wifi enable >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi on via svc wifi enable"; _sleep 3; return 0; fi
    fi

    # settings put global wifi_on 1 (Android 4.2+)
    if [ "$_HAS_SETTINGS" -eq 1 ]; then
        settings put global wifi_on 1 >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi on via settings wifi_on=1"; _sleep 3; return 0; fi
    fi

    # ndc wifi enable (Android 5-9)
    if [ "$_HAS_NDC" -eq 1 ]; then
        ndc wifi enable >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi on via ndc wifi enable"; _sleep 3; return 0; fi
    fi

    # setprop ctl.start wpa_supplicant (all versions)
    if [ "$_HAS_SETPROP" -eq 1 ]; then
        setprop ctl.start wpa_supplicant >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi on via setprop ctl.start"; _sleep 3; return 0; fi
    fi

    # ip link set wlan0 up
    if [ "$_HAS_IP" -eq 1 ]; then
        ip link set "$WIFI_IFACE" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi iface up via ip link set"; _sleep 3; return 0; fi
    fi

    # ifconfig wlan0 up (legacy)
    if [ "$_HAS_IFCONFIG" -eq 1 ]; then
        ifconfig "$WIFI_IFACE" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi iface up via ifconfig"; _sleep 3; return 0; fi
    fi

    # busybox (oldest fallback)
    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        busybox ifconfig "$WIFI_IFACE" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi iface up via busybox ifconfig"; _sleep 3; return 0; fi
    fi

    log_error "Could not enable WiFi — no method succeeded"
    return 1
}

# ==========================================================
# 2. CONNECT TO WIFI SSID
# ==========================================================
wifi_connect() {
    log_info "Connecting to WiFi: $WIFI_SSID"

    # Already connected?
    wifi_is_connected && { log_ok "Already connected to $WIFI_SSID"; return 0; }

    # cmd wifi connect-network wpa2 (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd wifi connect-network "$WIFI_SSID" wpa2 "$WIFI_PASS" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi connect via cmd wifi connect-network"; return 0; fi
    fi

    # cmd wifi connect-network open (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd wifi connect-network "$WIFI_SSID" open >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi connect via cmd wifi (open)"; return 0; fi
    fi

    # cmd wifi add-network then connect (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd wifi add-network "$WIFI_SSID" wpa2 "$WIFI_PASS" >/dev/null 2>&1
        cmd wifi start-scan >/dev/null 2>&1
        _sleep 3
        cmd wifi connect-network "$WIFI_SSID" wpa2 "$WIFI_PASS" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi connect via cmd wifi add+connect"; return 0; fi
    fi

    # wpa_cli: check if network saved, select it (cross-version)
    if [ "$_HAS_WPA_CLI" -eq 1 ]; then
        _net_id=""
        if [ "$_HAS_GREP" -eq 1 ] && [ "$_HAS_AWK" -eq 1 ]; then
            _net_id="$(wpa_cli -i "$WIFI_IFACE" list_networks 2>/dev/null | grep "$WIFI_SSID" | awk '{print $1}' | head -1)"
        fi
        if [ -n "$_net_id" ] && [ "$_net_id" != "network" ]; then
            wpa_cli -i "$WIFI_IFACE" select_network "$_net_id" >/dev/null 2>&1
            if [ $? -eq 0 ]; then log_ok "WiFi connect via wpa_cli select_network $_net_id"; return 0; fi
        fi
    fi

    # wpa_cli: add new network (cross-version)
    if [ "$_HAS_WPA_CLI" -eq 1 ]; then
        _net_id="$(wpa_cli -i "$WIFI_IFACE" add_network 2>/dev/null | tail -1)"
        if [ -n "$_net_id" ]; then
            wpa_cli -i "$WIFI_IFACE" set_network "$_net_id" ssid "\"$WIFI_SSID\"" >/dev/null 2>&1
            wpa_cli -i "$WIFI_IFACE" set_network "$_net_id" psk "\"$WIFI_PASS\"" >/dev/null 2>&1
            wpa_cli -i "$WIFI_IFACE" set_network "$_net_id" key_mgmt WPA-PSK >/dev/null 2>&1
            wpa_cli -i "$WIFI_IFACE" enable_network "$_net_id" >/dev/null 2>&1
            wpa_cli -i "$WIFI_IFACE" select_network "$_net_id" >/dev/null 2>&1
            if [ $? -eq 0 ]; then log_ok "WiFi connect via wpa_cli add+select"; return 0; fi
        fi
    fi

    # wpa_cli reassociate / reconnect (cross-version)
    if [ "$_HAS_WPA_CLI" -eq 1 ]; then
        wpa_cli -i "$WIFI_IFACE" reassociate >/dev/null 2>&1
        wpa_cli -i "$WIFI_IFACE" reconnect >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi connect via wpa_cli reassociate"; return 0; fi
    fi

    # ndc wifi connect (Android 5-9)
    if [ "$_HAS_NDC" -eq 1 ]; then
        ndc wifi connect "$WIFI_SSID" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi connect via ndc wifi connect"; return 0; fi
    fi

    # setprop to trigger wifi reconnect (all versions, most basic)
    if [ "$_HAS_SETPROP" -eq 1 ]; then
        setprop ctl.start "wpa_supplicant:$WIFI_IFACE" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "WiFi connect via setprop ctl.start wpa"; return 0; fi
    fi

    log_error "Could not connect to WiFi — no method succeeded"
    return 1
}

# ==========================================================
# 3. WAIT UNTIL CONNECTED
# ==========================================================
wifi_wait_connected() {
    log_info "Waiting for WiFi connection (timeout: ${WIFI_CONNECT_TIMEOUT}s)..."

    _elapsed=0
    while [ "$_elapsed" -lt "$WIFI_CONNECT_TIMEOUT" ]; do
        if wifi_is_connected; then
            log_ok "WiFi connected after ${_elapsed}s"
            _ip_wait=0
            while [ "$_ip_wait" -lt 10 ]; do
                if wifi_has_ip; then
                    _cur="$(get_current_ip)"
                    log_ok "IP obtained: $_cur"
                    return 0
                fi
                _sleep 1
                _ip_wait=$((_ip_wait + 1))
            done
            log_warn "Connected but no IP after 10s — continuing"
            return 0
        fi
        _sleep 2
        _elapsed=$((_elapsed + 2))
    done

    log_error "WiFi connection timed out after ${WIFI_CONNECT_TIMEOUT}s"
    return 1
}

# ==========================================================
# 4. ADD CUSTOM IP TO WIFI INTERFACE
# ==========================================================
add_custom_ip() {
    log_info "Adding custom IP ${CUSTOM_IP}/${CUSTOM_PREFIX} to $WIFI_IFACE"

    # ip addr add (modern standard)
    if [ "$_HAS_IP" -eq 1 ]; then
        ip addr add "${CUSTOM_IP}/${CUSTOM_PREFIX}" dev "$WIFI_IFACE" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via ip addr add"; return 0; fi
        log_warn "ip addr add failed"
    fi

    # ndc interface setcfg (Android 5-9)
    if [ "$_HAS_NDC" -eq 1 ]; then
        ndc interface setcfg "$WIFI_IFACE" "$CUSTOM_IP" "$CUSTOM_PREFIX" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via ndc setcfg"; return 0; fi
        log_warn "ndc interface setcfg failed"
    fi

    # toybox ip addr add (Android 6+)
    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        toybox ip addr add "${CUSTOM_IP}/${CUSTOM_PREFIX}" dev "$WIFI_IFACE" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via toybox ip"; return 0; fi
        log_warn "toybox ip addr add failed"
    fi

    # ifconfig alias (legacy)
    if [ "$_HAS_IFCONFIG" -eq 1 ]; then
        ifconfig "${WIFI_IFACE}:0" "$CUSTOM_IP" netmask "$CUSTOM_MASK" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via ifconfig :0"; return 0; fi
        log_warn "ifconfig alias failed"
    fi

    # toybox ifconfig alias (Android 6+)
    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        toybox ifconfig "${WIFI_IFACE}:0" "$CUSTOM_IP" netmask "$CUSTOM_MASK" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via toybox ifconfig"; return 0; fi
        log_warn "toybox ifconfig alias failed"
    fi

    # busybox ip addr add (oldest fallback)
    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        busybox ip addr add "${CUSTOM_IP}/${CUSTOM_PREFIX}" dev "$WIFI_IFACE" >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via busybox ip"; return 0; fi
        log_warn "busybox ip addr add failed"
    fi

    # busybox ifconfig alias (oldest fallback)
    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        busybox ifconfig "${WIFI_IFACE}:0" "$CUSTOM_IP" netmask "$CUSTOM_MASK" up >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "Custom IP added via busybox ifconfig"; return 0; fi
        log_warn "busybox ifconfig alias failed"
    fi

    log_error "Could not add custom IP — no method succeeded"
    return 1
}

# ==========================================================
# 5. SET DNS (multi-method — tries ALL, not just first success)
# ==========================================================
set_dns() {
    log_info "Setting DNS: $DNS_PRIMARY / $DNS_SECONDARY"

    _any=0

    # cmd connectivity (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd connectivity dns set "$WIFI_IFACE" "$DNS_PRIMARY" "$DNS_SECONDARY" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_ok "DNS set via cmd connectivity"
            _any=1
        fi
    fi

    # settings (Android 4.2+)
    if [ "$_HAS_SETTINGS" -eq 1 ]; then
        settings put global wifi_static_dns1 "$DNS_PRIMARY" >/dev/null 2>&1
        settings put global wifi_static_dns2 "$DNS_SECONDARY" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_ok "DNS set via settings"
            _any=1
        fi
    fi

    # ndc resolver setnetdns (Android 5-9)
    if [ "$_HAS_NDC" -eq 1 ]; then
        ndc resolver setnetdns "$WIFI_IFACE" "" "$DNS_PRIMARY" "$DNS_SECONDARY" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_ok "DNS set via ndc resolver setnetdns"
            _any=1
        else
            ndc resolver setifdns "$WIFI_IFACE" "" "$DNS_PRIMARY" "$DNS_SECONDARY" >/dev/null 2>&1
            if [ $? -eq 0 ]; then
                log_ok "DNS set via ndc resolver setifdns"
                _any=1
            fi
        fi
        ndc resolver setdefaultif "$WIFI_IFACE" >/dev/null 2>&1
    fi

    # setprop net.dns1/dns2 (all versions)
    if [ "$_HAS_SETPROP" -eq 1 ]; then
        setprop net.dns1 "$DNS_PRIMARY" >/dev/null 2>&1
        if [ $? -eq 0 ]; then _any=1; fi
        setprop net.dns2 "$DNS_SECONDARY" >/dev/null 2>&1
        setprop "net.${WIFI_IFACE}.dns1" "$DNS_PRIMARY" >/dev/null 2>&1
        setprop "net.${WIFI_IFACE}.dns2" "$DNS_SECONDARY" >/dev/null 2>&1
        setprop "dhcp.${WIFI_IFACE}.dns1" "$DNS_PRIMARY" >/dev/null 2>&1
        setprop "dhcp.${WIFI_IFACE}.dns2" "$DNS_SECONDARY" >/dev/null 2>&1
        if [ "$_any" -eq 1 ]; then log_ok "DNS set via setprop"; fi
    fi

    # iptables DNAT (redirect all DNS to primary)
    if [ "$_HAS_IPTABLES" -eq 1 ]; then
        iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination "${DNS_PRIMARY}:53" >/dev/null 2>&1
        iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination "${DNS_PRIMARY}:53" >/dev/null 2>&1
        iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination "${DNS_PRIMARY}:53" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_ok "DNS DNAT UDP via iptables"
            _any=1
        fi
        iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination "${DNS_PRIMARY}:53" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_ok "DNS DNAT TCP via iptables"
        fi
    fi

    # content provider (older method)
    if [ "$_HAS_CONTENT" -eq 1 ]; then
        content insert --uri content://settings/global --bind name:s:wifi_static_dns1 --bind value:s:"$DNS_PRIMARY" >/dev/null 2>&1
        content insert --uri content://settings/global --bind name:s:wifi_static_dns2 --bind value:s:"$DNS_SECONDARY" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log_ok "DNS set via content provider"
            _any=1
        fi
    fi

    if [ "$_any" -eq 0 ]; then
        log_warn "DNS could not be set by any method"
    fi
    return 0
}

# ==========================================================
# 6. WAIT FOR PI-HOLE
# ==========================================================
# ----------------------------------------------------------
# Check: port open on host (strict — no ping fallback)
# ----------------------------------------------------------
check_port_strict() {
    _host="$1"
    _port="$2"

    if [ "$_HAS_NC" -eq 1 ]; then
        nc -z -w 2 "$_host" "$_port" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_HAS_TOYBOX" -eq 1 ]; then
        toybox nc -z -w 2 "$_host" "$_port" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_HAS_BUSYBOX" -eq 1 ]; then
        busybox nc -z -w 2 "$_host" "$_port" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    if [ "$_port" = "53" ] && [ "$_HAS_NSLOOKUP" -eq 1 ]; then
        nslookup -timeout=2 google.com "$_host" >/dev/null 2>&1
        if [ $? -eq 0 ]; then return 0; fi
    fi

    return 1
}

wait_for_pihole() {
    log_info "Waiting for Pi-hole at $PIHOLE_IP:$PIHOLE_PORT (will retry until reachable)..."

    _elapsed=0
    while true; do
        if check_port_strict "$PIHOLE_IP" "$PIHOLE_PORT"; then
            log_ok "Pi-hole reachable after ${_elapsed}s"
            return 0
        fi

        _sleep 5
        _elapsed=$((_elapsed + 5))

        # Log progress every 30 seconds so user knows we're still trying
        _mod=$((_elapsed % 30))
        if [ "$_mod" -eq 0 ]; then
            log_warn "Pi-hole at $PIHOLE_IP:$PIHOLE_PORT still not responding after ${_elapsed}s — retrying..."
        fi
    done
}

# ==========================================================
# 7. ENABLE ETHERNET TETHERING
# ==========================================================
tether_eth_enable() {
    if [ "$ENABLE_ETH_TETHER" -ne 1 ]; then
        log_info "ETH tethering not requested — skip"
        return 0
    fi

    log_info "Enabling ethernet tethering..."

    # cmd tethering start-tethering 1 (Android 12+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd tethering start-tethering 1 >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "ETH tether on via cmd tethering start 1"; return 0; fi
    fi

    # cmd connectivity tether enable ethernet (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd connectivity tether enable ethernet >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "ETH tether on via cmd connectivity"; return 0; fi
    fi

    # svc ethernet (Android 7+)
    if [ "$_HAS_SVC" -eq 1 ]; then
        svc ethernet setEnabled true >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "ETH tether on via svc ethernet"; return 0; fi
    fi

    # settings (Android 4.2+)
    if [ "$_HAS_SETTINGS" -eq 1 ]; then
        settings put global tether_offload_disabled 0 >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "ETH tether on via settings"; return 0; fi
    fi

    # ndc tether start (Android 5-9)
    if [ "$_HAS_NDC" -eq 1 ]; then
        ndc tether start >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "ETH tether on via ndc tether start"; return 0; fi
    fi

    # setprop (all versions)
    if [ "$_HAS_SETPROP" -eq 1 ]; then
        setprop sys.usb.tethering 1 >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "ETH tether on via setprop"; return 0; fi
    fi

    log_warn "Could not enable ETH tethering — no method succeeded"
}

# ==========================================================
# 8. ENABLE USB TETHERING
# ==========================================================
tether_usb_enable() {
    if [ "$ENABLE_USB_TETHER" -ne 1 ]; then
        log_info "USB tethering not requested — skip"
        return 0
    fi

    log_info "Enabling USB tethering..."

    # cmd tethering start-tethering 0 (Android 12+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd tethering start-tethering 0 >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "USB tether on via cmd tethering start 0"; return 0; fi
    fi

    # cmd connectivity tether enable usb (Android 11+)
    if [ "$_HAS_CMD" -eq 1 ]; then
        cmd connectivity tether enable usb >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "USB tether on via cmd connectivity"; return 0; fi
    fi

    # svc usb setFunctions rndis (Android 7+)
    if [ "$_HAS_SVC" -eq 1 ]; then
        svc usb setFunctions rndis >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "USB tether on via svc usb rndis"; return 0; fi
    fi

    # svc usb setFunctions ncm (Android 11+, newer USB mode)
    if [ "$_HAS_SVC" -eq 1 ]; then
        svc usb setFunctions ncm >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "USB tether on via svc usb ncm"; return 0; fi
    fi

    # ndc tether start (Android 5-9)
    if [ "$_HAS_NDC" -eq 1 ]; then
        ndc tether start >/dev/null 2>&1
        if [ $? -eq 0 ]; then log_ok "USB tether on via ndc tether start"; return 0; fi
    fi

    # setprop sys.usb.config rndis,adb (all versions)
    if [ "$_HAS_SETPROP" -eq 1 ]; then
        setprop sys.usb.config "rndis,adb" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            setprop sys.usb.tethering 1 >/dev/null 2>&1
            log_ok "USB tether on via setprop rndis,adb"
            return 0
        fi
    fi

    log_warn "Could not enable USB tethering — no method succeeded"
}

# ==========================================================
# 9. MONITOR LOOP
# ==========================================================
monitor_loop() {
    log_info "=== Monitor loop started (interval: ${MONITOR_INTERVAL}s) ==="

    _fail_count=0
    _max_fail=5

    while true; do
        _sleep "$MONITOR_INTERVAL"

        if wifi_is_connected && wifi_has_ip; then
            _cur="$(get_current_ip)"
            _fail_count=0

            # Check Pi-hole
            if check_port "$PIHOLE_IP" "$PIHOLE_PORT"; then
                log_info "OK — ip=$_cur pihole=UP"
            else
                log_warn "Pi-hole unreachable — reapplying DNS"
                set_dns
            fi

            # Check custom IP still present
            if ! has_custom_ip; then
                log_warn "Custom IP $CUSTOM_IP missing — re-adding"
                add_custom_ip
                set_dns
            fi
        else
            _fail_count=$((_fail_count + 1))
            log_warn "WiFi down or no IP (fail ${_fail_count}/${_max_fail})"

            if [ "$_fail_count" -ge "$_max_fail" ]; then
                log_error "Max failures — full reconnect"
                _fail_count=0

                wifi_enable
                _sleep 2
                wifi_connect
                wifi_wait_connected

                if wifi_is_connected; then
                    add_custom_ip
                    set_dns
                    wait_for_pihole
                    tether_eth_enable
                    tether_usb_enable
                else
                    log_error "Full reconnect failed"
                fi
            else
                log_info "Quick reconnect attempt..."

                # try cmd wifi (Android 11+)
                if [ "$_HAS_CMD" -eq 1 ]; then
                    cmd wifi connect-network "$WIFI_SSID" wpa2 "$WIFI_PASS" >/dev/null 2>&1
                fi

                # try wpa_cli reassociate (cross-version)
                if [ "$_HAS_WPA_CLI" -eq 1 ]; then
                    wpa_cli -i "$WIFI_IFACE" reassociate >/dev/null 2>&1
                    wpa_cli -i "$WIFI_IFACE" reconnect >/dev/null 2>&1
                fi

                # try ndc (Android 5-9)
                if [ "$_HAS_NDC" -eq 1 ]; then
                    ndc wifi connect "$WIFI_SSID" >/dev/null 2>&1
                fi

                _sleep 5

                if wifi_is_connected; then
                    log_ok "Quick reconnect succeeded"
                    _fail_count=0
                    if ! has_custom_ip; then
                        add_custom_ip
                        set_dns
                    fi
                fi
            fi
        fi
    done
}

# ==========================================================
# MAIN
# ==========================================================
main() {
    log_info "============================================"
    log_info "Pi-hole WiFi Monitor — starting"
    log_info "============================================"
    log_info "SSID=$WIFI_SSID  CUSTOM_IP=$CUSTOM_IP/$CUSTOM_PREFIX"
    log_info "DNS=$DNS_PRIMARY,$DNS_SECONDARY  Pi-hole=$PIHOLE_IP:$PIHOLE_PORT"
    log_info "ETH_TETHER=$ENABLE_ETH_TETHER  USB_TETHER=$ENABLE_USB_TETHER"
    log_info "============================================"

    # Phase 0: Detect capabilities
    detect_capabilities

    # Phase 1: Enable WiFi
    wifi_enable

    # Phase 2: Connect to WiFi
    wifi_connect

    # Phase 3: Wait for connection
    wifi_wait_connected

    # Phase 4: Add custom IP
    add_custom_ip

    # Phase 5: Apply DNS
    set_dns

    # Phase 6: Wait for Pi-hole
    wait_for_pihole

    # Phase 7: Enable tethering (if configured)
    tether_eth_enable
    tether_usb_enable

    # Phase 8: Monitor loop
    log_info "============================================"
    log_info "Setup complete — entering monitor loop"
    log_info "============================================"
    monitor_loop
}

# ----------------------------------------------------------
# Entry point
# ----------------------------------------------------------
main "$@"
