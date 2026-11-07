# SØPHIA CIVOPS 2.0 — incremental source update

This source patch adds an evidence-limited equipment report to the existing live observation detail view. It uses **only** the existing Android Wi-Fi/BLE observations and the existing local classifier; it does not generate observations. It shows typical applications/sectors, alternatives, an explicit unverified ownership status, limitations and evidence-level labels.

## Important limitations
- This is a **partial implementation**, not a complete CIVOPS 2.0 release.
- No APK was compiled or tested in this environment. The APKs supplied with the request are unchanged.
- No current public manufacturer catalogs, procurement records or full OUI datasets were downloaded. The app already supports importing an IEEE OUI CSV.
- Existing numeric classifier confidence is heuristic, not a calibrated probability. The new evidence levels are conservative and do not claim high confidence solely from vendor identification.
- No real-world ownership, occupation, vehicle affiliation, or public-safety identity is inferred from nearby radio signals. Public-safety equipment documentation could be added as a **general offline reference**, but it should not be used to flag or track nearby emergency-service personnel or vehicles.
- Android Wi-Fi/BLE scan results are subject to device permissions, OS restrictions, throttling, address randomization, and advertisement availability.

## Next engineering steps
1. Add versioned, source-cited offline equipment catalogs with provenance, publication date and expiration/review date.
2. Separate manufacturer confidence from product-class confidence and validate classifications with labeled devices.
3. Add aggregate recurrence and signal-density views without inferring physical positions.
4. Write Android unit and instrumented tests and compile/sign a new APK in a configured Android build environment.
