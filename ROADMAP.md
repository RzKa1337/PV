# Roadmap

## Done (0.8.0)
Live sun, PV model with loss chain, weather, battery/energy flows, Anenji monitoring (simulator + real links), shading analysis, forecasts, health and predictive warnings, EMS recommendations, economics, designer, location comparison, vehicle mode, history with export, widget, signed self-updates.

## Done (PRO expansion)
Read-only telemetry validation, AutoCalibration 3.0, multi-horizon forecast accuracy, energy security, 72 h outlook, cold room, mobile PV, tilt optimizer, PV performance, predictive alerts, daily report, digital twin, dashboard overview.

## Done (PV Reality & Diagnostics, 0.13.0)
Loss chain with AOI and MPPT steps, `PvRealityEngine` (theoretical → losses → expected vs actual, unexplained, confidence), PV Doctor (`PvDiagnosticEngine`), soiling detection with rain recovery, year-over-year degradation, MPPT analysis, per-register quality + raw register log (CSV/JSON), PV radar (NOW…+6 h, cloud events), energy missions, `DataKind.SIMULATED`, Centrum → Diagnostyka PV. Core-only (API + tests, no screen yet): panel layout optimizer, dynamic tilt schedule, wind safety, what-if simulator.

## Next
1. Real-device validation of the Anenji register map with a user's inverter – record the log in Centrum → Diagnostyka PV and export CSV/JSON.
2. Screens in Narzędzia for the layout optimizer, tilt schedule, wind safety and what-if (core is ready and tested).
3. 15-minute Open-Meteo irradiance (`minutely_15`) for a sharper PV radar (now hourly, ±30 min).
4. Per-MPPT configuration (Wp, modules, orientation) instead of the even split.
2. Map view for the location comparison.
3. Real-device validation of each SMG register (mark VERIFIED in `SmgRegisters`).
4. Battery chemistry in the live SOC display (resting-voltage estimate when the inverter does not report SOC).
5. Cold-room schedule editor in the UI (model supports it already).
5. Optional store billing behind `FeatureAccessManager` (no change to screens).

Done in 0.9.0: flexible loads and generator for the EMS, extended weather fields, forecast accuracy tracking.

## Not planned
- Writing settings to the inverter (control) without an explicit, separately reviewed safety design.
