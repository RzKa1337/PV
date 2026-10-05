# Core API (selected entry points)

All in `core/src/main/kotlin/com/solartracker/pro/core`.

| Entry point | Input | Output |
|---|---|---|
| `pv.PvSimulationEngine.simulate / daily` | `PvArrayConfig`, `LossProfile`, location, time, conditions | `PvSimulationPoint` / `PvLossBreakdown` (waterfall) |
| `forecast.EnergyForecastEngine` | PV engine, load forecaster, battery predictor | day/short-term forecasts, SOC prediction, timeline |
| `analytics.ForecastAccuracy.evaluate` | forecast/actual pairs | MAE, RMSE, MAPE, bias |
| `analytics.AutoCalibrationEngine.evaluate` | observations | corrections with history |
| `analytics.HistoryPeriods.totals` | `HistorySample`s, period, zone | `PeriodTotals` |
| `health.PvHealthEngine.assess` | `HealthInput` | `HealthReport` (score or null, deductions, unknowns) |
| `health.PredictiveFaultEngine.analyze` | `DayStats` | `FaultWarning`s |
| `energy.BatteryChemistry` / `BatteryAgingModel` / `BatteryTiming` / `BatteryCapacityEstimator` | chemistry, voltages, powers | SOC estimate, capacity, cycles, SOH, times |
| `economics.EconomicsEngine.evaluate` | `SystemCosts`, `TariffAssumptions`, `EnergyYear` | `EconomicsResult` |
| `ems.EnergyOptimizationEngine.plan` | `EmsInput` (slots, battery, loads, generator) | `EmsPlan` (simulation, windows, recommendations, decisions) |
| `design.PvDesigner.design` | `DesignInput` (datasheets) | `DesignResult` (layout, voltages, warnings) |
| `design.YieldEstimator.annual / optimalTilt` | array, losses, location | `AnnualYield` (clear-sky upper bound) |
| `design.LocationComparison.compare` | named locations | `SiteYield`s |
| `vehicle.VehicleSolarEstimator` | `VehicleSolarConfig`, heading, period | `VehicleEstimate` |
| `export.HistoryExport` | samples, totals | CSV / JSON text |
| `access.FeatureAccessManager` | `SubscriptionState` | enabled / reason per `Feature` |

External network APIs used by the app (all optional, cached): Open-Meteo (forecast, climate, geocoding, elevation), Nominatim (address search), Overpass (OSM buildings/trees), GitHub Releases (updates). No API keys are embedded.
