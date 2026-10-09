# Calculations

All formulas live in `:core`. Units: W, kW, kWh, °C, degrees. Anything below that depends on an assumption says so in the UI.

## Sun and irradiance
- Sun position: NOAA solar calculator (declination, equation of time, hour angle, refraction). Checked against the NREL SPA
  paper example (elevation without refraction 39.87207° vs 39.872046°, azimuth 194.3426° vs 194.34024°), USNO equinox/solstice
  declinations, equation-of-time extremes and the meridian-altitude identity (`SolarReferenceTest`); agreement ≈ 0.01°
  (no solar parallax, standard-atmosphere refraction).
- Clear sky (`ClearSkyModel`): DNI = E₀·0.7^(AM^0.678) (Meinel), E₀ = 1361·(1 + 0.033·cos(2π·day/365)) W/m², AM by Kasten–Young
  (pressure-scaled for elevation in `SolarCalculator.airMass`, not in the clear-sky model), DHI ≈ 0.1·DNI. This is a **cloudless upper bound**.
- Plane of array (`poaComponents`): beam = DNI·max(0, cos θ); sky diffuse by **Hay–Davies**
  DHI·[A·Rb + (1 − A)·(1 + cos β)/2], A = DNI/E₀ (0–1), Rb = cos θ / max(cos z, 0.01745) (ISOTROPIC = Liu–Jordan stays available);
  ground-reflected GHI·albedo·(1 − cos β)/2 (isotropic, albedo 0.2, no snow albedo). No Perez model: its coefficient tables cannot be verified offline.
- Weather → irradiance at an instant: Open-Meteo radiation is the mean of the PRECEDING hour (forecast hour = (end−1 h, end]);
  the clear-sky index k and diffuse fraction d of each hour are interpolated between hour centres and applied to the clear-sky GHI of the
  instant (k ≤ 1.2); temperature and wind (instantaneous values) are interpolated linearly. Missing hour → climate → clear sky, labelled.

## PV loss chain (`PvSimulationEngine`)
Order: POA (+ bifacial rear: P·g·(GHI·albedo + 0.5·DHI)/1000) → AOI (ASHRAE IAM, beam only) → temperature → snow → soiling → shading → mismatch → degradation → DC wiring → MPPT → inverter → clipping → AC wiring. Each loss is applied exactly once; the factors are multiplicative.
- Cell temperature (Faiman): T_cell = T_amb + POA / (U0 + U1·wind), U0 = 25, U1 = 6.84 (NOCT 45 °C model, `T_amb + POA/800·25`, when wind is unknown). Power factor 1 + γ·(T_cell − 25), γ < 0 (datasheet, editable); 0.95 when the air temperature is unknown.
- Snow: panels treated as covered when snow depth ≥ 2 cm, T_amb ≤ +1 °C and tilt < 60°.
- Inverter: AC = DC·η_peak − self-consumption·P_rated; clipped at the inverter limit.
- Trackers: 1-axis N–S rotation tan R = tan(zenith)·sin(az_sun − 180°) limited to ±max; 2-axis follows the sun.
- Performance ratio = AC / (P_peak·POA/1000).

## Estimator used by Live, dashboard and forecasts (`PvEstimator`)
P_AC = P_stc · [beam·IAM + sky diffuse + ground] / 1000 / F_angle · PR · (1 + γ(T_c − 25)) / 0.95, capped at min(P_stc, inverter limit).
PR (default 0.80, user setting) is an ANNUAL figure that already contains a typical temperature loss (0.95) and a typical angle-of-incidence loss;
both are divided out (0.95 and F_angle = clear-sky annual mean of POA-after-IAM / POA for this latitude, tilt and azimuth, 12 days × 30 min) and
replaced by the values of the actual moment, so the day/year shape follows physics without counting these losses twice. Wind acts only through T_c.
Inverter efficiency, soiling, wiring and mismatch are inside PR (not applied again). The cap at kWp without a configured inverter limit is a sanity bound
and is not reported as clipping. `PvSimulationEngine` with a lumped efficiency η = PR satisfies P_estimator · 0.95 · F_angle = P_chain exactly
(`EngineConsistencyTest`); with the default `LossProfile` the explicit chain is ≈ 5 % above the PR 0.80 model at a clear summer noon, which is why the
reality check and the forecast show different "expected" values.

## Weather effects in forecasts (`WeatherEffects`)
- Variables from Open-Meteo: wind at 10 m (m/s), relative humidity, precipitation, snow depth, visibility. A missing variable means no correction.
- Wind: only inside the cell temperature of `PvEstimator` (Faiman). There is no separate wind factor (it was removed: it counted the same effect twice).
- Snow: forecast power 0 when snow depth ≥ 2 cm, air ≤ +1 °C and tilt < 60°.
- Precipitation, humidity and visibility are shown and explained (fog, rain) but do not change the power: their effect is already in the forecast irradiance.

## Energy
E = ∫ P dt. Estimator: midpoint rule over exact time slices (5 min live, 5–15 min in plans); the first/last slice may be shorter. Forecast engines
(`EnergyForecastEngine.day`, `EnergySecurity.outlook` without battery) use the same rule: Σ kW·hours with the power taken at the MIDDLE of each slice —
power sampled at the start of 15-min steps overstated the remaining energy of the day by 4.6 % at 14:07 and 19 % at 18:07. The battery prediction steps
the state explicitly (Euler, 15 min) and is not an energy integral of the PV curve. Hourly rows: mean of 6 midpoint samples (10 min) = Wh in that hour.
Measured history: Σ P·Δt over recorded intervals, gaps are never bridged.

## Forecast accuracy
MAE = mean|f−a|, RMSE = √mean(f−a)², MAPE = mean|f−a|/a only over a ≥ 0.05 (kWh or kW, `minActualForMape`), bias = (Σf − Σa)/Σa, accuracy = 100 − Σ|f−a|/Σa·100 (0–100).
Live relative error (a − f)/f only when f ≥ 0.05 kW. Hourly pairs need ≥ 90 % of the hour covered by recorded rows (gaps are not zeros); rows are attributed
to the forecast hour [key, key+1 h), also in zones with a 30/45-minute offset.
Forecast confidence (`PredictivePvEngine`): base 0.8 (forecast) / 0.35 (climate) / 0.2 (clear sky); for a forecast it falls by lead/72 h (max 0.3) where
lead = max(time − now, time − download time) – an old cached forecast is less trusted although the hour is "now"; factors for shading and calibration confidence
follow; it is a heuristic score, NOT a statistical probability.

## Health score
Start at 100; deduct only for evidence-backed effects (unexplained shortfall vs weather- and shading-aware model, temperature, shading if shading confidence ≥ 0.4, clipping, inverter efficiency, link quality, faults, battery capacity). No score without ≥ 2 days and ≥ 12 h of producing-hours coverage.

## Battery
- Resting SOC: piecewise-linear OCV curve per chemistry, per-cell voltage = pack V / cells (nominal V / cell V). Only valid at rest; LiFePO4 band is wide (flat curve).
- Peukert: C_eff = C·(P20 / P)^(k−1) for P > P20 (20-hour rate). Cold: −x %/°C below 25 °C.
- Cycle life: N(DoD) = N_ref·(DoD_ref / DoD)^n. SOH = 1 − 0.2·EFC/N(DoD) − calendar loss·years.
- Time to full: bulk to 90 % at charge power·η, CV phase above 90 % at half the rate.

## Economics
Year-n savings = (self-consumed·grid price + export·feed-in + avoided generator kWh·generator price)·(1+escalation)^(n−1)·(1−degradation)^(n−1) − maintenance (− battery replacement in its year).
NPV = Σ savings_n/(1+r)^n − investment. Payback = first year the (discounted) cumulative cash flow ≥ 0, interpolated. ROI = (Σ savings − investment)/investment. LCOE = (investment + Σ discounted costs)/Σ discounted energy. Storage cost = battery cost / (yearly throughput·battery life).

## EMS
Hourly slots. Surplus charges the battery up to max SOC and max charge power (×η); deficit discharges down to min SOC (÷η); the rest is grid import or, off-grid, unserved energy or generator. Generator starts when SOC (or SOC projected after the slot) ≤ start SOC, stops at stop SOC. Flexible loads are placed in the window with the highest share covered by residual surplus; ties go to the window with the largest spare surplus. A load is recommended only when coverage ≥ 70 % (configurable).

## Designer
Voc_cold = Voc·(1 + β_Voc·(T_min − 25)); Vmp_hot = Vmp·(1 + γ·(T_cell,max − 25)); Vmp_cold uses β_Voc.
Max panels in series = min(⌊V_max,in / Voc_cold⌋, ⌊V_MPPT,max / Vmp_cold⌋); min = ⌈V_MPPT,min / Vmp_hot⌉. Strings in parallel ≤ ⌊I_max / Imp⌋ (and ⌊Isc_max / Isc⌋ when specified).
Annual yield: 12 simulated clear-sky days (15th of each month) × month length — upper bound; a realistic figure needs a measured clearness factor.

## History
Energy per bucket is integrated from measured power (no bridging across link gaps). Self-consumption = (PV − export)/PV, autarky = 1 − import/load, completeness = recorded time / period time.


## Telemetry validation (Anenji, read-only)
Generic physical bounds for a 48 V hybrid inverter (not vendor data): PV ≤ 600 V, ≤ 40 A; battery voltage 0.7–1.4 × nominal; |P| ≤ 2 × rated; SOC 0–100%; temperatures −40…120 °C; frequency 40–70 Hz.
Consistency: |U×I − P| ≤ 35% of P when P > 300 W. Jumps: SOC > 5%/min, battery voltage > 6 V (48 V system) within 2 min. Zero PV is reported when the model expects > 300 W. Rejected values become INVALID and are removed before any further use.

## AutoCalibration 3.0
Samples are excluded when: link lost, inverter fault, telemetry rejected, clipping/limit, non-finite or negative values, model < 0.05 kW, sun < 10°,
POA < 100 W/m² (when known), real = 0 while model > 0.3 kW, shading factor < 0.5. `evaluate()` (corrections list) applies the same exclusions; clipped samples are kept
only to report the clipping share. Nothing is learned from a single reading (≥ 60 samples and ≥ 3 days before "ready"). The ratio is real / UNcalibrated shaded model.
The nowcast ratio is measured / the forecast engine's own expectation (`PredictivePvEngine.nowcastRatio`), so the calibration is applied once.
Global factor g = median(real/model) after MAD outlier rejection. For each bucket b (sky condition, elevation band, hour, month): f_b = g + (median_b − g)·n_b/(n_b + 30). Applied factor = g·Π(f_b/g)^w (w = 1 for condition and elevation, 0.5 for hour and month), clamped 0.5–1.3, then 1 + (f − 1)·confidence with confidence = min(1, n/60)·min(1, days/14)·(1 − 2·spread).

## Energy security
Three runs of the battery predictor: expected, pessimistic (lower PV band, load × (1 + 0.10 + 0.25·(1 − load confidence))) and optimistic. Security % = usable stored energy above minimum SOC / maximum cumulative energy the battery must deliver before PV covers the load again (charging and discharging efficiencies, load ÷ inverter efficiency). Risk: HIGH if the expected run reaches minimum SOC, MEDIUM if only the pessimistic one does.

## Cold room
Average power = P_min + duty·(P_nom − P_min); duty = duty_ref·(T_amb − T_target)/(T_ref − T_target)·(idle factor outside opening hours), limited to 0–1. Pre-cooling uses the lower target before opening.

## PV performance
Expected = ideal DC power at the weather-based plane-of-array irradiance (25 °C, no losses). Each modelled loss is shown as a share of expected (ESTIMATED; soiling/mismatch/wiring from the loss profile); unknown = (model output − actual)/expected. Performance = actual/expected.

## AOI and MPPT steps (0.13.0)
- Beam reflection: ASHRAE IAM = 1 − b₀·(1/cos θ − 1), b₀ = 0.05 (editable `PvArrayConfig.iamB0`), applied to the beam component of the POA only (diffuse/ground reflection loss is not modelled). AOI loss = Wp · beam · (1 − IAM) / 1000.
- MPPT: `LossProfile.mpptEfficiency` (default 0.995, typical datasheet value) applied after DC wiring.
- `PvEstimator` (forecasts, dashboard) keeps its PR model (see "Estimator" above: AOI and temperature are computed for the moment and normalised by their typical share inside PR); the explicit chain is used for diagnostics, design and vehicle mode.

## PV reality (`PvRealityEngine`)
theoretical = Wp·POA/1000 (+ bifacial) → minus each loss of the chain → expected; unexplained = expected − actual.
Uncertainty (± fraction of expected): 1 − base(irradiance source: sensor 0.95, forecast 0.65, climate 0.30, clear sky 0.20) + 0.15 for broken clouds (clear-sky index 0.3–0.85) + 0.08·(1 − shading confidence), reduced by up to 30 % with calibration; at least 5 %. A deviation inside the band is OK. Confidence = 1 − uncertainty (× 0.5 for simulator data). The learned AutoCalibration factor belongs to the PR model and is not applied to the loss chain.

## PV Doctor (`PvDiagnosticEngine`)
Rules (each with severity, confidence, evidence, impact, action): link offline/stale → COMMUNICATION; rejected PV readings → SENSOR; forecast snow + output < 20 % → SNOW; SOC ≥ max − 3 % without export and output below the band → CURTAILMENT (not a fault); low output for ≥ 70 % of ≥ 3 samples in 30 min beyond max(band, 15 %) and no other explanation → UNEXPECTED_LOW (CRITICAL below −40 %); same above → UNEXPECTED_HIGH (info); clear-sky hours with a repeatable deficit (< 0.8 of model on ≥ 2 days) while ≥ 2 other hours match (≥ 0.93) → SHADING; temperature loss ≥ 10 % → TEMPERATURE (info); soiling/degradation/MPPT from their analyses; conversion efficiency median < 80 % over ≥ 10 readings → INVERTER_EFFICIENCY (info, low confidence).

## Soiling (`SoilingDetector`)
Clear clean days only (≥ 3 h with clear-sky index ≥ 0.85, link OK, no fault, not near the limit, sun ≥ 15°, modelled shading ≥ 0.8). PI = Σ real / Σ model. Reference = P90 of PI (60 days, reset at a shading change). SUSPECTED: last days ≥ 4 % below the reference on ≥ 3 consecutive clear days. CONFIRMED: rain ≥ 2 mm improves the deficit by ≥ 3 p.p. (last 3 clear days before vs first 3 after). Rain comes from the Open-Meteo data (only ~2 past days are fetched, older days = unknown).

## Degradation (`DegradationAnalyzer`)
Monthly PI = P90 of the month's clear days (stored in `perf_month`, kept for years). Year-over-year ratio for each month pair 12 months apart; rate = −median, confidence from the number of pairs (12 = full) and IQR. Needs ≥ 24 months and ≥ 6 pairs. Trend: < 0.3 %/yr stable, ≤ 0.8 normal, above elevated. Projection: Wp·(1 − d)^(year − base).

## MPPT (`MpptAnalyzer`)
Deviation vs expected per MPPT and vs the median of the other inputs (power per configured Wp). ABNORMAL when below −15 % against both (or −22.5 % vs model without peers). Voltage spread > 10 % between inputs with the same module count → string mismatch. Causes are listed, never asserted.

## PV radar and missions
Radar points from `PredictivePvEngine` at NOW, +5, +15, +30 min, +1, +2, +3, +6 h; cloud impact = 1 − forecast/clear sky (same engine without weather); cloud event = cloud factor and power both fall ≥ 30 % within 3 h while the clear sky is ≥ 5 % of peak (sunset excluded). Timing resolution = weather data (hourly).
Missions reuse the three `EnergySecurityAnalyzer` scenarios: probability estimate = 0.5 + (base − 0.5)·confidence with base 0.97 (pessimistic OK), 0.7 (expected OK), 0.25 (optimistic OK), 0.03; safety margin = stored energy above the floor at the lowest SOC × discharge efficiency.

## Layout, tilt schedule, wind, what-if
- Layout: all count combinations sorted by Wp (ties: mass, area); exact bottom-left placement with rotation, margins and gaps; load limit only when all weights are known.
- Tilt schedule: dynamic programme over slots × tilts × moves used × time since last move; value per slot = PV (energy), min(PV, load) (self-consumption) or min(PV, load + charge limit) (battery); each move costs `moveEnergyWh`; wind limits force the stow tilt even without free moves. "Worth it" only when the net gain is > 0 and ≥ 2 %.
- Wind: q = ½·1.225·v², F = q·A·Cn, Cn = max(0.3, 1.2·sin tilt); ratio to the force at the rated gust/tilt: < 0.6 SAFE, < 0.8 CAUTION, < 1 LOWER, ≥ 1 STOW. Without gusts: mean × 1.5 (estimate). Default rating (20 m/s at 30°) is labelled low confidence.
- What-if: one year of `EnergyFlowSimulator` (hourly) for base and variant; cost via `EconomicsEngine.periodCost`; payback = extra investment / yearly saving.

## Anenji Deep Analyzer
- Import: delimiter = the one used equally in the first lines (outside quotes); header units `(kW)`, `[V]`, `_kw`, inline `1.2kW` → SI (kW→W ×1000, Wh→kWh ÷1000, mA→A ÷1000); local times in the phone zone, epoch s/ms; empty/`N/A`/`-` = missing (never 0). PV power ≤ 20 with V×I > 200 W and no unit → reported as possible kW, not rescaled.
- Events: warning/fault bits become intervals (closed when the bit clears or at a data gap > 3 × typical interval). Restart = uptime or lifetime-energy counter going backwards, or POWER_ON after a gap. Grid start = grid power crosses 50 W. Patterns: dominant 90-minute window (share of occurrences), medians of SOC/load/PV/temperature in the 15 min before, grid import within 15 min after; causes by rules (e.g. low-battery + SOC < 30 % at night/morning → insufficient overnight energy). Confidence = rule confidence × (0.6 + 0.4·min(1, n/10)).
- Communication score = 100 − (failure rate·60) − (missing share·30) − outage (1 per 6 min, max 10) − frozen runs (2 each, max 10).
- Trends: windows 1 h…1 year, only with ≥ 10 samples and ≥ 30 % coverage; anomalies = |x − median| > 5·1.4826·MAD; direction counts when the fitted change exceeds 10 % of the range.
- Advisor (physics only, no vendor values): usable Ah = Σ|I|·Δt / ΔSOC over discharges ≥ 20 p.p. (median) vs configured; clipping = PV ≥ 97 % of the limit, lost energy = Σ(model − measured) while clipped; charge current at ≥ 97 % of the setting; bulk below the chemistry's full resting voltage (cells × OCV(100 %)); float > bulk; cut-off below OCV(0 %) or above OCV(20 %); reconnect ≤ cut-off.
- Health: each category 100 − listed deductions; overall = weighted mean (PV 20, battery 20, inverter 15, MPPT 5, communication 10, configuration 10, forecast 10, data quality 10) over assessable categories only.
