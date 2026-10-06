# Roadmap

## Done (0.8.0)
Live sun, PV model with loss chain, weather, battery/energy flows, Anenji monitoring (simulator + real links), shading analysis, forecasts, health and predictive warnings, EMS recommendations, economics, designer, location comparison, vehicle mode, history with export, widget, signed self-updates.

## Done (PRO expansion)
Read-only telemetry validation, AutoCalibration 3.0, multi-horizon forecast accuracy, energy security, 72 h outlook, cold room, mobile PV, tilt optimizer, PV performance, predictive alerts, daily report, digital twin, dashboard overview.

## Next
1. Real-device validation of the Anenji register map with a user's inverter (logs from the Energy Center).
2. Map view for the location comparison.
3. Real-device validation of each SMG register (mark VERIFIED in `SmgRegisters`).
4. Battery chemistry in the live SOC display (resting-voltage estimate when the inverter does not report SOC).
5. Cold-room schedule editor in the UI (model supports it already).
5. Optional store billing behind `FeatureAccessManager` (no change to screens).

Done in 0.9.0: flexible loads and generator for the EMS, extended weather fields, forecast accuracy tracking.

## Not planned
- Writing settings to the inverter (control) without an explicit, separately reviewed safety design.
