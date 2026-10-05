# Roadmap

## Done (0.8.0)
Live sun, PV model with loss chain, weather, battery/energy flows, Anenji monitoring (simulator + real links), shading analysis, forecasts, health and predictive warnings, EMS recommendations, economics, designer, location comparison, vehicle mode, history with export, widget, signed self-updates.

## Next
1. Real-device validation of the Anenji register map with a user's inverter (logs from the Energy Center).
2. User-defined flexible loads (washer, boiler, EV) stored in settings and fed to the EMS.
3. Generator configuration (rated power, start/stop SOC, fuel use) in settings.
4. Extended weather fields in the UI (wind, humidity, precipitation, snow, visibility) and in the loss chain (wind cooling, snow).
5. Forecast accuracy panel based on stored forecasts vs. measurements.
6. Map view for the location comparison.
7. Optional store billing behind `FeatureAccessManager` (no change to screens).

## Not planned
- Writing settings to the inverter (control) without an explicit, separately reviewed safety design.
