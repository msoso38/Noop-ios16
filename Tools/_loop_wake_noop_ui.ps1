# Dynamic /loop wake — one-shot ~40m. 6h window ends ~11:43 PM local Jul 11 2026.
# Rotate: after this wake, REMAKE the three NEW items from tick1 (themes Ember/Sunset/Midnight + PC onboard).
Start-Sleep -Seconds 2400
Write-Output 'AGENT_LOOP_WAKE_noop_ui {"prompt":"6h loop (~ends 23:43). Read ANY_MODEL_CONTINUE.md + UGLY_AVOID. ROTATE: remake/polish last NEW3 (EmberNight, SunsetTrack, MidnightIndigo themes + PC import onboarding + 12mo /2 tests). Then optionally start next NEW3. One ship. Deploy Fold if green. Update handoff LAST. Re-arm ~40m only if before 23:43. Pull pins if any.","rotate":"remake_tick1","end":"2026-07-11T23:43:00-04:00"}'
