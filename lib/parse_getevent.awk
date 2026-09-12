# parse_getevent.awk
# Parses the output of `adb shell getevent -i` into one pipe-delimited
# summary line per input device:
#
#   EVENT:/dev/input/eventN|VENDOR:xxxx|PRODUCT:yyyy|NAME:...|HAS_BTNDPAD:0|1|HAS_ARROWS:0|1|HAS_HAT:0|1|HAS_BTNA:0|1
#
# Usage: awk -f parse_getevent.awk < getevent_i_output.txt

function flush() {
  if (cur_event != "") {
    printf("EVENT:%s|VENDOR:%s|PRODUCT:%s|NAME:%s|HAS_BTNDPAD:%d|HAS_ARROWS:%d|HAS_HAT:%d|HAS_BTNA:%d\n",
      cur_event, cur_vendor, cur_product, cur_name,
      has_btndpad, has_arrows, has_hat, has_btna)
  }
}

BEGIN {
  cur_event = ""
  section = ""
}

/^add device/ {
  flush()
  cur_event = ""
  if (match($0, /\/dev\/input\/[^ \t]+/)) {
    cur_event = substr($0, RSTART, RLENGTH)
  }
  cur_name = ""; cur_vendor = "0000"; cur_product = "0000"
  has_btndpad = 0; has_arrows = 0; has_hat = 0; has_btna = 0
  section = ""
  next
}

/^[[:space:]]*vendor/ { cur_vendor = $2; next }
/^[[:space:]]*product/ { cur_product = $2; next }

/^[[:space:]]*name:/ {
  if (match($0, /"[^"]*"/)) {
    cur_name = substr($0, RSTART + 1, RLENGTH - 2)
  }
  next
}

/KEY \(0001\)/ { section = "KEY" }
/ABS \(0003\)/ { section = "ABS" }
/^[[:space:]]*[A-Z]+[A-Z0-9]* \(/ {
  if ($0 !~ /KEY \(0001\)/ && $0 !~ /ABS \(0003\)/) section = "OTHER"
}
/^[[:space:]]*input props:/ { section = "" }
/^[[:space:]]*events:/ { next }

{
  if (section == "KEY") {
    line = $0
    sub(/^[[:space:]]*KEY \(0001\):[[:space:]]*/, "", line)
    n = split(line, codes, /[ \t]+/)
    for (i = 1; i <= n; i++) {
      c = tolower(codes[i])
      if (c == "0220" || c == "0221" || c == "0222" || c == "0223") has_btndpad = 1
      if (c == "0067" || c == "006c" || c == "0069" || c == "006a") has_arrows = 1
      if (c == "0130") has_btna = 1
    }
  } else if (section == "ABS") {
    line = $0
    sub(/^[[:space:]]*ABS \(0003\):[[:space:]]*/, "", line)
    split(line, parts, ":")
    code = parts[1]
    gsub(/[ \t]+/, "", code)
    code = tolower(code)
    if (code == "0010" || code == "0011") has_hat = 1
  }
}

END { flush() }
