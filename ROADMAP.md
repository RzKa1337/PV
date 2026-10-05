# Roadmap

## Done (0.8.0)
Live sun, PV model with loss chain, weather, battery/energy flows, Anenji monitoring (simulator + real links), shading analysis, forecasts, health and predictive warnings, EMS recommendations, economics, designer, location comparison, vehicle mode, history with export, widget, signed self-updates.

## Next
1. Real-device validation of the Anenji register map with a user's inverter (logs from the Energy Center).
2. Map view for the location comparison.
3. AutoCalibration 2.0 applied automatically from stored forecast/measurement pairs (bias correction per hour of day).
4. Battery chemistry in the live SOC display (resting-voltage estimate when the inverter does not report SOC).
5. Optional store billing behind `FeatureAccessManager` (no change to screens).

Done in 0.9.0: flexible loads and generator for the EMS, extended weather fields, forecast accuracy tracking.

## Not planned
- Writing settings to the inverter (control) without an explicit, separately reviewed safety design.
