# Calculations

All formulas live in `:core`. Units: W, kW, kWh, °C, degrees. Anything below that depends on an assumption says so in the UI.

## Sun and irradiance
- Sun position: NOAA solar calculator (declination, equation of time, hour angle, refraction).
- Clear sky (`ClearSkyModel`): DNI = 1361·E₀·0.7^(AM^0.678) (Meinel), AM by Kasten–Young, DHI ≈ 0.1·DNI. This is a **cloudless upper bound**.
- Plane of array: beam·cos(AOI) + isotropic diffuse·(1+cos β)/2 + ground-reflected GHI·albedo·(1−cos β)/2.

## PV loss chain (`PvSimulationEngine`)
Order: POA (+ bifacial rear: P·g·(GHI·albedo + 0.5·DHI)/1000) → temperature → snow → soiling → shading → mismatch → degradation → DC wiring → inverter → clipping → AC wiring.
- Cell temperature (Faiman): T_cell = T_amb + POA / (U0 + U1·wind), U0 = 25, U1 = 6.84. Power factor 1 + γ·(T_cell − 25).
- Snow: panels treated as covered when snow depth ≥ 2 cm, T_amb ≤ +1 °C and tilt < 60°.
- Inverter: AC = DC·η_peak − self-consumption·P_rated; clipped at the inverter limit.
- Trackers: 1-axis N–S rotation tan R = tan(zenith)·sin(az_sun − 180°) limited to ±max; 2-axis follows the sun.
- Performance ratio = AC / (P_peak·POA/1000).

## Weather effects in forecasts (`WeatherEffects`)
- Variables from Open-Meteo: wind at 10 m (m/s), relative humidity, precipitation, snow depth, visibility. A missing variable means no correction.
- Wind: power × (1 + γ·(T_w − 25)) / (1 + γ·(T_1 − 25)), where T_w = T_amb + POA/(25 + 6.84·wind) and T_1 is the same at 1 m/s (≈ NOCT conditions); limited to 0.9–1.1.
- Snow: forecast power 0 when snow depth ≥ 2 cm, air ≤ +1 °C and tilt < 60°.
- Precipitation, humidity and visibility are shown and explained (fog, rain) but do not change the power: their effect is already in the forecast irradiance.

## Forecast accuracy
MAE = mean|f−a|, RMSE = √mean(f−a)², MAPE = mean|f−a|/a over a > threshold, bias = mean(f−a).

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
Global factor g = median(real/model) after MAD outlier rejection. For each bucket b (sky condition, elevation band, hour, month): f_b = g + (median_b − g)·n_b/(n_b + 30). Applied factor = g·Π(f_b/g)^w (w = 1 for condition and elevation, 0.5 for hour and month), clamped 0.5–1.3, then 1 + (f − 1)·confidence with confidence = min(1, n/60)·min(1, days/14)·(1 − 2·spread).

## Energy security
Three runs of the battery predictor: expected, pessimistic (lower PV band, load × (1 + 0.10 + 0.25·(1 − load confidence))) and optimistic. Security % = usable stored energy above minimum SOC / maximum cumulative energy the battery must deliver before PV covers the load again (charging and discharging efficiencies, load ÷ inverter efficiency). Risk: HIGH if the expected run reaches minimum SOC, MEDIUM if only the pessimistic one does.

## Cold room
Average power = P_min + duty·(P_nom − P_min); duty = duty_ref·(T_amb − T_target)/(T_ref − T_target)·(idle factor outside opening hours), limited to 0–1. Pre-cooling uses the lower target before opening.

## PV performance
Expected = ideal DC power at the weather-based plane-of-array irradiance (25 °C, no losses). Each modelled loss is shown as a share of expected (ESTIMATED; soiling/mismatch/wiring from the loss profile); unknown = (model output − actual)/expected. Performance = actual/expected.
