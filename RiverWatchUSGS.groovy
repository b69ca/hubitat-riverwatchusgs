import groovy.transform.Field

/*
 * River Watch (USGS)
 *
 * Monitors a USGS stream gauge using the modern Water Data OGC API. Exposes
 * stage, discharge, trends, threshold states and automation events without an
 * API key or companion service.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "River Watch (USGS)",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-riverwatchusgs/main/RiverWatchUSGS.groovy"
  ) {
    capability "Sensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "clearHistory"
    command "testWatchEvent"
    command "testWarningEvent"
    command "testChangeEvent"

    attribute "watchStatus", "enum", ["setup", "updating", "ready", "partial", "error"]
    attribute "riverState", "enum", ["unknown", "normal", "watch", "warning", "critical", "stale"]
    attribute "lastChecked", "string"
    attribute "lastSuccessfulCheck", "string"
    attribute "lastError", "string"
    attribute "dataAgeMinutes", "number"

    attribute "stationNumber", "string"
    attribute "stationName", "string"
    attribute "stationType", "string"
    attribute "stationState", "string"
    attribute "stationCounty", "string"
    attribute "stationUrl", "string"

    attribute "gageHeight", "number"
    attribute "gageHeightUnit", "string"
    attribute "gageHeightTime", "string"
    attribute "gageHeightEpoch", "number"
    attribute "gageHeightStatus", "string"
    attribute "gageTrend", "enum", ["rising", "steady", "falling", "unknown"]
    attribute "gageChange", "number"
    attribute "gageChangePerHour", "number"

    attribute "discharge", "number"
    attribute "dischargeUnit", "string"
    attribute "dischargeTime", "string"
    attribute "dischargeEpoch", "number"
    attribute "dischargeStatus", "string"
    attribute "dischargeTrend", "enum", ["rising", "steady", "falling", "unknown"]
    attribute "dischargeChange", "number"
    attribute "dischargeChangePercent", "number"

    attribute "conditionSummary", "string"
    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["watch", "warning", "critical", "rapidRise", "stale", "recovery", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "River Watch 1.0.0",
      description: "Enter an 8- to 15-digit USGS monitoring-location number. Public gauge measurements are downloaded from api.waterdata.usgs.gov. No API key is required."
    input name: "siteNumber", type: "text", title: "USGS station number", required: true
    input name: "pollMinutes", type: "enum", title: "Automatic check interval", options: [
      "30":"30 minutes", "60":"1 hour"], defaultValue: "60"
    input name: "staleMinutes", type: "enum", title: "Treat the gauge as stale after", options: [
      "60":"1 hour", "120":"2 hours", "180":"3 hours", "360":"6 hours", "720":"12 hours"], defaultValue: "180"

    input name: "thresholdNotes", type: "paragraph", title: "Local stage thresholds",
      description: "Optional thresholds use the same unit reported by the station, normally feet. Confirm meaningful levels with the station's official USGS or local flood-information page."
    input name: "watchStage", type: "decimal", title: "Watch stage (optional)"
    input name: "warningStage", type: "decimal", title: "Warning stage (optional)"
    input name: "criticalStage", type: "decimal", title: "Critical stage (optional)"
    input name: "notifyRecovery", type: "bool", title: "Notify when stage returns below the watch threshold", defaultValue: true

    input name: "changeNotes", type: "paragraph", title: "Rapid-change detection",
      description: "Button 3 can report a rapid stage rise or stale data. Trend calculations compare successful samples collected by this driver."
    input name: "notifyRapidRise", type: "bool", title: "Notify for a rapid stage rise", defaultValue: true
    input name: "rapidRisePerHour", type: "decimal", title: "Rapid-rise threshold per hour", defaultValue: 0.5
    input name: "notifyStale", type: "bool", title: "Notify when data first becomes stale", defaultValue: true

    input name: "automationNotes", type: "paragraph", title: "Automation behavior",
      description: "Button 1: watch or recovery. Button 2: warning or critical. Button 3: rapid rise or stale data. A warning/critical event wins when several conditions happen together."
    input name: "notifyInitialState", type: "bool", title: "Notify for the current state on the first successful check", defaultValue: false
    input name: "notifyOnlyOnChange", type: "bool", title: "Notify only when state changes or severity increases", defaultValue: true
    input name: "historySize", type: "number", title: "Samples retained for trends", range: "2..48", defaultValue: 24
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

@Field static final String API_ROOT = "https://api.waterdata.usgs.gov/ogcapi/v0/collections"

def installed() {
  unschedule()
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.configured = false
  sendEvent(name: "numberOfButtons", value: 3)
  sendChanged("watchStatus", "setup")
}

def updated() {
  state.configured = true
  initialize()
}

def uninstalled() {
  unschedule()
}

def initialize() {
  unschedule()
  state.configured = true
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  sendEvent(name: "numberOfButtons", value: 3)
  sendChanged("watchStatus", "updating")
  configureSchedule()
  if (settings?.logEnable) runIn(1800, "logsOff")
  refresh()
}

def configureSchedule() {
  int minutes = settingNumber("pollMinutes", 60d, 30d, 60d).intValue()
  if (minutes == 60) runEvery1Hour("refresh")
  else schedule("0 */${minutes} * ? * *", "refresh")
}

def logsOff() {
  device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
  if (state.configured != true) {
    sendChanged("watchStatus", "setup")
    return
  }
  if (state.inFlight) {
    if (now() - ((state.requestStarted ?: 0L) as Long) < 120000L) return
    state.inFlight = null
    recordError("The previous USGS request did not complete")
  }

  try {
    String number = normalizedSiteNumber()
    if (state.stationNumber != number) {
      state.stationNumber = number
      state.stationMetadata = null
      state.samples = []
      state.hasBaseline = false
      state.previousRiverState = "unknown"
    }
    validateThresholds()
    long started = now()
    String token = "${started}-${state.generation}"
    state.inFlight = token
    state.requestStarted = started
    state.partialErrors = []
    sendChanged("lastChecked", localText(started))
    sendChanged("watchStatus", "updating")
    if (settings?.logEnable) log.debug "Checking USGS station ${number}"
    if (state.stationMetadata instanceof Map) requestValues(token, number)
    else requestMetadata(token, number)
  } catch (Exception e) {
    state.inFlight = null
    recordError("Configuration/request: ${e.message}")
  }
}

private void requestMetadata(String token, String number) {
  request("metadataResponse", "${API_ROOT}/monitoring-locations/items", [f:"json", id:"USGS-${number}", limit:"1"], token)
}

private void requestValues(String token, String number) {
  request("valuesResponse", "${API_ROOT}/latest-continuous/items", [
    f:"json", monitoring_location_id:"USGS-${number}", parameter_code:"00060,00065", limit:"20"
  ], token)
}

private void request(String callback, String uri, Map query, String token) {
  try {
    asynchttpGet(callback, [
      uri: uri,
      query: query,
      contentType: "application/json",
      timeout: 20,
      headers: ["User-Agent": "Hubitat-RiverWatch/1.0 (github.com/b69ca/hubitat-riverwatchusgs)"]
    ], [token: token, station: state.stationNumber])
  } catch (Exception e) {
    state.inFlight = null
    recordError("USGS request: ${e.message}")
  }
}

def metadataResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    Map body = responseFeatureCollection(resp, "station metadata")
    if (!body.features) throw new IllegalArgumentException("Station ${data.station} was not found")
    Map feature = body.features[0] instanceof Map ? body.features[0] : [:]
    Map properties = feature.properties instanceof Map ? feature.properties : [:]
    state.stationMetadata = [
      name: safeText(properties.monitoring_location_name, 200),
      type: safeText(properties.site_type, 80),
      state: safeText(properties.state_name, 80),
      county: safeText(properties.county_name, 100)
    ]
  } catch (Exception e) {
    state.partialErrors = ["Station metadata: ${e.message}"]
  }
  requestValues(data.token, data.station)
}

def valuesResponse(resp, data) {
  if (!validCallback(data)) return
  state.inFlight = null
  try {
    Map body = responseFeatureCollection(resp, "latest measurements")
    List measurements = body.features.collect { parseMeasurement(it) }.findAll { it }
    Map stage = measurements.findAll { it.code == "00065" }.max { it.time }
    Map flow = measurements.findAll { it.code == "00060" }.max { it.time }
    if (!stage && !flow) throw new IllegalArgumentException("No current stage or discharge series was returned for station ${data.station}")
    processMeasurements(stage, flow)
  } catch (Exception e) {
    recordError("Latest measurements: ${e.message}")
  }
}

private void processMeasurements(Map stage, Map flow) {
  boolean first = state.hasBaseline != true
  List oldSamples = state.samples instanceof List ? state.samples : []
  Map previous = oldSamples ? oldSamples[-1] : [:]
  long measurementTime = (([stage?.time, flow?.time].findAll { it != null }.max()) as Number).longValue()
  long ageMillis = now() - measurementTime
  long ageMinutes = Math.max(0L, ageMillis).intdiv(60000L)
  boolean stale = ageMinutes > settingNumber("staleMinutes", 180d, 30d, 1440d)

  Map stageTrend = trend(stage?.value, stage?.time, previous.stage, previous.stageTime, 0.01d)
  Map flowTrend = trend(flow?.value, flow?.time, previous.flow, previous.flowTime, 1d)
  String newState = stale ? "stale" : classifyStage(stage?.value)
  String oldState = state.previousRiverState ?: "unknown"
  Map event = chooseEvent(newState, oldState, stage, stageTrend, first)

  publishStation()
  publishMeasurement("gage", stage, stageTrend)
  publishMeasurement("discharge", flow, flowTrend)
  sendChanged("dataAgeMinutes", ageMinutes)
  sendChanged("riverState", newState)
  sendChanged("conditionSummary", summaryText(stage, flow, newState, stageTrend))

  Map sample = [captured: now(), stage: stage?.value, stageTime: stage?.time, flow: flow?.value, flowTime: flow?.time]
  int maximum = settingNumber("historySize", 24d, 2d, 48d).intValue()
  state.samples = (oldSamples + [sample]).takeRight(maximum)
  state.previousRiverState = newState
  state.hasBaseline = true
  if (event) emitAutomationEvent(event)

  List errors = state.partialErrors instanceof List ? state.partialErrors : []
  state.partialErrors = null
  sendChanged("lastSuccessfulCheck", localText(now()))
  sendChanged("lastError", errors ? errors.join("; ").take(500) : "")
  sendChanged("watchStatus", errors ? "partial" : "ready")
}

private Map chooseEvent(String newState, String oldState, Map stage, Map stageTrend, boolean first) {
  if (first && settings?.notifyInitialState != true) return null
  boolean changedOnly = settings?.notifyOnlyOnChange != false
  int newRank = stateRank(newState)
  int oldRank = stateRank(oldState)

  if (newState in ["warning", "critical"] && (!changedOnly || newRank > oldRank)) {
    return [button:2, type:newState, text:"USGS river ${newState}: ${stageText(stage)} at ${stationLabel()}."]
  }
  if (newState == "stale" && settings?.notifyStale != false && (!changedOnly || oldState != "stale")) {
    return [button:3, type:"stale", text:"USGS river data is stale at ${stationLabel()}."]
  }
  Double rise = stageTrend?.perHour as Double
  Double rapidThreshold = optionalNumber(settings?.rapidRisePerHour)
  if (settings?.notifyRapidRise != false && rise != null && rapidThreshold != null && rise >= rapidThreshold &&
      (!changedOnly || safeDouble(state.previousRisePerHour, 0d) < rapidThreshold)) {
    state.previousRisePerHour = rise
    return [button:3, type:"rapidRise", text:"Rapid river rise at ${stationLabel()}: ${formatNumber(rise)} ${stage?.unit ?: "units"}/hour; current stage ${stageText(stage)}."]
  }
  state.previousRisePerHour = rise
  if (newState == "watch" && (!changedOnly || oldRank < stateRank("watch"))) {
    return [button:1, type:"watch", text:"USGS river watch threshold reached: ${stageText(stage)} at ${stationLabel()}."]
  }
  if (settings?.notifyRecovery != false && newState == "normal" && oldRank >= stateRank("watch")) {
    return [button:1, type:"recovery", text:"USGS river stage returned below the watch threshold at ${stationLabel()}: ${stageText(stage)}."]
  }
  return null
}

private void publishStation() {
  Map station = state.stationMetadata instanceof Map ? state.stationMetadata : [:]
  sendChanged("stationNumber", state.stationNumber ?: "")
  sendChanged("stationName", station.name ?: "USGS station ${state.stationNumber}")
  sendChanged("stationType", station.type ?: "")
  sendChanged("stationState", station.state ?: "")
  sendChanged("stationCounty", station.county ?: "")
  sendChanged("stationUrl", "https://waterdata.usgs.gov/monitoring-location/USGS-${state.stationNumber}/")
}

private void publishMeasurement(String kind, Map measurement, Map change) {
  if (kind == "gage") {
    sendChanged("gageHeight", measurement ? rounded(measurement.value as Double, 2) : 0)
    sendChanged("gageHeightUnit", measurement?.unit ?: "")
    sendChanged("gageHeightTime", measurement ? localText(measurement.time as Long) : "")
    sendChanged("gageHeightEpoch", measurement ? ((measurement.time as Long) / 1000L) : 0)
    sendChanged("gageHeightStatus", measurement?.status ?: "")
    sendChanged("gageTrend", change.direction)
    sendChanged("gageChange", change.delta == null ? 0 : rounded(change.delta as Double, 2))
    sendChanged("gageChangePerHour", change.perHour == null ? 0 : rounded(change.perHour as Double, 2))
  } else {
    sendChanged("discharge", measurement ? rounded(measurement.value as Double, 1) : 0)
    sendChanged("dischargeUnit", measurement?.unit ?: "")
    sendChanged("dischargeTime", measurement ? localText(measurement.time as Long) : "")
    sendChanged("dischargeEpoch", measurement ? ((measurement.time as Long) / 1000L) : 0)
    sendChanged("dischargeStatus", measurement?.status ?: "")
    sendChanged("dischargeTrend", change.direction)
    sendChanged("dischargeChange", change.delta == null ? 0 : rounded(change.delta as Double, 1))
    sendChanged("dischargeChangePercent", change.percent == null ? 0 : rounded(change.percent as Double, 1))
  }
}

private Map parseMeasurement(def raw) {
  if (!(raw instanceof Map) || !(raw.properties instanceof Map)) return null
  Map p = raw.properties
  String code = safeText(p.parameter_code, 10)
  if (!(code in ["00060", "00065"])) return null
  Double value = finiteNumber(p.value)
  Long time = parseTime(p.time)
  if (value == null || time == null) return null
  return [code:code, value:value, time:time, unit:safeText(p.unit_of_measure, 30), status:safeText(p.approval_status, 40)]
}

private Map trend(def currentRaw, def currentTimeRaw, def oldRaw, def oldTimeRaw, double tolerance) {
  Double current = finiteNumber(currentRaw)
  Double old = finiteNumber(oldRaw)
  Long currentTime = currentTimeRaw == null ? null : currentTimeRaw as Long
  Long oldTime = oldTimeRaw == null ? null : oldTimeRaw as Long
  if (current == null || old == null || currentTime == null || oldTime == null || currentTime <= oldTime) {
    return [direction:"unknown", delta:null, perHour:null, percent:null]
  }
  double delta = current - old
  double hours = (currentTime - oldTime) / 3600000d
  String direction = Math.abs(delta) <= tolerance ? "steady" : delta > 0d ? "rising" : "falling"
  Double percent = old == 0d ? null : (delta / Math.abs(old)) * 100d
  return [direction:direction, delta:delta, perHour:delta / hours, percent:percent]
}

private String classifyStage(def raw) {
  Double value = finiteNumber(raw)
  if (value == null) return "unknown"
  Double critical = optionalNumber(settings?.criticalStage)
  Double warning = optionalNumber(settings?.warningStage)
  Double watch = optionalNumber(settings?.watchStage)
  if (critical != null && value >= critical) return "critical"
  if (warning != null && value >= warning) return "warning"
  if (watch != null && value >= watch) return "watch"
  return "normal"
}

private void validateThresholds() {
  List values = [optionalNumber(settings?.watchStage), optionalNumber(settings?.warningStage), optionalNumber(settings?.criticalStage)].findAll { it != null }
  for (int i = 1; i < values.size(); i++) {
    if (values[i] <= values[i - 1]) throw new IllegalArgumentException("Stage thresholds must increase from watch to warning to critical")
  }
  Double rapid = optionalNumber(settings?.rapidRisePerHour)
  if (rapid != null && rapid <= 0d) throw new IllegalArgumentException("Rapid-rise threshold must be greater than zero")
}

private String normalizedSiteNumber() {
  String value = settings?.siteNumber?.toString()?.trim()?.replaceFirst(/(?i)^USGS-/, "")
  if (!(value ==~ /\d{8,15}/)) throw new IllegalArgumentException("USGS station number must contain 8 to 15 digits")
  return value
}

private Map responseFeatureCollection(resp, String label) {
  if (resp == null || resp.hasError() || resp.status != 200) throw new IllegalArgumentException("${label} request failed (HTTP ${resp?.status ?: "unknown"})")
  def body = resp.getJson()
  if (!(body instanceof Map) || body.type != "FeatureCollection" || !(body.features instanceof List)) {
    throw new IllegalArgumentException("Unexpected ${label} response")
  }
  return body as Map
}

private boolean validCallback(def data) {
  return data?.token && data.token == state.inFlight && data.station == state.stationNumber
}

private int stateRank(String value) {
  return [unknown:0, normal:1, stale:2, watch:3, warning:4, critical:5][value] ?: 0
}

private String stationLabel() {
  return state.stationMetadata?.name ?: "USGS station ${state.stationNumber}"
}

private String stageText(Map stage) {
  return stage ? "${formatNumber(stage.value)} ${stage.unit ?: "units"}" : "unavailable"
}

private String summaryText(Map stage, Map flow, String riverState, Map stageTrend) {
  String text = "${stationLabel()}: ${riverState}"
  if (stage) text += "; stage ${stageText(stage)} (${stageTrend.direction})"
  if (flow) text += "; discharge ${formatNumber(flow.value)} ${flow.unit}"
  return text.take(500)
}

private void emitAutomationEvent(Map event) {
  String text = safeText(event.text, 500)
  sendEvent(name:"pushed", value:event.button, isStateChange:true, descriptionText:text)
  sendEvent(name:"notificationText", value:text, isStateChange:true)
  sendEvent(name:"notificationType", value:event.type, isStateChange:true)
}

def clearHistory() {
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.samples = []
  state.hasBaseline = false
  state.previousRiverState = "unknown"
  state.previousRisePerHour = null
  refresh()
}

def testWatchEvent() {
  emitAutomationEvent([button:1, type:"test", text:"TEST: River watch threshold event. No real gauge condition is implied."])
}

def testWarningEvent() {
  emitAutomationEvent([button:2, type:"test", text:"TEST: River warning or critical event. No real gauge condition is implied."])
}

def testChangeEvent() {
  emitAutomationEvent([button:3, type:"test", text:"TEST: Rapid-change or stale-data event. No real gauge condition is implied."])
}

private Double settingNumber(String name, double fallback, double minimum, double maximum) {
  Double value = finiteNumber(settings?.get(name))
  if (value == null) value = fallback
  return Math.max(minimum, Math.min(maximum, value))
}

private Double optionalNumber(def raw) {
  return finiteNumber(raw)
}

private Double finiteNumber(def raw) {
  try {
    if (raw == null || raw.toString().trim() == "") return null
    double value = raw as Double
    return Double.isNaN(value) || Double.isInfinite(value) ? null : value
  } catch (Exception ignored) {
    return null
  }
}

private double safeDouble(def raw, double fallback) {
  Double value = finiteNumber(raw)
  return value == null ? fallback : value
}

private Long parseTime(def raw) {
  String value = raw?.toString()?.trim()
  if (!value) return null
  List formats = ["yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'"]
  for (String format : formats) {
    try { return Date.parse(format, value, TimeZone.getTimeZone("UTC")).time }
    catch (Exception ignored) { }
  }
  return null
}

private String safeText(def raw, int maximum) {
  if (raw == null) return ""
  return raw.toString().replaceAll(/[\u0000-\u001F\u007F]/, " ").replaceAll(/\s+/, " ").trim().take(maximum)
}

private String formatNumber(def raw) {
  Double value = finiteNumber(raw)
  return value == null ? "unavailable" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private def rounded(double value, int places) {
  return new BigDecimal(Double.toString(value)).setScale(places, BigDecimal.ROUND_HALF_UP)
}

private TimeZone hubTimezone() {
  return location?.timeZone ?: TimeZone.getTimeZone("UTC")
}

private String localText(long time) {
  return new Date(time).format("yyyy-MM-dd HH:mm:ss z", hubTimezone())
}

private def sendChanged(String name, value) {
  if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name:name, value:value)
}

private void recordError(String message) {
  sendChanged("watchStatus", "error")
  sendEvent(name:"lastError", value:safeText(message, 500))
  log.warn "River Watch: ${message}"
}
