# rewrite_dpad.awk
# Rewrites only the four D-pad "key" lines of a .kl file, preserving everything
# else. Invoke with -v vars:
#   up_code, down_code, left_code, right_code   -> decimal scancodes to match
#   up_name, down_name, left_name, right_name   -> replacement KEYCODE names
#
# Usage:
#   awk -v up_code=544 -v down_code=545 -v left_code=546 -v right_code=547 \
#       -v up_name=F1 -v down_name=F2 -v left_name=F3 -v right_name=F4 \
#       -f rewrite_dpad.awk base.kl > out.kl

function hex2dec(h,    i, c, v, n) {
  h = tolower(h)
  sub(/^0x/, "", h)
  n = length(h)
  v = 0
  for (i = 1; i <= n; i++) {
    c = substr(h, i, 1)
    if (c ~ /[0-9]/) v = v * 16 + (c + 0)
    else v = v * 16 + (index("abcdef", c) + 9)
  }
  return v
}

function tok2dec(tok) {
  if (tok ~ /^0[xX]/) return hex2dec(tok)
  return tok + 0
}

{
  line = $0
  matched = 0
  if (line ~ /^[[:space:]]*key[[:space:]]+/) {
    n = split(line, f, /[[:space:]]+/)
    # f[1] may be empty if line starts with whitespace; find the "key" field
    ki = 0
    for (i = 1; i <= n; i++) {
      if (f[i] == "key") { ki = i; break }
    }
    if (ki > 0 && (ki + 1) <= n) {
      tok = f[ki + 1]
      dec = tok2dec(tok)
      newname = ""
      if (dec == up_code)    { newname = up_name;    seen_up = 1 }
      else if (dec == down_code)  { newname = down_name;  seen_down = 1 }
      else if (dec == left_code)  { newname = left_name;  seen_left = 1 }
      else if (dec == right_code) { newname = right_name; seen_right = 1 }
      if (newname != "") {
        out = "key " tok " " newname
        # preserve any trailing flag fields beyond the old keycode name
        for (i = ki + 3; i <= n; i++) out = out " " f[i]
        print out
        matched = 1
      }
    }
  }
  if (!matched) print line
}

END {
  if (!seen_up)    print "key " up_code    " " up_name
  if (!seen_down)  print "key " down_code  " " down_name
  if (!seen_left)  print "key " left_code  " " left_name
  if (!seen_right) print "key " right_code " " right_name
}
