# River Watch (USGS)

A Hubitat driver that monitors a U.S. Geological Survey stream gauge and turns current river stage, discharge, trends, local thresholds and stale data into dashboard attributes and Rule Machine events.

It uses the modern USGS Water Data OGC API. There is no account, API key, subscription, location request, companion server or additional Hubitat library.

## Features

- Accepts any valid 8- to 15-digit USGS monitoring-location number.
- Retrieves the station name, type, state and county.
- Reports the latest gage height (parameter `00065`) and discharge (parameter `00060`) when the station provides them.
- Calculates rising, steady or falling trends from locally retained samples.
- Calculates stage change per hour and discharge percentage change.
- Supports optional watch, warning and critical stage thresholds.
- Detects rapid stage rises and old gauge readings.
- Suppresses existing conditions on the first successful update by default.
- Keeps working when station metadata is temporarily unavailable but measurements remain usable.

This driver reports provisional public measurements. It is not a flood-warning or life-safety system. Thresholds vary by gauge and are not automatically supplied by USGS. Confirm appropriate levels with the station page and local emergency authorities.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-riverwatchusgs/main/RiverWatchUSGS.groovy) and click **Save**.
3. Open **Devices → Add Device → Virtual**.
4. Select **River Watch (USGS)** and name the device.
5. Enter the USGS station number and review the thresholds.
6. Click **Save Preferences**. `watchStatus` should progress from `setup` to `ready`.

Creating the device does not contact USGS. Requests begin only after preferences are saved or **Initialize** is deliberately pressed.

Find a gauge through [USGS Water Data for the Nation](https://waterdata.usgs.gov/). The station number appears in its monitoring-location identifier, for example `01646500` in `USGS-01646500`.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| USGS station number | Required | An 8- to 15-digit monitoring-location number. A leading `USGS-` is accepted. |
| Check interval | 60 minutes | 30 or 60 minutes. Hourly is polite for the no-key service and suitable for many gauges. |
| Stale after | 3 hours | Changes `riverState` to `stale` when the newest measurement is older. |
| Watch/warning/critical stage | Empty | Optional local thresholds in the station's reported stage unit. |
| Notify recovery | On | Emits button 1 when stage falls below the watch threshold. |
| Rapid-rise notification | On | Enables button 3 for fast stage increases. |
| Rapid rise per hour | 0.5 | Required increase per hour in the station's stage unit. |
| Notify stale | On | Emits button 3 the first time data becomes stale. |
| Notify initial state | Off | Optionally reports a qualifying condition on the first update. |
| Notify only on change | On | Prevents repeated events while severity remains unchanged. |
| Samples retained | 24 | Keeps 2–48 compact samples for trend calculations. |

Thresholds must increase from watch to warning to critical. Any threshold may be left empty, although setting all three gives the clearest state progression.

## Automation buttons

| Button | Meaning |
| --- | --- |
| 1 | Watch threshold or recovery below watch. |
| 2 | Warning or critical threshold. |
| 3 | Rapid stage rise or stale gauge data. |

Only one event is emitted per update. Warning and critical events take precedence, followed by stale or rapid-rise events, followed by watch or recovery. `notificationText` contains the message and `notificationType` identifies the reason.

For Rule Machine, trigger on the desired button and use `notificationText` in the notification. The three test commands generate clearly marked `TEST:` messages without changing river measurements or history.

**Clear History** invalidates an active request, removes retained trend samples, and creates a new baseline on the next successful update.

## Main attributes

| Attribute | Meaning |
| --- | --- |
| `watchStatus` | `setup`, `updating`, `ready`, `partial` or `error`. |
| `riverState` | `unknown`, `normal`, `watch`, `warning`, `critical` or `stale`. |
| `stationNumber`, `stationName` | Configured gauge and official station name. |
| `stationType`, `stationState`, `stationCounty` | Public USGS station metadata. |
| `stationUrl` | Link to the official monitoring-location page. |
| `gageHeight`, `gageHeightUnit`, `gageHeightTime` | Latest stage measurement. |
| `gageTrend`, `gageChange`, `gageChangePerHour` | Stage movement since the preceding locally collected sample. |
| `discharge`, `dischargeUnit`, `dischargeTime` | Latest streamflow measurement. |
| `dischargeTrend`, `dischargeChange`, `dischargeChangePercent` | Flow movement since the previous sample. |
| `dataAgeMinutes` | Age of the newest usable measurement. |
| `conditionSummary` | Compact station, state, stage, trend and discharge description. |
| `lastError` | Most recent configuration, request or partial metadata error. |

Not every monitoring location publishes both stage and discharge. Missing series are reported as unavailable without discarding a usable companion series.

## Data and trend interpretation

USGS real-time data is commonly provisional and subject to review, correction or deletion. Sensors can report ice effects, equipment problems, rating-curve changes or unusual qualifiers. River Watch exposes the approval status but does not independently validate hydrologic meaning.

Trends compare samples collected by the driver. After installation, a restart, a station change, or **Clear History**, at least two distinct measurement times are needed before a trend appears. A repeated USGS observation remains `unknown` rather than pretending it is steady over a new interval.

Stage is height relative to the gauge datum, not necessarily water depth. Discharge is normally cubic feet per second. Flood stage is not interchangeable between stations.

## Network and privacy

Requests send the selected public station number and parameter codes to `api.waterdata.usgs.gov`. They contain no hub location, device name, account, API key or automation data. The service receives the hub's public IP address and driver user-agent as part of normal HTTPS operation.

The driver uses `monitoring-locations` for metadata and `latest-continuous` for measurements, following USGS migration guidance away from the legacy Water Services API.

## Development

Run the simulated Hubitat suite with Groovy 2.4.21:

```sh
groovy tests/RiverWatchUSGSTest.groovy
```

Tests cover setup gating, metadata and values queries, first-run suppression, trends, rapid rises, escalation, recovery, stale data, station changes, partial metadata failure, validation, stale callbacks and JSON-safe state. GitHub Actions runs the same suite.

Data courtesy of the [U.S. Geological Survey](https://api.waterdata.usgs.gov/docs/ogcapi). Source code is MIT licensed. River Watch is not affiliated with or endorsed by USGS or Hubitat.
