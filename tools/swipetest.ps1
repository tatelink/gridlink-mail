# Regression test for the swipe fix.
#
# The three cases below are exactly the ones the OLD code got wrong, and each one isolates a
# different half of the bug:
#
#   slow  900ms full-width  - worked before. Must still work (no regression).
#   fast  350ms full-width  - FAILED before. I blamed synthetic input and wrote that in memory.
#                             If it passes now, that memory note was wrong: the real cause was
#                             dropped deltas starving the row of travel, not touch slop.
#   flick 120ms 20% travel  - correctly rejected before (under the 25% distance threshold).
#                             Must now commit, via the new velocity rule.
#
# Signal: whether the pixels AT THE SWIPED ROW changed. A committed archive pulls the next message
# up into that band, so the strip is unrecognisable afterwards. A rejected swipe springs the same
# row back to exactly where it started, so the strip is pixel-identical. No OCR, no colour
# heuristics, and it cannot be fooled by the background.
#
# ⚠️ Only valid with `--ez recycle false`. With recycling on, the archived message reappears at the
# head a second later and shoves every row down, which changes the strip whether or not the gesture
# was the cause.
#
# 🔴 The launch gate is not a sleep. A fixed 2200ms wait silently ran the whole first attempt
# against the splash screen, and every swipe "passed" by doing nothing to a logo.

param([string]$OutDir = "C:\Users\brand\AppData\Local\Temp\claude\C--Users-brand\4ce4b2d8-34ab-4b95-88e3-8e63a0648be3\scratchpad\swipetest")

$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
Add-Type -AssemblyName System.Drawing
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

function Grab($name) {
    adb shell screencap -p /sdcard/st.png 2>$null | Out-Null
    adb pull /sdcard/st.png "$OutDir\$name.png" 2>$null | Out-Null
    return "$OutDir\$name.png"
}

# 🔴 Probes for the near-black "Inbox" title, NOT for blue.
#
# Blue was the wrong test and it silently invalidated two of the three cases on the first run: day
# mode paints a saturated cyan aurora across the whole window, and it arrives a beat BEFORE the
# list does. So "blue exists" went true while the screen was still empty, the gate released, and
# the swipe was delivered to a background. Dark text in the title band cannot appear until the
# header has actually composed, and the splash has nothing dark anywhere near it.
function TitleInk($path) {
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    try {
        $n = 0
        for ($y = 150; $y -lt 235; $y += 3) {
            for ($x = 45; $x -lt 260; $x += 3) {
                $p = $bmp.GetPixel($x, $y)
                if (($p.R * 0.3 + $p.G * 0.59 + $p.B * 0.11) -lt 90) { $n++ }
            }
        }
        return $n
    } finally { $bmp.Dispose() }
}

# 🔴 Not $Y. PowerShell variables are case-INSENSITIVE, so a `for ($y = ...)` loop inside RowDiff
# would be assigning to this very variable, and the loop bound would chase its own counter forever.
$RowY = 1385

# Percentage of pixels in the swiped row's band that differ between two frames.
# Springback leaves the same row in the same place, so this lands near zero. A commit pulls the
# next message up into the band, so it lands high.
function RowDiff($a, $b) {
    $ba = [System.Drawing.Bitmap]::FromFile($a)
    $bb = [System.Drawing.Bitmap]::FromFile($b)
    try {
        $n = 0; $tot = 0
        for ($y = $Y - 34; $y -lt $Y + 34; $y += 4) {
            for ($x = 60; $x -lt 1020; $x += 6) {
                $pa = $ba.GetPixel($x, $y); $pb = $bb.GetPixel($x, $y)
                $la = $pa.R * 0.3 + $pa.G * 0.59 + $pa.B * 0.11
                $lb = $pb.R * 0.3 + $pb.G * 0.59 + $pb.B * 0.11
                $tot++
                if ([Math]::Abs($la - $lb) -gt 28) { $n++ }
            }
        }
        return [int](100 * $n / $tot)
    } finally { $ba.Dispose(); $bb.Dispose() }
}

function Launch {
    adb shell am force-stop app.gridlink.test | Out-Null
    adb shell am start -n app.gridlink.test/app.gridlink.gallery.GridlinkGalleryActivity `
        --es mode day --ez recycle false | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 400
        $p = Grab "_gate"
        if ((TitleInk $p) -gt 60) {
            # Settled: the enter animation is still running when the first rows paint.
            Start-Sleep -Milliseconds 900
            return $true
        }
    }
    return $false
}

function Case($label, $x1, $x2, $ms) {
    if (-not (Launch)) { "  $label : LAUNCH GATE TIMED OUT"; return }
    $before = Grab "$label-1-before"
    adb shell input swipe $x1 $Y $x2 $Y $ms | Out-Null
    # Long enough for both the fly-off and the gap collapse to finish.
    Start-Sleep -Milliseconds 1500
    $after = Grab "$label-2-after"
    $d = RowDiff $before $after
    $verdict = if ($d -gt 25) { "COMMITTED" } elseif ($d -lt 6) { "sprang back" } else { "AMBIGUOUS" }
    "  {0,-6} {1,4}ms  {2,3}px travel   row changed {3,3}%   {4}" -f `
        $label, $ms, [Math]::Abs($x2 - $x1), $d, $verdict
}

"Swipe regression test"
Case "slow"  200 1000 900
Case "fast"  200 1000 350
Case "flick" 200  420 120
""
"Screenshots in $OutDir"
